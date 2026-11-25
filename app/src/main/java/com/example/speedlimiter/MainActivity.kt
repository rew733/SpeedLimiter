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
import android.text.InputType
import android.view.Gravity
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
    private lateinit var toggleButton: Button
    private lateinit var speedInput: EditText
    private lateinit var statusText: TextView
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
            text = "الحالة: متوقف (ملاحظة: تمرير الحزم الكامل يتطلب توجيه الشبكة)"
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
        ).apply { topMargin = 16; bottomMargin = 20 }
        rootLayout.addView(toggleButton, btnParams)

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

        val appsListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

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
            val checkBox = CheckBox(this).apply {
                text = "$label ($pkgName)"
                textSize = 14f
                setTextColor(Color.BLACK)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedPackages.add(pkgName) else selectedPackages.remove(pkgName)
                }
            }
            appsListLayout.addView(checkBox)
        }

        scrollView.addView(appsListLayout)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
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
            putExtra("speed_kbps", speedKbps)
            putStringArrayListExtra("selected_packages", ArrayList(selectedPackages))
        }
        startService(intent)
        isVpnActive = true
        toggleButton.text = "إيقاف SpeedLimiter VPN"
        statusText.text = "الحالة: نشط — الحد: $speedKbps KB/s | التطبيقات المحددة: ${selectedPackages.size}"
        statusText.gravity = Gravity.START
        Toast.makeText(this, "تم بدء الخدمة بحد $speedKbps KB/s", Toast.LENGTH_SHORT).show()
    }

    private fun stopSpeedLimiterVpn() {
        stopService(Intent(this, MyVpnService::class.java))
        isVpnActive = false
        toggleButton.text = "تشغيل SpeedLimiter VPN"
        statusText.text = "الحالة: متوقف"
    }
}
