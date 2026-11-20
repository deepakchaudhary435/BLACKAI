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
        // Conservative prototype threshold only. This is not a validated fall or crime detector.
        if (magnitude > 25f && now - lastTrigger > 60_000L) {
            lastTrigger = now
            updateNotification("तेज झटका महसूस हुआ। क्या आप सुरक्षित हैं? यह केवल एक संभावित संकेत है; कोई अलर्ट नहीं भेजा गया।")
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
