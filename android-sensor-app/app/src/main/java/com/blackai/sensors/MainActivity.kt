package com.blackai.sensors

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        layout.addView(TextView(this).apply {
            text = "BLACKAI — Smart Motion Safety"
            textSize = 24f
        })
        layout.addView(TextView(this).apply {
            text = "यह शुरुआती परीक्षण है। सेंसर केवल तेज झटके जैसे सीमित संकेत पहचानते हैं; वे अपहरण, यौन अपराध या किसी अपराध का पता नहीं लगा सकते। कोई पुलिस या संपर्क अलर्ट नहीं भेजा जाता।"
            textSize = 16f
            setPadding(0, 24, 0, 24)
        })
        status = TextView(this).apply { text = "Monitoring बंद है"; textSize = 16f }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = "मैं सहमत हूँ — Motion Monitoring शुरू करें"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
                } else startMonitoring()
            }
        })
        layout.addView(Button(this).apply {
            text = "Monitoring बंद करें"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MotionMonitorService::class.java))
                status.text = "Monitoring बंद है"
            }
        })
        setContentView(layout)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMonitoring()
        else status.text = "Notification अनुमति नहीं मिली; Monitoring शुरू नहीं हुई।"
    }

    private fun startMonitoring() {
        val intent = Intent(this, MotionMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Monitoring चालू — स्थायी notification दिखाई देगा।"
    }
}
