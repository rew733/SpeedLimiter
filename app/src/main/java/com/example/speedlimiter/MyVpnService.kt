package com.example.speedlimiter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MyVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.example.speedlimiter.ACTION_STOP"
        const val EXTRA_SPEED_KBPS = "speed_kbps"
        const val EXTRA_SELECTED_PACKAGES = "selected_packages"
        private const val CHANNEL_ID = "speed_limiter_vpn_channel"
        private const val NOTIFICATION_ID = 101
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var executorService: ExecutorService? = null
    @Volatile
    private var isRunning = false
    private val rateLimiter = RateLimiter(153600L)

    private val tcpSockets = ConcurrentHashMap<String, Socket>()
    private val udpSockets = ConcurrentHashMap<String, DatagramSocket>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isRunning = false
            closeAllResources()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val speedKbps = intent?.getLongExtra(EXTRA_SPEED_KBPS, 150L) ?: 150L
        val selectedPackages = intent?.getStringArrayListExtra(EXTRA_SELECTED_PACKAGES) ?: arrayListOf()

        // تنظيف الجلسة السابقة فقط دون استدعاء stopSelf()
        isRunning = false
        closeAllResources()

        rateLimiter.setSpeedLimitKbps(speedKbps)
        startForegroundNotification(speedKbps, selectedPackages.size)

        isRunning = true
        startVpn(selectedPackages)
        return START_STICKY
    }

    private fun startForegroundNotification(speedKbps: Long, appsCount: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SpeedLimiter VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("SpeedLimiter VPN نشط")
            .setContentText("الحد: $speedKbps KB/s | التطبيقات: $appsCount")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun startVpn(selectedPackages: List<String>) {
        val builder = Builder()
            .setSession("SpeedLimiterVPN")
            .setMtu(1400)
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")

        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
        }

        if (selectedPackages.isNotEmpty()) {
            for (pkg in selectedPackages) {
                if (pkg != packageName) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                }
            }
        }

        val established = builder.establish()
        if (established == null) {
            isRunning = false
            stopForegroundCompat()
            stopSelf()
            return
        }
        vpnInterface = established

        val pool = Executors.newCachedThreadPool()
        executorService = pool
        pool.execute {
            runPacketLoop(established)
        }
    }

    private fun runPacketLoop(pfd: ParcelFileDescriptor) {
        try {
            val input = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteArray(16384)

            while (isRunning) {
                val length = try {
                    input.read(buffer)
                } catch (_: Exception) {
                    break
                }
                if (!isRunning) break
                if (length <= 0) continue

                rateLimiter.throttle(length)
                handleIpv4Packet(buffer, length, output)
            }
        } catch (_: Exception) {
        }
    }

    private fun handleIpv4Packet(packet: ByteArray, length: Int, tunOut: FileOutputStream) {
        if (length < 20) return
        val versionAndIhl = packet[0].toInt() and 0xFF
        val version = versionAndIhl ushr 4
        if (version != 4) return

        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < 20 || length < ihl) return

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)

        when (protocol) {
            17 -> handleUdp(packet, ihl, length, srcIp, dstIp, tunOut)
            6 -> handleTcp(packet, ihl, length, srcIp, dstIp, tunOut)
        }
    }

    private fun handleUdp(
        packet: ByteArray,
        ihl: Int,
        length: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        tunOut: FileOutputStream
    ) {
        if (length < ihl + 8) return
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        val payloadOffset = ihl + 8
        val payloadLen = (udpLen - 8).coerceAtMost(length - payloadOffset)
        if (payloadLen <= 0 || payloadOffset + payloadLen > length) return

        val key = "${ipToString(srcIp)}:$srcPort->${ipToString(dstIp)}:$dstPort"
        val socket = udpSockets.computeIfAbsent(key) {
            val ds = DatagramSocket()
            protect(ds)
            ds.soTimeout = 10000
            executorService?.execute {
                readUdpResponses(ds, srcIp, srcPort, dstIp, dstPort, tunOut, key)
            }
            ds
        }

        try {
            val targetAddr = InetAddress.getByAddress(dstIp)
            val outPacket = DatagramPacket(packet, payloadOffset, payloadLen, targetAddr, dstPort)
            socket.send(outPacket)
        } catch (_: Exception) {
            udpSockets.remove(key)?.close()
        }
    }

    private fun readUdpResponses(
        ds: DatagramSocket,
        clientIp: ByteArray,
        clientPort: Int,
        serverIp: ByteArray,
        serverPort: Int,
        tunOut: FileOutputStream,
        key: String
    ) {
        val respBuf = ByteArray(4096)
        try {
            while (isRunning && !ds.isClosed) {
                val dp = DatagramPacket(respBuf, respBuf.size)
                ds.receive(dp)
                if (!isRunning) break
                val dataLen = dp.length
                if (dataLen <= 0) continue

                rateLimiter.throttle(dataLen)
                val ipPacket = buildIpv4UdpPacket(
                    srcIp = serverIp,
                    srcPort = serverPort,
                    dstIp = clientIp,
                    dstPort = clientPort,
                    payload = respBuf,
                    payloadOffset = dp.offset,
                    payloadLen = dataLen
                )
                synchronized(tunOut) {
                    if (isRunning) {
                        tunOut.write(ipPacket)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            udpSockets.remove(key)?.close()
        }
    }

    private fun handleTcp(
        packet: ByteArray,
        ihl: Int,
        length: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        tunOut: FileOutputStream
    ) {
        if (length < ihl + 20) return
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val seqNum = readUint32(packet, ihl + 4)
        val ackNum = readUint32(packet, ihl + 8)
        val dataOffset = ((packet[ihl + 12].toInt() and 0xF0) ushr 4) * 4
        val flags = packet[ihl + 13].toInt() and 0xFF
        if (dataOffset < 20 || length < ihl + dataOffset) return

        val syn = (flags and 0x02) != 0
        val ack = (flags and 0x10) != 0
        val fin = (flags and 0x01) != 0
        val rst = (flags and 0x04) != 0

        val payloadOffset = ihl + dataOffset
        val payloadLen = length - payloadOffset
        val key = "${ipToString(srcIp)}:$srcPort->${ipToString(dstIp)}:$dstPort"

        if (rst || fin) {
            tcpSockets.remove(key)?.let {
                try { it.close() } catch (_: Exception) {}
            }
            return
        }

        if (syn && !ack) {
            executorService?.execute {
                try {
                    val socket = Socket()
                    protect(socket)
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 5000)
                    tcpSockets[key] = socket

                    val synAck = buildTcpControlPacket(
                        srcIp = dstIp,
                        srcPort = dstPort,
                        dstIp = srcIp,
                        dstPort = srcPort,
                        seq = 1000L,
                        ack = (seqNum + 1L) and 0xFFFFFFFFL,
                        flags = 0x12
                    )
                    synchronized(tunOut) {
                        if (isRunning) tunOut.write(synAck)
                    }

                    val inStream = socket.getInputStream()
                    val buf = ByteArray(8192)
                    var serverSeq = 1001L
                    while (isRunning && socket.isConnected && !socket.isClosed) {
                        val readBytes = inStream.read(buf)
                        if (readBytes <= 0) break
                        rateLimiter.throttle(readBytes)

                        val dataPacket = buildTcpDataPacket(
                            srcIp = dstIp,
                            srcPort = dstPort,
                            dstIp = srcIp,
                            dstPort = srcPort,
                            seq = serverSeq,
                            ack = ackNum,
                            payload = buf,
                            payloadLen = readBytes
                        )
                        serverSeq = (serverSeq + readBytes) and 0xFFFFFFFFL
                        synchronized(tunOut) {
                            if (isRunning) tunOut.write(dataPacket)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    tcpSockets.remove(key)?.let {
                        try { it.close() } catch (_: Exception) {}
                    }
                }
            }
            return
        }

        if (payloadLen > 0) {
            val socket = tcpSockets[key]
            if (socket != null && socket.isConnected && !socket.isClosed) {
                try {
                    socket.getOutputStream().write(packet, payloadOffset, payloadLen)
                    socket.getOutputStream().flush()
                } catch (_: Exception) {
                    tcpSockets.remove(key)?.close()
                }
            }
        }
    }

    private fun buildIpv4UdpPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLen: Int
    ): ByteArray {
        val udpLen = 8 + payloadLen
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)

        out[0] = 0x45.toByte()
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[6] = 0x40.toByte()
        out[8] = 64
        out[9] = 17
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        writeIpv4Checksum(out)

        out[20] = ((srcPort ushr 8) and 0xFF).toByte()
        out[21] = (srcPort and 0xFF).toByte()
        out[22] = ((dstPort ushr 8) and 0xFF).toByte()
        out[23] = (dstPort and 0xFF).toByte()
        out[24] = ((udpLen ushr 8) and 0xFF).toByte()
        out[25] = (udpLen and 0xFF).toByte()

        System.arraycopy(payload, payloadOffset, out, 28, payloadLen)
        return out
    }

    private fun buildTcpControlPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int
    ): ByteArray {
        val totalLen = 40
        val out = ByteArray(totalLen)

        out[0] = 0x45.toByte()
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[8] = 64
        out[9] = 6
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        writeIpv4Checksum(out)

        out[20] = ((srcPort ushr 8) and 0xFF).toByte()
        out[21] = (srcPort and 0xFF).toByte()
        out[22] = ((dstPort ushr 8) and 0xFF).toByte()
        out[23] = (dstPort and 0xFF).toByte()
        writeUint32(out, 24, seq)
        writeUint32(out, 28, ack)
        out[32] = 0x50.toByte()
        out[33] = (flags and 0xFF).toByte()
        out[34] = 0xFF.toByte()
        out[35] = 0xFF.toByte()
        return out
    }

    private fun buildTcpDataPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        payload: ByteArray,
        payloadLen: Int
    ): ByteArray {
        val totalLen = 40 + payloadLen
        val out = ByteArray(totalLen)

        out[0] = 0x45.toByte()
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[8] = 64
        out[9] = 6
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        writeIpv4Checksum(out)

        out[20] = ((srcPort ushr 8) and 0xFF).toByte()
        out[21] = (srcPort and 0xFF).toByte()
        out[22] = ((dstPort ushr 8) and 0xFF).toByte()
        out[23] = (dstPort and 0xFF).toByte()
        writeUint32(out, 24, seq)
        writeUint32(out, 28, ack)
        out[32] = 0x50.toByte()
        out[33] = 0x18.toByte()
        out[34] = 0xFF.toByte()
        out[35] = 0xFF.toByte()

        System.arraycopy(payload, 0, out, 40, payloadLen)
        return out
    }

    private fun writeIpv4Checksum(header: ByteArray) {
        header[10] = 0
        header[11] = 0
        var sum = 0L
        var i = 0
        while (i < 20) {
            val word = ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        val checksum = sum.inv().toInt() and 0xFFFF
        header[10] = ((checksum ushr 8) and 0xFF).toByte()
        header[11] = (checksum and 0xFF).toByte()
    }

    private fun readUint32(buf: ByteArray, offset: Int): Long {
        return (((buf[offset].toLong() and 0xFFL) shl 24) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 8) or
            (buf[offset + 3].toLong() and 0xFFL))
    }

    private fun writeUint32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xFFL).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFFL).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFFL).toByte()
        buf[offset + 3] = (value and 0xFFL).toByte()
    }

    private fun ipToString(ip: ByteArray): String {
        return "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
    }

    private fun closeAllResources() {
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        tcpSockets.values.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        tcpSockets.clear()

        udpSockets.values.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        udpSockets.clear()

        executorService?.shutdownNow()
        executorService = null
    }

    override fun onDestroy() {
        isRunning = false
        closeAllResources()
        super.onDestroy()
    }

    override fun onRevoke() {
        isRunning = false
        closeAllResources()
        stopForegroundCompat()
        stopSelf()
        super.onRevoke()
    }
}
