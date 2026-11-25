package com.example.speedlimiter

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PacketForwarder(
    private val vpnService: VpnService,
    private val rateLimiter: RateLimiter,
    private val maxSessionBytes: Long = Long.MAX_VALUE,
    @Suppress("unused") private val allowedUids: Set<Int> = emptySet(),
    private val blockedUids: Set<Int> = emptySet()
) {
    companion object {
        /** صلاحية الوصول لبيانات الاستخدام غير ممنوحة: لا يمكن معرفة تطبيق المقدمة، فلا يُحظر شيء */
        const val UID_UNKNOWN = -1

        /** لا يوجد أي تطبيق في المقدمة (الشاشة مطفأة مثلاً): تُحظر كل التطبيقات المحظورة */
        const val UID_NONE = -2

        /**
         * أقصى عدد اتصالات TCP متزامنة لكل تطبيق (كان 2 لكل التطبيقات مجتمعة).
         * أنزله إلى 2 إن أردت منع التحميل المسبق للفيديوهات بشكل أشد.
         */
        private const val MAX_CONNECTIONS_PER_APP = 4

        /** عند تعذّر معرفة صاحب الاتصال (أندرويد أقدم من 10) تُشارك كل التطبيقات هذا الحد */
        private const val MAX_CONNECTIONS_UNKNOWN_UID = 32

        private const val SLOT_WAIT_SECONDS = 10L
        private const val UID_CACHE_TTL_MS = 30_000L
    }

    private class CachedUid(val uid: Int, val time: Long)

    private val executor = Executors.newCachedThreadPool()
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()

    // قنوات اتصال مستقلة لكل تطبيق: تطبيق واحد لا يستطيع تجويع بقية التطبيقات
    private val connectionSlots = ConcurrentHashMap<Int, Semaphore>()
    private val uidCache = ConcurrentHashMap<String, CachedUid>()
    private val connectivityManager: ConnectivityManager? =
        vpnService.getSystemService(ConnectivityManager::class.java)

    @Volatile private var running = false

    @Volatile
    var currentForegroundUid: Int = UID_UNKNOWN
        private set

    val totalBytesTransferred = AtomicLong(0L)

    fun start(inputStream: FileInputStream, outputStream: FileOutputStream) {
        running = true
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

    fun stop() {
        running = false
        tcpSessions.values.forEach { it.close() }
        tcpSessions.clear()
        executor.shutdownNow()
    }

    /** يُستدعى من مراقب التطبيق الأمامي. عند تغيّر التطبيق تُقطع فوراً اتصالات التطبيقات المحظورة المفتوحة. */
    fun updateForegroundUid(uid: Int) {
        if (uid == currentForegroundUid) return
        currentForegroundUid = uid
        for (session in tcpSessions.values) {
            if (isBlocked(session.uid)) session.reset()
        }
    }

    /** هل يجب حظر هذا التطبيق الآن؟ (محظور + ليس في المقدمة) */
    private fun isBlocked(uid: Int): Boolean {
        if (uid < 0) return false                 // صاحب الاتصال مجهول: لا نحظر (fail-open)
        if (uid !in blockedUids) return false
        val fg = currentForegroundUid
        if (fg == UID_UNKNOWN) return false       // لا نعرف تطبيق المقدمة: لا نحظر
        return uid != fg
    }

    /** يحدد UID صاحب الاتصال (أندرويد 10+). يُرجع UID_UNKNOWN إن تعذّر. */
    private fun lookupUid(protocol: Int, srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return UID_UNKNOWN
        val cm = connectivityManager ?: return UID_UNKNOWN

        val isUdp = protocol == OsConstants.IPPROTO_UDP
        val cacheKey = "u:$srcPort"
        val now = System.currentTimeMillis()
        if (isUdp) {
            val cached = uidCache[cacheKey]
            if (cached != null && now - cached.time < UID_CACHE_TTL_MS) return cached.uid
        }

        val uid = try {
            cm.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort),
                InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
            )
        } catch (_: Exception) {
            UID_UNKNOWN
        }

        if (isUdp && uid >= 0) {
            if (uidCache.size > 1024) uidCache.clear()
            uidCache[cacheKey] = CachedUid(uid, now)
        }
        return if (uid >= 0) uid else UID_UNKNOWN
    }

    private fun slotFor(uid: Int): Semaphore =
        connectionSlots.getOrPut(uid) {
            Semaphore(if (uid < 0) MAX_CONNECTIONS_UNKNOWN_UID else MAX_CONNECTIONS_PER_APP)
        }

    private fun writePacket(outputStream: FileOutputStream, pkt: ByteArray) {
        synchronized(outputStream) {
            try {
                outputStream.write(pkt)
            } catch (_: Exception) {}
        }
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

    /**
     * IPv6 غير مُمرَّر عبر هذا المُرسِل. بدل إسقاط الحزم بصمت (فيعلق التطبيق حتى انتهاء المهلة)
     * نردّ بـ ICMPv6 "unreachable" فيتراجع التطبيق إلى IPv4 فوراً.
     */
    private fun handleIpv6Packet(rawPacket: ByteArray, outputStream: FileOutputStream) {
        if (rawPacket.size < 40) return
        val nextHeader = rawPacket[6].toInt() and 0xFF
        if (nextHeader != 6 && nextHeader != 17) return
        writePacket(outputStream, buildIcmp6Unreachable(rawPacket))
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

        // QUIC (UDP/443): نردّ بـ ICMP unreachable ليعود التطبيق إلى TCP فوراً
        if (dstPort == 443) {
            writePacket(outputStream, buildIcmpPortUnreachable(rawPacket, ipHeaderLen))
            return
        }

        val uid = lookupUid(OsConstants.IPPROTO_UDP, srcIp, srcPort, dstIp, dstPort)
        if (isBlocked(uid)) {
            writePacket(outputStream, buildIcmpPortUnreachable(rawPacket, ipHeaderLen))
            return
        }

        val payloadOffset = ipHeaderLen + 8
        val payloadLen = rawPacket.size - payloadOffset
        if (payloadLen <= 0) return
        val payload = rawPacket.copyOfRange(payloadOffset, rawPacket.size)

        executor.execute {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                vpnService.protect(socket)
                socket.soTimeout = 5000

                val dstAddress = InetAddress.getByAddress(dstIp)
                socket.send(DatagramPacket(payload, payload.size, dstAddress, dstPort))
                totalBytesTransferred.addAndGet(payload.size.toLong())

                val respBuf = ByteArray(4096)
                val respPacket = DatagramPacket(respBuf, respBuf.size)
                socket.receive(respPacket)

                // إن أصبح التطبيق محظوراً أثناء الانتظار فلا نُسلّمه الرد
                if (isBlocked(uid)) return@execute

                val respData = respPacket.data.copyOf(respPacket.length)
                totalBytesTransferred.addAndGet(respData.size.toLong())
                val ipResponse = buildUdpIpPacket(dstIp, dstPort, srcIp, srcPort, respData)
                writePacket(outputStream, ipResponse)
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
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
            val existing = tcpSessions[sessionKey]
            if (existing != null && existing.clientIsn == seqNum) {
                // إعادة إرسال SYN نفسه: لا ننشئ جلسة جديدة (كانت تسبب جلسات "زومبي" تستنزف القنوات)
                existing.resendSynAckIfConnected()
                return
            }
            if (existing != null) {
                tcpSessions.remove(sessionKey, existing)
                existing.close()
            }

            val uid = lookupUid(OsConstants.IPPROTO_TCP, srcIp, srcPort, dstIp, dstPort)
            if (isBlocked(uid)) {
                // RST فوري ليفشل الاتصال بسرعة بدل أن يعيد التطبيق المحاولة
                val rstPkt = buildTcpIpPacket(
                    dstIp, dstPort, srcIp, srcPort,
                    0L, (seqNum + 1) and 0xFFFFFFFFL, 0x14, ByteArray(0)
                )
                writePacket(outputStream, rstPkt)
                return
            }

            val session = TcpSession(sessionKey, uid, srcIp, srcPort, dstIp, dstPort, seqNum, outputStream)
            tcpSessions[sessionKey] = session
            executor.execute { session.connect() }
            return
        }

        val session = tcpSessions[sessionKey] ?: return

        if (isBlocked(session.uid)) {
            session.reset()
            return
        }

        if (fin) {
            session.handleClientFin(seqNum)
            tcpSessions.remove(sessionKey, session)
            return
        }

        if (payload.isNotEmpty()) {
            session.sendToRemote(payload, seqNum)
        }
    }

    inner class TcpSession(
        private val key: String,
        val uid: Int,
        private val clientIp: ByteArray,
        private val clientPort: Int,
        private val remoteIp: ByteArray,
        private val remotePort: Int,
        initialClientSeq: Long,
        private val outputStream: FileOutputStream
    ) {
        val clientIsn: Long = initialClientSeq

        private val socket = Socket()
        private val initialSeq: Long = 10000L
        private var mySeq: Long = initialSeq
        private var clientNextSeq: Long = (initialClientSeq + 1) and 0xFFFFFFFFL
        private var totalDownloadedBytes: Long = 0L
        private val slot: Semaphore = slotFor(uid)
        private val slotHeld = AtomicBoolean(false)

        @Volatile private var connected = false
        @Volatile private var closed = false
        @Volatile private var handshakeDone = false

        fun connect() {
            try {
                // انتظار قناة متاحة لهذا التطبيق (مهلة محددة بدل الانتظار للأبد)
                if (!slot.tryAcquire(SLOT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    if (!closed) sendRst()
                    return
                }
                slotHeld.set(true)

                // أُغلقت الجلسة (إعادة SYN / حظر / إيقاف) أثناء الانتظار: لا نتصل
                if (closed || !running) return

                vpnService.protect(socket)
                socket.tcpNoDelay = true
                // ضبط حجم مخزن الاستقبال قبل الاتصال لإجبار نواة لينكس على إعلان نافذة صغيرة للخادم
                socket.receiveBufferSize = 1024
                socket.connect(InetSocketAddress(InetAddress.getByAddress(remoteIp), remotePort), 8000)
                if (closed) return
                connected = true

                val synAck = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x12, ByteArray(0))
                mySeq = (mySeq + 1) and 0xFFFFFFFFL
                writeToTun(synAck)
                handshakeDone = true

                val inStream = socket.getInputStream()
                val buffer = ByteArray(512)
                while (running && connected && !socket.isClosed) {
                    val read = inStream.read(buffer)
                    if (read == -1) {
                        val finPkt = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x11, ByteArray(0))
                        mySeq = (mySeq + 1) and 0xFFFFFFFFL
                        writeToTun(finPkt)
                        break
                    }
                    if (read > 0) {
                        if (isBlocked(uid)) {
                            sendRst()
                            break
                        }
                        if (maxSessionBytes != Long.MAX_VALUE && totalDownloadedBytes >= maxSessionBytes) {
                            sendRst()
                            break
                        }
                        rateLimiter.acquire(read)
                        totalDownloadedBytes += read
                        totalBytesTransferred.addAndGet(read.toLong())
                        val chunk = buffer.copyOf(read)
                        val dataPkt = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x18, chunk)
                        mySeq = (mySeq + read) and 0xFFFFFFFFL
                        writeToTun(dataPkt)
                    }
                }
            } catch (_: Exception) {
                // فشل الاتصال بالخادم: نُبلغ التطبيق فوراً بدل تركه ينتظر المهلة
                if (!handshakeDone && !closed) sendRst()
            } finally {
                close()
                // نحذف جلستنا فقط، وليس جلسة جديدة بالمفتاح نفسه
                tcpSessions.remove(key, this)
            }
        }

        fun resendSynAckIfConnected() {
            if (!connected || closed) return
            val synAck = buildTcpIpPacket(
                remoteIp, remotePort, clientIp, clientPort,
                initialSeq, (clientIsn + 1) and 0xFFFFFFFFL, 0x12, ByteArray(0)
            )
            writeToTun(synAck)
        }

        @Synchronized
        fun sendToRemote(data: ByteArray, seq: Long) {
            if (!connected || closed || socket.isClosed) return
            try {
                if (seq == clientNextSeq) {
                    clientNextSeq = (seq + data.size) and 0xFFFFFFFFL
                    socket.getOutputStream().write(data)
                    socket.getOutputStream().flush()
                    totalBytesTransferred.addAndGet(data.size.toLong())
                }
                val ackPkt = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x10, ByteArray(0))
                writeToTun(ackPkt)
            } catch (_: Exception) {
                close()
            }
        }

        @Synchronized
        fun handleClientFin(seq: Long) {
            try {
                clientNextSeq = (seq + 1) and 0xFFFFFFFFL
                val finAck = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x11, ByteArray(0))
                writeToTun(finAck)
            } catch (_: Exception) {}
            close()
        }

        /** قطع الجلسة فوراً مع إرسال RST للتطبيق (يُستدعى عند الحظر) */
        fun reset() {
            sendRst()
            close()
            tcpSessions.remove(key, this)
        }

        private fun sendRst() {
            val rstPkt = buildTcpIpPacket(remoteIp, remotePort, clientIp, clientPort, mySeq, clientNextSeq, 0x14, ByteArray(0))
            writeToTun(rstPkt)
        }

        private fun writeToTun(pkt: ByteArray) {
            writePacket(outputStream, pkt)
        }

        private fun releaseSlot() {
            if (slotHeld.compareAndSet(true, false)) {
                try { slot.release() } catch (_: Exception) {}
            }
        }

        fun close() {
            closed = true
            connected = false
            releaseSlot()
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
        buf.putShort(1024.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.put(payload)

        val tcpChecksum = computeTcpChecksum(srcIp, dstIp, buf.array(), 20, 20 + payload.size)
        buf.putShort(36, tcpChecksum)

        return buf.array()
    }

    /** ICMP "destination unreachable / port unreachable" ردّاً على حزمة IPv4 صادرة */
    private fun buildIcmpPortUnreachable(orig: ByteArray, ipHeaderLen: Int): ByteArray {
        val embedded = minOf(orig.size, ipHeaderLen + 8)
        val icmpLen = 8 + embedded
        val totalLen = 20 + icmpLen
        val buf = ByteBuffer.allocate(totalLen)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(1.toByte())            // ICMP
        buf.putShort(0)
        buf.put(orig, 16, 4)           // المصدر = وجهة الحزمة الأصلية
        buf.put(orig, 12, 4)           // الوجهة = مصدر الحزمة الأصلية

        buf.putShort(10, computeChecksum(buf.array(), 0, 20))

        buf.put(3.toByte())            // type: destination unreachable
        buf.put(3.toByte())            // code: port unreachable
        buf.putShort(0)                // checksum (placeholder)
        buf.putInt(0)                  // unused
        buf.put(orig, 0, embedded)

        buf.putShort(22, computeChecksum(buf.array(), 20, icmpLen))
        return buf.array()
    }

    /** ICMPv6 "destination unreachable / port unreachable" ردّاً على حزمة IPv6 صادرة */
    private fun buildIcmp6Unreachable(orig: ByteArray): ByteArray {
        val embedded = minOf(orig.size, 200)
        val icmpLen = 8 + embedded
        val buf = ByteBuffer.allocate(40 + icmpLen)

        buf.putInt(0x60000000)             // version 6, traffic class 0, flow label 0
        buf.putShort(icmpLen.toShort())    // payload length
        buf.put(58.toByte())               // next header: ICMPv6
        buf.put(64.toByte())               // hop limit
        buf.put(orig, 24, 16)              // المصدر = وجهة الحزمة الأصلية
        buf.put(orig, 8, 16)               // الوجهة = مصدر الحزمة الأصلية

        buf.put(1.toByte())                // type: destination unreachable
        buf.put(4.toByte())                // code: port unreachable
        buf.putShort(0)                    // checksum (placeholder)
        buf.putInt(0)                      // unused
        buf.put(orig, 0, embedded)

        buf.putShort(42, computeIcmp6Checksum(buf.array(), 40, icmpLen))
        return buf.array()
    }

    private fun computeIcmp6Checksum(packet: ByteArray, icmpOffset: Int, icmpLen: Int): Short {
        var sum = 0L
        // pseudo-header: عنوان المصدر (8..23) ثم الوجهة (24..39)
        var i = 8
        while (i < 40) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += icmpLen.toLong()
        sum += 58L

        i = icmpOffset
        while (i < icmpOffset + icmpLen - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (icmpLen % 2 == 1) {
            sum += (packet[icmpOffset + icmpLen - 1].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
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
