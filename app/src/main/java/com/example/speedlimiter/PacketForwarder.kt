package com.example.speedlimiter

import android.net.VpnService
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class PacketForwarder(
    private val vpnService: VpnService,
    private val rateLimiter: RateLimiter
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false
    val localPort = 10808

    fun startServer() {
        running = true
        executor.execute {
            try {
                serverSocket = ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
                while (running && serverSocket?.isClosed == false) {
                    val client = serverSocket!!.accept()
                    executor.execute { handleSocksClient(client) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun handleSocksClient(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 15000
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            // 1. SOCKS5 Handshake
            val ver = clientIn.read()
            val nMethods = clientIn.read()
            if (ver != 5 || nMethods <= 0) return
            val methods = ByteArray(nMethods)
            readFully(clientIn, methods)
            clientOut.write(byteArrayOf(0x05, 0x00)) // No Auth
            clientOut.flush()

            // 2. SOCKS5 Request
            val reqHeader = ByteArray(4)
            readFully(clientIn, reqHeader)
            if (reqHeader[1].toInt() != 1) return // CONNECT only

            val targetHost: String = when (reqHeader[3].toInt()) {
                1 -> { // IPv4
                    val addr = ByteArray(4)
                    readFully(clientIn, addr)
                    InetAddress.getByAddress(addr).hostAddress ?: return
                }
                3 -> { // Domain
                    val len = clientIn.read()
                    val domain = ByteArray(len)
                    readFully(clientIn, domain)
                    String(domain)
                }
                4 -> { // IPv6
                    val addr = ByteArray(16)
                    readFully(clientIn, addr)
                    InetAddress.getByAddress(addr).hostAddress ?: return
                }
                else -> return
            }

            val portBytes = ByteArray(2)
            readFully(clientIn, portBytes)
            val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            // 3. Connect to real internet with protect()
            remote = Socket()
            vpnService.protect(remote)
            remote.connect(InetSocketAddress(targetHost, targetPort), 8000)

            // Reply Success
            clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()

            // 4. Relay Data (Upload unrestricted, Download throttled by RateLimiter)
            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()

            val uploadThread = Thread {
                try {
                    val buf = ByteArray(4096)
                    while (running && !client.isClosed && !remote.isClosed) {
                        val n = clientIn.read(buf)
                        if (n == -1) break
                        remoteOut.write(buf, 0, n)
                        remoteOut.flush()
                    }
                } catch (_: Exception) {}
            }
            uploadThread.start()

            // Download loop with RateLimiter
            val downBuf = ByteArray(2048)
            while (running && !client.isClosed && !remote.isClosed) {
                val n = remoteIn.read(downBuf)
                if (n == -1) break
                if (n > 0) {
                    rateLimiter.acquire(n)
                    clientOut.write(downBuf, 0, n)
                    clientOut.flush()
                }
            }
            uploadThread.interrupt()
        } catch (_: Exception) {
        } finally {
            try { remote?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(inStream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = inStream.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw Exception("EOF")
            offset += read
        }
    }
}
