package com.example.speedlimiter

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button

class MainActivity : Activity() {

    private val vpnRequestCode = 1001
    private var isVpnActive = false
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        toggleButton = Button(this).apply {
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
                    startSpeedLimiterVpn()
                }
            } else {
                stopService(Intent(this, MyVpnService::class.java))
                isVpnActive = false
                toggleButton.text = "تشغيل SpeedLimiter VPN"
            }
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
        startService(Intent(this, MyVpnService::class.java))
        isVpnActive = true
        toggleButton.text = "إيقاف SpeedLimiter VPN"
    }
}
