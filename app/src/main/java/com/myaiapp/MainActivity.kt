package com.myaiapp // ⚠️ ध्यान दें: अगर आपका पैकेज नाम अलग है, तो यहाँ अपना असली पैकेज नाम ही रहने दें

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

    // ⚡ THE GOD-MODE CONTROLLER (Unlimited Sequences) ⚡
    private fun executeAutonomousAction(command: String) {
        runOnUiThread {
            val service = MyAccessibilityService.instance
            if (service == null) {
                addMessageToChat("System: ❌ Accessibility Access Denied. Turn it ON in settings.")
                return@runOnUiThread
            }

            val handler = Handler(Looper.getMainLooper())

            when {
                // 🔥 THE YOUTUBE AUTO-UPLOAD SEQUENCE 🔥
                command.contains("upload") || command.contains("video") -> {
                    addMessageToChat("System: 🎬 Initiating YouTube Auto-Upload Sequence...")
                    
                    val intent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    if (intent != null) {
                        startActivity(intent)
                        
                        // Step 1: 3 सेकंड बाद '+' (Create) बटन पर क्लिक करेगा
                        handler.postDelayed({ 
                            service.clickButtonByText("Create") 
                            addMessageToChat("System: 👆 Clicked '+' button")
                        }, 3000)
                        
                        // Step 2: 5 सेकंड बाद 'Upload a video' पर क्लिक करेगा
                        handler.postDelayed({ 
                            service.clickButtonByText("Upload a video") 
                            addMessageToChat("System: 👆 Clicked 'Upload a video'")
                        }, 5000)
                        
                        // गैलरी से वीडियो आपको खुद सेलेक्ट करनी होगी, J.A.R.V.I.S 7 सेकंड इंतज़ार करेगा 
                        handler.postDelayed({ 
                            addMessageToChat("J.A.R.V.I.S: Please select the video from gallery quickly. I will do the rest!") 
                        }, 7000)
                        
                        // Step 3: 15 सेकंड बाद खुद Title टाइप करेगा
                        handler.postDelayed({ 
                            service.autoType("Uploaded by J.A.R.V.I.S 4.0 - Auto Test") 
                            addMessageToChat("System: 📝 Auto-typed Title.")
                        }, 15000)
                        
                        // Step 4: 17 सेकंड बाद 'Next' बटन दबाएगा
                        handler.postDelayed({ 
                            service.clickButtonByText("Next") 
                        }, 17000)
                        
                        // Step 5: 20 सेकंड बाद फाइनल 'Upload video' बटन दबाएगा
                        handler.postDelayed({ 
                            service.clickButtonByText("Upload video") 
                            addMessageToChat("System: ✅ Video Upload Started in Background!")
                            
                            // काम खत्म होने के बाद वापस होम स्क्रीन पर आ जाएगा
                            handler.postDelayed({ service.executeSystemCommand("home") }, 2000)
                        }, 20000)
                        
                    } else {
                        addMessageToChat("System: ❌ YouTube App not found.")
                    }
                }

                // बेसिक यूट्यूब सर्च 
                command.contains("youtube") || command.contains("play") -> {
                    val query = command.replace("youtube", "").replace("play", "").replace("song", "").trim()
                    addMessageToChat("System: 🚀 Opening YouTube and automating search...")
                    val intent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    if (intent != null) {
                        startActivity(intent)
                        handler.postDelayed({ service.clickButtonByText("Search") }, 2000)
                        handler.postDelayed({ service.autoType(query) }, 3500)
                    }
                }

                // ग्लोबल कमांड्स (Back, Home, Notification)
                command.contains("home") -> service.executeSystemCommand("home")
                command.contains("back") -> service.executeSystemCommand("back")
                command.contains("notification") -> service.executeSystemCommand("notification")
            }
        }
    }
}
