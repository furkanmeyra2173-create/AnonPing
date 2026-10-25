package com.anonh4x.anonping.vpn

import android.util.Log
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Local SOCKS5 gateway for hev-socks5-tunnel.
 *
 * Remote sockets are protected from VpnService routing, so the relay does not
 * send its own connections back into the TUN. UDP datagrams can be dropped
 * safely. TCP is a byte stream, so its loss setting is represented by a
 * retransmission-like pause while bytes are preserved (never corrupted).
 */
internal class LocalSocks5Proxy(
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val latency: () -> Int,
    private val loss: () -> Int,
    private val bandwidth: () -> Int,
    private val blockDownload: () -> Boolean
) : Closeable {

    companion object {
        private const val TAG = "AnonPing-SOCKS"
        private const val PORT = 1080
        private const val SOCKS_VERSION = 5
        private const val CMD_CONNECT = 1
        private const val CMD_UDP_ASSOCIATE = 3
        private const val REP_OK = 0
        private const val REP_GENERAL_FAILURE = 1
        private const val REP_NOT_ALLOWED = 2
        private const val REP_COMMAND_NOT_SUPPORTED = 7
        private const val REP_ADDRESS_NOT_SUPPORTED = 8
    }

    private val running = AtomicBoolean(false)
    private val workers: ExecutorService = Executors.newCachedThreadPool()
    private val clients = ConcurrentHashMap<Socket, Boolean>()
    private val udpRelays = ConcurrentHashMap<UdpRelay, Boolean>()
    private val throttleLock = Any()

    @Volatile private var listener: ServerSocket? = null
    @Volatile private var nextAvailableNanos: Long = System.nanoTime()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 128)
            listener = server
            workers.execute { acceptLoop(server) }
            Log.i(TAG, "Local SOCKS5 relay listening on 127.0.0.1:$PORT")
        } catch (t: Throwable) {
            running.set(false)
            try { server.close() } catch (_: Exception) {}
            throw t
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            try {
                val client = server.accept()
                client.tcpNoDelay = true
                clients[client] = true
                workers.execute { handleClient(client) }
            } catch (_: SocketException) {
                if (running.get()) Log.w(TAG, "SOCKS listener closed unexpectedly")
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "SOCKS accept failed: ${e.message}")
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val greeting = readExact(input, 2)
            if ((greeting[0].toInt() and 0xff) != SOCKS_VERSION) return
            val methodCount = greeting[1].toInt() and 0xff
            val methods = readExact(input, methodCount)
            if (!methods.contains(0.toByte())) {
                output.write(byteArrayOf(5, 0xff.toByte()))
                output.flush()
                return
            }
            output.write(byteArrayOf(5, 0))
            output.flush()

            val request = readExact(input, 4)
            if ((request[0].toInt() and 0xff) != SOCKS_VERSION) {
                writeReply(output, REP_GENERAL_FAILURE)
                return
            }

            val command = request[1].toInt() and 0xff
            val atyp = request[3].toInt() and 0xff
            if (atyp != 1 && atyp != 3 && atyp != 4) {
                writeReply(output, REP_ADDRESS_NOT_SUPPORTED)
                return
            }
            val address = readAddress(input, atyp)
            val portBytes = readExact(input, 2)
            val port = ((portBytes[0].toInt() and 0xff) shl 8) or
                (portBytes[1].toInt() and 0xff)

            when (command) {
                CMD_CONNECT -> handleConnect(client, output, address, port)
                CMD_UDP_ASSOCIATE -> handleUdpAssociate(client, input, output, address, port)
                else -> writeReply(output, REP_COMMAND_NOT_SUPPORTED)
            }
        } catch (e: EOFException) {
            // Normal client disconnect.
        } catch (e: Exception) {
            if (running.get()) Log.d(TAG, "SOCKS client ended: ${e.message}")
            try { writeReply(client.getOutputStream(), REP_GENERAL_FAILURE) } catch (_: Exception) {}
        } finally {
            clients.remove(client)
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleConnect(
        client: Socket,
        output: OutputStream,
        destination: InetAddress,
        destinationPort: Int
    ) {
        val remote = Socket()
        try {
            if (!protectTcp(remote)) throw IOException("Unable to protect outbound TCP socket")
            remote.tcpNoDelay = true
            remote.keepAlive = true
            remote.connect(InetSocketAddress(destination, destinationPort), 15000)
            writeReply(output, REP_OK)

            val dns = destinationPort == 53
            val download = workers.submit {
                try {
                    pipe(remote.getInputStream(), client.getOutputStream(), isDownload = true, dns = dns)
                } finally {
                    try { client.shutdownOutput() } catch (_: Exception) {}
                }
            }

            try {
                pipe(client.getInputStream(), remote.getOutputStream(), isDownload = false, dns = dns)
            } finally {
                try { remote.shutdownOutput() } catch (_: Exception) {}
            }

            try {
                download.get(30, TimeUnit.SECONDS)
            } catch (_: Exception) {
                download.cancel(true)
            }
        } catch (e: Exception) {
            Log.d(TAG, "TCP connect ${destination.hostAddress}:$destinationPort failed: ${e.message}")
            try { writeReply(output, REP_GENERAL_FAILURE) } catch (_: Exception) {}
        } finally {
            try { remote.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, isDownload: Boolean, dns: Boolean) {
        val buffer = ByteArray(16 * 1024)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val count = input.read(buffer)
            if (count < 0) return
            if (count == 0) continue
            if (isDownload && blockDownload()) continue

            if (!dns) {
                applyLatency()
                // Dropping bytes in a TCP stream corrupts HTTP/TLS and game
                // protocols. Emulate a loss/retransmission pause instead.
                val lossPercent = loss().coerceIn(0, 100)
                if (lossPercent > 0 && ThreadLocalRandom.current().nextInt(100) < lossPercent) {
                    val pause = (100L + lossPercent * 4L).coerceAtMost(700L)
                    Thread.sleep(pause)
                }
            }
            throttle(count)
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private fun handleUdpAssociate(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        requestedAddress: InetAddress,
        requestedPort: Int
    ) {
        val relay = UdpRelay(requestedAddress, requestedPort)
        try {
            relay.start()
            udpRelays[relay] = true
            val bind = relay.boundAddress
            writeReply(output, REP_OK, bind.address, bind.port)

            // SOCKS5 UDP ASSOCIATE remains alive while its TCP control
            // connection is open. Closing it tears down all UDP flows.
            while (running.get() && input.read() >= 0) {
                // No control-channel payload is required.
            }
        } finally {
            udpRelays.remove(relay)
            relay.close()
        }
    }

    private inner class UdpRelay(
        requestedAddress: InetAddress,
        requestedPort: Int
    ) : Closeable {
        private val stopped = AtomicBoolean(false)
        private val flows = ConcurrentHashMap<String, DirectUdpFlow>()
        private val relaySocket: DatagramSocket = DatagramSocket(null as java.net.SocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            soTimeout = 1000
        }

        @Volatile var clientAddress: InetAddress? =
            if (requestedAddress.isAnyLocalAddress || requestedPort == 0) null else requestedAddress
        @Volatile var clientPort: Int = requestedPort

        val boundAddress: InetSocketAddress
            get() = relaySocket.localSocketAddress as InetSocketAddress

        fun start() {
            workers.execute { receiveRequests() }
        }

        private fun receiveRequests() {
            val buffer = ByteArray(65507)
            while (running.get() && !stopped.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    relaySocket.receive(packet)
                    clientAddress = packet.address
                    clientPort = packet.port

                    val parsed = parseUdpFrame(packet.data, packet.length) ?: continue
                    val dns = parsed.port == 53
                    if (!dns && shouldDrop()) continue
                    if (!dns) applyLatency()
                    throttle(parsed.payload.size)

                    val key = "${parsed.address.hostAddress}:${parsed.port}"
                    val flow = getOrCreateFlow(key, parsed.address, parsed.port)
                    flow.send(parsed.payload)
                } catch (_: SocketTimeoutException) {
                } catch (e: SocketException) {
                    if (!stopped.get() && running.get()) Log.w(TAG, "UDP relay socket: ${e.message}")
                    break
                } catch (e: Exception) {
                    if (running.get() && !stopped.get()) Log.d(TAG, "UDP relay packet ignored: ${e.message}")
                }
            }
        }

        private fun getOrCreateFlow(key: String, address: InetAddress, port: Int): DirectUdpFlow {
            return flows[key] ?: synchronized(flows) {
                flows[key] ?: DirectUdpFlow(this, address, port).also { flow ->
                    flows[key] = flow
                    workers.execute { flow.receiveResponses() }
                }
            }
        }

        fun sendResponse(remoteAddress: InetAddress, remotePort: Int, payload: ByteArray) {
            val targetAddress = clientAddress ?: return
            val targetPort = clientPort
            if (targetPort <= 0) return
            val frame = createUdpFrame(remoteAddress.address, remotePort, payload)
            relaySocket.send(DatagramPacket(frame, frame.size, targetAddress, targetPort))
        }

        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            try { relaySocket.close() } catch (_: Exception) {}
            flows.values.forEach { it.close() }
            flows.clear()
        }

        fun isClosed(): Boolean = stopped.get()
    }

    private inner class DirectUdpFlow(
        private val parent: UdpRelay,
        private val destination: InetAddress,
        private val destinationPort: Int
    ) : Closeable {
        private val stopped = AtomicBoolean(false)
        private val socket = DatagramSocket().apply {
            if (!protectUdp(this)) {
                close()
                throw IOException("Unable to protect outbound UDP socket")
            }
            soTimeout = 1000
        }

        fun send(payload: ByteArray) {
            if (stopped.get()) return
            socket.send(DatagramPacket(payload, payload.size, destination, destinationPort))
        }

        fun receiveResponses() {
            val buffer = ByteArray(65507)
            while (running.get() && !stopped.get() && !parent.isClosed()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (blockDownload()) continue

                    val dns = destinationPort == 53 || packet.port == 53
                    if (!dns && shouldDrop()) continue
                    if (!dns) applyLatency()
                    val payload = packet.data.copyOf(packet.length)
                    throttle(payload.size)
                    parent.sendResponse(packet.address, packet.port, payload)
                } catch (_: SocketTimeoutException) {
                } catch (e: SocketException) {
                    if (!stopped.get() && running.get()) Log.d(TAG, "UDP flow closed: ${e.message}")
                    break
                } catch (e: Exception) {
                    if (running.get() && !stopped.get()) Log.d(TAG, "UDP flow packet ignored: ${e.message}")
                }
            }
        }

        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private data class ParsedUdp(val address: InetAddress, val port: Int, val payload: ByteArray)

    private fun parseUdpFrame(data: ByteArray, length: Int): ParsedUdp? {
        if (length < 10 || data[0].toInt() != 0 || data[1].toInt() != 0) return null
        if ((data[2].toInt() and 0xff) != 0) return null // Fragmented SOCKS UDP is not supported.
        var offset = 4
        val atyp = data[3].toInt() and 0xff
        val address: InetAddress = when (atyp) {
            1 -> {
                if (length < offset + 4 + 2) return null
                val bytes = data.copyOfRange(offset, offset + 4)
                offset += 4
                InetAddress.getByAddress(bytes)
            }
            4 -> {
                if (length < offset + 16 + 2) return null
                val bytes = data.copyOfRange(offset, offset + 16)
                offset += 16
                InetAddress.getByAddress(bytes)
            }
            3 -> {
                if (length < offset + 1) return null
                val nameLength = data[offset].toInt() and 0xff
                offset++
                if (nameLength == 0 || length < offset + nameLength + 2) return null
                val name = String(data, offset, nameLength, Charsets.UTF_8)
                offset += nameLength
                InetAddress.getByName(name)
            }
            else -> return null
        }
        if (length < offset + 2) return null
        val port = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        offset += 2
        if (port == 0 || offset > length) return null
        return ParsedUdp(address, port, data.copyOfRange(offset, length))
    }

    private fun createUdpFrame(address: ByteArray, port: Int, payload: ByteArray): ByteArray {
        val atyp = if (address.size == 16) 4 else 1
        val frame = ByteArray(4 + address.size + 2 + payload.size)
        frame[0] = 0
        frame[1] = 0
        frame[2] = 0
        frame[3] = atyp.toByte()
        System.arraycopy(address, 0, frame, 4, address.size)
        val portOffset = 4 + address.size
        frame[portOffset] = (port shr 8).toByte()
        frame[portOffset + 1] = port.toByte()
        System.arraycopy(payload, 0, frame, portOffset + 2, payload.size)
        return frame
    }

    private fun readAddress(input: InputStream, atyp: Int): InetAddress {
        return when (atyp) {
            1 -> InetAddress.getByAddress(readExact(input, 4))
            4 -> InetAddress.getByAddress(readExact(input, 16))
            3 -> {
                val length = input.read()
                if (length <= 0) throw EOFException("Missing SOCKS domain name")
                InetAddress.getByName(String(readExact(input, length), Charsets.UTF_8))
            }
            else -> throw IOException("Unsupported SOCKS address type: $atyp")
        }
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        if (count < 0 || count > 65535) throw IOException("Invalid SOCKS field length")
        val data = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(data, offset, count - offset)
            if (read < 0) throw EOFException("Unexpected end of SOCKS request")
            offset += read
        }
        return data
    }

    private fun writeReply(
        output: OutputStream,
        reply: Int,
        address: InetAddress? = null,
        port: Int = 0
    ) {
        val bytes = address?.address ?: byteArrayOf(0, 0, 0, 0)
        val atyp = if (bytes.size == 16) 4 else 1
        output.write(byteArrayOf(5, reply.toByte(), 0, atyp.toByte()))
        output.write(bytes)
        output.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
        output.flush()
    }

    private fun shouldDrop(): Boolean {
        val percent = loss().coerceIn(0, 100)
        return percent > 0 && ThreadLocalRandom.current().nextInt(100) < percent
    }

    private fun applyLatency() {
        val delay = latency().coerceIn(0, 2000)
        if (delay > 0) Thread.sleep(delay.toLong())
    }

    /**
     * Global byte pacing across streams in both directions. TCP is throttled
     * by bytes written, UDP by datagram payload size.
     */
    private fun throttle(byteCount: Int) {
        if (byteCount <= 0) return
        val limitKbs = bandwidth().coerceIn(0, 10000)
        if (limitKbs <= 0) {
            synchronized(throttleLock) { nextAvailableNanos = System.nanoTime() }
            return
        }

        val deadline = synchronized(throttleLock) {
            val now = System.nanoTime()
            val start = max(now, nextAvailableNanos)
            val duration = byteCount.toLong() * 1_000_000_000L / (limitKbs.toLong() * 1024L)
            nextAvailableNanos = start + duration
            nextAvailableNanos
        }
        val remaining = deadline - System.nanoTime()
        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        try { listener?.close() } catch (_: Exception) {}
        listener = null
        clients.keys.toList().forEach { try { it.close() } catch (_: Exception) {} }
        udpRelays.keys.toList().forEach { it.close() }
        udpRelays.clear()
        workers.shutdownNow()
    }
}
