package com.blackai.sensors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-started, visible on-device acoustic scene estimate.
 * Audio is processed in memory only and is never stored or uploaded.
 * Model labels cannot authenticate live speech vs TV/radio.
 */
class YamnetSceneMonitorService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private val channelId = "blackai_yamnet_scene"
    private val notificationId = 7011

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(notificationId, makeNotification("Audio Scene Check चालू — mic सक्रिय है; बंद करने के लिए BLACKAI खोलें।"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            worker = Thread({ processAudio() }, "BLACKAI-YAMnet-Audio")
            worker?.start()
        }
        return START_NOT_STICKY
    }

    private fun processAudio() {
        var recorder: AudioRecord? = null
        var classifier: YamnetAudioClassifier? = null
        try {
            classifier = YamnetAudioClassifier(this)
            val minBuffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minBuffer > 0) { "AudioRecord buffer size unavailable: $minBuffer" }
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 15600 * 2)
            )
            require(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone recorder did not initialize" }
            val shorts = ShortArray(15600)
            val floats = FloatArray(15600)
            recorder.startRecording()
            var filled = 0
            while (running.get()) {
                val count = recorder.read(shorts, filled, shorts.size - filled)
                if (count < 0) throw IllegalStateException("AudioRecord read error: $count")
                filled += count
                if (filled == shorts.size) {
                    for (i in shorts.indices) floats[i] = shorts[i] / 32768.0f
                    val assessment = classifier.assessSource(floats)
                    val label = when (assessment.category) {
                        AudioSourceAssessment.Category.MEDIA_LIKE -> "मीडिया-जैसी ध्वनि (अनुमान)"
                        AudioSourceAssessment.Category.SPEECH_LIKE -> "बोलने जैसी ध्वनि (अनुमान)"
                        AudioSourceAssessment.Category.MIXED_OR_AMBIGUOUS -> "मीडिया + speech / अस्पष्ट"
                        AudioSourceAssessment.Category.UNKNOWN -> "स्रोत अस्पष्ट"
                    }
                    getSharedPreferences("blackai_sensor_log", MODE_PRIVATE).edit()
                        .putString("yamnet_last_category", label)
                        .putString("yamnet_last_explanation", assessment.explanation)
                        .putLong("yamnet_last_time", System.currentTimeMillis())
                        .apply()
                    updateNotification("$label — ${assessment.explanation} कोई SOS स्वतः नहीं भेजा गया।")
                    filled = 0
                }
            }
        } catch (e: Exception) {
            updateNotification("YAMNet Audio Check बंद: ${e.message ?: "ऑडियो/मॉडल समस्या"}")
            running.set(false)
            stopSelf()
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            recorder?.release()
            try { classifier?.close() } catch (_: Exception) {}
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "BLACKAI YAMNet Audio Check", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun makeNotification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder.setContentTitle("BLACKAI — YAMNet Audio Check")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(notificationId, makeNotification(message))
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
