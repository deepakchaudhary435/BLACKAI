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
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Consent-based experimental prototype. Android 10+ uses the installed speech-recognition
 * provider; audio handling depends on that provider and may involve network processing.
 * This is not a validated distress detector.
 */
class VoiceMonitorService : Service(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var lastMatchAt = 0L
    private var retryCount = 0
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryAction = Runnable { startListening() }
    private val channelId = "blackai_voice_monitor"
    private val notificationId = 7002

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(notificationId, notification("Voice monitoring चालू है। बंद करने के लिए BLACKAI खोलें।"))
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("इस फोन पर speech recognition सेवा उपलब्ध नहीं है। Voice monitoring बंद है।")
            stopSelf()
            return
        }
        try {
            recognizer = if (Build.VERSION.SDK_INT >= 31 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                // Android 10/11 fallback: use the device's installed recognition provider.
                SpeechRecognizer.createSpeechRecognizer(this)
            }
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
            // Do not force hi-IN or offline mode: let the installed provider choose a
            // supported default language/mode. Recognition may use network processing.
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
        retryCount = 0
        if (found != null) recordPossibleVoiceAlert(found)
        retryHandler.removeCallbacks(retryAction)
        retryHandler.postDelayed(retryAction, 500L)
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

    private fun errorLabel(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "माइक्रोफोन ऑडियो समस्या"
        SpeechRecognizer.ERROR_CLIENT -> "ऐप/recognizer client समस्या"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "माइक्रोफोन अनुमति नहीं"
        SpeechRecognizer.ERROR_NETWORK -> "नेटवर्क समस्या"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "नेटवर्क समय समाप्त"
        SpeechRecognizer.ERROR_NO_MATCH -> "आवाज़/शब्द पहचान नहीं पाए"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "पहचान सेवा व्यस्त"
        SpeechRecognizer.ERROR_SERVER -> "Speech सेवा की सर्वर समस्या"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "कोई बोलना पहचाना नहीं गया"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech सेवा से कनेक्शन टूटा"
        else -> "अन्य Speech Recognition त्रुटि"
    }

    override fun onError(error: Int) {
        val detail = "Voice त्रुटि कोड $error: " + errorLabel(error)
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            updateNotification("$detail। Voice monitoring बंद है।")
            stopSelf()
            return
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            updateNotification("$detail। दोबारा कोशिश हो रही है।")
            retryHandler.removeCallbacks(retryAction)
            retryHandler.postDelayed(retryAction, 1500L)
            return
        }
        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            // No recognized words is common during quiet periods. Keep the
            // user-started foreground listener available and retry after a pause.
            updateNotification("कोई शब्द पहचान में नहीं आया। Voice monitoring फिर सुनने की कोशिश करेगी।")
            retryHandler.removeCallbacks(retryAction)
            retryHandler.postDelayed(retryAction, 1500L)
            return
        }
        if (error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
            retryCount++
            if (retryCount <= 3) {
                updateNotification("$detail। पुनः प्रयास $retryCount/3।")
                retryHandler.removeCallbacks(retryAction)
                retryHandler.postDelayed(retryAction, 2000L * retryCount)
            } else {
                updateNotification("$detail। 3 प्रयासों के बाद Voice recognition रुक गई।")
                stopSelf()
            }
            return
        }
        updateNotification("$detail। Voice monitoring बंद है।")
        stopSelf()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        retryCount = 0
        updateNotification("Voice monitoring चालू — बोले गए शब्द पहचानने की कोशिश।")
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
        retryHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
