package com.example.speedlimiter

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val vpnRequestCode = 1001
    private var isVpnActive = false
    private var isRefreshing = false

    private lateinit var toggleButton: Button
    private lateinit var selectAllButton: Button
    private lateinit var refreshButton: Button
    private lateinit var speedInput: EditText
    private lateinit var statusText: TextView
    private lateinit var appsListLayout: LinearLayout

    private val mainHandler = Handler(Looper.getMainLooper())
    private val allUserPackages = mutableListOf<String>()
    private val checkBoxes = mutableListOf<CheckBox>()
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
        }

        val titleText = TextView(this).apply {
            text = "SpeedLimiter — التحكم بالسرعة والتطبيقات"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }
        rootLayout.addView(titleText)

        statusText = TextView(this).apply {
            text = "الحالة: متوقف"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 12, 0, 16)
        }
        rootLayout.addView(statusText)

        val speedLabel = TextView(this).apply {
            text = "حد السرعة (KB/s):"
            textSize = 16f
            setTextColor(Color.BLACK)
        }
        rootLayout.addView(speedLabel)

        speedInput = EditText(this).apply {
            setText("150")
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(16, 16, 16, 16)
        }
        rootLayout.addView(speedInput)

        toggleButton = Button(this).apply {
            text = "تشغيل SpeedLimiter VPN"
            textSize = 16f
            setOnClickListener {
                if (!isVpnActive) {
                    val prepareIntent = VpnService.prepare(this@MainActivity)
                    if (prepareIntent != null) {
                        startActivityForResult(prepareIntent, vpnRequestCode)
                    } else {
                        startSpeedLimiterVpn()
                    }
                } else {
                    stopSpeedLimiterVpn()
                }
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16; bottomMargin = 10 }
        rootLayout.addView(toggleButton, btnParams)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        selectAllButton = Button(this).apply {
            text = "تحديد الكل"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener {
                toggleSelectAll()
            }
        }
        actionRow.addView(selectAllButton)

        refreshButton = Button(this).apply {
            text = "تحديث داخلي"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
            setOnClickListener {
                performInternalRefresh()
            }
        }
        actionRow.addView(refreshButton)

        rootLayout.addView(actionRow)

        val appsHeader = TextView(this).apply {
            text = "قائمة التطبيقات المثبتة (غير النظامية):"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(appsHeader)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        appsListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollView.addView(appsListLayout)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
        loadInstalledApps()
    }

    private fun performInternalRefresh() {
        if (isRefreshing) return
        isRefreshing = true
        refreshButton.isEnabled = false
        selectAllButton.isEnabled = false

        mainHandler.removeCallbacksAndMessages(null)
        statusText.text = "جاري التحديث الداخلي وتصفية الأوامر..."

        loadInstalledApps {
            isRefreshing = false
            refreshButton.isEnabled = true
            selectAllButton.isEnabled = true
            statusText.text = if (isVpnActive) {
                val speedKbps = speedInput.text.toString().toLongOrNull() ?: 150L
                "الحالة: نشط — الحد: $speedKbps KB/s | التطبيقات المحددة: ${selectedPackages.size}"
            } else {
                "الحالة: متوقف (تم التحديث الداخلي)"
            }
            Toast.makeText(this, "تم التحديث الداخلي بنجاح", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadInstalledApps(onDone: (() -> Unit)? = null) {
        allUserPackages.clear()
        checkBoxes.clear()
        selectedPackages.clear()
        appsListLayout.removeAllViews()

        val pm = packageManager
        val installedApps: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        val userPackages = installedApps
            .filter { app ->
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val isSelf = app.packageName == packageName
                !isSystem && !isUpdatedSystem && !isSelf
            }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        for (appInfo in userPackages) {
            val label = pm.getApplicationLabel(appInfo).toString()
            val pkgName = appInfo.packageName
            allUserPackages.add(pkgName)

            val checkBox = CheckBox(this).apply {
                text = "$label ($pkgName)"
                textSize = 14f
                setTextColor(Color.BLACK)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedPackages.add(pkgName) else selectedPackages.remove(pkgName)
                    updateSelectAllButtonLabel()
                }
            }
            checkBoxes.add(checkBox)
            appsListLayout.addView(checkBox)
        }

        updateSelectAllButtonLabel()
        onDone?.invoke()
    }

    private fun toggleSelectAll() {
        val shouldSelectAll = selectedPackages.size < allUserPackages.size
        selectedPackages.clear()
        for (i in checkBoxes.indices) {
            val cb = checkBoxes[i]
            val pkg = allUserPackages.getOrNull(i) ?: continue
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = shouldSelectAll
            if (shouldSelectAll) {
                selectedPackages.add(pkg)
            }
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
                updateSelectAllButtonLabel()
            }
        }
        updateSelectAllButtonLabel()
    }

    private fun updateSelectAllButtonLabel() {
        selectAllButton.text = if (allUserPackages.isNotEmpty() && selectedPackages.size == allUserPackages.size) {
            "إلغاء تحديد الكل"
        } else {
            "تحديد الكل"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode && resultCode == RESULT_OK) {
            startSpeedLimiterVpn()
        }
    }

    private fun startSpeedLimiterVpn() {
        val speedKbps = speedInput.text.toString().toLongOrNull() ?: 150L
        val intent = Intent(this, MyVpnService::class.java).apply {
            putExtra(MyVpnService.EXTRA_SPEED_KBPS, speedKbps)
            putStringArrayListExtra(MyVpnService.EXTRA_SELECTED_PACKAGES, ArrayList(selectedPackages))
        }
        startService(intent)
        isVpnActive = true
        toggleButton.text = "إيقاف SpeedLimiter VPN"
        statusText.text = "الحالة: نشط — الحد: $speedKbps KB/s | التطبيقات المحددة: ${selectedPackages.size}"
        Toast.makeText(this, "تم بدء الخدمة بحد $speedKbps KB/s", Toast.LENGTH_SHORT).show()
    }

    private fun stopSpeedLimiterVpn() {
        val stopIntent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_STOP
        }
        startService(stopIntent)
        stopService(Intent(this, MyVpnService::class.java))
        isVpnActive = false
        toggleButton.text = "تشغيل SpeedLimiter VPN"
        statusText.text = "الحالة: متوقف"
        Toast.makeText(this, "تم إيقاف VPN", Toast.LENGTH_SHORT).show()
    }
}
