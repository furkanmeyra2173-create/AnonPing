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
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
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

/**
 * UDP → latency / loss / bandwidth / block-download
 * TCP → passthrough proxy (oyun bağlanabilsin)
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
        private const val MAX_QUEUE_SIZE = 2000
        private const val PROTO_UDP = 17
        private const val PROTO_TCP = 6

        private const val TCP_FIN = 0x01
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_PSH = 0x08
        private const val TCP_ACK = 0x10
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
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()

    data class UdpSession(
        val originalSrcIp: ByteArray,
        val originalSrcPort: Int
    )

    class TcpSession(
        val socket: Socket,
        val clientIp: ByteArray,
        val clientPort: Int,
        val serverIp: ByteArray,
        val serverPort: Int,
        @Volatile var clientSeq: Long,   // next seq client should send / our ACK
        @Volatile var serverSeq: Long,   // our SEQ toward client
        @Volatile var alive: Boolean = true
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
                    if (length <= 0) continue

                    // TCP → hemen passthrough (lag/loss yok)
                    if (length >= 20) {
                        val version = (buffer[0].toInt() shr 4) and 0x0F
                        if (version == 4) {
                            val protocol = buffer[9].toInt() and 0xFF
                            if (protocol == PROTO_TCP) {
                                handleTcpFromTun(buffer.copyOf(length))
                                continue
                            }
                        }
                    }

                    // UDP / diğer → loss + delay queue
                    if (packetLossPercent.get() > 0 &&
                        ThreadLocalRandom.current().nextInt(100) < packetLossPercent.get()) continue
                    if (packetQueue.size >= MAX_QUEUE_SIZE) packetQueue.poll()
                    val data = buffer.copyOf(length)
                    val releaseTime = System.nanoTime() +
                            TimeUnit.MILLISECONDS.toNanos(latencyMs.get().toLong())
                    packetQueue.put(DelayedPacket(data, releaseTime))
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
                    forwardUdpOnly(packet.data)
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

    // ---------- UDP ----------

    private fun forwardUdpOnly(ipPacket: ByteArray) {
        if (ipPacket.size < 20) return
        val version = (ipPacket[0].toInt() shr 4) and 0x0F
        if (version != 4) return
        val ihl = (ipPacket[0].toInt() and 0x0F) * 4
        if (ipPacket.size < ihl + 8) return
        if ((ipPacket[9].toInt() and 0xFF) != PROTO_UDP) return

        val srcIp = ipPacket.copyOfRange(12, 16)
        val dstIp = ipPacket.copyOfRange(16, 20)
        val srcPort = ((ipPacket[ihl].toInt() and 0xFF) shl 8) or (ipPacket[ihl + 1].toInt() and 0xFF)
        val dstPort = ((ipPacket[ihl + 2].toInt() and 0xFF) shl 8) or (ipPacket[ihl + 3].toInt() and 0xFF)
        val payload = ipPacket.copyOfRange(ihl + 8, ipPacket.size)
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
                if (blockDownload.get()) continue
                val remoteIp = packet.address.hostAddress ?: continue
                val session = udpSessions["$remoteIp:${packet.port}"] ?: continue
                if (packetLossPercent.get() > 0 &&
                    ThreadLocalRandom.current().nextInt(100) < packetLossPercent.get()) continue
                val payload = packet.data.copyOf(packet.length)
                val ipPacket = buildUdpIpPacket(
                    packet.address.address, packet.port,
                    session.originalSrcIp, session.originalSrcPort, payload
                )
                val delay = latencyMs.get()
                if (delay > 0) Thread.sleep(delay.toLong())
                synchronized(this) {
                    tunOutput?.write(ipPacket)
                    tunOutput?.flush()
                }
            } catch (_: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "UDP reverse: ${e.message}")
            }
        }
    }

    // ---------- TCP passthrough proxy ----------

    private fun handleTcpFromTun(ipPacket: ByteArray) {
        if (ipPacket.size < 40) return
        val ihl = (ipPacket[0].toInt() and 0x0F) * 4
        if (ipPacket.size < ihl + 20) return

        val srcIp = ipPacket.copyOfRange(12, 16)
        val dstIp = ipPacket.copyOfRange(16, 20)
        val tcpOff = ihl
        val srcPort = ((ipPacket[tcpOff].toInt() and 0xFF) shl 8) or (ipPacket[tcpOff + 1].toInt() and 0xFF)
        val dstPort = ((ipPacket[tcpOff + 2].toInt() and 0xFF) shl 8) or (ipPacket[tcpOff + 3].toInt() and 0xFF)
        val seq = readU32(ipPacket, tcpOff + 4)
        val ack = readU32(ipPacket, tcpOff + 8)
        val dataOff = ((ipPacket[tcpOff + 12].toInt() and 0xF0) shr 4) * 4
        val flags = ipPacket[tcpOff + 13].toInt() and 0xFF
        val payloadOff = tcpOff + dataOff
        val payload = if (ipPacket.size > payloadOff) ipPacket.copyOfRange(payloadOff, ipPacket.size) else ByteArray(0)

        val key = "$srcPort-${dstIp.joinToString(".") { (it.toInt() and 0xFF).toString() }}-$dstPort"

        if (flags and TCP_SYN != 0 && flags and TCP_ACK == 0) {
            // New connection
            if (tcpSessions.containsKey(key)) return
            thread(name = "TCP-$key") {
                try {
                    val sock = Socket()
                    protect(sock)
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 8000)
                    val session = TcpSession(
                        socket = sock,
                        clientIp = srcIp.copyOf(),
                        clientPort = srcPort,
                        serverIp = dstIp.copyOf(),
                        serverPort = dstPort,
                        clientSeq = (seq + 1) and 0xFFFFFFFFL,
                        serverSeq = ThreadLocalRandom.current().nextInt().toLong() and 0xFFFFFFFFL
                    )
                    tcpSessions[key] = session

                    // SYN-ACK to client
                    writeTcpToTun(
                        srcIp = dstIp, srcPort = dstPort,
                        dstIp = srcIp, dstPort = srcPort,
                        seq = session.serverSeq,
                        ack = session.clientSeq,
                        flags = TCP_SYN or TCP_ACK,
                        payload = ByteArray(0)
                    )
                    session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL

                    // Read from server → TUN
                    val buf = ByteArray(8192)
                    val input: InputStream = sock.getInputStream()
                    while (running.get() && session.alive) {
                        val n = try { input.read(buf) } catch (_: Exception) { -1 }
                        if (n < 0) break
                        if (n == 0) continue
                        if (blockDownload.get()) continue
                        val chunk = buf.copyOf(n)
                        writeTcpToTun(
                            srcIp = dstIp, srcPort = dstPort,
                            dstIp = srcIp, dstPort = srcPort,
                            seq = session.serverSeq,
                            ack = session.clientSeq,
                            flags = TCP_PSH or TCP_ACK,
                            payload = chunk
                        )
                        session.serverSeq = (session.serverSeq + n) and 0xFFFFFFFFL
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "TCP connect failed $key: ${e.message}")
                    // RST to client
                    try {
                        writeTcpToTun(
                            srcIp = dstIp, srcPort = dstPort,
                            dstIp = srcIp, dstPort = srcPort,
                            seq = 0, ack = (seq + 1) and 0xFFFFFFFFL,
                            flags = TCP_RST or TCP_ACK,
                            payload = ByteArray(0)
                        )
                    } catch (_: Exception) {}
                } finally {
                    closeTcpSession(key)
                }
            }
            return
        }

        val session = tcpSessions[key] ?: return

        if (flags and TCP_RST != 0) {
            closeTcpSession(key)
            return
        }

        if (payload.isNotEmpty()) {
            try {
                session.socket.getOutputStream().write(payload)
                session.socket.getOutputStream().flush()
                session.clientSeq = (seq + payload.size) and 0xFFFFFFFFL
                // ACK back
                writeTcpToTun(
                    srcIp = session.serverIp, srcPort = session.serverPort,
                    dstIp = session.clientIp, dstPort = session.clientPort,
                    seq = session.serverSeq,
                    ack = session.clientSeq,
                    flags = TCP_ACK,
                    payload = ByteArray(0)
                )
            } catch (e: Exception) {
                Log.w(TAG, "TCP write failed: ${e.message}")
                closeTcpSession(key)
            }
        } else if (flags and TCP_ACK != 0) {
            // pure ACK – update client seq if advanced
            if (ack != 0L) {
                // serverSeq already managed on send side
            }
        }

        if (flags and TCP_FIN != 0) {
            session.clientSeq = (seq + 1) and 0xFFFFFFFFL
            try {
                writeTcpToTun(
                    srcIp = session.serverIp, srcPort = session.serverPort,
                    dstIp = session.clientIp, dstPort = session.clientPort,
                    seq = session.serverSeq,
                    ack = session.clientSeq,
                    flags = TCP_FIN or TCP_ACK,
                    payload = ByteArray(0)
                )
                session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
            } catch (_: Exception) {}
            closeTcpSession(key)
        }
    }

    private fun closeTcpSession(key: String) {
        val s = tcpSessions.remove(key) ?: return
        s.alive = false
        try { s.socket.close() } catch (_: Exception) {}
    }

    private fun writeTcpToTun(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int, payload: ByteArray
    ) {
        val ihl = 20
        val tcpHdr = 20
        val total = ihl + tcpHdr + payload.size
        val buf = ByteBuffer.allocate(total)

        // IP
        buf.put(0x45.toByte()); buf.put(0); buf.putShort(total.toShort())
        buf.putShort(0); buf.putShort(0x4000.toShort()); buf.put(64.toByte())
        buf.put(PROTO_TCP.toByte()); buf.putShort(0)
        buf.put(srcIp); buf.put(dstIp)

        // TCP
        buf.putShort(srcPort.toShort()); buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt()); buf.putInt(ack.toInt())
        buf.put(((5 shl 4).toByte())); buf.put(flags.toByte())
        buf.putShort(65535.toShort()); buf.putShort(0); buf.putShort(0)
        buf.put(payload)

        val packet = buf.array()
        val ipCs = ipChecksum(packet, 0, 20)
        packet[10] = (ipCs shr 8).toByte()
        packet[11] = (ipCs and 0xFF).toByte()

        // TCP checksum with pseudo-header
        val tcpCs = tcpChecksum(srcIp, dstIp, packet, 20, tcpHdr + payload.size)
        packet[36] = (tcpCs shr 8).toByte()
        packet[37] = (tcpCs and 0xFF).toByte()

        synchronized(this) {
            tunOutput?.write(packet)
            tunOutput?.flush()
        }
    }

    private fun tcpChecksum(srcIp: ByteArray, dstIp: ByteArray, packet: ByteArray, tcpOff: Int, tcpLen: Int): Int {
        var sum = 0
        // pseudo header
        for (i in 0 until 4) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (if (i + 1 < 4) srcIp[i + 1].toInt() and 0xFF else 0)
        }
        // do properly
        sum = 0
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += PROTO_TCP
        sum += tcpLen

        var i = tcpOff
        val end = tcpOff + tcpLen
        while (i < end - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (tcpLen % 2 != 0) sum += (packet[end - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun readU32(data: ByteArray, off: Int): Long {
        return ((data[off].toLong() and 0xFF) shl 24) or
                ((data[off + 1].toLong() and 0xFF) shl 16) or
                ((data[off + 2].toLong() and 0xFF) shl 8) or
                (data[off + 3].toLong() and 0xFF)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AnonPing active")
            .setContentText("UDP lag | TCP pass | ${latencyMs.get()}ms | %${packetLossPercent.get()} | $bwText")
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
        tcpSessions.keys.toList().forEach { closeTcpSession(it) }
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
