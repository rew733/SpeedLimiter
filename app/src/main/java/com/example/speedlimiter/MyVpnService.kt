package com.example.speedlimiter

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
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
    private var autoRefreshExecutor: ScheduledExecutorService? = null
    private var bgEnforcerExecutor: ScheduledExecutorService? = null
    private var screenReceiver: BroadcastReceiver? = null

    private var currentLimitKb: Int = 70
    private var lastBytesSnapshot: Long = 0L
    private var boostEndTimeMillis: Long = 0L
    private var savedAllowedPackages: List<String> = emptyList()
    private var savedBgKillPackages: List<String> = emptyList()
    private var lastDetectedForegroundPkg: String? = null
    private var launcherPackages: Set<String> = emptySet()

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
            "BOOST_3M" -> {
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
        val sessionLimitKb = intent?.getIntExtra("SESSION_LIMIT_KB", prefs.getInt("session_limit_kb", 0))
            ?: prefs.getInt("session_limit_kb", 0)

        currentLimitKb = speedKb
        savedBgKillPackages = bgKillPackages
        savedAllowedPackages = targetPackages.filter { !bgKillPackages.contains(it) && it != packageName }
        launcherPackages = resolveLauncherPackages()

        killBackgroundProcesses(getNonForegroundPackages())

        cleanupTunnel()
        acquireWakeLock()
        registerScreenStateReceiver()
        startForegroundNotification(buildNotification(0.0, 0.0, 0))
        startVpn(savedAllowedPackages, savedBgKillPackages, speedKb, sessionLimitKb)
        startLiveMonitor()
        startAutoRefreshTimer()
        startBackgroundEnforcer()

        return START_REDELIVER_INTENT
    }

    private fun resolveLauncherPackages(): Set<String> {
        val result = HashSet<String>()
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val resolveInfos = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (info in resolveInfos) {
                info.activityInfo?.packageName?.let { result.add(it) }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun registerScreenStateReceiver() {
        unregisterScreenStateReceiver()
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        packetForwarder?.isScreenOn = false
                        packetForwarder?.activeForegroundUid = Process.INVALID_UID
                        packetForwarder?.closeAllSessionsImmediately()
                        killBackgroundProcesses(getNonForegroundPackages())
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                        packetForwarder?.isScreenOn = pm?.isInteractive ?: true
                        updateForegroundAppState()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun unregisterScreenStateReceiver() {
        try {
            if (screenReceiver != null) {
                unregisterReceiver(screenReceiver)
            }
        } catch (_: Exception) {}
        screenReceiver = null
    }

    private fun getNonForegroundPackages(): List<String> {
        val fgPkg = detectForegroundPackageName()
        return LinkedHashSet<String>().apply {
            addAll(savedAllowedPackages)
            addAll(savedBgKillPackages)
            remove(packageName)
            if (fgPkg != null) remove(fgPkg)
        }.toList()
    }

    private fun detectForegroundPackageName(): String? {
        if (hasUsageStatsPermission()) {
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usm != null) {
                    val now = System.currentTimeMillis()
                    val events = usm.queryEvents(now - 900_000L, now)
                    val event = UsageEvents.Event()
                    var latestPkg: String? = null
                    var latestTime = 0L
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latestTime) {
                            val pkg = event.packageName
                            if (pkg != null && pkg != "android" && pkg != "com.android.systemui") {
                                latestTime = event.timeStamp
                                latestPkg = pkg
                            }
                        }
                    }
                    if (latestPkg != null) {
                        lastDetectedForegroundPkg = latestPkg
                        return latestPkg
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val runningApps = am?.runningAppProcesses
            if (runningApps != null) {
                for (proc in runningApps) {
                    if (proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        val pkg = proc.pkgList?.firstOrNull()
                        if (pkg != null && pkg != "android" && pkg != "com.android.systemui") {
                            lastDetectedForegroundPkg = pkg
                            return pkg
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return lastDetectedForegroundPkg
    }

    private fun updateForegroundAppState() {
        val forwarder = packetForwarder ?: return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenInteractive = pm?.isInteractive ?: true
        forwarder.isScreenOn = screenInteractive
        forwarder.hasUsagePermission = hasUsageStatsPermission()

        if (!screenInteractive) {
            forwarder.activeForegroundUid = Process.INVALID_UID
            forwarder.closeAllSessionsImmediately()
            return
        }

        val fgPkg = detectForegroundPackageName() ?: return
        if (fgPkg == packageName) return

        if (launcherPackages.contains(fgPkg)) {
            val prevUid = forwarder.activeForegroundUid
            forwarder.activeForegroundUid = Process.INVALID_UID
            if (prevUid != Process.INVALID_UID) {
                forwarder.closeSessionsForUid(prevUid)
            }
            return
        }

        try {
            val appInfo = packageManager.getApplicationInfo(fgPkg, 0)
            val prevUid = forwarder.activeForegroundUid
            forwarder.activeForegroundUid = appInfo.uid
            if (prevUid != Process.INVALID_UID && prevUid != appInfo.uid) {
                forwarder.closeSessionsForUid(prevUid)
            }
        } catch (_: Exception) {}
    }

    private fun performInternalRefresh() {
        if (!isServiceRunning) return
        try {
            updateForegroundAppState()
            packetForwarder?.refreshSessions()
            killBackgroundProcesses(getNonForegroundPackages())
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

    private fun startBackgroundEnforcer() {
        stopBackgroundEnforcer()
        bgEnforcerExecutor = Executors.newSingleThreadScheduledExecutor()
        bgEnforcerExecutor?.scheduleWithFixedDelay({
            if (!isServiceRunning) return@scheduleWithFixedDelay
            try {
                updateForegroundAppState()
                killBackgroundProcesses(getNonForegroundPackages())
            } catch (_: Exception) {}
        }, 2, 2, TimeUnit.SECONDS)
    }

    private fun stopBackgroundEnforcer() {
        try { bgEnforcerExecutor?.shutdownNow() } catch (_: Exception) {}
        bgEnforcerExecutor = null
    }

    private fun activateBoost() {
        if (!isServiceRunning) return
        boostEndTimeMillis = System.currentTimeMillis() + 180_000L
        rateLimiter?.setBoost(true)
        refreshNotificationNow()
    }

    private fun stopBoost() {
        boostEndTimeMillis = 0L
        rateLimiter?.setBoost(false)
        refreshNotificationNow()
    }

    private fun refreshNotificationNow() {
        if (!isServiceRunning) return
        val now = System.currentTimeMillis()
        val remainingSec = if (boostEndTimeMillis > now) ((boostEndTimeMillis - now) / 1000L).toInt() + 1 else 0
        val totalUsedMb = (packetForwarder?.totalBytesTransferred?.get() ?: 0L) / (1024.0 * 1024.0)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, buildNotification(0.0, totalUsedMb, remainingSec))
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
    }

    private fun killBackgroundProcesses(packages: List<String>) {
        if (packages.isEmpty()) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (pkg in packages) {
                if (pkg == packageName) continue
                am.killBackgroundProcesses(pkg)
            }
        } catch (_: Exception) {}
    }

    private fun resolveUserAppUids(packages: List<String>): Set<Int> {
        val uids = HashSet<Int>()
        val pm = packageManager
        for (pkg in packages) {
            if (pkg == packageName) continue
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                if (appInfo.uid >= Process.FIRST_APPLICATION_UID) {
                    uids.add(appInfo.uid)
                }
            } catch (_: Exception) {}
        }
        return uids
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

    private fun startVpn(
        allowedSpeedPackages: List<String>,
        blockedBgPackages: List<String>,
        speedKb: Int,
        sessionLimitKb: Int
    ) {
        isServiceRunning = true
        getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_running", true).apply()

        val limiter = RateLimiter(speedKb.toLong() * 1024L)
        rateLimiter = limiter
        val maxSessionBytes = if (sessionLimitKb > 0) sessionLimitKb.toLong() * 1024L else Long.MAX_VALUE

        val allowedUids = resolveUserAppUids(allowedSpeedPackages)
        val blockedUids = resolveUserAppUids(blockedBgPackages)
        val connManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        packetForwarder = PacketForwarder(
            vpnService = this,
            rateLimiter = limiter,
            maxSessionBytes = maxSessionBytes,
            allowedUids = allowedUids,
            blockedUids = blockedUids,
            connectivityManager = connManager
        )

        updateForegroundAppState()

        val builder = Builder()
            .setSession("SpeedLimiter")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1::2", 64)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addDnsServer("2001:4860:4860::8888")
            .setMtu(1400)
            .setBlocking(true)

        val essentialSystemExceptions = setOf(
            packageName,
            "com.android.phone",
            "com.android.mms",
            "com.android.server.telecom",
            "com.android.providers.telephony"
        )

        for (excludedPkg in essentialSystemExceptions) {
            try {
                builder.addDisallowedApplication(excludedPkg)
            } catch (_: Exception) {}
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
        unregisterScreenStateReceiver()
        stopBackgroundEnforcer()
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
            Intent(this, MyVpnService::class.java).apply { action = "BOOST_3M" },
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
            val mins = boostRemainingSec / 60
            val secs = boostRemainingSec % 60
            String.format(Locale.US, "🚀 التعزيز نشط (%02d:%02d) - %.1f KB/s", mins, secs, liveSpeedKb)
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
            "🚀 تعزيز 3د",
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
