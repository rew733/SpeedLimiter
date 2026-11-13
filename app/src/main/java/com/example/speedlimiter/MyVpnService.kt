package com.example.speedlimiter

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var executorService: ExecutorService
    private var isRunning = false
    private val rateLimiter = RateLimiter(153600) // 150 KB/s

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        val builder = Builder()
            .setSession("SpeedLimiterVPN")
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")

        vpnInterface = builder.establish()
        executorService = Executors.newSingleThreadExecutor()

        executorService.execute {
            try {
                val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
                val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
                val buffer = ByteBuffer.allocate(32767)

                while (isRunning) {
                    val length = inputStream.read(buffer.array())
                    if (length > 0) {
                        rateLimiter.throttle(length)
                        outputStream.write(buffer.array(), 0, length)
                    }
                    buffer.clear()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override onDestroy() {
        super.onDestroy()
        isRunning = false
        vpnInterface?.close()
        executorService.shutdownNow()
    }
}
