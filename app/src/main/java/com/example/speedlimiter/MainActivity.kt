package com.example.speedlimiter

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val vpnRequestCode = 1001
    private var isVpnActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toggleButton = Button(this).apply {
            text = "تشغيل SpeedLimiter VPN"
            textSize = 18f
        }
        setContentView(toggleButton)

        toggleButton.setOnClickListener {
            if (!isVpnActive) {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    startActivityForResult(prepareIntent, vpnRequestCode)
                } else {
                    startSpeedLimiterVpn(toggleButton)
                }
            } else {
                stopService(Intent(this, MyVpnService::class.java))
                isVpnActive = false
                toggleButton.text = "تشغيل SpeedLimiter VPN"
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode && resultCode == Activity.RESULT_OK) {
            val button = findViewById<Button>(android.R.id.content)
            startSpeedLimiterVpn(button)
        }
    }

    private fun startSpeedLimiterVpn(button: Button?) {
        startService(Intent(this, MyVpnService::class.java))
        isVpnActive = true
        button?.text = "إيقاف SpeedLimiter VPN"
    }
}
