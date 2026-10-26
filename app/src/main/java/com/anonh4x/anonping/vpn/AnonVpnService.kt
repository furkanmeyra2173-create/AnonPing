package com.anonh4x.anonping.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anonh4x.anonping.MainActivity
import hev.htproxy.TProxyService
import java.io.File
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * AnonPing VPN service.
 *
 * The TUN descriptor is handled by hev-socks5-tunnel (lwIP based), rather than
 * manually synthesizing TCP packets. A local SOCKS5 relay opens protected
 * Android sockets and applies latency/loss/bandwidth effects to forwarded
 * traffic. No app (including Chrome) is excluded from the VPN.
 */
class AnonVpnService : VpnService() {

    companion object {
        const val EXTRA_LATENCY_MS = "latency_ms"
        const val EXTRA_PACKET_LOSS = "packet_loss"
        const val EXTRA_BANDWIDTH_KBS = "bandwidth_kbs"
        const val EXTRA_BLOCK_DOWNLOAD = "block_download"

        private const val TAG = "AnonVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "anonping_vpn"
        private const val TUN_IPV4 = "10.8.0.2"
        private const val TUN_IPV6 = "fc00::1"
        private const val SOCKS_ADDRESS = "127.0.0.1"
        private const val SOCKS_PORT = 1080
    }

    private val running = AtomicBoolean(false)
    private val lifecycleLock = Any()

    private val latencyMs = AtomicInteger(0)
    private val packetLossPercent = AtomicInteger(0)
    private val bandwidthKbs = AtomicInteger(0)
    private val blockDownload = AtomicBoolean(false)

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var localProxy: LocalSocks5Proxy? = null
    @Volatile private var nativeStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latencyMs.set((intent?.getIntExtra(EXTRA_LATENCY_MS, latencyMs.get()) ?: latencyMs.get()).coerceIn(0, 2000))
        packetLossPercent.set((intent?.getIntExtra(EXTRA_PACKET_LOSS, packetLossPercent.get()) ?: packetLossPercent.get()).coerceIn(0, 100))
        bandwidthKbs.set((intent?.getIntExtra(EXTRA_BANDWIDTH_KBS, bandwidthKbs.get()) ?: bandwidthKbs.get()).coerceIn(0, 10000))
        blockDownload.set(intent?.getBooleanExtra(EXTRA_BLOCK_DOWNLOAD, blockDownload.get()) ?: blockDownload.get())

        startForeground(NOTIFICATION_ID, createNotification())

        // Apply loss at the IP-packet boundary in the native TUN engine.
        // Do not simulate TCP loss by pausing byte-stream writes: that is not
        // packet loss and can make TLS/game protocols behave unpredictably.
        try {
            TProxyService.TProxySetPacketLoss(packetLossPercent.get())
        } catch (t: Throwable) {
            Log.e(TAG, "Native packet-loss control unavailable", t)
        }

        if (running.compareAndSet(false, true)) {
            thread(name = "AnonPing-Tunnel-Startup") { startTunnel() }
        } else {
            updateNotification()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        try {
            val proxy = LocalSocks5Proxy(
                protectTcp = { socket: Socket -> protect(socket) },
                protectUdp = { socket: DatagramSocket -> protect(socket) },
                latency = { latencyMs.get() },
                bandwidth = { bandwidthKbs.get() },
                blockDownload = { blockDownload.get() }
            )

            synchronized(lifecycleLock) {
                if (!running.get()) {
                    proxy.close()
                    return
                }
                localProxy = proxy
                proxy.start()
            }

            if (!running.get()) return

            val builder = Builder()
                .setSession("AnonPing")
                .addAddress(TUN_IPV4, 32)
                .addRoute("0.0.0.0", 0)
                .addAddress(TUN_IPV6, 128)
                .addRoute("::", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setMtu(1400)
                .setBlocking(true)

            // Keep AnonPing's own sockets outside its VPN. The native
            // tun2socks engine connects to the local SOCKS5 relay, while the
            // relay's real internet sockets are separately protected with
            // VpnService.protect(). This prevents the VPN engine/relay from
            // capturing its own control traffic and creating a routing loop.
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                throw IOException("Could not exclude AnonPing's own sockets", e)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val established = builder.establish()
                ?: throw IOException("Android did not create the VPN interface")

            synchronized(lifecycleLock) {
                if (!running.get()) {
                    established.close()
                    return
                }

                vpnInterface = established
                val config = writeTunnelConfig()
                val started = TProxyService.TProxyStartService(config.absolutePath, established.fd)
                if (!started) {
                    vpnInterface = null
                    established.close()
                    throw IOException("hev-socks5-tunnel refused to start")
                }
                nativeStarted = true
            }

            // TProxyStartService starts a native worker thread; make sure it
            // stays alive long enough to catch invalid config/library failures.
            var engineReady = false
            for (attempt in 0 until 12) {
                if (!running.get()) return
                try {
                    if (TProxyService.TProxyIsRunning()) {
                        engineReady = true
                        break
                    }
                } catch (e: Throwable) {
                    throw IOException("Could not query the native tunnel state", e)
                }
                Thread.sleep(100)
            }

            if (!running.get()) return
            if (!engineReady) throw IOException("The native tunnel stopped during startup")

            Log.i(TAG, "VPN and tun2socks engine started")
            updateNotification()
        } catch (t: Throwable) {
            Log.e(TAG, "VPN startup failed", t)
            running.set(false)
            stopSelf()
        }
    }

    private fun writeTunnelConfig(): File {
        val config = File(filesDir, "hev-socks5-tunnel.yml")
        config.writeText(
            """
            tunnel:
              name: anonping
              mtu: 1400
              multi-queue: false
              ipv4: $TUN_IPV4
              ipv6: '$TUN_IPV6'
              icmp: 'reply'
            socks5:
              port: $SOCKS_PORT
              address: '$SOCKS_ADDRESS'
              udp: 'udp'
            misc:
              tcp-buffer-size: 65536
              max-session-count: 2048
              connect-timeout: 15000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: null
              log-level: warn
            """.trimIndent() + "\n"
        )
        return config
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AnonPing VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AnonPing")
            .setContentText(
                if (nativeStarted) {
                    "VPN active | ${latencyMs.get()} ms | loss ${packetLossPercent.get()}%"
                } else {
                    "Starting network engine…"
                }
            )
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        running.set(false)
        synchronized(lifecycleLock) {
            if (nativeStarted) {
                try {
                    TProxyService.TProxyStopService()
                } catch (t: Throwable) {
                    Log.w(TAG, "Native tunnel stop failed", t)
                }
                nativeStarted = false
            }
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
        }

        try {
            localProxy?.close()
        } catch (e: Exception) {
            Log.w(TAG, "SOCKS relay stop failed", e)
        }
        localProxy = null
        super.onDestroy()
    }
}
