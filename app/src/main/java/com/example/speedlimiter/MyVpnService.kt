package com.example.speedlimiter

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var packetForwarder: PacketForwarder? = null
    private var rateLimiter: RateLimiter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorExecutor: ScheduledExecutorService? = null
    private var fgWatcherExecutor: ScheduledExecutorService? = null
    private var autoRefreshExecutor: ScheduledExecutorService? = null

    private var currentLimitKb: Int = 70
    private var lastBytesSnapshot: Long = 0L
    private var boostEndTimeMillis: Long = 0L
    private var lastKnownForegroundPkg: String? = null
    private var savedBgKillPackages: List<String> = emptyList()

    companion object {
        @Volatile
        var isServiceRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                stopVpn()
                return START_NOT_STICKY
            }
            "BOOST_30S", "BOOST_3M" -> {
                activateBoost()
                return START_STICKY
            }
            "STOP_BOOST" -> {
                stopBoost()
                return START_STICKY
            }
            "INTERNAL_REFRESH", "MANUAL_REFRESH" -> {
                performInternalRefresh()
                return START_STICKY
            }
        }

        val prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
        val targetPackages = intent?.getStringArrayListExtra("TARGET_APPS")
            ?: ArrayList(prefs.getStringSet("selected_apps", emptySet()) ?: emptySet())
        val bgKillPackages = intent?.getStringArrayListExtra("BG_KILL_APPS")
            ?: ArrayList(prefs.getStringSet("bg_kill_apps", emptySet()) ?: emptySet())

        val speedKb = intent?.getIntExtra("SPEED_KB", prefs.getInt("speed_kb", 70))
            ?: prefs.getInt("speed_kb", 70)
        val sessionLimitKb = intent?.getIntExtra("SESSION_LIMIT_KB", prefs.getInt("session_limit_kb", 250))
            ?: prefs.getInt("session_limit_kb", 250)

        currentLimitKb = speedKb
        savedBgKillPackages = bgKillPackages
        killBackgroundProcesses(bgKillPackages)

        cleanupTunnel()
        acquireWakeLock()
        startForegroundNotification(buildNotification(0.0, 0.0, 0))
        startVpn(targetPackages, bgKillPackages, speedKb, sessionLimitKb)
        startForegroundAppWatcher()
        startLiveMonitor()
        startAutoRefreshTimer()

        return START_REDELIVER_INTENT
    }

    private fun activateBoost() {
        if (!isServiceRunning) return
        boostEndTimeMillis = System.currentTimeMillis() + 30_000L
        rateLimiter?.setBoost(true)
        refreshNotificationNow()
    }

    private fun stopBoost() {
        boostEndTimeMillis = 0L
        rateLimiter?.setBoost(false)
        refreshNotificationNow()
    }

    private fun performInternalRefresh() {
        if (!isServiceRunning) return
        try {
            killBackgroundProcesses(savedBgKillPackages)
            System.gc()
            refreshNotificationNow()
        } catch (_: Exception) {}
    }

    private fun startAutoRefreshTimer() {
        stopAutoRefreshTimer()
        autoRefreshExecutor = Executors.newSingleThreadScheduledExecutor()
        autoRefreshExecutor?.scheduleWithFixedDelay({
            performInternalRefresh()
        }, 3, 3, TimeUnit.MINUTES)
    }

    private fun stopAutoRefreshTimer() {
        try { autoRefreshExecutor?.shutdownNow() } catch (_: Exception) {}
        autoRefreshExecutor = null
    }

    private fun refreshNotificationNow() {
        if (!isServiceRunning) return
        val now = System.currentTimeMillis()
        val remainingSec = if (boostEndTimeMillis > now) ((boostEndTimeMillis - now) / 1000L).toInt() + 1 else 0
        val totalUsedMb = (packetForwarder?.totalBytesTransferred?.get() ?: 0L) / (1024.0 * 1024.0)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, buildNotification(0.0, totalUsedMb, remainingSec))
    }

    private fun startForegroundAppWatcher() {
        fgWatcherExecutor?.shutdownNow()
        fgWatcherExecutor = Executors.newSingleThreadScheduledExecutor()
        fgWatcherExecutor?.scheduleWithFixedDelay({
            if (!isServiceRunning) return@scheduleWithFixedDelay
            try {
                val fgPkg = getForegroundPackageName()
                if (fgPkg != null && fgPkg != lastKnownForegroundPkg) {
                    lastKnownForegroundPkg = fgPkg
                    val uid = try {
                        packageManager.getApplicationInfo(fgPkg, 0).uid
                    } catch (_: Exception) { -1 }
                    packetForwarder?.currentForegroundUid = uid
                }
            } catch (_: Exception) {}
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun getForegroundPackageName(): String? {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60_000L, now)
            val event = UsageEvents.Event()
            var latestPkg: String? = lastKnownForegroundPkg
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    latestPkg = event.packageName
                }
            }
            return latestPkg
        } catch (_: Exception) {
            return lastKnownForegroundPkg
        }
    }

    private fun startLiveMonitor() {
        stopLiveMonitor()
        lastBytesSnapshot = 0L
        monitorExecutor = Executors.newSingleThreadScheduledExecutor()
        monitorExecutor?.scheduleWithFixedDelay({
            if (!isServiceRunning) return@scheduleWithFixedDelay
            try {
                val now = System.currentTimeMillis()
                var boostRemainingSec = 0
                if (boostEndTimeMillis > now) {
                    boostRemainingSec = ((boostEndTimeMillis - now) / 1000L).toInt() + 1
                    rateLimiter?.setBoost(true)
                } else if (rateLimiter?.isBoostActive == true) {
                    rateLimiter?.setBoost(false)
                }

                val totalBytes = packetForwarder?.totalBytesTransferred?.get() ?: 0L
                val deltaBytes = (totalBytes - lastBytesSnapshot).coerceAtLeast(0L)
                lastBytesSnapshot = totalBytes

                val liveSpeedKb = deltaBytes / 1024.0
                val totalUsedMb = totalBytes / (1024.0 * 1024.0)

                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(1, buildNotification(liveSpeedKb, totalUsedMb, boostRemainingSec))
            } catch (_: Exception) {}
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stopLiveMonitor() {
        try { monitorExecutor?.shutdownNow() } catch (_: Exception) {}
        monitorExecutor = null
        try { fgWatcherExecutor?.shutdownNow() } catch (_: Exception) {}
        fgWatcherExecutor = null
    }

    private fun killBackgroundProcesses(packages: List<String>) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (pkg in packages) {
                if (pkg == packageName) continue
                am.killBackgroundProcesses(pkg)
            }
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeedLimiter::VpnWakeLock")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    private fun startVpn(targetPackages: List<String>, bgKillPackages: List<String>, speedKb: Int, sessionLimitKb: Int) {
        isServiceRunning = true
        getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_running", true).apply()

        val speedUids = mutableSetOf<Int>()
        for (pkg in targetPackages) {
            try { speedUids.add(packageManager.getApplicationInfo(pkg, 0).uid) } catch (_: Exception) {}
        }

        val bgBlockedUids = mutableSetOf<Int>()
        for (pkg in bgKillPackages) {
            try { bgBlockedUids.add(packageManager.getApplicationInfo(pkg, 0).uid) } catch (_: Exception) {}
        }

        val limiter = RateLimiter(speedKb.toLong() * 1024L)
        rateLimiter = limiter
        val maxSessionBytes = if (sessionLimitKb > 0) sessionLimitKb.toLong() * 1024L else Long.MAX_VALUE
        packetForwarder = PacketForwarder(this, limiter, maxSessionBytes, speedUids, bgBlockedUids)

        val builder = Builder()
            .setSession("SpeedLimiter")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setMtu(1400)
            .setBlocking(true)

        val allTunnelPackages = (targetPackages + bgKillPackages).toSet()
        for (pkg in allTunnelPackages) {
            if (pkg == packageName) continue
            try { builder.addAllowedApplication(pkg) } catch (_: Exception) {}
        }

        vpnInterface = builder.establish()

        vpnThread = Thread({
            val fd = vpnInterface?.fileDescriptor ?: return@Thread
            val inputStream = FileInputStream(fd)
            val outputStream = FileOutputStream(fd)
            try {
                packetForwarder?.start(inputStream, outputStream)
            } catch (_: Exception) {}
        }, "VpnTunnelThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun cleanupTunnel() {
        stopAutoRefreshTimer()
        stopLiveMonitor()
        packetForwarder?.stop()
        packetForwarder = null
        rateLimiter = null
        vpnThread?.interrupt()
        vpnThread = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
    }

    private fun stopVpn() {
        isServiceRunning = false
        getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_running", false).apply()

        cleanupTunnel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(liveSpeedKb: Double, totalUsedMb: Double, boostRemainingSec: Int): Notification {
        val channelId = "speed_limiter_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Speed Limiter Service", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val boostIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MyVpnService::class.java).apply { action = "BOOST_30S" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopBoostIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MyVpnService::class.java).apply { action = "STOP_BOOST" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val refreshIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, MyVpnService::class.java).apply { action = "INTERNAL_REFRESH" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (boostRemainingSec > 0) {
            "🚀 تعزيز السرعة نشط (${boostRemainingSec}ث)"
        } else {
            String.format(Locale.US, "السرعة: %.1f KB/s (الحد: %d KB/s)", liveSpeedKb, currentLimitKb)
        }

        val content = String.format(Locale.US, "المستهلك عبر النفق: %.2f MB", totalUsedMb)

        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }

        val boostAction = Notification.Action.Builder(
            android.R.drawable.ic_media_ff,
            if (boostRemainingSec > 0) "تمديد 30ث" else "🚀 تعزيز 30ث",
            boostIntent
        ).build()

        val stopBoostAction = Notification.Action.Builder(
            android.R.drawable.ic_media_pause,
            "🛑 إيقاف التعزيز",
            stopBoostIntent
        ).build()

        val refreshAction = Notification.Action.Builder(
            android.R.drawable.ic_popup_sync,
            "🔄 تحديث",
            refreshIntent
        ).build()

        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(boostAction)
            .addAction(stopBoostAction)
            .addAction(refreshAction)
            .build()
    }

    private fun startForegroundNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isServiceRunning) {
            val restartIntent = Intent(applicationContext, MyVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (!isServiceRunning) {
            cleanupTunnel()
            releaseWakeLock()
        }
        super.onDestroy()
    }
}
