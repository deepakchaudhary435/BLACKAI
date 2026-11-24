package com.blackai.sensors

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.Firebase
import com.google.firebase.initialize
import com.google.firebase.appcheck.appCheck
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var signalLog: TextView
    private lateinit var dashboard: TextView
    private lateinit var voiceLanguageButton: Button
    private val createBackupRequest = 51
    private val restoreBackupRequest = 52
    private val voicePermissionRequest = 61
    private val locationPermissionRequest = 71
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dashboardHandler = Handler(Looper.getMainLooper())
    private val dashboardRefreshTask = object : Runnable {
        override fun run() {
            if (::dashboard.isInitialized) refreshDashboard()
            dashboardHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Test-build only: use App Check debug attestation while developing on a physical device.
        // The generated debug secret must be registered privately in Firebase Console.
        if (BuildConfig.DEBUG) {
            Firebase.initialize(context = this)
            Firebase.appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
        }
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
        layout.addView(TextView(this).apply {
            text = "BLACKAI — Status Dashboard"
            textSize = 20f
            setPadding(0, 20, 0, 8)
        })
        dashboard = TextView(this).apply {
            textSize = 16f
            setPadding(16, 12, 16, 12)
        }
        layout.addView(dashboard)
        layout.addView(Button(this).apply {
            text = "🛡️ Emergency Control Center"
            setOnClickListener { showEmergencyControlCenter() }
        })
        layout.addView(Button(this).apply {
            text = "🕘 Recent Activity — हाल की गतिविधियाँ"
            setOnClickListener { showRecentActivity() }
        })
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
                refreshDashboard()
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
        voiceLanguageButton = Button(this).apply {
            text = "Voice भाषा चुनें: " + currentVoiceLanguageName()
            setOnClickListener { chooseVoiceLanguage() }
        }
        layout.addView(voiceLanguageButton)
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
                refreshDashboard()
            }
        })
        layout.addView(TextView(this).apply {
            text = "BLACKAI — अकाउंट और प्रोफाइल"
            textSize = 20f
            setPadding(0, 28, 0, 8)
        })
        layout.addView(TextView(this).apply {
            text = "Firebase ईमेल अकाउंट से लॉगिन/रजिस्ट्रेशन करें। प्रोफाइल की जानकारी इसी अकाउंट से दिखाई जाएगी।"
            textSize = 14f
        })
        layout.addView(Button(this).apply {
            text = "Firebase Login / मेरी Profile"
            setOnClickListener { showAccountCenter() }
        })
        layout.addView(Button(this).apply {
            text = "BLACKAI सहायता केंद्र — समस्या भेजें"
            setOnClickListener { showSupportCenter() }
        })
        layout.addView(Button(this).apply {
            text = "मेरी सहायता रिपोर्ट और जवाब देखें"
            setOnClickListener { showMySupportReports() }
        })
        layout.addView(Button(this).apply {
            text = "BLACKAI AI Assistant — Chat और History"
            setOnClickListener { showAiChatCenter() }
        })
        if (BuildConfig.DEBUG) {
            layout.addView(Button(this).apply {
                text = "App Check जाँच (अस्थायी Debug)"
                setOnClickListener { showAppCheckDiagnostics() }
            })
        }
        layout.addView(TextView(this).apply {
            text = "BLACKAI — लोकेशन साझा करें"
            textSize = 20f
            setPadding(0, 28, 0, 8)
        })
        layout.addView(TextView(this).apply {
            text = "लोकेशन केवल आपके बटन दबाने और अनुमति देने पर एक बार ली जाएगी। बैकग्राउंड में ट्रैकिंग नहीं होगी; भेजने से पहले आप शेयर स्क्रीन में प्राप्तकर्ता चुनेंगे।"
            textSize = 14f
        })
        layout.addView(Button(this).apply {
            text = "मेरी वर्तमान लोकेशन साझा करें"
            setOnClickListener { requestAndShareLocation() }
        })
        layout.addView(TextView(this).apply {
            text = "BLACKAI — सुरक्षा केंद्र"
            textSize = 22f
            setPadding(0, 28, 0, 8)
        })
        layout.addView(TextView(this).apply {
            text = "यहाँ से आपातकालीन कॉल स्क्रीन खोलें, भरोसेमंद संपर्क सेव करें और SMS स्वयं जाँचकर भेजने के लिए तैयार करें। BLACKAI अपने आप कॉल या संदेश नहीं भेजेगा।"
            textSize = 14f
        })
        layout.addView(Button(this).apply {
            text = "आपातकालीन सहायता — 112 कॉल स्क्रीन"
            setOnClickListener { confirmEmergencyDial() }
        })
        layout.addView(Button(this).apply {
            text = "भरोसेमंद संपर्क जोड़ें"
            setOnClickListener { addTrustedContact() }
        })
        layout.addView(Button(this).apply {
            text = "मेरे भरोसेमंद संपर्क देखें"
            setOnClickListener { showTrustedContacts() }
        })
        layout.addView(Button(this).apply {
            text = "आपातकालीन SMS तैयार करें"
            setOnClickListener { prepareEmergencySms() }
        })
        layout.addView(Button(this).apply {
            text = "🚨 Emergency Alert — अलर्ट तैयार करें"
            setOnClickListener { confirmEmergencyAlert() }
        })
        layout.addView(Button(this).apply {
            text = "सुरक्षा चेकलिस्ट"
            setOnClickListener { showSafetyChecklist() }
        })
        layout.addView(Button(this).apply {
            text = "आपातकालीन गतिविधि इतिहास"
            setOnClickListener { showEmergencyHistory() }
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
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        }
        setContentView(scrollView)
        applyPremiumTheme(scrollView)
        refreshSignalLog()
        refreshDashboard()
        checkBackupReminder()
    }

    override fun onResume() {
        super.onResume()
        if (::signalLog.isInitialized) refreshSignalLog()
        dashboardHandler.removeCallbacks(dashboardRefreshTask)
        dashboardHandler.post(dashboardRefreshTask)
    }

    override fun onPause() {
        dashboardHandler.removeCallbacks(dashboardRefreshTask)
        super.onPause()
    }

    override fun onDestroy() {
        aiScope.cancel()
        super.onDestroy()
    }


    /**
     * Premium BLACKAI dark dashboard styling. This changes presentation only;
     * existing consent, monitoring, history, backup and restore actions remain intact.
     */
    private fun applyPremiumTheme(view: View) {
        val navy = Color.rgb(9, 16, 31)
        val panel = Color.rgb(19, 31, 51)
        val cyan = Color.rgb(74, 220, 218)
        val white = Color.rgb(239, 245, 250)

        if (view is ScrollView) view.setBackgroundColor(navy)
        if (view is LinearLayout) {
            view.setBackgroundColor(navy)
            view.setPadding(28, 40, 28, 36)
        }
        if (view is Button) {
            view.setTextColor(white)
            view.textSize = 15f
            view.isAllCaps = false
            view.minHeight = 54
            view.setPadding(22, 14, 22, 14)
            view.background = GradientDrawable().apply {
                setColor(panel)
                cornerRadius = 24f
                setStroke(2, cyan)
            }
        } else if (view is TextView) {
            view.setTextColor(white)
            if (view.textSize >= 20f) {
                view.setTextColor(cyan)
                view.setPadding(0, 22, 0, 10)
            } else {
                view.setLineSpacing(3f, 1.08f)
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyPremiumTheme(view.getChildAt(index))
            }
        }
    }

    private fun showAccountCenter() {
        val user = firebaseAuth.currentUser
        if (user != null) {
            val createdAt = user.metadata?.creationTimestamp ?: 0L
            val registrationDate = if (createdAt > 0L) DateFormat.getDateTimeInstance().format(Date(createdAt)) else "उपलब्ध नहीं"
            val photoText = if (user.photoUrl != null) "उपलब्ध — Preview खोल सकते हैं" else "सेट नहीं है"
            val details = "नाम: ${user.displayName ?: "सेट नहीं"}\\n" +
                "लॉगिन ईमेल: ${user.email ?: "उपलब्ध नहीं"}\\n" +
                "ईमेल सत्यापित: ${if (user.isEmailVerified) "हाँ" else "नहीं"}\\n" +
                "रजिस्ट्रेशन: $registrationDate\\n" +
                "प्रोफाइल फोटो: $photoText\\n\\n" +
                "यह जानकारी Firebase अकाउंट से पढ़ी गई है।"
            val actions = mutableListOf("ईमेल verification दोबारा भेजें")
            if (user.photoUrl != null) actions.add("Profile Photo Preview खोलें")
            actions.add("Logout")
            AlertDialog.Builder(this)
                .setTitle("BLACKAI — मेरी Profile")
                .setMessage(details)
                .setItems(actions.toTypedArray()) { _, which ->
                    when (actions[which]) {
                        "ईमेल verification दोबारा भेजें" -> user.sendEmailVerification()
                            .addOnCompleteListener(this) { task ->
                                Toast.makeText(this, if (task.isSuccessful) "Verification ईमेल भेजा गया।" else "ईमेल नहीं भेजा जा सका।", Toast.LENGTH_LONG).show()
                            }
                        "Profile Photo Preview खोलें" -> user.photoUrl?.let { photoUri ->
                            try { startActivity(Intent(Intent.ACTION_VIEW, photoUri)) }
                            catch (_: Exception) { Toast.makeText(this, "Photo Preview उपलब्ध नहीं है।", Toast.LENGTH_LONG).show() }
                        }
                        "Logout" -> {
                            firebaseAuth.signOut()
                            Toast.makeText(this, "आप लॉगआउट हो गए।", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("बंद करें", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("BLACKAI अकाउंट")
            .setItems(arrayOf("लॉगिन", "नया अकाउंट बनाएँ", "पासवर्ड रीसेट")) { _, which ->
                when (which) {
                    0 -> showAuthForm("login")
                    1 -> showAuthForm("register")
                    2 -> showPasswordReset()
                }
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    private fun showAuthForm(mode: String) {
        val email = android.widget.EditText(this).apply {
            hint = "ईमेल"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val password = android.widget.EditText(this).apply {
            hint = "पासवर्ड (कम से कम 6 अक्षर)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
            addView(email)
            addView(password)
        }
        AlertDialog.Builder(this).setTitle(if (mode == "register") "नया BLACKAI अकाउंट" else "BLACKAI Login")
            .setView(panel)
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton(if (mode == "register") "रजिस्टर करें" else "लॉगिन") { _, _ ->
                val mail = email.text.toString().trim()
                val pass = password.text.toString()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches() || pass.length < 6) {
                    Toast.makeText(this, "सही ईमेल और कम से कम 6 अक्षर का पासवर्ड भरें।", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val task = if (mode == "register") firebaseAuth.createUserWithEmailAndPassword(mail, pass)
                    else firebaseAuth.signInWithEmailAndPassword(mail, pass)
                task.addOnCompleteListener(this) { result ->
                    if (result.isSuccessful) {
                        if (mode == "register") firebaseAuth.currentUser?.sendEmailVerification()
                        val message = if (mode == "register") "अकाउंट बन गया। ईमेल verification लिंक जाँचें।" else "लॉगिन सफल।"
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Firebase अकाउंट नहीं खुला: ${result.exception?.localizedMessage ?: "कृपया सेटिंग जाँचें"}", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun showPasswordReset() {
        val email = android.widget.EditText(this).apply {
            hint = "रजिस्टर्ड ईमेल"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        AlertDialog.Builder(this).setTitle("पासवर्ड रीसेट")
            .setView(email)
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("रीसेट ईमेल भेजें") { _, _ ->
                val mail = email.text.toString().trim()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches()) {
                    Toast.makeText(this, "सही ईमेल भरें।", Toast.LENGTH_LONG).show()
                } else {
                    firebaseAuth.sendPasswordResetEmail(mail).addOnCompleteListener(this) { result ->
                        val message = if (result.isSuccessful) "यदि ईमेल अकाउंट मौजूद है तो रीसेट लिंक भेज दिया गया।" else "रीसेट अनुरोध पूरा नहीं हुआ।"
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun requestAndShareLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), locationPermissionRequest)
            return
        }
        fetchOneTimeLocation()
    }

    @Suppress("MissingPermission")
    private fun fetchOneTimeLocation() {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            Toast.makeText(this, "फोन की Location setting चालू करें, फिर दोबारा प्रयास करें।", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "आपकी अनुमति से एक बार लोकेशन प्राप्त की जा रही है…"
        if (Build.VERSION.SDK_INT >= 30) {
            manager.getCurrentLocation(provider, null, mainExecutor) { location ->
                if (location != null) shareLocationToUser(location)
                else Toast.makeText(this, "अभी लोकेशन नहीं मिली। खुले स्थान पर दोबारा प्रयास करें।", Toast.LENGTH_LONG).show()
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    shareLocationToUser(location)
                }
                @Deprecated("Deprecated by Android")
                override fun onStatusChanged(providerName: String?, statusCode: Int, extras: Bundle?) {}
                override fun onProviderEnabled(providerName: String) {}
                override fun onProviderDisabled(providerName: String) {}
            }
            try { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
            catch (_: Exception) { Toast.makeText(this, "लोकेशन नहीं मिल सकी।", Toast.LENGTH_LONG).show() }
        }
    }

    private fun shareLocationToUser(location: Location) {
        val maps = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
        val message = "मेरी BLACKAI लोकेशन (समय: ${DateFormat.getDateTimeInstance().format(Date())}):\n$maps\nकृपया पहले मुझसे संपर्क करें।"
        AlertDialog.Builder(this).setTitle("लोकेशन मिल गई")
            .setMessage("लोकेशन लिंक शेयर करने से पहले जाँचें। इसे भेजना आपकी पसंद है।")
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("शेयर करें") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                startActivity(Intent.createChooser(send, "लोकेशन किसके साथ साझा करें?"))
            }.show()
    }



    // Temporary debug-only check. Never display or store the App Check token.
    private fun showAppCheckDiagnostics() {
        val message = TextView(this).apply {
            text = "App Check की जाँच हो रही है…"
            textSize = 16f
            setPadding(28, 20, 28, 20)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("BLACKAI — App Check Debug")
            .setView(message)
            .setPositiveButton("बंद करें", null)
            .create()
        dialog.show()
        Firebase.appCheck.getAppCheckToken(false)
            .addOnSuccessListener {
                message.text = "App Check से टोकन मिल गया।\n\nध्यान दें: यह केवल टोकन मिलने की जाँच है; इससे यह साबित नहीं होता कि Debug Secret Firebase Console में रजिस्टर है या AI सेवा स्वीकार कर रही है।"
            }
            .addOnFailureListener { error ->
                val detail = (error.localizedMessage ?: error.javaClass.simpleName)
                    .replace(Regex("\\s+"), " ")
                    .take(220)
                message.text = "App Check टोकन नहीं मिला।\nकारण: $detail"
            }
    }

    private fun showAiChatCenter() {
        val user = firebaseAuth.currentUser
        if (user == null) {
            AlertDialog.Builder(this).setTitle("पहले Login करें")
                .setMessage("AI Chat और चैट हिस्ट्री इस्तेमाल करने के लिए पहले BLACKAI में Login करें।")
                .setPositiveButton("ठीक है", null).show()
            return
        }
        val prefs = getSharedPreferences("blackai_ai_chat", MODE_PRIVATE)
        val historyKey = "history_" + user.uid
        val history = org.json.JSONArray(prefs.getString(historyKey, "[]") ?: "[]")
        val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12) }
        val scroll = ScrollView(this).apply { addView(messages) }
        val question = android.widget.EditText(this).apply {
            hint = "BLACKAI से कुछ पूछें…"; minLines = 2; maxLines = 4
            gravity = android.view.Gravity.TOP
        }
        val statusText = TextView(this).apply { text = "AI जवाब देने के लिए तैयार है। इंटरनेट आवश्यक है।" }
        fun addMessage(who: String, body: String) {
            messages.addView(TextView(this).apply {
                text = who + ": " + body
                textSize = 15f
                setPadding(8, 8, 8, 8)
            })
        }
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            addMessage("आप", item.optString("q"))
            addMessage("BLACKAI", item.optString("a"))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(question)
            addView(statusText)
        }
        val dialog = AlertDialog.Builder(this).setTitle("BLACKAI AI Assistant")
            .setView(panel).setNegativeButton("बंद करें", null)
            .setNeutralButton("History खोजें", null).setPositiveButton("भेजें", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val prompt = question.text.toString().trim()
                if (prompt.isBlank()) { question.error = "पहले अपना सवाल लिखें"; return@setOnClickListener }
                if (prompt.length > 4000) { question.error = "सवाल 4000 अक्षरों से छोटा रखें"; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                statusText.text = "BLACKAI जवाब तैयार कर रहा है…"
                aiScope.launch {
                    try {
                        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel("gemini-4-hydrogen")
                        val prior = StringBuilder("आप BLACKAI के सहायक हैं। उपयोगकर्ता की भाषा में सरल, सुरक्षित और तथ्यात्मक जवाब दें। आपातकाल में स्थानीय आपात सेवाओं से संपर्क करने की सलाह दें।\n")
                        val start = maxOf(0, history.length() - 6)
                        for (index in start until history.length()) {
                            val item = history.optJSONObject(index) ?: continue
                            prior.append("User: ").append(item.optString("q")).append("\nAssistant: ").append(item.optString("a")).append("\n")
                        }
                        prior.append("User: ").append(prompt)
                        val answer = withContext(Dispatchers.IO) { model.generateContent(prior.toString()).text ?: "जवाब उपलब्ध नहीं हुआ।" }
                        addMessage("आप", prompt)
                        addMessage("BLACKAI", answer)
                        history.put(JSONObject().put("q", prompt).put("a", answer).put("ts", System.currentTimeMillis()))
                        while (history.length() > 30) history.remove(0)
                        prefs.edit().putString(historyKey, history.toString()).apply()
                        question.setText("")
                        statusText.text = "जवाब तैयार है। चैट फोन में सेव हुई।"
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    } catch (e: Exception) {
                        val detail = (e.localizedMessage ?: e.javaClass.simpleName)
                            .replace(Regex("\\s+"), " ")
                            .take(240)
                        statusText.text = "AI जवाब नहीं मिल सका। कारण: $detail"
                    } finally {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val search = android.widget.EditText(this).apply { hint = "चैट में शब्द खोजें" }
                AlertDialog.Builder(this).setTitle("चैट History खोजें").setView(search)
                    .setPositiveButton("खोजें") { _, _ ->
                        val term = search.text.toString().trim()
                        val found = (0 until history.length()).mapNotNull { history.optJSONObject(it) }
                            .filter { it.optString("q").contains(term, true) || it.optString("a").contains(term, true) }
                        val result = found.joinToString("\n\n") { "आप: " + it.optString("q") + "\nBLACKAI: " + it.optString("a") }
                        AlertDialog.Builder(this).setTitle("खोज परिणाम")
                            .setMessage(if (result.isBlank()) "कोई मिलती-जुलती चैट नहीं मिली।" else result.take(12000))
                            .setPositiveButton("ठीक है", null).show()
                    }.setNegativeButton("रद्द करें", null).show()
            }
        }
        dialog.show()
    }

    private fun showSupportCenter() {
        val user = firebaseAuth.currentUser
        if (user == null) {
            AlertDialog.Builder(this).setTitle("पहले Login करें")
                .setMessage("सहायता रिपोर्ट आपके BLACKAI अकाउंट से जोड़ी जाएगी। पहले Firebase Login करें।")
                .setPositiveButton("ठीक है", null).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "अपनी समस्या या सुझाव लिखें"
            minLines = 3
            maxLines = 6
            gravity = android.view.Gravity.TOP
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("BLACKAI सहायता केंद्र")
            .setMessage("रिपोर्ट 5 से 2000 अक्षरों की हो सकती है। इसमें पासवर्ड, OTP या निजी जानकारी न लिखें।")
            .setView(panel)
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("रिपोर्ट भेजें", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val message = input.text.toString().trim()
                if (message.length !in 5..2000) {
                    input.error = "5 से 2000 अक्षर लिखें"
                    return@setOnClickListener
                }
                val ticket = hashMapOf<String, Any>(
                    "userId" to user.uid,
                    "message" to message,
                    "createdAt" to System.currentTimeMillis(),
                    "status" to "Pending",
                    "priority" to "Medium",
                    "adminReply" to "",
                    "adminReplyAt" to 0L
                )
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                try {
                    FirebaseFirestore.getInstance().collection("supportTickets")
                        .add(ticket)
                        .addOnSuccessListener {
                            logEmergencyAction("BLACKAI सहायता रिपोर्ट भेजी")
                            Toast.makeText(this, "रिपोर्ट भेज दी गई।", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        }
                        .addOnFailureListener {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            Toast.makeText(this, "रिपोर्ट नहीं भेजी गई। Firestore Rules लागू हैं या नहीं, जाँचें।", Toast.LENGTH_LONG).show()
                        }
                } catch (_: Exception) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    Toast.makeText(this, "Firestore उपलब्ध नहीं है। सेटअप जाँचें।", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showMySupportReports() {
        val user = firebaseAuth.currentUser
        if (user == null) {
            AlertDialog.Builder(this).setTitle("पहले Login करें")
                .setMessage("अपनी रिपोर्ट देखने के लिए उसी BLACKAI अकाउंट से Login करें जिससे रिपोर्ट भेजी थी।")
                .setPositiveButton("ठीक है", null).show()
            return
        }
        val loading = AlertDialog.Builder(this).setTitle("मेरी सहायता रिपोर्ट")
            .setMessage("रिपोर्ट लोड हो रही हैं…").setNegativeButton("बंद करें", null).create()
        loading.show()
        FirebaseFirestore.getInstance().collection("supportTickets")
            .whereEqualTo("userId", user.uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(20).get()
            .addOnCompleteListener(this) { task ->
                if (!loading.isShowing) return@addOnCompleteListener
                loading.dismiss()
                if (!task.isSuccessful) {
                    AlertDialog.Builder(this).setTitle("रिपोर्ट नहीं खुलीं")
                        .setMessage("Firestore Rules, इंटरनेट और अकाउंट अनुमति जाँचें।")
                        .setPositiveButton("ठीक है", null).show()
                    return@addOnCompleteListener
                }
                val rows = task.result.documents
                if (rows.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("मेरी सहायता रिपोर्ट")
                        .setMessage("अभी आपने कोई रिपोर्ट नहीं भेजी है। पुरानी Realtime Database रिपोर्टें अभी वहीं सुरक्षित हैं।")
                        .setPositiveButton("ठीक है", null).show()
                    return@addOnCompleteListener
                }
                val labels = rows.map { doc ->
                    val statusValue = doc.getString("status") ?: "Pending"
                    val dateValue = doc.getLong("createdAt") ?: 0L
                    val date = if (dateValue > 0L) DateFormat.getDateTimeInstance().format(Date(dateValue)) else "समय उपलब्ध नहीं"
                    val state = when (statusValue.lowercase()) {
                        "answered", "replied", "resolved", "solved" -> "जवाब मिल गया"
                        "closed" -> "बंद"
                        "in_progress" -> "जाँच जारी"
                        else -> "जाँच जारी"
                    }
                    "$state • $date"
                }
                AlertDialog.Builder(this).setTitle("मेरी सहायता रिपोर्ट")
                    .setItems(labels.toTypedArray()) { _, index ->
                        val doc = rows[index]
                        val message = doc.getString("message") ?: ""
                        val reply = doc.getString("adminReply") ?: ""
                        val statusValue = doc.getString("status") ?: "Pending"
                        val dateValue = doc.getLong("createdAt") ?: 0L
                        val date = if (dateValue > 0L) DateFormat.getDateTimeInstance().format(Date(dateValue)) else "समय उपलब्ध नहीं"
                        val statusLabel = when (statusValue.lowercase()) {
                            "answered", "replied", "resolved", "solved" -> "जवाब दिया गया"
                            "closed" -> "बंद"
                            else -> "जाँच जारी"
                        }
                        val detail = "तारीख: $date\\nस्थिति: $statusLabel\\nPriority: ${doc.getString("priority") ?: "Medium"}\\n\\nआपकी रिपोर्ट:\\n$message\\n\\nसहायता टीम का जवाब:\\n" +
                            if (reply.isBlank()) "अभी जवाब नहीं आया है।" else reply
                        AlertDialog.Builder(this).setTitle("रिपोर्ट का विवरण")
                            .setMessage(detail).setPositiveButton("ठीक है", null).show()
                    }.setNegativeButton("बंद करें", null).show()
            }
    }

    private fun emergencyPrefs() = getSharedPreferences("blackai_emergency", MODE_PRIVATE)

    private fun logEmergencyAction(action: String) {
        val p = emergencyPrefs()
        val old = p.getString("history", "") ?: ""
        val entry = "${System.currentTimeMillis()}|$action"
        val updated = (listOf(entry) + old.split("\\n").filter { it.isNotBlank() }).take(50).joinToString("\\n")
        p.edit().putString("history", updated).apply()
    }

    private fun confirmEmergencyDial() {
        AlertDialog.Builder(this)
            .setTitle("112 आपातकालीन सहायता")
            .setMessage("केवल वास्तविक आपातकाल में आगे बढ़ें। अगली स्क्रीन में कॉल करने से पहले नंबर और स्थिति जाँचें। BLACKAI कॉल अपने आप नहीं करेगा।")
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("कॉल स्क्रीन खोलें") { _, _ ->
                logEmergencyAction("112 कॉल स्क्रीन खोली")
                try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) }
                catch (_: Exception) { Toast.makeText(this, "कॉल स्क्रीन उपलब्ध नहीं है।", Toast.LENGTH_LONG).show() }
            }.show()
    }

    private fun addTrustedContact() {
        val nameInput = android.widget.EditText(this).apply { hint = "नाम" }
        val phoneInput = android.widget.EditText(this).apply {
            hint = "मोबाइल नंबर"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
            addView(nameInput)
            addView(phoneInput)
        }
        AlertDialog.Builder(this).setTitle("भरोसेमंद संपर्क जोड़ें")
            .setView(panel)
            .setMessage("अधिकतम 5 संपर्क। नंबर सही होने की पुष्टि करें।")
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("सेव करें") { _, _ ->
                val name = nameInput.text.toString().trim()
                val phone = phoneInput.text.toString().trim()
                val digits = phone.filter { it.isDigit() }
                if (name.isBlank() || digits.length !in 10..13) {
                    Toast.makeText(this, "नाम और सही मोबाइल नंबर भरें।", Toast.LENGTH_LONG).show()
                } else {
                    val arr = org.json.JSONArray(emergencyPrefs().getString("contacts", "[]"))
                    if (arr.length() >= 5) {
                        Toast.makeText(this, "अधिकतम 5 संपर्क सेव किए जा सकते हैं।", Toast.LENGTH_LONG).show()
                    } else {
                        arr.put(JSONObject().put("name", name).put("phone", phone))
                        emergencyPrefs().edit().putString("contacts", arr.toString()).apply()
                        Toast.makeText(this, "संपर्क इस फोन में सेव हो गया।", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun showTrustedContacts() {
        val arr = org.json.JSONArray(emergencyPrefs().getString("contacts", "[]"))
        if (arr.length() == 0) {
            AlertDialog.Builder(this).setMessage("अभी कोई भरोसेमंद संपर्क सेव नहीं है।").setPositiveButton("ठीक है", null).show()
            return
        }
        val lines = (0 until arr.length()).map { i ->
            val item = arr.getJSONObject(i)
            "${i + 1}. ${item.optString("name")} — ${item.optString("phone")}"
        }
        AlertDialog.Builder(this).setTitle("भरोसेमंद संपर्क")
            .setItems(lines.toTypedArray()) { _, which ->
                val item = arr.getJSONObject(which)
                AlertDialog.Builder(this).setTitle(item.optString("name"))
                    .setMessage(item.optString("phone"))
                    .setNegativeButton("बंद करें", null)
                    .setPositiveButton("हटाएँ") { _, _ ->
                        val updated = org.json.JSONArray()
                        for (i in 0 until arr.length()) if (i != which) updated.put(arr.get(i))
                        emergencyPrefs().edit().putString("contacts", updated.toString()).apply()
                        Toast.makeText(this, "संपर्क हटा दिया गया।", Toast.LENGTH_SHORT).show()
                    }.show()
            }.setNegativeButton("बंद करें", null).show()
    }

    private fun prepareEmergencySms() {
        val arr = org.json.JSONArray(emergencyPrefs().getString("contacts", "[]"))
        if (arr.length() == 0) {
            Toast.makeText(this, "पहले भरोसेमंद संपर्क जोड़ें।", Toast.LENGTH_LONG).show()
            return
        }
        val numbers = (0 until arr.length()).map { arr.getJSONObject(it).optString("phone") }
        val body = "मुझे सहायता की आवश्यकता है। कृपया मुझसे संपर्क करें। यह संदेश BLACKAI से स्वयं जाँचकर भेजा जा रहा है।"
        AlertDialog.Builder(this).setTitle("SMS तैयार करें")
            .setMessage("संदेश आपके SMS ऐप में खुलेगा। भेजने से पहले प्राप्तकर्ता और संदेश जाँचें। BLACKAI इसे अपने आप नहीं भेजेगा।")
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("SMS ऐप खोलें") { _, _ ->
                logEmergencyAction("संपर्कों के लिए SMS composer खोला")
                val uri = Uri.parse("smsto:" + numbers.joinToString(";"))
                val intent = Intent(Intent.ACTION_SENDTO, uri).putExtra("sms_body", body)
                try { startActivity(intent) }
                catch (_: Exception) { Toast.makeText(this, "SMS ऐप उपलब्ध नहीं है।", Toast.LENGTH_LONG).show() }
            }.show()
    }

    private fun confirmEmergencyAlert() {
        val arr = org.json.JSONArray(emergencyPrefs().getString("contacts", "[]"))
        if (arr.length() == 0) {
            AlertDialog.Builder(this).setTitle("भरोसेमंद संपर्क नहीं मिले")
                .setMessage("Emergency Alert तैयार करने से पहले अपने भरोसेमंद संपर्क जोड़ें।")
                .setPositiveButton("संपर्क जोड़ें") { _, _ -> addTrustedContact() }
                .setNegativeButton("बंद करें", null).show()
            return
        }
        val numbers = (0 until arr.length()).map { arr.getJSONObject(it).optString("phone") }
            .filter { it.isNotBlank() }
        if (numbers.isEmpty()) {
            Toast.makeText(this, "संपर्क नंबर उपलब्ध नहीं हैं।", Toast.LENGTH_LONG).show()
            return
        }
        val time = DateFormat.getDateTimeInstance().format(Date())
        val message = "BLACKAI Emergency Alert: मुझे सहायता की आवश्यकता है। कृपया जल्द मुझसे संपर्क करें। समय: $time। यदि तत्काल खतरा है तो स्थानीय आपातकालीन सेवा 112 से संपर्क करें।"
        AlertDialog.Builder(this)
            .setTitle("🚨 Emergency Alert")
            .setMessage("यह संदेश आपके SMS ऐप में तैयार होगा। प्राप्तकर्ता और संदेश जाँचकर आप स्वयं Send दबाएँगे। BLACKAI अपने आप SMS या कॉल नहीं करेगा। वर्तमान लोकेशन इसमें शामिल नहीं है; उसे अलग से साझा कर सकते हैं।")
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("SMS तैयार करें") { _, _ ->
                logEmergencyAction("Emergency Alert SMS composer खोला")
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(";")))
                    .putExtra("sms_body", message)
                try { startActivity(intent) }
                catch (_: Exception) { Toast.makeText(this, "SMS ऐप उपलब्ध नहीं है।", Toast.LENGTH_LONG).show() }
            }.show()
    }

    private fun showSafetyChecklist() {
        val items = arrayOf(
            "फोन में पर्याप्त बैटरी है",
            "किसी भरोसेमंद व्यक्ति को अपनी योजना बताई है",
            "आवश्यक संपर्क नंबर उपलब्ध हैं",
            "यात्रा/मुलाकात का स्थान और वापसी योजना साझा है",
            "फोन की लोकेशन सेटिंग अपनी इच्छा से जाँची है"
        )
        val checks = items.map { android.widget.CheckBox(this).apply { text = it } }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            checks.forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle("व्यक्तिगत सुरक्षा चेकलिस्ट")
            .setView(panel).setNegativeButton("बंद करें", null)
            .setPositiveButton("पूरा करें") { _, _ ->
                val done = checks.count { it.isChecked }
                logEmergencyAction("सुरक्षा चेकलिस्ट: $done/${items.size} पूरे")
                Toast.makeText(this, "$done में से ${items.size} बिंदु पूरे।", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun showEmergencyHistory() {
        val raw = emergencyPrefs().getString("history", "") ?: ""
        val lines = raw.split("\\n").filter { it.isNotBlank() }.map { row ->
            val p = row.split("|", limit = 2)
            val whenText = p.firstOrNull()?.toLongOrNull()?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "समय अज्ञात"
            "$whenText — ${p.getOrElse(1) { "गतिविधि" }}"
        }
        AlertDialog.Builder(this).setTitle("आपातकालीन गतिविधि इतिहास")
            .setMessage(if (lines.isEmpty()) "अभी कोई गतिविधि दर्ज नहीं है।" else lines.joinToString("\\n"))
            .setPositiveButton("ठीक है", null).show()
    }


    /**
     * Quick, user-controlled entry point for the safety tools already present in BLACKAI.
     * This screen does not send alerts, calls, or location automatically.
     */
    private fun showEmergencyControlCenter() {
        val user = firebaseAuth.currentUser
        val contacts = org.json.JSONArray(emergencyPrefs().getString("contacts", "[]"))
        val sensorPrefs = historyPrefs()
        val motionOn = isServiceRunning(MotionMonitorService::class.java)
        val voiceOn = isServiceRunning(VoiceMonitorService::class.java)
        val summary = "अकाउंट: ${if (user != null) "लॉगिन" else "लॉगिन नहीं"}\n" +
            "Motion Monitoring: ${if (motionOn) "चालू" else "बंद"}\n" +
            "Voice Detection: ${if (voiceOn) "चालू" else "बंद"}\n" +
            "भरोसेमंद संपर्क: ${contacts.length()}/5\n" +
            "Motion संकेत: ${sensorPrefs.getInt("signal_count", 0)}\n" +
            "Voice रिकॉर्ड: ${sensorPrefs.getInt("voice_signal_count", 0)}\n\n" +
            "सभी अलर्ट और शेयरिंग आपके बटन दबाने व पुष्टि करने पर ही होंगे। सेंसर संकेत आपातकाल की पुष्टि नहीं करते।"
        val actions = arrayOf(
            "112 कॉल स्क्रीन खोलें",
            "भरोसेमंद संपर्क देखें",
            "आपातकालीन SMS तैयार करें",
            "सुरक्षा चेकलिस्ट खोलें",
            "Recent Activity देखें"
        )
        AlertDialog.Builder(this)
            .setTitle("BLACKAI — Emergency Control Center")
            .setMessage(summary)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> confirmEmergencyDial()
                    1 -> showTrustedContacts()
                    2 -> prepareEmergencySms()
                    3 -> showSafetyChecklist()
                    4 -> showRecentActivity()
                }
            }
            .setNegativeButton("बंद करें", null)
            .show()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == serviceClass.name
        }
    }

    /**
     * Combines locally stored emergency, motion and voice events into a time-sorted view.
     * No ordinary recognized speech or live audio is added here.
     */
    private fun showRecentActivity() {
        val events = mutableListOf<Pair<Long, String>>()
        fun addRows(raw: String, fallback: String) {
            raw.replace("\\\\n", "\n").split("\n").filter { it.isNotBlank() }.forEach { row ->
                val parts = row.split("|", limit = 2)
                val time = parts.firstOrNull()?.toLongOrNull() ?: return@forEach
                val label = parts.getOrElse(1) { fallback }.take(180)
                events.add(time to label)
            }
        }

        val emergencyRaw = emergencyPrefs().getString("history", "") ?: ""
        addRows(emergencyRaw, "सुरक्षा गतिविधि")

        val prefs = historyPrefs()
        addRows(prefs.getString("signal_history", "") ?: "", "Motion संकेत")
        addRows(prefs.getString("voice_signal_history", "") ?: "", "Voice संकेत")

        val lines = events.sortedByDescending { it.first }.take(30).map { (time, label) ->
            "${DateFormat.getDateTimeInstance().format(Date(time))} — $label"
        }
        AlertDialog.Builder(this)
            .setTitle("BLACKAI — Recent Activity")
            .setMessage(
                if (lines.isEmpty()) "अभी कोई हाल की गतिविधि दर्ज नहीं है।"
                else lines.joinToString("\n\n")
            )
            .setPositiveButton("ठीक है", null)
            .show()
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

    private fun refreshDashboard() {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val running = manager.getRunningServices(Int.MAX_VALUE).map { it.service.className }.toSet()
        val motionOn = MotionMonitorService::class.java.name in running
        val voiceOn = VoiceMonitorService::class.java.name in running
        val prefs = historyPrefs()
        val motionCount = prefs.getInt("signal_count", 0)
        val voiceCount = prefs.getInt("voice_signal_count", 0)
        val motionTime = prefs.getLong("last_signal_time", 0L)
        val voiceTime = prefs.getLong("voice_last_signal_time", 0L)
        fun timeLabel(time: Long): String =
            if (time > 0L) DateFormat.getDateTimeInstance().format(Date(time)) else "अभी कोई रिकॉर्ड नहीं"
        dashboard.text = "Voice Detection: ${if (voiceOn) "चालू" else "बंद"}\n" +
            "Motion Monitoring: ${if (motionOn) "चालू" else "बंद"}\n\n" +
            "Voice History: $voiceCount रिकॉर्ड\nआखिरी Voice रिकॉर्ड: ${timeLabel(voiceTime)}\n\n" +
            "Motion History: $motionCount रिकॉर्ड\nआखिरी Motion संकेत: ${timeLabel(motionTime)}\n\n" +
            "नोट: चालू/बंद स्थिति Android service के आधार पर दिखाई जाती है। ये प्रयोगात्मक संकेत हैं, आपातकाल या अपराध की पुष्टि नहीं।"
    }

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
        val emergency = emergencyPrefs()
        val json = JSONObject().apply {
            put("format", "BLACKAI_SENSOR_HISTORY")
            put("version", 2)
            put("signal_count", prefs.getInt("signal_count", 0))
            put("last_signal", prefs.getString("last_signal", null) ?: JSONObject.NULL)
            put("last_signal_time", prefs.getLong("last_signal_time", 0L))
            put("signal_history", prefs.getString("signal_history", "") ?: "")
            put("voice_signal_count", prefs.getInt("voice_signal_count", 0))
            put("voice_last_signal", prefs.getString("voice_last_signal", null) ?: JSONObject.NULL)
            put("voice_last_signal_time", prefs.getLong("voice_last_signal_time", 0L))
            put("voice_signal_history", prefs.getString("voice_signal_history", "") ?: "")
            put("emergency_history", emergency.getString("history", "") ?: "")
            put("trusted_contacts", emergency.getString("contacts", "[]") ?: "[]")
            put("backup_created_at", System.currentTimeMillis())
        }
        contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(json.toString(2))
        } ?: throw IllegalStateException("फ़ाइल नहीं खुल सकी")
        historyPrefs().edit().putLong("last_backup_at", System.currentTimeMillis()).remove("backup_reminder_snoozed_until").apply()
        Toast.makeText(this, "BLACKAI बैकअप सेव हो गया। इसमें सुरक्षा इतिहास और भरोसेमंद संपर्क भी शामिल हैं। फ़ाइल निजी रखें।", Toast.LENGTH_LONG).show()
    }

    private fun restoreBackup(uri: Uri) {
        val jsonText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: throw IllegalStateException("बैकअप फ़ाइल नहीं पढ़ी जा सकी")
        val json = JSONObject(jsonText)
        require(json.optString("format") == "BLACKAI_SENSOR_HISTORY") { "यह BLACKAI का मान्य बैकअप नहीं है" }
        val backupVersion = json.optInt("version", -1)
        require(backupVersion in 1..2) { "बैकअप वर्जन समर्थित नहीं है" }
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
        if (backupVersion >= 2) {
            val contactsText = json.optString("trusted_contacts", "[]")
            val contacts = org.json.JSONArray(contactsText)
            require(contacts.length() <= 5) { "बैकअप में भरोसेमंद संपर्कों की सीमा से अधिक डेटा है" }
            for (i in 0 until contacts.length()) {
                val item = contacts.getJSONObject(i)
                require(item.optString("name").isNotBlank()) { "संपर्क का नाम सही नहीं है" }
                val digits = item.optString("phone").filter { it.isDigit() }
                require(digits.length in 10..13) { "संपर्क नंबर सही नहीं है" }
            }
            emergencyPrefs().edit()
                .putString("history", json.optString("emergency_history", ""))
                .putString("contacts", contacts.toString())
                .apply()
        }
        refreshSignalLog()
        Toast.makeText(this, if (backupVersion >= 2) "BLACKAI हिस्ट्री और भरोसेमंद संपर्क Restore हो गए। कृपया नंबर जाँच लें।" else "पुरानी BLACKAI हिस्ट्री वापस आ गई।", Toast.LENGTH_LONG).show()
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
                    .setMessage("इससे स्थानीय सेंसर/सुरक्षा इतिहास और (यदि बैकअप v2 है) भरोसेमंद संपर्क चुनी गई फ़ाइल से बदल सकते हैं। Restore के बाद संपर्क नंबर जाँचें।")
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
                    .remove("last_signal_time").remove("signal_history")
                    .remove("voice_signal_count").remove("voice_last_signal")
                    .remove("voice_last_signal_time").remove("voice_signal_history").apply()
                refreshSignalLog()
                status.text = "स्थानीय सेंसर इतिहास मिटा दिया गया। Monitoring की स्थिति नहीं बदली।"
            }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMonitoring()
        else if (requestCode == voicePermissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceMonitoring()
        else if (requestCode == locationPermissionRequest && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) fetchOneTimeLocation()
        else if (requestCode == locationPermissionRequest) Toast.makeText(this, "लोकेशन अनुमति नहीं मिली।", Toast.LENGTH_LONG).show()
        else if (requestCode == 41 || requestCode == voicePermissionRequest) status.text = "ज़रूरी अनुमति नहीं मिली; Monitoring शुरू नहीं हुई।"
    }

    private fun currentVoiceLanguageName(): String =
        historyPrefs().getString("voice_language_name", "हिन्दी") ?: "हिन्दी"

    private fun chooseVoiceLanguage() {
        val names = arrayOf(
            "অসমীয়া (Assamese)", "বাংলা (Bengali)", "बड़ो (Bodo)", "डोगरी (Dogri)",
            "ગુજરાતી (Gujarati)", "हिन्दी (Hindi)", "ಕನ್ನಡ (Kannada)", "कॉशुर/کٲشُر (Kashmiri)",
            "कोंकणी (Konkani)", "मैथिली (Maithili)", "മലയാളം (Malayalam)", "ꯃꯤꯇꯩꯂꯣꯟ (Manipuri)",
            "मराठी (Marathi)", "नेपाली (Nepali)", "ଓଡ଼ିଆ (Odia)", "ਪੰਜਾਬੀ (Punjabi)",
            "संस्कृतम् (Sanskrit)", "ᱥᱟᱱᱛᱟᱲᱤ (Santali)", "سنڌي (Sindhi)", "தமிழ் (Tamil)",
            "తెలుగు (Telugu)", "اردو (Urdu)"
        )
        val tags = arrayOf(
            "as-IN", "bn-IN", "brx-IN", "doi-IN", "gu-IN", "hi-IN", "kn-IN", "ks-IN",
            "kok-IN", "mai-IN", "ml-IN", "mni-IN", "mr-IN", "ne-IN", "or-IN", "pa-IN",
            "sa-IN", "sat-IN", "sd-IN", "ta-IN", "te-IN", "ur-IN"
        )
        val prefs = historyPrefs()
        val selected = tags.indexOf(prefs.getString("voice_language_tag", "hi-IN") ?: "hi-IN").coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Voice पहचान की भाषा चुनें")
            .setSingleChoiceItems(names, selected) { dialog, which ->
                prefs.edit()
                    .putString("voice_language_tag", tags[which])
                    .putString("voice_language_name", names[which])
                    .apply()
                voiceLanguageButton.text = "Voice भाषा चुनें: " + names[which]
                val running = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
                    .getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == VoiceMonitorService::class.java.name }
                dialog.dismiss()
                if (running) {
                    stopService(Intent(this, VoiceMonitorService::class.java))
                    startVoiceMonitoring()
                }
                Toast.makeText(this, "चुनी गई भाषा: " + names[which] + "। Provider समर्थन पर निर्भर करेगा।", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    private fun startVoiceMonitoring() {
        val intent = Intent(this, VoiceMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Voice Detection शुरू करने का अनुरोध भेजा गया।"
        refreshDashboard()
    }

    private fun startMonitoring() {
        val intent = Intent(this, MotionMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Monitoring चालू — स्थायी notification दिखाई देगा।"
        refreshDashboard()
    }
}
