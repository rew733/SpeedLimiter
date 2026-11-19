package com.example.speedlimiter

import android.net.VpnService
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class PacketForwarder(
    private val vpnService: VpnService,
    private val rateLimiter: RateLimiter,
    private val maxSessionBytes: Long = Long.MAX_VALUE
) {
    private val executor = Executors.newCachedThreadPool()
    private val cleaner: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    @Volatile private var running = false

    val totalBytesTransferred = AtomicLong(0L)

    fun start(inputStream: FileInputStream, outputStream: FileOutputStream) {
        running = true

        cleaner.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            val it = tcpSessions.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (now - entry.value.lastActivityTime > 15_000L) {
                    entry.value.close()
                    it.remove()
                }
            }
        }, 15, 15, TimeUnit.SECONDS)

        val packet = ByteBuffer.allocate(32767)

        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val length = inputStream.read(packet.array())
                if (length > 0) {
                    val data = packet.array().copyOf(length)
                    handleOutgoingPacket(data, outputStream)
                    packet.clear()
                } else {
                    Thread.sleep(2)
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                Thread.sleep(5)
            }
        }
    }

    fun refreshSessions() {
        val it = tcpSessions.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.value.close()
            it.remove()
        }
    }

    fun stop() {
        running = false
        try { cleaner.shutdownNow() } catch (_: Exception) {}
        refreshSessions()
        try { executor.shutdownNow() } catch (_: Exception) {}
    }

    private fun handleOutgoingPacket(rawPacket: ByteArray, outputStream: FileOutputStream) {
        if (rawPacket.size < 20) return
        val version = (rawPacket[0].toInt() shr 4) and 0x0F
        if (version != 4) return

        val ipHeaderLen = (rawPacket[0].toInt() and 0x0F) * 4
        if (rawPacket.size < ipHeaderLen) return
        val protocol = rawPacket[9].toInt() and 0xFF
        val srcIp = ByteArray(4) { rawPacket[12 + it] }
        val dstIp = ByteArray(4) { rawPacket[16 + it] }

        when (protocol) {
            17 -> handleUdpPacket(rawPacket, ipHeaderLen, srcIp, dstIp, outputStream)
            6 -> handleTcpPacket(rawPacket, ipHeaderLen, srcIp, dstIp, outputStream)
        }
    }

    private fun handleUdpPacket(
        rawPacket: ByteArray,
        ipHeaderLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        outputStream: FileOutputStream
    ) {
        if (rawPacket.size < ipHeaderLen + 8) return
        val srcPort = ((rawPacket[ipHeaderLen].toInt() and 0xFF) shl 8) or (rawPacket[ipHeaderLen + 1].toInt() and 0xFF)
        val dstPort = ((rawPacket[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (rawPacket[ipHeaderLen + 3].toInt() and 0xFF)

        // السماح بمنفذ DNS (53) فقط وإسقاط بقية منافذ UDP لإجبار البث المباشر على المرور عبر TCP المقيّد
        if (dstPort != 53) return

        val payloadOffset = ipHeaderLen + 8
        val payloadLen = rawPacket.size - payloadOffset
        if (payloadLen <= 0) return
        val payload = rawPacket.copyOfRange(payloadOffset, rawPacket.size)

        if (isDnsAaaaQuery(payload)) {
            val emptyDnsResponse = buildEmptyDnsResponse(payload)
            val ipResponse = buildUdpIpPacket(dstIp, dstPort, srcIp, srcPort, emptyDnsResponse)
            writeToTunSafe(outputStream, ipResponse)
            return
        }

        executor.execute {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                vpnService.protect(socket)
                socket.soTimeout = 1500

                val dstAddress = InetAddress.getByAddress(dstIp)
                socket.send(DatagramPacket(payload, payload.size, dstAddress, dstPort))
                totalBytesTransferred.addAndGet(payload.size.toLong())

                val respBuf = ByteArray(2048)
                val respPacket = DatagramPacket(respBuf, respBuf.size)
                socket.receive(respPacket)

                val respData = respPacket.data.copyOf(respPacket.length)
                totalBytesTransferred.addAndGet(respData.size.toLong())
                val ipResponse = buildUdpIpPacket(dstIp, dstPort, srcIp, srcPort, respData)
                writeToTunSafe(outputStream, ipResponse)
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun writeToTunSafe(outputStream: FileOutputStream, packet: ByteArray) {
        synchronized(outputStream) {
            try {
                outputStream.write(packet)
            } catch (_: Exception) {}
        }
    }

    private fun isDnsAaaaQuery(dnsPayload: ByteArray): Boolean {
        if (dnsPayload.size < 16) return false
        var pos = 12
        while (pos < dnsPayload.size) {
            val len = dnsPayload[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if ((len and 0xC0) == 0xC0) {
                pos += 2
                break
            }
            pos += len + 1
        }
        if (pos + 2 > dnsPayload.size) return false
        val qType = ((dnsPayload[pos].toInt() and 0xFF) shl 8) or (dnsPayload[pos + 1].toInt() and 0xFF)
        return qType == 28
    }

    private fun buildEmptyDnsResponse(query: ByteArray): ByteArray {
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 5
                break
            }
            if ((len and 0xC0) == 0xC0) {
                pos += 6
                break
            }
            pos += len + 1
        }
        val endPos = pos.coerceAtMost(query.size)
        val resp = query.copyOf(endPos)
        if (resp.size >= 12) {
            resp[2] = 0x81.toByte()
            resp[3] = 0x80.toByte()
            resp[6] = 0; resp[7] = 0
            resp[8] = 0; resp[9] = 0
            resp[10] = 0; resp[11] = 0
        }
        return resp
    }

    private fun handleTcpPacket(
        rawPacket: ByteArray,
        ipHeaderLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        outputStream: FileOutputStream
    ) {
        if (rawPacket.size < ipHeaderLen + 20) return
        val srcPort = ((rawPacket[ipHeaderLen].toInt() and 0xFF) shl 8) or (rawPacket[ipHeaderLen + 1].toInt() and 0xFF)
        val dstPort = ((rawPacket[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (rawPacket[ipHeaderLen + 3].toInt() and 0xFF)
        val seqNum = ByteBuffer.wrap(rawPacket, ipHeaderLen + 4, 4).int.toLong() and 0xFFFFFFFFL
        val tcpHeaderLen = ((rawPacket[ipHeaderLen + 12].toInt() shr 4) and 0x0F) * 4
        if (rawPacket.size < ipHeaderLen + tcpHeaderLen) return
        val flags = rawPacket[ipHeaderLen + 13].toInt() and 0xFF

        val syn = (flags and 0x02) != 0
        val ack = (flags and 0x10) != 0
        val fin = (flags and 0x01) != 0
        val rst = (flags and 0x04) != 0

        val payloadOffset = ipHeaderLen + tcpHeaderLen
        val payload = if (rawPacket.size > payloadOffset) {
            rawPacket.copyOfRange(payloadOffset, rawPacket.size)
        } else {
            ByteArray(0)
        }

        val sessionKey = "${InetAddress.getByAddress(srcIp).hostAddress}:$srcPort-${InetAddress.getByAddress(dstIp).hostAddress}:$dstPort"

        if (rst) {
            tcpSessions.remove(sessionKey)?.close()
            return
        }

        if (syn && !ack) {
            tcpSessions.remove(sessionKey)?.close()
            val session = TcpSession(sessionKey, srcIp, srcPort, dstIp, dstPort, seqNum, outputStream)
            tcpSessions[sessionKey] = session
            executor.execute { session.connect() }
            return
        }

        val session = tcpSessions[sessionKey] ?: return
        session.lastActivityTime = System.currentTimeMillis()

        if (fin) {
            session.handleClientFin(seqNum)
            tcpSessions.remove(sessionKey)
            return
        }

        if (payload.isNotEmpty()) {
            session.sendToRemote(payload, seqNum)
        }
    }

    inner class TcpSession(
        private val key: String,
        private val clientIp: ByteArray,
        private val clientPort: Int,
        private val remoteIp: ByteArray,
        private val remotePort: Int,
        initialClientSeq: Long,
        private val outputStream: FileOutputStream
    ) {
        private val socket = Socket()
        private var mySeq: Long = 10000L
        private var clientNextSeq: Long = (initialClientSeq + 1) and 0xFFFFFFFFL
        private var totalDownloadedBytes: Long = 0L
        @Volatile var lastActivityTime: Long = System.currentTimeMillis()
        @Volatile private var connected = false

        fun connect() {
            try {
                rateLimiter.acquire(1024)
                vpnService.protect(socket)
                socket.tcpNoDelay = true
                socket.soTimeout = 15000
                socket.receiveBufferSize = 2048
                socket.connect(InetSocketAddress(InetAddress.getByAddress(remoteIp), remotePort), 4000)
                connected = true
                lastActivityTime = System.currentTimeMillis()

                val synAck = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x12, ByteArray(0))
                mySeq = (mySeq + 1) and 0xFFFFFFFFL
                writeToTunSafe(outputStream, synAck)

                val inStream = socket.getInputStream()
                val buffer = ByteArray(1024)
                while (running && connected && !socket.isClosed) {
                    val read = inStream.read(buffer)
                    lastActivityTime = System.currentTimeMillis()
                    if (read == -1) {
                        val finPkt = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x11, ByteArray(0))
                        mySeq = (mySeq + 1) and 0xFFFFFFFFL
                        writeToTunSafe(outputStream, finPkt)
                        break
                    }
                    if (read > 0) {
                        if (maxSessionBytes != Long.MAX_VALUE && totalDownloadedBytes >= maxSessionBytes) {
                            val rstPkt = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x14, ByteArray(0))
                            writeToTunSafe(outputStream, rstPkt)
                            break
                        }
                        rateLimiter.acquire(read)
                        totalDownloadedBytes += read
                        totalBytesTransferred.addAndGet(read.toLong())
                        val chunk = buffer.copyOf(read)
                        val dataPkt = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x18, chunk)
                        mySeq = (mySeq + read) and 0xFFFFFFFFL
                        writeToTunSafe(outputStream, dataPkt)
                    }
                }
            } catch (_: Exception) {
            } finally {
                close()
                tcpSessions.remove(key)
            }
        }

        @Synchronized
        fun sendToRemote(data: ByteArray, seq: Long) {
            if (!connected || socket.isClosed) return
            try {
                lastActivityTime = System.currentTimeMillis()
                if (seq == clientNextSeq) {
                    rateLimiter.acquire(data.size)
                    clientNextSeq = (seq + data.size) and 0xFFFFFFFFL
                    socket.getOutputStream().write(data)
                    socket.getOutputStream().flush()
                    totalBytesTransferred.addAndGet(data.size.toLong())
                }
                val ackPkt = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x10, ByteArray(0))
                writeToTunSafe(outputStream, ackPkt)
            } catch (_: Exception) {
                close()
            }
        }

        @Synchronized
        fun handleClientFin(seq: Long) {
            try {
                clientNextSeq = (seq + 1) and 0xFFFFFFFFL
                val finAck = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x11, ByteArray(0))
                writeToTunSafe(outputStream, finAck)
            } catch (_: Exception) {}
            close()
        }

        fun close() {
            connected = false
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun buildUdpIpPacket(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val buf = ByteBuffer.allocate(totalLen)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(17.toByte())
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)

        val ipChecksum = computeChecksum(buf.array(), 0, 20)
        buf.putShort(10, ipChecksum)

        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)
        buf.put(payload)

        return buf.array()
    }

    private fun buildTcpIpPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = 40 + payload.size
        val buf = ByteBuffer.allocate(totalLen)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(6.toByte())
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)

        val ipChecksum = computeChecksum(buf.array(), 0, 20)
        buf.putShort(10, ipChecksum)

        buf.putShort
