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
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Free Fire / Oxide uyumlu:
 * - TCP: senkron handshake + proxy (bağlantı kopmasın)
 * - UDP: latency / loss / bandwidth / block-download
 * - DNS (UDP 53): loss uygulanmaz, hızlı geçer
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
        private const val PROTO_ICMP = 1

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
    private val tcpPool = Executors.newCachedThreadPool()

    data class UdpSession(val originalSrcIp: ByteArray, val originalSrcPort: Int)

    class TcpSession(
        val socket: Socket,
        val clientIp: ByteArray,
        val clientPort: Int,
        val serverIp: ByteArray,
        val serverPort: Int,
        @Volatile var clientSeq: Long,
        @Volatile var serverSeq: Long,
        val outboundQueue: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue(256),
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
        // Bazı OEM'lerde metered/flag sorunlarını azaltır
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        vpnInterface = builder.establish()
        tunOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
        udpSocket = DatagramSocket().also {
            protect(it)
            it.soTimeout = 50
        }
        Log.i(TAG, "VPN ready")
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
                    if (length < 20) continue

                    val version = (buffer[0].toInt() shr 4) and 0x0F
                    if (version != 4) continue
                    val protocol = buffer[9].toInt() and 0xFF

                    when (protocol) {
                        PROTO_TCP -> {
                            // TCP: lag/loss YOK – hemen proxy
                            handleTcpFromTun(buffer.copyOf(length))
                        }
                        PROTO_UDP -> {
                            val ihl = (buffer[0].toInt() and 0x0F) * 4
                            val dstPort = if (length >= ihl + 4) {
                                ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
                            } else 0

                            // DNS (53): loss/latency uygulama – bağlantı için kritik
                            val isDns = dstPort == 53
                            if (!isDns && packetLossPercent.get() > 0 &&
                                ThreadLocalRandom.current().nextInt(100) < packetLossPercent.get()) {
                                continue
                            }
                            if (packetQueue.size >= MAX_QUEUE_SIZE) packetQueue.poll()
                            val data = buffer.copyOf(length)
                            val delayNs = if (isDns) 0L else TimeUnit.MILLISECONDS.toNanos(latencyMs.get().toLong())
                            packetQueue.put(DelayedPacket(data, System.nanoTime() + delayNs))
                        }
                        else -> {
                            // ICMP vb. şimdilik yoksay
                        }
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
                    forwardUdp(packet.data)
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

    private fun forwardUdp(ipPacket: ByteArray) {
        if (ipPacket.size < 28) return
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
            Log.w(TAG, "UDP fw: ${e.message}")
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

                // DNS cevaplarına loss uygulama
                val isDns = packet.port == 53
                if (!isDns && packetLossPercent.get() > 0 &&
                    ThreadLocalRandom.current().nextInt(100) < packetLossPercent.get()) continue

                val payload = packet.data.copyOf(packet.length)
                val ipPacket = buildUdpIpPacket(
                    packet.address.address, packet.port,
                    session.originalSrcIp, session.originalSrcPort, payload
                )
                if (!isDns) {
                    val delay = latencyMs.get()
                    if (delay > 0) Thread.sleep(delay.toLong())
                }
                writeTun(ipPacket)
            } catch (_: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "UDP rx: ${e.message}")
            }
        }
    }

    // ---------- TCP ----------

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
        val dataOff = ((ipPacket[tcpOff + 12].toInt() and 0xF0) shr 4) * 4
        val flags = ipPacket[tcpOff + 13].toInt() and 0xFF
        val payloadOff = tcpOff + dataOff
        val payload = if (ipPacket.size > payloadOff) ipPacket.copyOfRange(payloadOff, ipPacket.size) else ByteArray(0)

        val key = tcpKey(srcPort, dstIp, dstPort)

        // SYN (yeni bağlantı)
        if (flags and TCP_SYN != 0 && flags and TCP_ACK == 0) {
            if (tcpSessions.containsKey(key)) return
            openTcpSession(key, srcIp, srcPort, dstIp, dstPort, seq)
            return
        }

        val session = tcpSessions[key] ?: return

        if (flags and TCP_RST != 0) {
            closeTcp(key)
            return
        }

        if (payload.isNotEmpty()) {
            try {
                session.outboundQueue.offer(payload)
                session.clientSeq = (seq + payload.size) and 0xFFFFFFFFL
                // ACK
                writeTcpToTun(
                    session.serverIp, session.serverPort,
                    session.clientIp, session.clientPort,
                    session.serverSeq, session.clientSeq,
                    TCP_ACK, ByteArray(0)
                )
            } catch (_: Exception) {
                closeTcp(key)
            }
        }

        if (flags and TCP_FIN != 0) {
            session.clientSeq = (seq + 1) and 0xFFFFFFFFL
            try {
                writeTcpToTun(
                    session.serverIp, session.serverPort,
                    session.clientIp, session.clientPort,
                    session.serverSeq, session.clientSeq,
                    TCP_FIN or TCP_ACK, ByteArray(0)
                )
                session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
            } catch (_: Exception) {}
            closeTcp(key)
        }
    }

    private fun openTcpSession(
        key: String,
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        clientIsn: Long
    ) {
        tcpPool.execute {
            try {
                val sock = Socket()
                protect(sock)
                sock.tcpNoDelay = true
                sock.soTimeout = 0
                sock.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 10000)

                val serverIsn = ThreadLocalRandom.current().nextInt().toLong() and 0xFFFFFFFFL
                val session = TcpSession(
                    socket = sock,
                    clientIp = srcIp.copyOf(),
                    clientPort = srcPort,
                    serverIp = dstIp.copyOf(),
                    serverPort = dstPort,
                    clientSeq = (clientIsn + 1) and 0xFFFFFFFFL,
                    serverSeq = serverIsn
                )
                tcpSessions[key] = session

                // SYN-ACK (+ MSS option)
                writeTcpSynAck(
                    dstIp, dstPort, srcIp, srcPort,
                    serverIsn, (clientIsn + 1) and 0xFFFFFFFFL
                )
                session.serverSeq = (serverIsn + 1) and 0xFFFFFFFFL

                // Client → Server writer
                val outThread = thread(name = "TCP-out-$key", isDaemon = true) {
                    try {
                        val out = sock.getOutputStream()
                        while (running.get() && session.alive) {
                            val data = session.outboundQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                            out.write(data)
                            out.flush()
                        }
                    } catch (_: Exception) {
                        closeTcp(key)
                    }
                }

                // Server → Client reader
                try {
                    val input: InputStream = sock.getInputStream()
                    val buf = ByteArray(16384)
                    while (running.get() && session.alive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (blockDownload.get()) continue
                        val chunk = buf.copyOf(n)
                        writeTcpToTun(
                            session.serverIp, session.serverPort,
                            session.clientIp, session.clientPort,
                            session.serverSeq, session.clientSeq,
                            TCP_PSH or TCP_ACK, chunk
                        )
                        session.serverSeq = (session.serverSeq + n) and 0xFFFFFFFFL
                    }
                } catch (_: Exception) {
                } finally {
                    outThread.interrupt()
                    closeTcp(key)
                }
            } catch (e: Exception) {
                Log.w(TAG, "TCP open fail $key: ${e.message}")
                try {
                    writeTcpToTun(
                        dstIp, dstPort, srcIp, srcPort,
                        0, (clientIsn + 1) and 0xFFFFFFFFL,
                        TCP_RST or TCP_ACK, ByteArray(0)
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private fun closeTcp(key: String) {
        val s = tcpSessions.remove(key) ?: return
        s.alive = false
        try { s.socket.close() } catch (_: Exception) {}
    }

    private fun writeTcpSynAck(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        seq: Long, ack: Long
    ) {
        // TCP options: MSS 1460
        val options = byteArrayOf(
            0x02, 0x04, 0x05, 0xb4.toByte() // MSS 1460
        )
        writeTcpToTun(srcIp, srcPort, dstIp, dstPort, seq, ack, TCP_SYN or TCP_ACK, ByteArray(0), options)
    }

    private fun writeTcpToTun(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int, payload: ByteArray,
        options: ByteArray = ByteArray(0)
    ) {
        val optLen = ((options.size + 3) / 4) * 4 // pad to 4
        val tcpHdrLen = 20 + optLen
        val total = 20 + tcpHdrLen + payload.size
        val buf = ByteBuffer.allocate(total)

        // IP
        buf.put(0x45.toByte()); buf.put(0); buf.putShort(total.toShort())
        buf.putShort(ThreadLocalRandom.current().nextInt(0, 65535).toShort())
        buf.putShort(0x4000.toShort()); buf.put(64.toByte())
        buf.put(PROTO_TCP.toByte()); buf.putShort(0)
        buf.put(srcIp); buf.put(dstIp)

        // TCP
        buf.putShort(srcPort.toShort()); buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt()); buf.putInt(ack.toInt())
        val dataOffWords = tcpHdrLen / 4
        buf.put((dataOffWords shl 4).toByte())
        buf.put(flags.toByte())
        buf.putShort(65535.toShort())
        buf.putShort(0) // checksum later
        buf.putShort(0) // urgent
        if (options.isNotEmpty()) {
            buf.put(options)
            repeat(optLen - options.size) { buf.put(0) }
        }
        buf.put(payload)

        val packet = buf.array()
        val ipCs = ipChecksum(packet, 0, 20)
        packet[10] = (ipCs shr 8).toByte()
        packet[11] = (ipCs and 0xFF).toByte()

        val tcpCs = tcpChecksum(srcIp, dstIp, packet, 20, tcpHdrLen + payload.size)
        packet[36] = (tcpCs shr 8).toByte()
        packet[37] = (tcpCs and 0xFF).toByte()

        writeTun(packet)
    }

    private fun writeTun(packet: ByteArray) {
        synchronized(this) {
            try {
                tunOutput?.write(packet)
                tunOutput?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "TUN write: ${e.message}")
            }
        }
    }

    private fun tcpKey(srcPort: Int, dstIp: ByteArray, dstPort: Int): String {
        val ip = dstIp.joinToString(".") { (it.toInt() and 0xFF).toString() }
        return "$srcPort-$ip-$dstPort"
    }

    private fun tcpChecksum(srcIp: ByteArray, dstIp: ByteArray, packet: ByteArray, tcpOff: Int, tcpLen: Int): Int {
        var sum = 0
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += PROTO_TCP
        sum += tcpLen
        var i = tcpOff
        val end = tcpOff + tcpLen
        while (i < end - 1) {
            // zero checksum field when computing
            if (i == tcpOff + 16) {
                i += 2
                continue
            }
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AnonPing active")
            .setContentText("TCP pass + UDP lag | ${latencyMs.get()}ms | %${packetLossPercent.get()}")
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
        tcpSessions.keys.toList().forEach { closeTcp(it) }
        tcpPool.shutdownNow()
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
