package com.example.jarvis // ⚠️ ध्यान दें: अपनी फाइल का पुराना असली पैकेज नाम ही यहाँ रखें!

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
                    put("content", "You are J.A.R.V.I.S. Just confirm the action shortly.")
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

    // ⚡ THE GOD-MODE CONTROLLER (Autonomous Actions) ⚡
    private fun executeAutonomousAction(command: String) {
        runOnUiThread {
            val service = MyAccessibilityService.instance
            if (service == null) {
                addMessageToChat("System: ❌ Accessibility Access Denied.")
                return@runOnUiThread
            }

            val handler = Handler(Looper.getMainLooper())

            when {
                // 1. YouTube Auto-Type (ऐप खुलेगा, इंतज़ार करेगा, और खुद टाइप करेगा)
                command.contains("youtube") || command.contains("play") -> {
                    val query = command.replace("youtube", "").replace("play", "").replace("song", "").trim()
                    addMessageToChat("System: 🚀 Opening YouTube and automating search...")
                    
                    val intent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    if (intent != null) {
                        startActivity(intent)
                        
                        // 2 सेकंड रुककर सर्च बटन (Search) पर क्लिक करेगा
                        handler.postDelayed({ service.clickButtonByText("Search") }, 2000)
                        
                        // 3.5 सेकंड बाद सर्च बॉक्स में गाना टाइप कर देगा
                        handler.postDelayed({ service.autoType(query) }, 3500)
                    } else {
                        addMessageToChat("System: ❌ YouTube App not found.")
                    }
                }

                // 2. Global Actions (पूरे फोन को हैकर्स की तरह कंट्रोल करना)
                command.contains("home") -> {
                    addMessageToChat("System: 🏠 Going to Home Screen")
                    service.executeSystemCommand("home")
                }
                
                command.contains("back") -> {
                    addMessageToChat("System: 🔙 Going Back")
                    service.executeSystemCommand("back")
                }
                
                command.contains("notification") -> {
                    addMessageToChat("System: 🔔 Opening Notifications")
                    service.executeSystemCommand("notification")
                }
                
                command.contains("recent") -> {
                    addMessageToChat("System: 🗂️ Opening Recent Apps")
                    service.executeSystemCommand("recent")
                }

                else -> addMessageToChat("System: Command executed.")
            }
        }
    }
}
