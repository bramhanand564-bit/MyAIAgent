package com.example.jarvis // ⚠️ ध्यान दें: यहाँ अपनी फाइल का पुराना असली पैकेज नाम ही रखें!

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

    // आपका लोकल OmniRoute सर्वर (OpenCode Free के लिए)
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

        // चेक करना कि J.A.R.V.I.S की आँखें (Accessibility) चालू हैं या नहीं
        if (MyAccessibilityService.instance == null) {
            addMessageToChat("System: ⚠️ Accessibility Service बंद है! ऐप खुद काम नहीं कर पाएगा। कृपया फोन की Settings -> Accessibility में जाकर J.A.R.V.I.S को ON करें।")
            // उपयोगकर्ता को सीधा सेटिंग्स में भेजने के लिए:
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            addMessageToChat("System: 🟢 J.A.R.V.I.S Eyes & Hands are ONLINE!")
        }

        sendButton.setOnClickListener {
            val text = userInput.text.toString().trim()
            if (text.isNotEmpty()) {
                addMessageToChat("User: $text")
                userInput.setText("")
                callOmniRouteAI(text)
            }
        }
    }

    private fun addMessageToChat(message: String) {
        runOnUiThread {
            chatBox.append("\n$message\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun callOmniRouteAI(prompt: String) {
        addMessageToChat("Agent: Processing...")

        val jsonBody = JSONObject().apply {
            put("model", "auto")
            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are J.A.R.V.I.S. Just confirm the action shortly.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            put("messages", messagesArray)
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
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                        
                        addMessageToChat("J.A.R.V.I.S: $aiReply")
                        
                        // AI का जवाब आने के बाद असली एक्शन ट्रिगर करना
                        executeAutonomousAction(originalPrompt)
                    } catch (e: Exception) {}
                }
            }
        })
    }

    // ⚡ असली मैजिक: ऐप खोलना और खुद टाइप करना ⚡
    private fun executeAutonomousAction(command: String) {
        runOnUiThread {
            when {
                command.contains("youtube") || command.contains("play") -> {
                    val query = command.replace("youtube", "").replace("play", "").replace("song", "").trim()
                    addMessageToChat("System: 🚀 Opening YouTube and auto-typing '$query'...")
                    
                    // 1. YouTube ऐप खोलना
                    val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                        
                        // 2. दो सेकंड रुककर (ताकि ऐप खुल जाए), सर्विस को टाइप करने का आर्डर देना
                        chatBox.postDelayed({
                            if (MyAccessibilityService.instance != null) {
                                MyAccessibilityService.instance?.autoTypeAndClick(query)
                            } else {
                                addMessageToChat("System: ❌ Accessibility Service is OFF.")
                            }
                        }, 2500) // 2.5 सेकंड का डिले

                    } else {
                        addMessageToChat("System: ❌ YouTube App not found.")
                    }
                }
                
                // आप ऐसे ही WhatsApp, Chrome आदि के लिए लॉजिक जोड़ सकते हैं
            }
        }
    }
}
