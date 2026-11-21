package com.blackai.sensors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import java.util.Locale

/**
 * Consent-based prototype. Uses on-device speech recognition only.
 * No audio is saved or transmitted by this app. This is not a validated distress detector.
 */
class VoiceMonitorService : Service(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var lastMatchAt = 0L
    private val channelId = "blackai_voice_monitor"
    private val notificationId = 7002

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(notificationId, notification("Voice monitoring चालू है। बंद करने के लिए BLACKAI खोलें।"))
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            updateNotification("इस फोन पर on-device speech recognition उपलब्ध नहीं है। Voice monitoring बंद है।")
            stopSelf()
            return
        }
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            recognizer?.setRecognitionListener(this)
            startListening()
        } catch (e: Exception) {
            updateNotification("Voice recognition शुरू नहीं हो सकी।")
            stopSelf()
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            updateNotification("Voice recognition उपलब्ध नहीं है।")
            stopSelf()
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val phrases = listOf(
            "help", "बचाओ", "बचाओ मुझे", "मदद करो", "मदद चाहिए",
            "मेरा अपहरण", "किडनैप", "kidnap", "मुझे छोड़ दो",
            "पुलिस बुलाओ", "मुझे बचाओ"
        )
        val found = matches.firstOrNull { spoken ->
            val normalized = spoken.lowercase(Locale.ROOT)
            phrases.any { normalized.contains(it.lowercase(Locale.ROOT)) }
        }
        if (found != null) recordPossibleVoiceAlert(found)
        startListening()
    }

    private fun recordPossibleVoiceAlert(spoken: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastMatchAt < 60_000L) return
        lastMatchAt = nowElapsed
        val prefs = getSharedPreferences("blackai_sensor_log", MODE_PRIVATE)
        val timestamp = System.currentTimeMillis()
        val text = "संभावित मदद की पुकार — पहचाने गए शब्द: $spoken"
        val count = prefs.getInt("voice_signal_count", 0) + 1
        val history = prefs.getString("voice_signal_history", "") ?: ""
        val newEntry = "$timestamp|$text"
        val updated = (listOf(newEntry) + history.split("\n").filter { it.isNotBlank() })
            .take(10).joinToString("\n")
        prefs.edit()
            .putInt("voice_signal_count", count)
            .putString("voice_last_signal", text)
            .putLong("voice_last_signal_time", timestamp)
            .putString("voice_signal_history", updated)
            .apply()
        updateNotification("संभावित मदद की पुकार पहचानी गई। यह पुष्टि नहीं है; सुरक्षित हों तो सहायता लें। कोई संदेश/कॉल नहीं भेजा गया।")
    }

    override fun onError(error: Int) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            updateNotification("माइक्रोफोन अनुमति नहीं है। Voice monitoring बंद है।")
            stopSelf()
            return
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            return
        }
        // Recognition sessions may end naturally; restart while the user-enabled service remains active.
        startListening()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        updateNotification("Voice monitoring चालू — केवल on-device शब्द पहचान।")
    }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "BLACKAI Voice Safety", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
                      else Notification.Builder(this)
        return builder.setContentTitle("BLACKAI Voice Safety")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(notificationId, notification(message))
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
