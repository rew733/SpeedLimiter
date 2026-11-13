package com.example.speedlimiter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    data class AppItem(
        val name: String,
        val packageName: String,
        val isSystem: Boolean
    )

    private val selectedPackages = mutableSetOf<String>()
    private var allApps = listOf<AppItem>()
    private var displayedApps = mutableListOf<AppItem>()
    private var isVpnActive = false
    private var showSystemApps = false
    private var currentQuery = ""

    private lateinit var listView: ListView
    private lateinit var speedInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var adapter: ArrayAdapter<AppItem>

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startLimiterService()
        } else {
            Toast.makeText(this, "يجب منح إذن VPN لتشغيل محدد السرعة", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("speed_limiter_prefs", Context.MODE_PRIVATE)
        selectedPackages.addAll(prefs.getStringSet("saved_apps", emptySet()) ?: emptySet())
        val savedSpeed = prefs.getInt("saved_speed_kb", 150)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        // صف تحديد السرعة
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        val speedLabel = TextView(this).apply {
            text = "السرعة القصوى (KB/s): "
            textSize = 15f
        }

        speedInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(savedSpeed.toString())
            hint = "150"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        speedRow.addView(speedLabel)
        speedRow.addView(speedInput)

        toggleButton = Button(this).apply {
            text = "تشغيل تحديد السرعة"
            textSize = 16f
        }

        val searchBox = EditText(this).apply {
            hint = "🔍 ابحث عن اسم التطبيق..."
            setSingleLine(true)
        }

        val systemCheckBox = CheckBox(this).apply {
            text = "إظهار تطبيقات النظام"
            isChecked = false
        }

        listView = ListView(this)

        rootLayout.addView(speedRow)
        rootLayout.addView(toggleButton)
        rootLayout.addView(searchBox)
        rootLayout.addView(systemCheckBox)
        rootLayout.addView(listView)
        setContentView(rootLayout)

        loadInstalledApps()

        adapter = object : ArrayAdapter<AppItem>(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            android.R.id.text1,
            displayedApps
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                val item = getItem(position)!!
                textView.text = "${item.name}\n(${item.packageName})"
                textView.textSize = 14f
                return view
            }
        }

        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        filterApps()

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.trim() ?: ""
                filterApps()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        systemCheckBox.setOnCheckedChangeListener { _, isChecked ->
            showSystemApps = isChecked
            filterApps()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = displayedApps[position].packageName
            if (selectedPackages.contains(pkg)) {
                selectedPackages.remove(pkg)
            } else {
                selectedPackages.add(pkg)
            }
            prefs.edit().putStringSet("saved_apps", selectedPackages).apply()
        }

        toggleButton.setOnClickListener {
            if (!isVpnActive) {
                if (selectedPackages.isEmpty()) {
                    Toast.makeText(this, "اختر تطبيقاً واحداً على الأقل", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val speedKb = speedInput.text.toString().toIntOrNull() ?: 150
                if (speedKb <= 0) {
                    Toast.makeText(this, "أدخل رقماً أكبر من 0", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putInt("saved_speed_kb", speedKb).apply()

                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    vpnPermissionLauncher.launch(vpnIntent)
                } else {
                    startLimiterService()
                }
            } else {
                val stopIntent = Intent(this, MyVpnService::class.java).apply {
                    action = "STOP"
                }
                startService(stopIntent)
                isVpnActive = false
                speedInput.isEnabled = true
                toggleButton.text = "تشغيل تحديد السرعة"
            }
        }
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName }
            .map { appInfo ->
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                AppItem(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    isSystem = isSystem
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun filterApps() {
        val filtered = allApps.filter { app ->
            val matchesSystem = showSystemApps || !app.isSystem
            val matchesQuery = currentQuery.isEmpty() ||
                    app.name.contains(currentQuery, ignoreCase = true) ||
                    app.packageName.contains(currentQuery, ignoreCase = true)
            matchesSystem && matchesQuery
        }.sortedWith(
            compareByDescending<AppItem> { selectedPackages.contains(it.packageName) }
                .thenBy { it.name.lowercase() }
        )

        displayedApps.clear()
        displayedApps.addAll(filtered)
        adapter.notifyDataSetChanged()

        for (i in displayedApps.indices) {
            listView.setItemChecked(i, selectedPackages.contains(displayedApps[i].packageName))
        }
    }

    private fun startLimiterService() {
        val speedKb = speedInput.text.toString().toIntOrNull() ?: 150
        val intent = Intent(this, MyVpnService::class.java).apply {
            putStringArrayListExtra("TARGET_APPS", ArrayList(selectedPackages))
            putExtra("SPEED_KB", speedKb)
        }
        startForegroundService(intent)
        isVpnActive = true
        speedInput.isEnabled = false
        toggleButton.text = "إيقاف تحديد السرعة ($speedKb KB/s)"
    }
}
