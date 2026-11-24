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
import android.media.AudioManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    private var providerLabel = "system default (not reported)"
    private var selectedLanguageTag = "hi-IN"
    private var selectedLanguageName = "हिन्दी"
    private var lastMatchedPhrase = ""
    private var lastMatchedAt = 0L
    private var peakSpeechLevel = 0f
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryAction = Runnable { startListening() }
    private val channelId = "blackai_voice_monitor"
    private val notificationId = 7002

    override fun onCreate() {
        super.onCreate()
        createChannel()
        providerLabel = Settings.Secure.getString(contentResolver, "voice_recognition_service")
            ?.substringBefore("/")
            ?.takeIf { it.isNotBlank() }
            ?: "system default (not reported)"
        val languagePrefs = getSharedPreferences("blackai_sensor_log", MODE_PRIVATE)
        selectedLanguageTag = languagePrefs.getString("voice_language_tag", "hi-IN") ?: "hi-IN"
        selectedLanguageName = languagePrefs.getString("voice_language_name", "हिन्दी") ?: "हिन्दी"
        startForeground(notificationId, notification("Voice monitoring चालू है। चुनी गई भाषा: $selectedLanguageName। बंद करने के लिए BLACKAI खोलें।"))
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
            // Ask the installed provider to recognize the user's selected language.
            // Android 11 providers may not support every locale; this is best-effort.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLanguageTag)
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
        val phrasesByLanguage = mapOf(
            "as-IN" to listOf("সহায়", "বচাও", "মোক বচাও", "help"),
            "bn-IN" to listOf("বাঁচাও", "আমাকে বাঁচাও", "সাহায্য করো", "সাহায্য চাই", "help"),
            "brx-IN" to listOf("help", "बचाओ"),
            "doi-IN" to listOf("बचाओ", "मेरी मदद करो", "help"),
            "gu-IN" to listOf("બચાવો", "મને બચાવો", "મદદ કરો", "મદદ જોઈએ", "help"),
            "hi-IN" to listOf("बचाओ", "बचाओ मुझे", "मदद करो", "मदद चाहिए", "मुझे बचाओ", "पुलिस बुलाओ", "help", "kidnap"),
            "kn-IN" to listOf("ಬಚಾವ್", "ನನ್ನನ್ನು ಕಾಪಾಡಿ", "ಸಹಾಯ ಮಾಡಿ", "ಸಹಾಯ ಬೇಕು", "help"),
            "ks-IN" to listOf("مدد", "بچاؤ", "مےٚ بچاو", "help"),
            "kok-IN" to listOf("वाचय", "म्हाका वाचय", "मदत कर", "help"),
            "mai-IN" to listOf("बचाउ", "हमरा बचाउ", "मदद करू", "help"),
            "ml-IN" to listOf("രക്ഷിക്കൂ", "എന്നെ രക്ഷിക്കൂ", "സഹായിക്കൂ", "സഹായം വേണം", "help"),
            "mni-IN" to listOf("ꯑꯄꯥꯕ", "ꯑꯩꯕꯨ ꯑꯄꯥꯕ", "ꯃꯇꯦꯡ ꯄꯤꯌꯨ", "help"),
            "mr-IN" to listOf("वाचवा", "मला वाचवा", "मदत करा", "मदत हवी", "help"),
            "ne-IN" to listOf("बचाउनुहोस्", "मलाई बचाउनुहोस्", "मद्दत गर्नुहोस्", "help"),
            "or-IN" to listOf("ବଞ୍ଚାଅ", "ମୋତେ ବଞ୍ଚାଅ", "ସାହାଯ୍ୟ କର", "help"),
            "pa-IN" to listOf("ਬਚਾਓ", "ਮੈਨੂੰ ਬਚਾਓ", "ਮਦਦ ਕਰੋ", "ਮਦਦ ਚਾਹੀਦੀ", "help"),
            "sa-IN" to listOf("रक्षतु", "मां रक्षतु", "साहाय्यं कुरुत", "help"),
            "sat-IN" to listOf("help", "बचाओ"),
            "sd-IN" to listOf("بچايو", "مون کي بچايو", "مدد ڪريو", "help"),
            "ta-IN" to listOf("காப்பாற்றுங்கள்", "என்னைக் காப்பாற்றுங்கள்", "உதவி செய்யுங்கள்", "உதவி வேண்டும்", "help"),
            "te-IN" to listOf("కాపాడండి", "నన్ను కాపాడండి", "సహాయం చేయండి", "సహాయం కావాలి", "help"),
            "ur-IN" to listOf("بچاؤ", "مجھے بچاؤ", "مدد کرو", "مدد چاہیے", "پولیس بلاؤ", "help")
        )
        val phrases = phrasesByLanguage[selectedLanguageTag] ?: listOf("help")
        val contextPhrases = if (selectedLanguageTag == "hi-IN") listOf(
            "मुझे जबरदस्ती", "मुझे पकड़ लिया", "मुझे छोड़ दो", "मुझे ले जा रहे",
            "मेरे साथ गलत", "मुझे धमकी", "मुझे बंद कर", "मुझे जाने दो",
            "मत मारो", "हाथ मत लगाओ", "यहां से बचाओ", "पुलिस को बुलाओ",
            "help me", "don't hurt me", "let me go", "leave me alone", "call the police"
        ) else emptyList()
        val found = matches.firstOrNull { spoken ->
            val normalized = spoken.lowercase(Locale.ROOT)
            phrases.any { normalized.contains(it.lowercase(Locale.ROOT)) } ||
                contextPhrases.any { normalized.contains(it.lowercase(Locale.ROOT)) }
        }
        retryCount = 0
        if (found != null) {
            val now = SystemClock.elapsedRealtime()
            // Android can report whether this phone is actively playing media.
            // If playback is active, suppress this phrase match to reduce alerts
            // caused by audio from music/video/radio apps. This cannot identify
            // TV/radio audio from another device or prove the source of a voice.
            if (isPhoneMediaPlaybackActive()) {
                lastMatchedPhrase = ""
                lastMatchedAt = 0L
                peakSpeechLevel = 0f
                updateNotification("फोन में media playback चालू है; मदद-जैसा phrase मिला लेकिन alert रिकॉर्ड नहीं किया गया। बाहरी TV/Radio की पहचान पक्की नहीं हो सकती।")
            } else {
                val normalized = found.lowercase(Locale.ROOT).trim()
                // Repeated identical phrases are only a weak additional media hint.
                val repeatedLikeMedia = normalized == lastMatchedPhrase &&
                    now - lastMatchedAt in 1_000L..45_000L
                // Repetition alone cannot prove that audio came from TV/radio:
                // a real person may repeat a help phrase. Keep it as an uncertain
                // signal instead of silently discarding it.
                recordPossibleVoiceAlert(
                    found,
                    false,
                    peakSpeechLevel,
                    if (repeatedLikeMedia) "दोहराव मिला; स्रोत अनिश्चित (मीडिया-जैसा संकेत)" else "स्रोत अनिश्चित"
                )
                if (repeatedLikeMedia) {
                    updateNotification("एक जैसा मदद वाला phrase दोहराया गया। स्रोत अनिश्चित है; इसे TV/Radio साबित नहीं माना गया।")
                }
                lastMatchedPhrase = normalized
                lastMatchedAt = now
                peakSpeechLevel = 0f
            }
        } else if (matches.isNotEmpty()) {
            // Privacy-preserving diagnostic: report that text alternatives arrived,
            // without displaying or storing the words spoken in the environment.
            updateNotification("Speech recognition से ${matches.size} text विकल्प मिले, लेकिन कोई मदद-संबंधी phrase match नहीं हुआ।")
        }
        retryHandler.removeCallbacks(retryAction)
        retryHandler.postDelayed(retryAction, 500L)
    }

    private fun isPhoneMediaPlaybackActive(): Boolean {
        return try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.isMusicActive
        } catch (_: Exception) {
            false
        }
    }

    private fun recordPossibleVoiceAlert(
        spoken: String,
        mediaLike: Boolean,
        peakLevel: Float,
        sourceHint: String = "स्रोत अनिश्चित"
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!mediaLike && nowElapsed - lastMatchAt < 60_000L) return
        lastMatchAt = nowElapsed
        val prefs = getSharedPreferences("blackai_sensor_log", MODE_PRIVATE)
        val timestamp = System.currentTimeMillis()
        val classification = if (mediaLike) "मीडिया-जैसी दोहराव वाली आवाज़ (अनुमान)" else "संभावित distress संकेत (पुष्टि आवश्यक)"
        val text = "$classification — $sourceHint — भाषा: $selectedLanguageName ($selectedLanguageTag) — पहचाने गए शब्द: $spoken — speech level peak: " + String.format(Locale.ROOT, "%.1f", peakLevel)
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
        if (mediaLike) {
            updateNotification("मीडिया जैसी दोहराई हुई आवाज़ का संदेह। पक्का TV/Radio पहचानना संभव नहीं; कोई SOS नहीं भेजा गया।")
        } else {
            updateNotification("संभावित मदद-संबंधी शब्द मिले। ऑडियो स्रोत की पुष्टि नहीं हुई ($sourceHint)। यह केवल संकेत है; कोई कॉल/SMS/लोकेशन साझा नहीं हुई।")
        }
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
            updateNotification("${detail}। Voice monitoring फिर सुनने की कोशिश करेगी।")
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
        updateNotification("Voice monitoring चालू — $selectedLanguageName में शब्द पहचानने की कोशिश।")
    }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) {
        if (rmsdB.isFinite() && rmsdB > peakSpeechLevel) peakSpeechLevel = rmsdB
    }
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
        val displayMessage = "$message\nSpeech provider: $providerLabel"
        return builder.setContentTitle("BLACKAI Voice Safety")
            .setContentText(displayMessage)
            .setStyle(Notification.BigTextStyle().bigText(displayMessage))
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
