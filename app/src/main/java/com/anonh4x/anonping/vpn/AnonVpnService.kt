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
import com.anonh4x.anonping.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Basit VpnService iskeleti.
 * Gerçek packet delay / loss için daha gelişmiş queue + scheduler gerekir.
 * Bu sürüm sadece VPN tünelini açar ve trafiği passthrough yapar.
 * Sonraki adımda latency injection eklenecek.
 */
class AnonVpnService : VpnService() {

    companion object {
        const val EXTRA_LATENCY_MS = "latency_ms"
        private const val TAG = "AnonVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "anonping_vpn"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var latencyMs = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latencyMs = intent?.getIntExtra(EXTRA_LATENCY_MS, 0) ?: 0
        startForeground(NOTIFICATION_ID, createNotification())

        if (running.compareAndSet(false, true)) {
            thread(name = "AnonVpnThread") {
                try {
                    establishVpn()
                    runTunnel()
                } catch (e: Exception) {
                    Log.e(TAG, "VPN error", e)
                } finally {
                    running.set(false)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun establishVpn() {
        val builder = Builder()
            .setSession("AnonPing")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500)

        vpnInterface = builder.establish()
        Log.i(TAG, "VPN interface established, latency target: ${latencyMs}ms")
    }

    private fun runTunnel() {
        val iface = vpnInterface ?: return
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)

        while (running.get()) {
            try {
                val length = input.read(buffer.array())
                if (length > 0) {
                    // TODO: burada latency + packet loss uygulanacak
                    // Şimdilik passthrough
                    if (latencyMs > 0) {
                        Thread.sleep(latencyMs.toLong()) // çok kaba, sadece demo
                    }
                    output.write(buffer.array(), 0, length)
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Tunnel read/write error", e)
                break
            }
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AnonPing VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AnonPing active")
            .setContentText("Latency: ${latencyMs} ms")
            .setSmallIcon(android.R.drawable.ic_lock_lock) // geçici
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running.set(false)
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }
}
