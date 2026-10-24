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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * AnonVpnService – Latency + Packet Loss + Bandwidth Limiting
 *
 * Bandwidth: basit token-bucket benzeri (saniyede max byte)
 */
class AnonVpnService : VpnService() {

    companion object {
        const val EXTRA_LATENCY_MS = "latency_ms"
        const val EXTRA_PACKET_LOSS = "packet_loss"     // 0-100
        const val EXTRA_BANDWIDTH_KBS = "bandwidth_kbs" // 0 = unlimited, else KB/s

        private const val TAG = "AnonVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "anonping_vpn"
        private const val MAX_QUEUE_SIZE = 2000
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private val latencyMs = AtomicInteger(0)
    private val packetLossPercent = AtomicInteger(0)
    private val bandwidthKbs = AtomicInteger(0) // 0 = unlimited

    private val packetQueue = DelayQueue<DelayedPacket>()

    // Bandwidth tracking
    private val bytesThisSecond = AtomicLong(0)
    private val secondStartNs = AtomicLong(System.nanoTime())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newLatency = intent?.getIntExtra(EXTRA_LATENCY_MS, 0) ?: 0
        val newLoss = intent?.getIntExtra(EXTRA_PACKET_LOSS, 0) ?: 0
        val newBw = intent?.getIntExtra(EXTRA_BANDWIDTH_KBS, 0) ?: 0

        latencyMs.set(newLatency.coerceIn(0, 2000))
        packetLossPercent.set(newLoss.coerceIn(0, 100))
        bandwidthKbs.set(newBw.coerceIn(0, 10000))

        startForeground(NOTIFICATION_ID, createNotification())

        if (running.compareAndSet(false, true)) {
            thread(name = "AnonVpn-Reader") { readerLoop() }
            thread(name = "AnonVpn-Writer") { writerLoop() }
        } else {
            updateNotification()
        }
        return START_STICKY
    }

    private fun establishVpnIfNeeded(): ParcelFileDescriptor? {
        if (vpnInterface != null) return vpnInterface

        val builder = Builder()
            .setSession("AnonPing")
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setMtu(1500)
            .setBlocking(true)

        vpnInterface = builder.establish()
        Log.i(TAG, "VPN interface established")
        return vpnInterface
    }

    private fun readerLoop() {
        try {
            val iface = establishVpnIfNeeded() ?: return
            val input = FileInputStream(iface.fileDescriptor)
            val buffer = ByteArray(32767)

            while (running.get()) {
                try {
                    val length = input.read(buffer)
                    if (length > 0) {
                        // Packet Loss
                        val loss = packetLossPercent.get()
                        if (loss > 0 && ThreadLocalRandom.current().nextInt(100) < loss) {
                            continue
                        }

                        if (packetQueue.size >= MAX_QUEUE_SIZE) {
                            packetQueue.poll()
                        }

                        val data = buffer.copyOf(length)
                        val releaseTime = System.nanoTime() +
                                TimeUnit.MILLISECONDS.toNanos(latencyMs.get().toLong())
                        packetQueue.put(DelayedPacket(data, releaseTime))
                    }
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "Reader error", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reader loop failed", e)
        } finally {
            running.set(false)
        }
    }

    private fun writerLoop() {
        try {
            val iface = establishVpnIfNeeded() ?: return
            val output = FileOutputStream(iface.fileDescriptor)

            while (running.get() || packetQueue.isNotEmpty()) {
                try {
                    val packet = packetQueue.poll(50, TimeUnit.MILLISECONDS)
                    if (packet != null) {
                        // === Bandwidth limiting ===
                        enforceBandwidth(packet.data.size)
                        output.write(packet.data)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "Writer error", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Writer loop failed", e)
        } finally {
            running.set(false)
            stopSelf()
        }
    }

    /**
     * Basit bandwidth limiter.
     * Saniyede max `bandwidthKbs * 1024` byte bırakır.
     * Aşılırsa sleep eder.
     */
    private fun enforceBandwidth(packetSize: Int) {
        val limitKbs = bandwidthKbs.get()
        if (limitKbs <= 0) return // unlimited

        val limitBytes = limitKbs * 1024L
        val now = System.nanoTime()

        // Yeni saniyeye geçtiyse sıfırla
        if (now - secondStartNs.get() >= TimeUnit.SECONDS.toNanos(1)) {
            secondStartNs.set(now)
            bytesThisSecond.set(0)
        }

        val current = bytesThisSecond.addAndGet(packetSize.toLong())
        if (current > limitBytes) {
            // Ne kadar aştık → orantılı bekle
            val over = current - limitBytes
            val sleepMs = (over * 1000 / limitBytes).coerceIn(1, 200)
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {}
            // Sleep sonrası sayacı biraz rahatlat
            bytesThisSecond.addAndGet(-packetSize.toLong() / 2)
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AnonPing VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val bwText = if (bandwidthKbs.get() > 0) "${bandwidthKbs.get()} KB/s" else "∞"
        val text = "Lat: ${latencyMs.get()}ms | Loss: %${packetLossPercent.get()} | BW: $bwText"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AnonPing active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onDestroy() {
        running.set(false)
        packetQueue.clear()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        super.onDestroy()
    }

    private class DelayedPacket(
        val data: ByteArray,
        private val releaseTimeNanos: Long
    ) : Delayed {
        override fun getDelay(unit: TimeUnit): Long {
            val diff = releaseTimeNanos - System.nanoTime()
            return unit.convert(diff, TimeUnit.NANOSECONDS)
        }

        override fun compareTo(other: Delayed): Int {
            return getDelay(TimeUnit.NANOSECONDS)
                .compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }
    }
}
