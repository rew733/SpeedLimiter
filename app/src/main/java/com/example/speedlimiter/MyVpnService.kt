package com.example.speedlimiter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var packetForwarder: PacketForwarder? = null

    companion object {
        @Volatile
        var isServiceRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        val targetPackages = intent?.getStringArrayListExtra("TARGET_APPS") ?: arrayListOf()
        val speedKb = intent?.getIntExtra("SPEED_KB", 70) ?: 70
        val sessionLimitKb = intent?.getIntExtra("SESSION_LIMIT_KB", 250) ?: 250

        cleanupTunnel()

        startForegroundNotification(speedKb, sessionLimitKb)
        startVpn(targetPackages, speedKb, sessionLimitKb)
        return START_STICKY
    }

    private fun startVpn(targetPackages: List<String>, speedKb: Int, sessionLimitKb: Int) {
        isServiceRunning = true
        getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_running", true).apply()

        val rateLimiter = RateLimiter(speedKb.toLong() * 1024L)
        val maxSessionBytes = if (sessionLimitKb > 0) sessionLimitKb.toLong() * 1024L else Long.MAX_VALUE
        packetForwarder = PacketForwarder(this, rateLimiter, maxSessionBytes)

        val builder = Builder()
            .setSession("SpeedLimiter")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1400)

        for (pkg in targetPackages) {
            try {
                builder.addAllowedApplication(pkg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        vpnInterface = builder.establish()

        vpnThread = Thread {
            val fd = vpnInterface?.fileDescriptor ?: return@Thread
            val inputStream = FileInputStream(fd)
            val outputStream = FileOutputStream(fd)
            try {
                packetForwarder?.start(inputStream, outputStream)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        vpnThread?.start()
    }

    private fun cleanupTunnel() {
        packetForwarder?.stop()
        packetForwarder = null
        vpnThread?.interrupt()
        vpnThread = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
    }

    private fun stopVpn() {
        isServiceRunning = false
        getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_running", false).apply()

        cleanupTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification(speedKb: Int, sessionLimitKb: Int) {
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

        val limitText = if (sessionLimitKb > 0) " | حد الجلسة: ${sessionLimitKb}KB" else ""
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Speed Limiter نشط")
                .setContentText("السرعة: $speedKb KB/s$limitText")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Speed Limiter نشط")
                .setContentText("السرعة: $speedKb KB/s$limitText")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }

        startForeground(1, notification)
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
