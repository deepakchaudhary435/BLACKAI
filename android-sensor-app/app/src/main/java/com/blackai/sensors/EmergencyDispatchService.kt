package com.blackai.sensors

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.security.SecureRandom

/** Pre-authorized SOS to locally stored trusted contacts only. */
class EmergencyDispatchService : Service(), LocationListener {
    companion object {
        const val ACTION_TRIGGER = "com.blackai.sensors.action.TRIGGER_PREAUTHORIZED_SOS"
        const val ACTION_SEND_NOW = "com.blackai.sensors.action.SEND_PREAUTHORIZED_SOS_NOW"
        const val ACTION_CANCEL = "com.blackai.sensors.action.CANCEL_PREAUTHORIZED_SOS"
        private const val CHANNEL = "blackai_auto_sos"
        private const val NOTIFICATION_ID = 7410
    }
    private val handler = Handler(Looper.getMainLooper())
    private var manager: LocationManager? = null
    private var shareRef: com.google.firebase.database.DatabaseReference? = null
    private var expiry = 0L
    private var activeId: String? = null
    private var countdownTask: Runnable? = null
    private var textToSpeech: android.speech.tts.TextToSpeech? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            countdownTask?.let { handler.removeCallbacks(it) }; countdownTask = null
            textToSpeech?.stop(); notifyStatus("SOS countdown voice-cancel से रद्द हुआ।"); stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TRIGGER) {
            if (!getSharedPreferences("blackai_emergency", MODE_PRIVATE).getBoolean("hands_free_sos_enabled", false)) { stopSelf(); return START_NOT_STICKY }
            createChannel(); startForeground(NOTIFICATION_ID, notification("SOS countdown: 15 seconds. Cancel SOS बोलकर रद्द करें।"))
            textToSpeech = android.speech.tts.TextToSpeech(this) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    textToSpeech?.language = java.util.Locale.forLanguageTag("hi-IN")
                    textToSpeech?.speak("BLACKAI SOS alert. पंद्रह सेकंड में trusted contact को alert भेजा जाएगा। रद्द करने के लिए Cancel SOS बोलें।", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "blackai-sos-countdown")
                }
            }
            countdownTask = Runnable { startService(Intent(this, EmergencyDispatchService::class.java).setAction(ACTION_SEND_NOW)) }
            handler.postDelayed(countdownTask!!, 15000L)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_SEND_NOW) { stopSelf(); return START_NOT_STICKY }
        countdownTask = null
        textToSpeech?.speak("SOS alert भेजने का प्रयास हो रहा है।", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "blackai-sos-send")
        val prefs = getSharedPreferences("blackai_emergency", MODE_PRIVATE)
        if (!prefs.getBoolean("hands_free_sos_enabled", false)) { stopSelf(); return START_NOT_STICKY }
        val contacts = org.json.JSONArray(prefs.getString("contacts", "[]"))
        val phones = (0 until contacts.length()).map { contacts.getJSONObject(it).optString("phone").trim() }.filter { it.isNotBlank() }.distinct().take(5)
        if (phones.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ||
            (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
             checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            stopSelf(); return START_NOT_STICKY
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Automatic SOS चालू — SMS, call और location भेजने का प्रयास।"))
        val bytes = ByteArray(24); SecureRandom().nextBytes(bytes)
        val shareId = bytes.joinToString("") { "%02x".format(it) }
        activeId = shareId
        expiry = System.currentTimeMillis() + 5L * 60L * 60L * 1000L
        shareRef = FirebaseDatabase.getInstance().reference.child("liveShares").child(shareId)
        val link = "https://deepakchaudhary435.github.io/BLACKAI/?live=" + shareId
        val body = "BLACKAI AUTOMATIC SOS: मुझे सहायता चाहिए। कृपया तुरंत संपर्क करें। Live location (5 घंटे तक): " + link + "। तत्काल खतरे में 112 पर संपर्क करें।"
        try {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            phones.forEach { phone -> sms.sendMultipartTextMessage(phone, null, sms.divideMessage(body), null, null) }
            notifyStatus("Trusted Contact को SOS SMS भेजने का प्रयास हुआ।")
        } catch (e: Exception) {
            Log.e("BLACKAI_SOS", "SMS failed", e)
            notifyStatus("SOS SMS भेजना विफल हुआ।")
        }
        beginLiveLocation(uid)
        try {
            val call = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(phones.first()))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(call)
        } catch (e: Exception) {
            Log.e("BLACKAI_SOS", "Call launch blocked", e)
            notifyStatus("Android ने automatic call रोक दी हो सकती है।")
        }
        handler.postDelayed({ stopSharing() }, 5L * 60L * 60L * 1000L)
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission")
    private fun beginLiveLocation(uid: String) {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        manager = lm
        val ref = shareRef ?: return
        val initial = mapOf("uid" to uid, "lat" to 0.0, "lng" to 0.0, "updatedAt" to System.currentTimeMillis(), "expiresAt" to expiry, "active" to true)
        ref.setValue(initial).addOnSuccessListener {
            var started = false
            try {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                    if (lm.isProviderEnabled(provider)) {
                        lm.requestLocationUpdates(provider, 10000L, 10f, this, Looper.getMainLooper())
                        started = true
                    }
                }
                if (!started) notifyStatus("SOS गया, लेकिन location provider बंद है।")
            } catch (e: Exception) { notifyStatus("Live location शुरू नहीं हो सकी।") }
        }.addOnFailureListener { notifyStatus("Live location server पर शुरू नहीं हुई।") }
    }

    override fun onLocationChanged(location: Location) {
        if (System.currentTimeMillis() >= expiry) { stopSharing(); return }
        shareRef?.updateChildren(mapOf("lat" to location.latitude, "lng" to location.longitude, "updatedAt" to System.currentTimeMillis(), "active" to true))
    }
    override fun onProviderDisabled(provider: String) {}
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Deprecated by Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "BLACKAI Automatic SOS", NotificationManager.IMPORTANCE_HIGH))
    }
    private fun notification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("BLACKAI Automatic SOS").setContentText(text).setOngoing(true).build()
    }
    private fun notifyStatus(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) } catch (_: Exception) {}
    }
    private fun stopSharing() {
        handler.removeCallbacksAndMessages(null)
        try { manager?.removeUpdates(this) } catch (_: Exception) {}
        manager = null
        shareRef?.removeValue()
        shareRef = null
        activeId = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { manager?.removeUpdates(this) } catch (_: Exception) {}
        if (activeId != null) shareRef?.updateChildren(mapOf("active" to false, "updatedAt" to System.currentTimeMillis()))
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}