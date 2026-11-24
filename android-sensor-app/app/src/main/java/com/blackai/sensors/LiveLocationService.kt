package com.blackai.sensors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

/**
 * Visible, user-started, time-limited location sharing.
 * The persistent notification always exposes a Stop action.
 */
class LiveLocationService : Service(), LocationListener {
    companion object {
        const val EXTRA_SHARE_ID = "share_id"
        const val EXTRA_UID = "owner_uid"
        const val EXTRA_EXPIRES_AT = "expires_at"
        const val ACTION_STOP = "com.blackai.sensors.action.STOP_LIVE_LOCATION"
        private const val CHANNEL_ID = "blackai_live_location"
        private const val NOTIFICATION_ID = 7402
    }

    private val handler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null
    private var shareRef: DatabaseReference? = null
    private var shareId: String? = null
    private var expiresAt: Long = 0L
    private var isSharing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_SHARE_ID)
        val uid = intent?.getStringExtra(EXTRA_UID)
        val expiry = intent?.getLongExtra(EXTRA_EXPIRES_AT, 0L) ?: 0L
        if (id.isNullOrBlank() || uid.isNullOrBlank() || expiry <= System.currentTimeMillis() ||
            FirebaseAuth.getInstance().currentUser?.uid != uid) {
            stopSelf()
            return START_NOT_STICKY
        }
        shareId = id
        expiresAt = expiry
        shareRef = FirebaseDatabase.getInstance().reference.child("liveShares").child(id)
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        beginLocationUpdates(uid)
        handler.postDelayed({ stopSharing() }, (expiresAt - System.currentTimeMillis()).coerceAtLeast(1000L))
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission")
    private fun beginLocationUpdates(uid: String) {
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            stopSharing()
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = manager
        val initial = mapOf(
            "uid" to uid,
            "lat" to 0.0,
            "lng" to 0.0,
            "updatedAt" to System.currentTimeMillis(),
            "expiresAt" to expiresAt,
            "active" to true
        )
        shareRef?.setValue(initial)?.addOnSuccessListener {
            isSharing = true
            try {
                var requested = false
                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (manager.isProviderEnabled(provider)) {
                        manager.requestLocationUpdates(provider, 10000L, 10f, this, Looper.getMainLooper())
                        requested = true
                    }
                }
                if (!requested) stopSharing()
            } catch (_: SecurityException) {
                stopSharing()
            } catch (_: Exception) {
                stopSharing()
            }
        }?.addOnFailureListener { stopSharing() }
    }

    override fun onLocationChanged(location: Location) {
        if (!isSharing || System.currentTimeMillis() >= expiresAt) {
            stopSharing()
            return
        }
        val update = mapOf(
            "lat" to location.latitude,
            "lng" to location.longitude,
            "updatedAt" to System.currentTimeMillis(),
            "active" to true
        )
        shareRef?.updateChildren(update)
    }

    override fun onProviderDisabled(provider: String) {}
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLACKAI Live Location",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "दिखाई देने वाली Live Location sharing और Stop control"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LiveLocationService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 7403, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 7404, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("BLACKAI Live Location चालू है")
            .setContentText("आपकी लोकेशन अधिकतम 5 घंटे शेयर हो रही है।")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP SHARING", stopPending)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun stopSharing() {
        handler.removeCallbacksAndMessages(null)
        try { locationManager?.removeUpdates(this) } catch (_: Exception) {}
        locationManager = null
        isSharing = false
        shareRef?.updateChildren(mapOf("active" to false, "updatedAt" to System.currentTimeMillis()))
        shareRef = null
        shareId = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { locationManager?.removeUpdates(this) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
