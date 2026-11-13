package com.example.speedlimiter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import tun2socks.PacketFlow
import tun2socks.Tun2socks
import java.io.FileInputStream
import java.io.FileOutputStream

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var vpnThread: Thread? = null
    private var packetForwarder: PacketForwarder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        val targetPackages = intent?.getStringArrayListExtra("TARGET_APPS") ?: arrayListOf()
        val speedKb = intent?.getIntExtra("SPEED_KB", 150) ?: 150

        startForegroundNotification(speedKb)
        startVpn(targetPackages, speedKb)
        return START_STICKY
    }

    private fun startVpn(targetPackages: List<String>, speedKb: Int) {
        if (isRunning) return
        isRunning = true

        val rateLimiter = RateLimiter(speedKb.toLong() * 1024L)
        packetForwarder = PacketForwarder(this, rateLimiter).apply {
            startServer()
        }

        val builder = Builder()
            .setSession("SpeedLimiter")
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500)

        for (pkg in targetPackages) {
            try {
                builder.addAllowedApplication(pkg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        vpnInterface = builder.establish()
        val fd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(fd)
        val outputStream = FileOutputStream(fd)

        val packetFlow = PacketFlow { packet ->
            try {
                synchronized(outputStream) {
                    outputStream.write(packet)
                }
            } catch (_: Exception) {}
        }

        Tun2socks.startSocks(packetFlow, "127.0.0.1", packetForwarder!!.localPort.toLong())

        vpnThread = Thread {
            val buffer = ByteArray(32767)
            try {
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        Tun2socks.inputPacket(buffer.copyOf(length))
                    } else {
                        Thread.sleep(5)
                    }
                }
            } catch (_: Exception) {}
        }
        vpnThread?.start()
    }

    private fun stopVpn() {
        isRunning = false
        packetForwarder?.stop()
        vpnThread?.interrupt()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification(speedKb: Int) {
        val channelId = "speed_limiter_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Speed Limiter Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Speed Limiter يعمل")
                .setContentText("تحديد السرعة إلى $speedKb كيلوبايت/ثانية")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Speed Limiter يعمل")
                .setContentText("تحديد السرعة إلى $speedKb كيلوبايت/ثانية")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }

        startForeground(1, notification)
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
