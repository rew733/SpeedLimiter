package com.example.speedlimiter

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val selectedPackages = mutableSetOf<String>()
    private var isVpnActive = false

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

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val toggleButton = Button(this).apply {
            text = "تشغيل تحديد السرعة (150 KB/s)"
        }

        val listView = ListView(this)
        rootLayout.addView(toggleButton)
        rootLayout.addView(listView)
        setContentView(rootLayout)

        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val appNames = apps.map { "${pm.getApplicationLabel(it)}\n(${it.packageName})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, appNames)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = apps[position].packageName
            if (listView.isItemChecked(position)) {
                selectedPackages.add(pkg)
            } else {
                selectedPackages.remove(pkg)
            }
        }

        toggleButton.setOnClickListener {
            if (!isVpnActive) {
                if (selectedPackages.isEmpty()) {
                    Toast.makeText(this, "اختر تطبيقاً واحداً على الأقل", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    vpnPermissionLauncher.launch(vpnIntent)
                } else {
                    startLimiterService()
                }
                toggleButton.text = "إيقاف تحديد السرعة"
            } else {
                val stopIntent = Intent(this, MyVpnService::class.java).apply {
                    action = "STOP"
                }
                startService(stopIntent)
                isVpnActive = false
                toggleButton.text = "تشغيل تحديد السرعة (150 KB/s)"
            }
        }
    }

    private fun startLimiterService() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            putStringArrayListExtra("TARGET_APPS", ArrayList(selectedPackages))
        }
        startForegroundService(intent)
        isVpnActive = true
    }
}
