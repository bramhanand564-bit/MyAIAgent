package com.myaiapp

import com.myaiapp.R // R वाला एरर फिक्स
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var chatBox: TextView
    private lateinit var userInput: EditText
    private lateinit var sendButton: Button
    private lateinit var scrollView: ScrollView

    // आपका लोकल सर्वर
    private val serverUrl = "http://127.0.0.1:20128/v1/chat/completions"
    private val apiKey = "sk-5f238e76072d7926-934b45-f09569f3" 
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatBox = findViewById(R.id.chatBox)
        userInput = findViewById(R.id.userInput)
        sendButton = findViewById(R.id.sendButton)
        scrollView = findViewById(R.id.scrollView)

        checkAccessibilityStatus()

        sendButton.setOnClickListener {
            val text = userInput.text.toString().trim()
            if (text.isNotEmpty()) {
                addMessageToChat("User: $text")
                userInput.setText("")
                callOmniRouteAI(text)
            }
        }
    }

    private fun checkAccessibilityStatus() {
        if (MyAccessibilityService.instance == null) {
            addMessageToChat("System: ⚠️ Accessibility Service OFF है! फोन की Settings में जाकर इसे चालू करें।")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            addMessageToChat("System: 🟢 J.A.R.V.I.S Super-Hands Online & Ready!")
        }
    }

    private fun addMessageToChat(message: String) {
        runOnUiThread {
            chatBox.append("\n$message\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun callOmniRouteAI(prompt: String) {
        addMessageToChat("J.A.R.V.I.S: Processing command...")

        val jsonBody = JSONObject().apply {
            put("model", "auto")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are JARVIS, an autonomous assistant. Confirm actions shortly.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(serverUrl).addHeader("Authorization", "Bearer $apiKey").post(body).build()

        client.newCall(request).enqueue(object : Callback {
            val originalPrompt = prompt.lowercase()

            override fun onFailure(call: Call, e: IOException) {
                addMessageToChat("System: ❌ Connection Error.")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string()
                if (response.isSuccessful && responseData != null) {
                    try {
                        val aiReply = JSONObject(responseData)
                            .getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content")
                        
                        addMessageToChat("J.A.R.V.I.S: $aiReply")
                        executeAutonomousAction(originalPrompt)
                    } catch (e: Exception) {}
                }
            }
        })
    }

    // ⚡ THE 25+ FEATURES GOD-MODE CONTROLLER ⚡
    private fun executeAutonomousAction(command: String) {
        runOnUiThread {
            val service = MyAccessibilityService.instance
            val handler = Handler(Looper.getMainLooper())
            val cmd = command.lowercase()

            when {
                // 🎬 1. YOUTUBE AUTO-UPLOAD SEQUENCE (Requires Accessibility)
                cmd.contains("upload") || cmd.contains("video") -> {
                    if (service == null) { addMessageToChat("System: ❌ Accessibility OFF."); return@runOnUiThread }
                    addMessageToChat("System: 🎬 Initiating YouTube Auto-Upload Sequence...")
                    val intent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    if (intent != null) {
                        startActivity(intent)
                        handler.postDelayed({ service.clickButtonByText("Create") }, 3000)
                        handler.postDelayed({ service.clickButtonByText("Upload a video") }, 5000)
                        handler.postDelayed({ addMessageToChat("J.A.R.V.I.S: Please select video quickly!") }, 7000)
                        handler.postDelayed({ service.autoType("Uploaded by J.A.R.V.I.S 4.0") }, 15000)
                        handler.postDelayed({ service.clickButtonByText("Next") }, 17000)
                        handler.postDelayed({ service.clickButtonByText("Upload video"); service.executeSystemCommand("home") }, 20000)
                    }
                }

                // 🚀 2. YOUTUBE AUTO-SEARCH (Requires Accessibility)
                (cmd.contains("youtube") || cmd.contains("play")) && !cmd.contains("music") -> {
                    if (service == null) { addMessageToChat("System: ❌ Accessibility OFF."); return@runOnUiThread }
                    val query = cmd.replace("youtube", "").replace("play", "").replace("song", "").trim()
                    addMessageToChat("System: 🚀 Automating YouTube search...")
                    startActivity(packageManager.getLaunchIntentForPackage("com.google.android.youtube"))
                    handler.postDelayed({ service.clickButtonByText("Search") }, 2000)
                    handler.postDelayed({ service.autoType(query) }, 3500)
                }

                // ⚡ ACCESSIBILITY SYSTEM CONTROLS (3, 4, 5, 6)
                cmd.contains("home") -> service?.executeSystemCommand("home")
                cmd.contains("back") -> service?.executeSystemCommand("back")
                cmd.contains("recent") -> service?.executeSystemCommand("recent")
                cmd.contains("notification") -> service?.executeSystemCommand("notification")

                // 🔦 7. FLASHLIGHT CONTROL
                cmd.contains("torch") || cmd.contains("flashlight") -> controlFlashlight(!cmd.contains("off"))
                
                // 🌐 8. WEB SEARCH
                cmd.contains("search") || cmd.contains("chrome") -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${cmd.replace("search", "").trim()}")))
                
                // 💬 9. WHATSAPP
                cmd.contains("whatsapp") -> openApp("com.whatsapp")
                
                // 📸 10. INSTAGRAM
                cmd.contains("instagram") -> openApp("com.instagram.android")
                
                // 📷 11. CAMERA (PHOTO)
                cmd.contains("camera") || cmd.contains("photo") -> startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                
                // 🎥 12. CAMERA (VIDEO)
                cmd.contains("record") || cmd.contains("shoot") -> startActivity(Intent(MediaStore.ACTION_VIDEO_CAPTURE))
                
                // 🗺️ 13. MAPS & NAVIGATION
                cmd.contains("map") || cmd.contains("navigate") -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${cmd.replace("navigate to", "").trim()}")))
                
                // 📞 14. PHONE CALL
                cmd.contains("call") || cmd.contains("dial") -> startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
                
                // ✉️ 15. SMS MESSAGE
                cmd.contains("sms") || cmd.contains("message") -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:")))
                
                // ⏰ 16. SET ALARM
                cmd.contains("alarm") -> startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Alarm"))
                
                // 🔋 17. CHECK BATTERY
                cmd.contains("battery") || cmd.contains("charge") -> checkBatteryLevel()
                
                // ⚙️ 18. SYSTEM SETTINGS
                cmd.contains("setting") -> startActivity(Intent(Settings.ACTION_SETTINGS))
                
                // 📶 19. WIFI SETTINGS
                cmd.contains("wifi") -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                
                // 🔵 20. BLUETOOTH SETTINGS
                cmd.contains("bluetooth") -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                
                // 📧 21. EMAIL
                cmd.contains("mail") || cmd.contains("email") -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:")))
                
                // 🧮 22. CALCULATOR
                cmd.contains("calculate") || cmd.contains("calculator") -> openApp("com.google.android.calculator")
                
                // 📅 23. CALENDAR
                cmd.contains("calendar") || cmd.contains("date") -> startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))
                
                // 🛒 24. PLAY STORE
                cmd.contains("store") || cmd.contains("download") -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=")))
                
                // ✈️ 25. AIRPLANE MODE SETTINGS
                cmd.contains("airplane") || cmd.contains("flight") -> startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
                
                // 🎵 26. MUSIC PLAYER
                cmd.contains("music") -> startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC))
                
                // ⏱️ 27. TIMER
                cmd.contains("timer") -> startActivity(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Timer"))
                
                // 🔊 28. VOLUME SETTINGS
                cmd.contains("volume") || cmd.contains("sound") -> startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
                
                // 📍 29. LOCATION SETTINGS
                cmd.contains("location") || cmd.contains("gps") -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                
                // 📘 30. FACEBOOK
                cmd.contains("facebook") -> openApp("com.facebook.katana")

                else -> addMessageToChat("System: Command executed.")
            }
        }
    }

    // --- HELPER FUNCTIONS ---
    private fun controlFlashlight(turnOn: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.setTorchMode(cameraManager.cameraIdList[0], turnOn)
            addMessageToChat(if (turnOn) "System: 🔦 Flashlight ON" else "System: 🔦 Flashlight OFF")
        } catch (e: Exception) { addMessageToChat("System: ❌ Camera access denied.") }
    }

    private fun openApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) startActivity(launchIntent)
        else addMessageToChat("System: ❌ App not installed.")
    }

    private fun checkBatteryLevel() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        addMessageToChat("System: 🔋 Battery is at $level%")
    }
}
