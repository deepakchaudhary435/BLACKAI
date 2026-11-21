package com.blackai.sensors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlin.math.sqrt

class MotionMonitorService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var lastTrigger = 0L
    private val channelId = "blackai_motion_monitor"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(7001, notification("Motion safety monitoring चालू है। बंद करने के लिए ऐप खोलें।"))
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            updateNotification("इस फोन में accelerometer उपलब्ध नहीं है। Monitoring काम नहीं कर सकती।")
            stopSelf()
            return
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val magnitude = sqrt(event.values[0] * event.values[0] +
            event.values[1] * event.values[1] + event.values[2] * event.values[2])
        val now = SystemClock.elapsedRealtime()
        // Prototype signal only: threshold is not a validated fall, assault, or crime detector.
        if (magnitude > 25f && now - lastTrigger > 60_000L) {
            lastTrigger = now
            val prefs = getSharedPreferences("blackai_sensor_log", MODE_PRIVATE)
            val count = prefs.getInt("signal_count", 0) + 1
            val signalText = "संभावित तेज़ झटका — ${String.format("%.1f", magnitude)} m/s²"
            val timestamp = System.currentTimeMillis()
            val history = prefs.getString("signal_history", "") ?: ""
            val newEntry = "$timestamp|$signalText"
            val updatedHistory = (listOf(newEntry) + history.split("\\n").filter { it.isNotBlank() }).take(10).joinToString("\\n")
            prefs.edit()
                .putInt("signal_count", count)
                .putString("last_signal", signalText)
                .putLong("last_signal_time", timestamp)
                .putString("signal_history", updatedHistory)
                .apply()
            updateNotification("संभावित तेज़ झटका दर्ज हुआ। कृपया स्वयं जाँचें कि आप सुरक्षित हैं। कोई संदेश या अलर्ट नहीं भेजा गया।")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "BLACKAI Motion Safety", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
                      else Notification.Builder(this)
        return builder.setContentTitle("BLACKAI Safety")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(7001, notification(message))
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
