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
import kotlin.concurrent.thread

/**
 * AnonVpnService – Latency + Packet Loss
 *
 * - Reader: TUN'dan paket okur → kayıp oranına göre drop eder → kalanları DelayQueue'ya koyar
 * - Writer: Süresi dolan paketleri yazar
 */
class AnonVpnService : VpnService() {

    companion object {
        const val EXTRA_LATENCY_MS = "latency_ms"
        const val EXTRA_PACKET_LOSS = "packet_loss"   // 0-100

        private const val TAG = "AnonVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "anonping_vpn"
        private const val MAX_QUEUE_SIZE = 2000
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private val latencyMs = AtomicInteger(0)
    private val packetLossPercent = AtomicInteger(0)

    private val packetQueue = DelayQueue<DelayedPacket>()

    // İstatistik (opsiyonel, notification için)
    private val droppedCount = AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newLatency = intent?.getIntExtra(EXTRA_LATENCY_MS, 0) ?: 0
        val newLoss = intent?.getIntExtra(EXTRA_PACKET_LOSS, 0) ?: 0

        latencyMs.set(newLatency.coerceIn(0, 2000))
        packetLossPercent.set(newLoss.coerceIn(0, 100))

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
                        // === Packet Loss ===
                        val loss = packetLossPercent.get()
                        if (loss > 0 && ThreadLocalRandom.current().nextInt(100) < loss) {
                            droppedCount.incrementAndGet()
                            continue // paketi düşür
                        }

                        // Queue doluysa en eski paketi at
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

        val text = "Latency: ${latencyMs.get()} ms  |  Loss: %${packetLossPercent.get()}  |  Queue: ${packetQueue.size}"

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
