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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class AnonVpnService : VpnService() {

    companion object {
        const val EXTRA_LATENCY_MS = "latency_ms"
        const val EXTRA_PACKET_LOSS = "packet_loss"
        const val EXTRA_BANDWIDTH_KBS = "bandwidth_kbs"
        const val EXTRA_BLOCK_DOWNLOAD = "block_download"

        private const val TAG = "AnonVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "anonping_vpn"
        private const val MAX_QUEUE_SIZE = 2000
        private const val PROTO_UDP = 17
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunOutput: FileOutputStream? = null
    private val running = AtomicBoolean(false)

    private val latencyMs = AtomicInteger(0)
    private val packetLossPercent = AtomicInteger(0)
    private val bandwidthKbs = AtomicInteger(0)
    private val blockDownload = AtomicBoolean(false)

    private val packetQueue = DelayQueue<DelayedPacket>()
    private val bytesThisSecond = AtomicLong(0)
    private val secondStartNs = AtomicLong(System.nanoTime())
    private var udpSocket: DatagramSocket? = null
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    data class UdpSession(
        val originalSrcIp: ByteArray,
        val originalSrcPort: Int,
        val lastSeen: AtomicLong = AtomicLong(System.currentTimeMillis())
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latencyMs.set((intent?.getIntExtra(EXTRA_LATENCY_MS, 0) ?: 0).coerceIn(0, 2000))
        packetLossPercent.set((intent?.getIntExtra(EXTRA_PACKET_LOSS, 0) ?: 0).coerceIn(0, 100))
        bandwidthKbs.set((intent?.getIntExtra(EXTRA_BANDWIDTH_KBS, 0) ?: 0).coerceIn(0, 10000))
        blockDownload.set(intent?.getBooleanExtra(EXTRA_BLOCK_DOWNLOAD, false) ?: false)

        startForeground(NOTIFICATION_ID, createNotification())

        if (running.compareAndSet(false, true)) {
            thread(name = "AnonVpn-Reader") { readerLoop() }
            thread(name = "AnonVpn-Writer") { writerLoop() }
            thread(name = "AnonVpn-UdpReceiver") { udpReceiveLoop() }
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
        tunOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
        udpSocket = DatagramSocket().also {
            protect(it)
            it.soTimeout = 100
        }
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
                        if (packetLossPercent.get() > 0 &&
                            ThreadLocalRandom.current().nextInt(100) < packetLossPercent.get()) continue
                        if (packetQueue.size >= MAX_QUEUE_SIZE) packetQueue.poll()
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
            Log.e(TAG, "Reader failed", e)
        } finally {
            running.set(false)
        }
    }

    private fun writerLoop() {
        try {
            establishVpnIfNeeded() ?: return
            while (running.get() || packetQueue.isNotEmpty()) {
                try {
                    val packet = packetQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    enforceBandwidth(packet.data.size)
                    forwardPacket(packet.data)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "Writer error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Writer failed", e)
        } finally {
            running.set(false)
            stopSelf()
        }
    }

    private fun forwardPacket(ipPacket: ByteArray) {
        if (ipPacket.size < 20) return
        val version = (ipPacket[0].toInt() shr 4) and 0x0F
        if (version != 4) return
        val ihl = (ipPacket[0].toInt() and 0x0F) * 4
        if (ipPacket.size < ihl + 8) return
        val protocol = ipPacket[9].toInt() and 0xFF
        val srcIp = ipPacket.copyOfRange(12, 16)
        val dstIp = ipPacket.copyOfRange(16, 20)
        if (protocol == PROTO_UDP) forwardUdp(ipPacket, ihl, srcIp, dstIp)
    }

    private fun forwardUdp(ipPacket: ByteArray, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val udpOffset = ihl
        if (ipPacket.size < udpOffset + 8) return
        val srcPort = ((ipPacket[udpOffset].toInt() and 0xFF) shl 8) or (ipPacket[udpOffset + 1].toInt() and 0xFF)
        val dstPort = ((ipPacket[udpOffset + 2].toInt() and 0xFF) shl 8) or (ipPacket[udpOffset + 3].toInt() and 0xFF)
        val payload = ipPacket.copyOfRange(udpOffset + 8, ipPacket.size)
        try {
            val socket = udpSocket ?: return
            val destAddr = InetAddress.getByAddress(dstIp)
            socket.send(DatagramPacket(payload, payload.size, destAddr, dstPort))
            udpSessions["${destAddr.hostAddress}:$dstPort"] = UdpSession(srcIp.copyOf(), srcPort)
        } catch (e: Exception) {
            Log.w(TAG, "UDP forward failed: ${e.message}")
        }
    }

    private fun udpReceiveLoop() {
        val buf = ByteArray(4096)
        while (running.get()) {
            try {
                val socket = udpSocket ?: break
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)

                // Block Download = drop all inbound (enemies freeze style)
                if (blockDownload.get()) continue

                val remoteIp = packet.address.hostAddress ?: continue
                val remotePort = packet.port
                val session = udpSessions["$remoteIp:$remotePort"] ?: continue

                if (packetLossPercent.get() > 0 &&
                    ThreadLocalRandom.current().nextInt(100) < packetLossPercent.get()) continue

                val payload = packet.data.copyOf(packet.length)
                val ipPacket = buildUdpIpPacket(
                    packet.address.address, remotePort,
                    session.originalSrcIp, session.originalSrcPort, payload
                )
                val delay = latencyMs.get()
                if (delay > 0) Thread.sleep(delay.toLong())
                tunOutput?.write(ipPacket)
                tunOutput?.flush()
            } catch (_: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "UDP reverse error: ${e.message}")
            }
        }
    }

    private fun buildUdpIpPacket(
        srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val buf = ByteBuffer.allocate(totalLen)
        buf.put(0x45.toByte()); buf.put(0); buf.putShort(totalLen.toShort())
        buf.putShort(0); buf.putShort(0x4000.toShort()); buf.put(64.toByte())
        buf.put(PROTO_UDP.toByte()); buf.putShort(0)
        buf.put(srcIp); buf.put(dstIp)
        buf.putShort(srcPort.toShort()); buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort()); buf.putShort(0); buf.put(payload)
        val packet = buf.array()
        val cs = ipChecksum(packet, 0, 20)
        packet[10] = (cs shr 8).toByte(); packet[11] = (cs and 0xFF).toByte()
        return packet
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0; var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF); i += 2
        }
        if (length % 2 != 0) sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun enforceBandwidth(packetSize: Int) {
        val limitKbs = bandwidthKbs.get(); if (limitKbs <= 0) return
        val limitBytes = limitKbs * 1024L
        val now = System.nanoTime()
        if (now - secondStartNs.get() >= TimeUnit.SECONDS.toNanos(1)) {
            secondStartNs.set(now); bytesThisSecond.set(0)
        }
        val current = bytesThisSecond.addAndGet(packetSize.toLong())
        if (current > limitBytes) {
            val over = current - limitBytes
            val sleepMs = (over * 1000 / limitBytes).coerceIn(1, 200)
            try { Thread.sleep(sleepMs) } catch (_: InterruptedException) {}
            bytesThisSecond.addAndGet(-packetSize.toLong() / 2)
        }
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
        val bwText = if (bandwidthKbs.get() > 0) "${bandwidthKbs.get()} KB/s" else "∞"
        val extra = if (blockDownload.get()) " | BlockDL" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AnonPing active")
            .setContentText("Lat: ${latencyMs.get()}ms | Loss: %${packetLossPercent.get()} | BW: $bwText$extra")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
    }

    override fun onDestroy() {
        running.set(false)
        packetQueue.clear()
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tunOutput?.close() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null; tunOutput = null; udpSocket = null
        super.onDestroy()
    }

    private class DelayedPacket(val data: ByteArray, private val releaseTimeNanos: Long) : Delayed {
        override fun getDelay(unit: TimeUnit) =
            unit.convert(releaseTimeNanos - System.nanoTime(), TimeUnit.NANOSECONDS)
        override fun compareTo(other: Delayed) =
            getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
    }
}
