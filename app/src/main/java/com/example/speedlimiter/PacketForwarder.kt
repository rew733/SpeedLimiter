package com.example.speedlimiter

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class PacketForwarder(
    private val vpnService: VpnService,
    private val rateLimiter: RateLimiter,
    private val maxSessionBytes: Long = Long.MAX_VALUE,
    @Suppress("unused") private val allowedUids: Set<Int> = emptySet(),
    private val blockedUids: Set<Int> = emptySet()
) {
    companion object {
        const val UID_UNKNOWN = -1
        const val UID_NONE = -2
        private const val IO_BUFFER_SIZE = 16384
    }

    private val executor = Executors.newCachedThreadPool()
    private val activeSockets = ConcurrentHashMap<Socket, Int>()
    private val connectivityManager: ConnectivityManager? =
        vpnService.getSystemService(ConnectivityManager::class.java)

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    @Volatile
    var listenPort: Int = 0
        private set

    @Volatile
    var currentForegroundUid: Int = UID_UNKNOWN
        private set

    val totalBytesTransferred = AtomicLong(0L)

    fun startServer(): Int {
        if (running && listenPort > 0) return listenPort
        val srv = ServerSocket()
        srv.reuseAddress = true
        srv.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 128)
        serverSocket = srv
        listenPort = srv.localPort
        running = true

        executor.execute {
            while (running && !srv.isClosed) {
                try {
                    val client = srv.accept()
                    client.tcpNoDelay = true
                    executor.execute { handleSocksClient(client) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }
        return listenPort
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        for (sock in activeSockets.keys) {
            try { sock.close() } catch (_: Exception) {}
        }
        activeSockets.clear()
        try { executor.shutdownNow() } catch (_: Exception) {}
    }

    fun updateForegroundUid(uid: Int) {
        if (uid == currentForegroundUid) return
        currentForegroundUid = uid
        for ((sock, uidOwner) in activeSockets.entries) {
            if (isBlocked(uidOwner)) {
                try { sock.close() } catch (_: Exception) {}
            }
        }
    }

    private fun isBlocked(uid: Int): Boolean {
        if (uid < 0) return false
        if (uid !in blockedUids) return false
        val fg = currentForegroundUid
        if (fg == UID_UNKNOWN) return false
        return uid != fg
    }

    private fun lookupUid(protocol: Int, srcPort: Int, dstIp: InetAddress, dstPort: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return UID_UNKNOWN
        val cm = connectivityManager ?: return UID_UNKNOWN
        val isIpv6 = dstIp.address.size == 16
        val candidates = if (isIpv6) listOf("::1", "fd00:1:fd00:1:fd00:1:fd00:1") else listOf("10.0.0.2", "127.0.0.1")
        for (localHost in candidates) {
            try {
                val uid = cm.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(InetAddress.getByName(localHost), srcPort),
                    InetSocketAddress(dstIp, dstPort)
                )
                if (uid >= 0) return uid
            } catch (_: Exception) {}
        }
        return UID_UNKNOWN
    }

    private fun handleSocksClient(client: Socket) {
        var remote: Socket? = null
        var udpRelay: DatagramSocket? = null
        try {
            client.soTimeout = 30000
            val inStream = client.getInputStream()
            val outStream = client.getOutputStream()

            val ver = inStream.read()
            if (ver != 0x05) return
            val nMethods = inStream.read()
            if (nMethods <= 0) return
            readExact(inStream, nMethods)
            outStream.write(byteArrayOf(0x05, 0x00))
            outStream.flush()

            val reqHead = readExact(inStream, 4)
            if (reqHead[0].toInt() != 0x05) return
            val cmd = reqHead[1].toInt() and 0xFF
            val atyp = reqHead[3].toInt() and 0xFF

            val dstAddr: InetAddress = when (atyp) {
                0x01 -> InetAddress.getByAddress(readExact(inStream, 4))
                0x04 -> InetAddress.getByAddress(readExact(inStream, 16))
                0x03 -> {
                    val len = inStream.read()
                    if (len <= 0) return
                    val host = String(readExact(inStream, len), Charsets.UTF_8)
                    InetAddress.getByName(host)
                }
                else -> return
            }
            val portBytes = readExact(inStream, 2)
            val dstPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            if (cmd == 0x03) {
                udpRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
                val relayPort = udpRelay.localPort
                val reply = byteArrayOf(
                    0x05, 0x00, 0x00, 0x01,
                    127, 0, 0, 1,
                    ((relayPort shr 8) and 0xFF).toByte(),
                    (relayPort and 0xFF).toByte()
                )
                outStream.write(reply)
                outStream.flush()
                relayUdpPackets(client, udpRelay)
                return
            }

            if (cmd != 0x01) {
                outStream.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                outStream.flush()
                return
            }

            val uid = lookupUid(OsConstants.IPPROTO_TCP, client.port, dstAddr, dstPort)
            if (isBlocked(uid)) {
                outStream.write(byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                outStream.flush()
                return
            }

            val target = Socket()
            remote = target
            vpnService.protect(target)
            target.tcpNoDelay = true
            target.soTimeout = 60000
            target.connect(InetSocketAddress(dstAddr, dstPort), 10000)

            activeSockets[target] = uid
            activeSockets[client] = uid

            outStream.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            outStream.flush()
            client.soTimeout = 0

            val upThread = Thread {
                pipeStream(inStream, target.getOutputStream(), uid, limitRate = true, checkSessionLimit = false)
            }.apply { start() }

            pipeStream(target.getInputStream(), outStream, uid, limitRate = true, checkSessionLimit = true)
            try { upThread.join(500) } catch (_: Exception) {}
        } catch (_: Exception) {
        } finally {
            remote?.let { activeSockets.remove(it); try { it.close() } catch (_: Exception) {} }
            activeSockets.remove(client)
            try { client.close() } catch (_: Exception) {}
            try { udpRelay?.close() } catch (_: Exception) {}
        }
    }

    private fun relayUdpPackets(tcpControl: Socket, localUdp: DatagramSocket) {
        val upstream = DatagramSocket()
        vpnService.protect(upstream)
        upstream.soTimeout = 10000
        var clientPacketAddr: InetSocketAddress? = null

        val downThread = Thread {
            val buf = ByteArray(8192)
            val pkt = DatagramPacket(buf, buf.size)
            while (running && !tcpControl.isClosed && !localUdp.isClosed) {
                try {
                    upstream.receive(pkt)
                    val dest = clientPacketAddr ?: continue
                    val srcAddrBytes = pkt.address.address
                    val atyp: Byte = if (srcAddrBytes.size == 16) 0x04 else 0x01
                    val header = ByteBuffer.allocate(4 + srcAddrBytes.size + 2 + pkt.length)
                    header.put(0)
                    header.put(0)
                    header.put(0)
                    header.put(atyp)
                    header.put(srcAddrBytes)
                    header.putShort(pkt.port.toShort())
                    header.put(pkt.data, 0, pkt.length)
                    val resp = header.array()
                    rateLimiter.acquire(resp.size)
                    totalBytesTransferred.addAndGet(pkt.length.toLong())
                    localUdp.send(DatagramPacket(resp, resp.size, dest))
                } catch (_: Exception) {
                    if (tcpControl.isClosed || localUdp.isClosed) break
                }
            }
        }.apply { start() }

        try {
            val buf = ByteArray(8192)
            val pkt = DatagramPacket(buf, buf.size)
            while (running && !tcpControl.isClosed) {
                localUdp.receive(pkt)
                clientPacketAddr = InetSocketAddress(pkt.address, pkt.port)
                if (pkt.length < 10) continue
                val atyp = buf[3].toInt() and 0xFF
                var offset = 4
                val dstAddr: InetAddress = when (atyp) {
                    0x01 -> { val a = buf.copyOfRange(offset, offset + 4); offset += 4; InetAddress.getByAddress(a) }
                    0x04 -> { val a = buf.copyOfRange(offset, offset + 16); offset += 16; InetAddress.getByAddress(a) }
                    0x03 -> { val l = buf[offset].toInt() and 0xFF; offset++; val h = String(buf, offset, l); offset += l; InetAddress.getByName(h) }
                    else -> continue
                }
                val dstPort = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
                offset += 2
                val payloadLen = pkt.length - offset
                if (payloadLen <= 0) continue

                val uid = lookupUid(OsConstants.IPPROTO_UDP, pkt.port, dstAddr, dstPort)
                if (isBlocked(uid)) continue

                rateLimiter.acquire(payloadLen)
                totalBytesTransferred.addAndGet(payloadLen.toLong())
                upstream.send(DatagramPacket(buf, offset, payloadLen, dstAddr, dstPort))
            }
        } catch (_: Exception) {
        } finally {
            try { upstream.close() } catch (_: Exception) {}
            try { downThread.interrupt() } catch (_: Exception) {}
        }
    }

    private fun pipeStream(
        input: InputStream,
        output: OutputStream,
        uid: Int,
        limitRate: Boolean,
        checkSessionLimit: Boolean
    ) {
        val buf = ByteArray(IO_BUFFER_SIZE)
        var sessionBytes = 0L
        try {
            while (running) {
                if (isBlocked(uid)) break
                val read = input.read(buf)
                if (read == -1) break
                if (read > 0) {
                    if (checkSessionLimit && maxSessionBytes != Long.MAX_VALUE && sessionBytes >= maxSessionBytes) {
                        break
                    }
                    if (limitRate) {
                        rateLimiter.acquire(read)
                    }
                    sessionBytes += read
                    totalBytesTransferred.addAndGet(read.toLong())
                    output.write(buf, 0, read)
                    output.flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            try { output.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
        }
    }

    private fun readExact(input: InputStream, len: Int): ByteArray {
        val data = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = input.read(data, off, len - off)
            if (r <= 0) throw java.io.EOFException()
            off += r
        }
        return data
    }
}
