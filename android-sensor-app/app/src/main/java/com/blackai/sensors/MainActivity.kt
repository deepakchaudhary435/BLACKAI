package com.blackai.sensors

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var signalLog: TextView
    private val createBackupRequest = 51
    private val restoreBackupRequest = 52
    private val voicePermissionRequest = 61

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
            text = "यह शुरुआती परीक्षण है। सेंसर केवल तेज़ गति/झटके जैसा संकेत दर्ज कर सकता है; यह किसी अपराध, गिरने या आपातकाल की पुष्टि नहीं करता। कोई पुलिस या संपर्क अलर्ट नहीं भेजा जाता।"
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
        layout.addView(TextView(this).apply {
            text = "Voice Safety — प्रयोगात्मक"
            textSize = 20f
            setPadding(0, 28, 0, 8)
        })
        layout.addView(TextView(this).apply {
            text = "यह प्रयोगात्मक सुविधा Android 10+ पर उपलब्ध speech-recognition सेवा से बोले गए शब्द पहचानने की कोशिश करेगी। पहचान सेवा के अनुसार ऑडियो नेटवर्क पर प्रोसेस हो सकता है। यह परिवार/पुलिस को संदेश या कॉल नहीं भेजती और रोने/चीखने की पहचान नहीं करती।"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        })
        layout.addView(Button(this).apply {
            text = "मैं सहमत हूँ — Voice Detection शुरू करें"
            setOnClickListener {
                val permissions = if (Build.VERSION.SDK_INT >= 33)
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                else arrayOf(Manifest.permission.RECORD_AUDIO)
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(permissions, voicePermissionRequest)
                } else startVoiceMonitoring()
            }
        })
        layout.addView(Button(this).apply {
            text = "Voice Detection बंद करें"
            setOnClickListener {
                stopService(Intent(this@MainActivity, VoiceMonitorService::class.java))
                status.text = "Voice Detection बंद करने का अनुरोध भेजा गया।"
            }
        })
        layout.addView(TextView(this).apply {
            text = "स्थानीय सेंसर लॉग"
            textSize = 20f
            setPadding(0, 28, 0, 8)
        })
        signalLog = TextView(this).apply { textSize = 15f }
        layout.addView(signalLog)
        layout.addView(Button(this).apply {
            text = "Backup History — फ़ाइल में सेव करें"
            setOnClickListener { createBackupFile() }
        })
        layout.addView(Button(this).apply {
            text = "Restore History — बैकअप वापस लाएँ"
            setOnClickListener { chooseBackupFile() }
        })
        layout.addView(Button(this).apply {
            text = "Export History / साझा करें"
            setOnClickListener { exportHistory() }
        })
        layout.addView(Button(this).apply {
            text = "Delete History / इतिहास मिटाएँ"
            setOnClickListener { confirmClearHistory() }
        })
        layout.addView(TextView(this).apply {
            text = "Backup से JSON फ़ाइल बनेगी। उसे Downloads में सुरक्षित रखें। ऐप uninstall करने के बाद दोबारा install करके Restore History से वही फ़ाइल चुनें। Delete History करने से ऐप का स्थानीय रिकॉर्ड मिटेगा; पहले बैकअप सुरक्षित कर लें।"
            textSize = 13f
            setPadding(0, 12, 0, 24)
        })
        setContentView(layout)
        refreshSignalLog()
        checkBackupReminder()
    }

    override fun onResume() {
        super.onResume()
        if (::signalLog.isInitialized) refreshSignalLog()
    }

    private fun historyPrefs() = getSharedPreferences("blackai_sensor_log", MODE_PRIVATE)

    private fun buildHistoryText(): String {
        val prefs = historyPrefs()
        val motionCount = prefs.getInt("signal_count", 0)
        val motionLast = prefs.getString("last_signal", null)
        val motionTime = prefs.getLong("last_signal_time", 0L)
        val motionHistory = prefs.getString("signal_history", "") ?: ""
        val voiceCount = prefs.getInt("voice_signal_count", 0)
        val voiceLast = prefs.getString("voice_last_signal", null)
        val voiceTime = prefs.getLong("voice_last_signal_time", 0L)
        val voiceHistory = prefs.getString("voice_signal_history", "") ?: ""

        fun formatEntries(raw: String): String {
            val entries = raw.split("\n").filter { it.isNotBlank() }.map { entry ->
                val parts = entry.split("|", limit = 2)
                if (parts.size == 2) {
                    val formattedTime = parts[0].toLongOrNull()?.let {
                        DateFormat.getDateTimeInstance().format(Date(it))
                    } ?: "समय उपलब्ध नहीं"
                    "• $formattedTime — ${parts[1]}"
                } else entry
            }
            return if (entries.isEmpty()) "कोई रिकॉर्ड उपलब्ध नहीं।" else entries.joinToString("\n")
        }

        val motionSection = if (motionLast == null) {
            "Motion: अभी तक कोई संभावित सेंसर संकेत दर्ज नहीं हुआ।"
        } else {
            "Motion संकेत: $motionCount\nआखिरी संकेत: $motionLast\nसमय: ${DateFormat.getDateTimeInstance().format(Date(motionTime))}\nहाल के संकेत:\n${formatEntries(motionHistory)}"
        }
        val voiceSection = if (voiceLast == null) {
            "Voice: अभी तक कोई संभावित phrase match इतिहास में दर्ज नहीं हुआ।"
        } else {
            "Voice संभावित phrase match: $voiceCount\nआखिरी रिकॉर्ड: $voiceLast\nसमय: ${DateFormat.getDateTimeInstance().format(Date(voiceTime))}\nहाल के रिकॉर्ड:\n${formatEntries(voiceHistory)}"
        }
        return "$motionSection\n\n$voiceSection\n\nनोट: ये प्रयोगात्मक संकेत हैं; गलत पहचान संभव है। सामान्य पहचाने गए शब्द इतिहास में सेव नहीं होते।"
    }

    private fun refreshSignalLog() { signalLog.text = buildHistoryText() }

    private fun createBackupFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "BLACKAI-history-backup.json")
        }
        startActivityForResult(intent, createBackupRequest)
    }

    private fun chooseBackupFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, restoreBackupRequest)
    }

    private fun checkBackupReminder() {
        val prefs = historyPrefs()
        val now = System.currentTimeMillis()
        val lastBackup = prefs.getLong("last_backup_at", 0L)
        val snoozedUntil = prefs.getLong("backup_reminder_snoozed_until", 0L)
        val sevenDays = 7L * 24L * 60L * 60L * 1000L
        if (now - lastBackup >= sevenDays && now >= snoozedUntil) {
            AlertDialog.Builder(this)
                .setTitle("BLACKAI बैकअप रिमाइंडर")
                .setMessage("अपनी स्थानीय सेंसर हिस्ट्री का बैकअप Downloads में सुरक्षित रखें। ऐप uninstall करने पर स्थानीय हिस्ट्री मिट सकती है।")
                .setNegativeButton("बाद में") { _, _ ->
                    prefs.edit().putLong("backup_reminder_snoozed_until", now + 24L * 60L * 60L * 1000L).apply()
                }
                .setPositiveButton("अभी बैकअप बनाएँ") { _, _ -> createBackupFile() }
                .show()
        }
    }

    private fun writeBackup(uri: Uri) {
        val prefs = historyPrefs()
        val json = JSONObject().apply {
            put("format", "BLACKAI_SENSOR_HISTORY")
            put("version", 1)
            put("signal_count", prefs.getInt("signal_count", 0))
            put("last_signal", prefs.getString("last_signal", null) ?: JSONObject.NULL)
            put("last_signal_time", prefs.getLong("last_signal_time", 0L))
            put("signal_history", prefs.getString("signal_history", "") ?: "")
            put("voice_signal_count", prefs.getInt("voice_signal_count", 0))
            put("voice_last_signal", prefs.getString("voice_last_signal", null) ?: JSONObject.NULL)
            put("voice_last_signal_time", prefs.getLong("voice_last_signal_time", 0L))
            put("voice_signal_history", prefs.getString("voice_signal_history", "") ?: "")
            put("backup_created_at", System.currentTimeMillis())
        }
        contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(json.toString(2))
        } ?: throw IllegalStateException("फ़ाइल नहीं खुल सकी")
        historyPrefs().edit().putLong("last_backup_at", System.currentTimeMillis()).apply()
        historyPrefs().edit().remove("backup_reminder_snoozed_until").apply()
        Toast.makeText(this, "BLACKAI हिस्ट्री बैकअप सेव हो गया।", Toast.LENGTH_LONG).show()
    }

    private fun restoreBackup(uri: Uri) {
        val jsonText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: throw IllegalStateException("बैकअप फ़ाइल नहीं पढ़ी जा सकी")
        val json = JSONObject(jsonText)
        require(json.optString("format") == "BLACKAI_SENSOR_HISTORY") { "यह BLACKAI का मान्य बैकअप नहीं है" }
        require(json.optInt("version", -1) == 1) { "बैकअप वर्जन समर्थित नहीं है" }
        val count = json.getInt("signal_count")
        val lastValue = json.opt("last_signal")
        val last = if (lastValue == null || lastValue == JSONObject.NULL) null else lastValue.toString()
        val time = json.getLong("last_signal_time")
        val history = json.getString("signal_history")
        val voiceCount = json.optInt("voice_signal_count", 0)
        val voiceLastValue = json.opt("voice_last_signal")
        val voiceLast = if (voiceLastValue == null || voiceLastValue == JSONObject.NULL) null else voiceLastValue.toString()
        val voiceTime = json.optLong("voice_last_signal_time", 0L)
        val voiceHistory = json.optString("voice_signal_history", "")
        require(count >= 0 && time >= 0L && voiceCount >= 0 && voiceTime >= 0L) { "बैकअप डेटा सही नहीं है" }
        historyPrefs().edit()
            .putInt("signal_count", count)
            .putString("last_signal", last)
            .putLong("last_signal_time", time)
            .putString("signal_history", history)
            .putInt("voice_signal_count", voiceCount)
            .putString("voice_last_signal", voiceLast)
            .putLong("voice_last_signal_time", voiceTime)
            .putString("voice_signal_history", voiceHistory)
            .apply()
        refreshSignalLog()
        Toast.makeText(this, "पुरानी BLACKAI हिस्ट्री वापस आ गई।", Toast.LENGTH_LONG).show()
    }

    @Deprecated("Uses the Android document picker for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                createBackupRequest -> writeBackup(uri)
                restoreBackupRequest -> AlertDialog.Builder(this)
                    .setTitle("पुरानी हिस्ट्री वापस लाएँ?")
                    .setMessage("इससे अभी ऐप में मौजूद स्थानीय हिस्ट्री चुने गए बैकअप से बदल जाएगी।")
                    .setNegativeButton("रद्द करें", null)
                    .setPositiveButton("Restore करें") { _, _ ->
                        try { restoreBackup(uri) }
                        catch (e: Exception) {
                            Toast.makeText(this, "Restore नहीं हुआ: ${e.message ?: "फ़ाइल में समस्या"}", Toast.LENGTH_LONG).show()
                        }
                    }.show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "काम पूरा नहीं हुआ: ${e.message ?: "फ़ाइल में समस्या"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportHistory() {
        val text = "BLACKAI — स्थानीय सेंसर इतिहास\nसमय: ${DateFormat.getDateTimeInstance().format(Date())}\n\n${buildHistoryText()}\n\nनोट: ये केवल प्रयोगात्मक सेंसर संकेत हैं, किसी घटना या अपराध की पुष्टि नहीं।"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BLACKAI Sensor History")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(shareIntent, "इतिहास साझा करने के लिए ऐप चुनें"))
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("इतिहास मिटाएँ?")
            .setMessage("इस ऐप में सेव पूरा स्थानीय सेंसर इतिहास मिट जाएगा। यह वापस नहीं मिलेगा। पहले बैकअप सुरक्षित कर लें।")
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("इतिहास मिटाएँ") { _, _ ->
                historyPrefs().edit().remove("signal_count").remove("last_signal")
                    .remove("last_signal_time").remove("signal_history").apply()
                refreshSignalLog()
                status.text = "स्थानीय सेंसर इतिहास मिटा दिया गया। Monitoring की स्थिति नहीं बदली।"
            }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMonitoring()
        else if (requestCode == voicePermissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceMonitoring()
        else if (requestCode == 41 || requestCode == voicePermissionRequest) status.text = "ज़रूरी अनुमति नहीं मिली; Monitoring शुरू नहीं हुई।"
    }

    private fun startVoiceMonitoring() {
        val intent = Intent(this, VoiceMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Voice Detection शुरू करने का अनुरोध भेजा गया।"
    }

    private fun startMonitoring() {
        val intent = Intent(this, MotionMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Monitoring चालू — स्थायी notification दिखाई देगा।"
    }
}
