package com.example.speedlimiter

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Process
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class PacketForwarder(
    private val vpnService: VpnService,
    private val rateLimiter: RateLimiter,
    private val maxSessionBytes: Long = Long.MAX_VALUE,
    private val allowedUids: Set<Int> = emptySet(),
    private val blockedUids: Set<Int> = emptySet(),
    private val connectivityManager: ConnectivityManager? = null
) {
    private val executor = Executors.newCachedThreadPool()
    private val cleaner: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val uidSessionMap = ConcurrentHashMap<String, Int>()
    // قصر القنوات المتزامنة لتوجيه النطاق الترددي نحو الفيديو قيد التشغيل وتقليل التحميل المسبق للخلفية
    private val connectionSlots = Semaphore(3)
    @Volatile private var running = false
    @Volatile var isScreenOn = true
    @Volatile var activeForegroundUid: Int = Process.INVALID_UID
    @Volatile var hasUsagePermission: Boolean = false

    val totalBytesTransferred = AtomicLong(0L)

    fun start(inputStream: FileInputStream, outputStream: FileOutputStream) {
        running = true

        cleaner.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            val it = tcpSessions.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val sessionUid = uidSessionMap[entry.key] ?: Process.INVALID_UID
                val expired = now - entry.value.lastActivityTime > 120_000L
                val isBlockedNow = !isScreenOn || isUidBlockedInBackground(sessionUid)
                if (expired || isBlockedNow) {
                    entry.value.close()
                    it.remove()
                    uidSessionMap.remove(entry.key)
                }
            }
        }, 3, 3, TimeUnit.SECONDS)

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

    fun closeSessionsForUid(targetUid: Int) {
        if (targetUid == Process.INVALID_UID) return
        val it = tcpSessions.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (uidSessionMap[entry.key] == targetUid) {
                entry.value.close()
                it.remove()
                uidSessionMap.remove(entry.key)
            }
        }
    }

    fun closeAllSessionsImmediately() {
        val it = tcpSessions.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.value.close()
            it.remove()
        }
        uidSessionMap.clear()
    }

    fun refreshSessions() {
        val now = System.currentTimeMillis()
        val it = tcpSessions.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val sessionUid = uidSessionMap[entry.key] ?: Process.INVALID_UID
            if (now - entry.value.lastActivityTime > 60_000L || !isScreenOn || isUidBlockedInBackground(sessionUid)) {
                entry.value.close()
                it.remove()
                uidSessionMap.remove(entry.key)
            }
        }
    }

    fun stop() {
        running = false
        try { cleaner.shutdownNow() } catch (_: Exception) {}
        closeAllSessionsImmediately()
        try { executor.shutdownNow() } catch (_: Exception) {}
    }

    private fun resolvePacketOwnerUid(
        protocol: Int,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int
    ): Int {
        if (connectivityManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Process.INVALID_UID
        }
        return try {
            val local = InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort)
            val remote = InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
            connectivityManager.getConnectionOwnerUid(protocol, local, remote)
        } catch (_: Exception) {
            Process.INVALID_UID
        }
    }

    /**
     * حظر صارم لحزم التطبيقات في الخلفية:
     * - عند إطفاء الشاشة: حظر كامل.
     * - عند معرفة UID لتطبيق مستخدم: يُحظر إذا كان ضمن blockedUids أو (عند وجود قائمة allowedUids أو تحديد تطبيق نشط في الواجهة) لم يكن ضمن allowedUids أو activeForegroundUid.
     * - خدمات النظام الأساسية (uid < FIRST_APPLICATION_UID) تمر دون تعطيل الجهاز.
     */
    private fun isUidBlockedInBackground(uid: Int): Boolean {
        if (!isScreenOn) return true
        if (uid == Process.INVALID_UID || uid < Process.FIRST_APPLICATION_UID) return false

        if (blockedUids.contains(uid)) return true

        if (hasUsagePermission && activeForegroundUid != Process.INVALID_UID) {
            return uid != activeForegroundUid
        }

        if (allowedUids.isNotEmpty()) {
            return !allowedUids.contains(uid)
        }

        return false
    }

    private fun isUidPermittedNow(uid: Int): Boolean {
        return !isUidBlockedInBackground(uid)
    }

    private fun handleOutgoingPacket(rawPacket: ByteArray, outputStream: FileOutputStream) {
        if (rawPacket.size < 20) return
        val version = (rawPacket[0].toInt() shr 4) and 0x0F

        if (version == 6) {
            handleIpv6Packet(rawPacket, outputStream)
            return
        }

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

    private fun handleIpv6Packet(rawPacket: ByteArray, outputStream: FileOutputStream) {
        if (rawPacket.size < 40) return
        val nextHeader = rawPacket[6].toInt() and 0xFF
        val srcIp = rawPacket.copyOfRange(8, 24)
        val dstIp = rawPacket.copyOfRange(24, 40)

        if (nextHeader == 6 && rawPacket.size >= 60) {
            val srcPort = ((rawPacket[40].toInt() and 0xFF) shl 8) or (rawPacket[41].toInt() and 0xFF)
            val dstPort = ((rawPacket[42].toInt() and 0xFF) shl 8) or (rawPacket[43].toInt() and 0xFF)
            val seqNum = ByteBuffer.wrap(rawPacket, 44, 4).int.toLong() and 0xFFFFFFFFL
            val flags = rawPacket[53].toInt() and 0xFF
            val rst = (flags and 0x04) != 0
            if (!rst) {
                val nextSeq = (seqNum + 1) and 0xFFFFFFFFL
                val rstV6 = buildTcpIpv6Packet(dstIp, dstPort, srcIp, srcPort, 0L, nextSeq, 0x14)
                writeToTunSafe(outputStream, rstV6)
            }
        } else if (nextHeader == 17 && rawPacket.size >= 48) {
            val icmpV6 = buildIcmpv6PortUnreachable(rawPacket, dstIp, srcIp)
            if (icmpV6 != null) {
                writeToTunSafe(outputStream, icmpV6)
            }
        }
    }

    private fun buildTcpIpv6Packet(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int
    ): ByteArray {
        val buf = ByteBuffer.allocate(60)
        buf.put(0x60.toByte())
        buf.put(0.toByte())
        buf.putShort(0)
        buf.putShort(20.toShort())
        buf.put(6.toByte())
        buf.put(64.toByte())
        buf.put(srcIp)
        buf.put(dstIp)

        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put(0x50.toByte())
        buf.put(flags.toByte())
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)

        val checksum = computeIpv6UpperChecksum(srcIp, dstIp, 6, buf.array(), 40, 20)
        buf.putShort(56, checksum)
        return buf.array()
    }

    private fun buildIcmpv6PortUnreachable(originalPacket: ByteArray, srcIp: ByteArray, dstIp: ByteArray): ByteArray? {
        val quotedLen = originalPacket.size.coerceAtMost(1200)
        if (quotedLen < 48) return null
        val icmpLen = 8 + quotedLen
        val totalLen = 40 + icmpLen
        val buf = ByteBuffer.allocate(totalLen)

        buf.put(0x60.toByte())
        buf.put(0.toByte())
        buf.putShort(0)
        buf.putShort(icmpLen.toShort())
        buf.put(58.toByte())
        buf.put(64.toByte())
        buf.put(srcIp)
        buf.put(dstIp)

        buf.put(1.toByte())
        buf.put(4.toByte())
        buf.putShort(0)
        buf.putInt(0)
        buf.put(originalPacket, 0, quotedLen)

        val checksum = computeIpv6UpperChecksum(srcIp, dstIp, 58, buf.array(), 40, icmpLen)
        buf.putShort(42, checksum)
        return buf.array()
    }

    private fun computeIpv6UpperChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        nextHeader: Int,
        data: ByteArray,
        offset: Int,
        length: Int
    ): Short {
        var sum = 0L
        for (i in 0 until 16 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += (length shr 16) and 0xFFFF
        sum += length and 0xFFFF
        sum += nextHeader.toLong()

        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 == 1) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
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

        val ownerUid = resolvePacketOwnerUid(17, srcIp, srcPort, dstIp, dstPort)
        if (!isUidPermittedNow(ownerUid)) {
            return
        }

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
                socket.soTimeout = 3000

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

        val sessionKey = "${InetAddress.getByAddress(srcIp).hostAddress}:$srcPort-${InetAddress.getByAddress(dstIp).hostAddress}:$dstPort"
        val ownerUid = resolvePacketOwnerUid(6, srcIp, srcPort, dstIp, dstPort)

        if (!isUidPermittedNow(ownerUid)) {
            tcpSessions.remove(sessionKey)?.close()
            uidSessionMap.remove(sessionKey)
            if (!rst) {
                val nextSeq = (seqNum + 1) and 0xFFFFFFFFL
                val rstPkt = buildTcpIpPacket(dstIp, dstPort, srcIp, srcPort, 0L, nextSeq, 0x14, ByteArray(0))
                writeToTunSafe(outputStream, rstPkt)
            }
            return
        }

        val payloadOffset = ipHeaderLen + tcpHeaderLen
        val payload = if (rawPacket.size > payloadOffset) {
            rawPacket.copyOfRange(payloadOffset, rawPacket.size)
        } else {
            ByteArray(0)
        }

        if (rst) {
            tcpSessions.remove(sessionKey)?.close()
            uidSessionMap.remove(sessionKey)
            return
        }

        if (syn && !ack) {
            val existing = tcpSessions[sessionKey]
            if (existing != null && existing.initialSeq == seqNum) {
                existing.resendSynAckIfReady()
                return
            }
            existing?.close()
            val session = TcpSession(sessionKey, ownerUid, srcIp, srcPort, dstIp, dstPort, seqNum, outputStream)
            tcpSessions[sessionKey] = session
            uidSessionMap[sessionKey] = ownerUid
            executor.execute { session.connect() }
            return
        }

        val session = tcpSessions[sessionKey] ?: return
        session.lastActivityTime = System.currentTimeMillis()

        if (fin) {
            session.handleClientFin(seqNum)
            tcpSessions.remove(sessionKey)
            uidSessionMap.remove(sessionKey)
            return
        }

        if (payload.isNotEmpty()) {
            session.sendToRemote(payload, seqNum)
        }
    }

    inner class TcpSession(
        private val key: String,
        private val ownerUid: Int,
        private val clientIp: ByteArray,
        private val clientPort: Int,
        private val remoteIp: ByteArray,
        private val remotePort: Int,
        val initialSeq: Long,
        private val outputStream: FileOutputStream
    ) {
        private val socket = Socket()
        private var mySeq: Long = 10000L
        private var clientNextSeq: Long = (initialSeq + 1) and 0xFFFFFFFFL
        private var totalDownloadedBytes: Long = 0L
        @Volatile var lastActivityTime: Long = System.currentTimeMillis()
        @Volatile private var connected = false
        @Volatile private var slotAcquired = false

        fun resendSynAckIfReady() {
            if (connected) {
                val synAck = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, 10000L, clientNextSeq, 0x12, ByteArray(0))
                writeToTunSafe(outputStream, synAck)
            }
        }

        fun connect() {
            try {
                if (!isUidPermittedNow(ownerUid)) return
                connectionSlots.acquire()
                slotAcquired = true

                vpnService.protect(socket)
                socket.tcpNoDelay = true
                socket.soTimeout = 60000
                socket.receiveBufferSize = 2048
                socket.sendBufferSize = 4096
                socket.connect(InetSocketAddress(InetAddress.getByAddress(remoteIp), remotePort), 12000)
                connected = true
                lastActivityTime = System.currentTimeMillis()

                val synAck = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x12, ByteArray(0))
                mySeq = (mySeq + 1) and 0xFFFFFFFFL
                writeToTunSafe(outputStream, synAck)

                val inStream = socket.getInputStream()
                val buffer = ByteArray(1024)
                while (running && connected && !socket.isClosed) {
                    if (!isUidPermittedNow(ownerUid)) {
                        val rstPkt = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x14, ByteArray(0))
                        writeToTunSafe(outputStream, rstPkt)
                        break
                    }
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
                        try {
                            rateLimiter.acquire(read)
                        } catch (_: Exception) {}
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
                uidSessionMap.remove(key)
            }
        }

        @Synchronized
        fun sendToRemote(data: ByteArray, seq: Long) {
            if (!connected || socket.isClosed) return
            if (!isUidPermittedNow(ownerUid)) {
                close()
                return
            }
            try {
                lastActivityTime = System.currentTimeMillis()
                socket.getOutputStream().write(data)
                socket.getOutputStream().flush()
                totalBytesTransferred.addAndGet(data.size.toLong())
                clientNextSeq = (seq + data.size) and 0xFFFFFFFFL
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
            if (slotAcquired) {
                slotAcquired = false
                try { connectionSlots.release() } catch (_: Exception) {}
            }
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

        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put(0x50.toByte())
        buf.put(flags.toByte())
        buf.putShort(2048.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.put(payload)

        val tcpChecksum = computeTcpChecksum(srcIp, dstIp, buf.array(), 20, 20 + payload.size)
        buf.putShort(36, tcpChecksum)

        return buf.array()
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 == 1) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }

    private fun computeTcpChecksum(srcIp: ByteArray, dstIp: ByteArray, data: ByteArray, tcpOffset: Int, tcpLength: Int): Short {
        var sum = 0L
        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 6L
        sum += tcpLength.toLong()

        var i = tcpOffset
        while (i < tcpOffset + tcpLength - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (tcpLength % 2 == 1) {
            sum += (data[tcpOffset + tcpLength - 1].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }
}
