package com.example.speedlimiter

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var speedInput: EditText
    private lateinit var sessionLimitInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var listView: ListView
    private lateinit var adapter: AppAdapter

    private val allApps = mutableListOf<AppItem>()
    private val filteredApps = mutableListOf<AppItem>()
    private val selectedPackages = mutableSetOf<String>()
    private var isVpnRunning = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "تم رفض إذن الـ VPN", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val titleText = TextView(this).apply {
            text = "مُحدّد سرعة الإنترنت للتطبيقات"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }
        rootLayout.addView(titleText)

        // صف أفقي يجمع خيار السرعة وخيار سقف الجلسة جنباً إلى جنب
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 20)
        }

        // عمود السرعة (يمين)
        val speedCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 12, 0)
            }
        }
        val speedLabel = TextView(this).apply {
            text = "السرعة (KB/s):"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 8)
        }
        speedInput = EditText(this).apply {
            setText("70")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        speedCol.addView(speedLabel)
        speedCol.addView(speedInput)

        // عمود سقف الجلسة (يسار)
        val sessionCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(12, 0, 0, 0)
            }
        }
        val sessionLabel = TextView(this).apply {
            text = "حد الجلسة (KB) [0=بلا]:"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 8)
        }
        sessionLimitInput = EditText(this).apply {
            setText("250")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        sessionCol.addView(sessionLabel)
        sessionCol.addView(sessionLimitInput)

        controlsRow.addView(speedCol)
        controlsRow.addView(sessionCol)
        rootLayout.addView(controlsRow)

        // زر التشغيل والإيقاف
        toggleButton = Button(this).apply {
            text = "تشغيل تحديد السرعة"
            setBackgroundColor(Color.parseColor("#00C853"))
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 24)
            setOnClickListener {
                if (isVpnRunning) {
                    stopVpnService()
                } else {
                    prepareAndStartVpn()
                }
            }
        }
        rootLayout.addView(toggleButton)

        // مربع البحث
        searchInput = EditText(this).apply {
            hint = "ابحث عن تطبيق..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 20, 24, 20)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 20, 0, 16)
            layoutParams = params
        }
        rootLayout.addView(searchInput)

        // قائمة التطبيقات
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rootLayout.addView(listView)

        setContentView(rootLayout)

        loadInstalledApps()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        allApps.clear()

        for (appInfo in packages) {
            if (appInfo.packageName == packageName) continue
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon = pm.getApplicationIcon(appInfo)
            allApps.add(AppItem(label, appInfo.packageName, icon))
        }

        allApps.sortBy { it.name.lowercase() }
        filteredApps.clear()
        filteredApps.addAll(allApps)

        adapter = AppAdapter()
        listView.adapter = adapter
    }

    private fun filterApps(query: String) {
        filteredApps.clear()
        if (query.isBlank()) {
            filteredApps.addAll(allApps)
        } else {
            val q = query.lowercase()
            filteredApps.addAll(allApps.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
        adapter.notifyDataSetChanged()
    }

    private fun prepareAndStartVpn() {
        if (selectedPackages.isEmpty()) {
            Toast.makeText(this, "اختر تطبيقاً واحداً على الأقل", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val speedKb = speedInput.text.toString().toIntOrNull() ?: 70
        val sessionLimitKb = sessionLimitInput.text.toString().toIntOrNull() ?: 250

        val intent = Intent(this, MyVpnService::class.java).apply {
            putStringArrayListExtra("TARGET_APPS", ArrayList(selectedPackages))
            putExtra("SPEED_KB", speedKb)
            putExtra("SESSION_LIMIT_KB", sessionLimitKb)
        }
        ContextCompat.startForegroundService(this, intent)

        isVpnRunning = true
        toggleButton.text = "إيقاف تحديد السرعة"
        toggleButton.setBackgroundColor(Color.parseColor("#D50000"))
    }

    private fun stopVpnService() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = "STOP"
        }
        startService(intent)

        isVpnRunning = false
        toggleButton.text = "تشغيل تحديد السرعة"
        toggleButton.setBackgroundColor(Color.parseColor("#00C853"))
    }

    data class AppItem(val name: String, val packageName: String, val icon: Drawable)

    inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = filteredApps.size
        override fun getItem(position: Int): Any = filteredApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 20, 16, 20)
            }
            row.removeAllViews()

            val item = filteredApps[position]

            val iconView = ImageView(this@MainActivity).apply {
                setImageDrawable(item.icon)
                layoutParams = LinearLayout.LayoutParams(96, 96)
            }

            val nameView = TextView(this@MainActivity).apply {
                text = item.name
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(24, 0, 16, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val checkBox = CheckBox(this@MainActivity).apply {
                isChecked = selectedPackages.contains(item.packageName)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedPackages.add(item.packageName)
                    else selectedPackages.remove(item.packageName)
                }
            }

            row.addView(iconView)
            row.addView(nameView)
            row.addView(checkBox)

            row.setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
            }

            return row
        }
    }
}
