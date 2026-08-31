package com.myaiapp

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.widget.*
import android.view.ViewGroup
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val apiKey = "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"
    private val apiUrl = "https://openrouter.ai/api/v1/chat/completions"

    private val localClient = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private var llamaProcess: Process? = null

    private lateinit var chatHistory: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var downloadButton: Button
    private lateinit var modeSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
        modeSwitch = Switch(this).apply {
            text = "Use Local AI (Offline Mode)"
            textSize = 16f
            setTextColor(Color.BLACK)
            isChecked = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
        }
        chatHistory = TextView(this).apply {
            text = "System: AI Action Agent Ready!\n"
            textSize = 15f
            setTextColor(Color.BLACK)
        }
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
            addView(chatHistory)
        }
        downloadButton = Button(this).apply {
            text = "DOWNLOAD LOCAL AI MODEL"
            setBackgroundColor(Color.parseColor("#28A745"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
        }

        val modelFile = File(filesDir, "llama_model.gguf")
        if (modelFile.exists()) {
            downloadButton.text = "MODEL ALREADY DOWNLOADED"
            downloadButton.setBackgroundColor(Color.GRAY)
            downloadButton.isEnabled = false
        }

        downloadButton.setOnClickListener {
            downloadButton.isEnabled = false
            chatHistory.append("\nSystem: Downloading Llama Model... Please wait.\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            downloadModelFile()
        }

        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        inputField = EditText(this).apply {
            hint = "Command do (e.g. Open YouTube)..."
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        val runButton = Button(this).apply {
            text = "SEND"
            setBackgroundColor(Color.parseColor("#007BFF"))
            setTextColor(Color.WHITE)
        }

        runButton.setOnClickListener {
            val userText = inputField.text.toString().trim()
            if (userText.isNotEmpty()) {
                chatHistory.append("\nUser: $userText\n")
                inputField.text.clear()
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }

                if (modeSwitch.isChecked) {
                    if (modelFile.exists()) {
                        startLocalServerAndChat(modelFile, userText)
                    } else {
                        chatHistory.append("System: Model not found!\n")
                    }
                } else {
                    chatHistory.append("Agent: Thinking (Cloud API)...\n")
                    callAI(userText)
                }
            }
        }

        inputLayout.addView(inputField)
        inputLayout.addView(runButton)
        mainLayout.addView(modeSwitch)
        mainLayout.addView(downloadButton)
        mainLayout.addView(scrollView)
        mainLayout.addView(inputLayout)

        setContentView(mainLayout)
    }

    // === ANDROID CONTROLLER: ACTION EXECUTOR ===
    private fun executeAndroidAction(jsonString: String) {
        try {
            // AI के रिप्लाई में से सिर्फ JSON हिस्सा ढूँढना (अगर उसने फालतू टेक्स्ट भी लिख दिया हो)
            val jsonStart = jsonString.indexOf("{")
            val jsonEnd = jsonString.lastIndexOf("}")
            if (jsonStart != -1 && jsonEnd != -1) {
                val pureJson = jsonString.substring(jsonStart, jsonEnd + 1)
                val jsonObject = JSONObject(pureJson)
                
                val action = jsonObject.optString("action", "")
                val target = jsonObject.optString("target", "")
                
                when (action) {
                    "open_app" -> {
                        when (target.lowercase()) {
                            "youtube" -> {
                                chatHistory.append("System: 🔴 Opening YouTube...\n")
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
                                startActivity(intent)
                            }
                            "chrome", "browser" -> {
                                chatHistory.append("System: 🔵 Opening Chrome...\n")
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                                startActivity(intent)
                            }
                            "settings" -> {
                                chatHistory.append("System: ⚙️ Opening Settings...\n")
                                startActivity(Intent(Settings.ACTION_SETTINGS))
                            }
                            else -> chatHistory.append("System: App '$target' not recognized yet.\n")
                        }
                    }
                    "chat" -> {
                        val message = jsonObject.optString("message", "")
                        chatHistory.append("Agent: $message\n")
                    }
                    else -> chatHistory.append("System: Unknown action received.\n")
                }
            } else {
                chatHistory.append("Agent: $jsonString\n") // अगर JSON नहीं है तो नॉर्मल टेक्स्ट दिखाओ
            }
        } catch (e: Exception) {
            chatHistory.append("System Error parsing Action JSON: ${e.message}\n")
        }
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun startLocalServerAndChat(modelFile: File, prompt: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val serverFile = File(applicationInfo.nativeLibraryDir, "libllama-server.so")
                if (!serverFile.exists()) return@launch

                if (llamaProcess == null) {
                    withContext(Dispatchers.Main) { chatHistory.append("System: 🚀 Starting AI Server...\n") }

                    val processBuilder = ProcessBuilder(
                        serverFile.absolutePath, "-m", modelFile.absolutePath, "--port", "8080", "--host", "127.0.0.1", "-c", "1024"
                    )
                    processBuilder.directory(filesDir)
                    processBuilder.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
                    processBuilder.redirectErrorStream(true)
                    llamaProcess = processBuilder.start()
                    delay(8000) 
                }

                withContext(Dispatchers.Main) { chatHistory.append("Agent: Planning Action...\n") }
                callLocalAI(prompt)

            } catch (e: Exception) {}
        }
    }

    private fun callLocalAI(prompt: String) {
        try {
            // === AGENT PLANNER (SYSTEM PROMPT) ===
            val systemPrompt = """
                You are an Android Controller AI. You MUST respond ONLY in JSON format. Do not add any conversational text.
                If the user asks to open an app (like youtube, chrome, or settings), use this format:
                {"action": "open_app", "target": "youtube"}
                If the user just wants to chat or ask a question, use this format:
                {"action": "chat", "message": "your helpful reply"}
            """.trimIndent()

            val messagesArray = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", prompt))
            }

            val jsonBody = JSONObject().apply {
                put("messages", messagesArray)
                put("temperature", 0.1) // कम टेम्परेचर ताकि JSON गलत न बने
            }

            val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url("http://127.0.0.1:8080/v1/chat/completions").post(body).build()

            localClient.newCall(request).execute().use { response ->
                val responseData = response.body?.string()
                if (response.isSuccessful && responseData != null) {
                    val aiReply = JSONObject(responseData).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                    
                    runOnUiThread {
                        // यहाँ हम JSON को पार्स करेंगे और असली एक्शन (Intent) चलाएंगे!
                        executeAndroidAction(aiReply)
                    }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { chatHistory.append("Connection Error: Wait 5s and try again.\n") }
        }
    }

    private fun callAI(prompt: String) {
        // क्लाउड API के लिए भी यही काम (टेस्टिंग के लिए)
        Thread {
            try {
                val systemPrompt = "You are an Android Controller. Reply ONLY in JSON. Format: {\"action\": \"open_app\", \"target\": \"youtube\"} or {\"action\": \"chat\", \"message\": \"reply\"}"
                val messagesArray = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", prompt))
                }
                val jsonBody = JSONObject().apply { put("model", "nvidia/nemotron-3-ultra-550b-a55b:free"); put("messages", messagesArray) }
                val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(apiUrl).addHeader("Authorization", "Bearer $apiKey").post(body).build()
                client.newCall(request).execute().use { response ->
                    val responseData = response.body?.string()
                    if (response.isSuccessful && responseData != null) {
                        try {
                            val aiReply = JSONObject(responseData).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                            runOnUiThread { executeAndroidAction(aiReply) }
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun downloadModelFile() {
        val modelUrl = "https://huggingface.co/unsloth/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        Thread {
            try {
                val request = Request.Builder().url(modelUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) { return@Thread }
                    val inputStream: InputStream = response.body!!.byteStream()
                    val file = File(filesDir, "llama_model.gguf")
                    val outputStream = FileOutputStream(file)
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) { outputStream.write(buffer, 0, bytesRead) }
                    outputStream.flush(); outputStream.close(); inputStream.close()
                    runOnUiThread {
                        chatHistory.append("\nSystem: Model Downloaded Successfully! Saved locally.\n")
                        downloadButton.text = "MODEL ALREADY DOWNLOADED"; downloadButton.setBackgroundColor(Color.GRAY)
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        llamaProcess?.destroy()
    }
}
