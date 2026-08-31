package com.myaiapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
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

    private val localClient = OkHttpClient.Builder().connectTimeout(300, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).writeTimeout(300, TimeUnit.SECONDS).build()
    private var llamaProcess: Process? = null

    private lateinit var chatHistory: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var downloadButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 30, 30, 30); setBackgroundColor(Color.parseColor("#F5F5F5")) }
        
        val modeSwitch = Switch(this).apply {
            text = "Use Local AI (Offline Mode)"; textSize = 16f; setTextColor(Color.BLACK); isChecked = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
        }
        
        val btnAccess = Button(this).apply {
            text = "1. TURN ON AI ACCESSIBILITY"
            setBackgroundColor(Color.parseColor("#FF9800")); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        downloadButton = Button(this).apply {
            text = "2. DOWNLOAD LOCAL AI MODEL"
            setBackgroundColor(Color.parseColor("#28A745")); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
        }

        chatHistory = TextView(this).apply { text = "System: AI Super Agent + Accessibility Ready!\n"; textSize = 15f; setTextColor(Color.BLACK) }
        scrollView = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f); addView(chatHistory) }
        
        val modelFile = File(filesDir, "llama_model.gguf")
        if (modelFile.exists()) {
            downloadButton.text = "MODEL ALREADY DOWNLOADED"; downloadButton.setBackgroundColor(Color.GRAY); downloadButton.isEnabled = false
        }

        downloadButton.setOnClickListener {
            downloadButton.isEnabled = false
            chatHistory.append("\nSystem: Downloading Llama Model... Please wait.\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            downloadModelFile()
        }

        val inputLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        inputField = EditText(this).apply { hint = "Command (e.g. Go Back)..."; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f) }
        val runButton = Button(this).apply { text = "SEND"; setBackgroundColor(Color.parseColor("#007BFF")); setTextColor(Color.WHITE) }

        runButton.setOnClickListener {
            val userText = inputField.text.toString().trim()
            if (userText.isNotEmpty()) {
                chatHistory.append("\nUser: $userText\n"); inputField.text.clear(); scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                if (modeSwitch.isChecked) {
                    if (modelFile.exists()) { startLocalServerAndChat(modelFile, userText) } else { chatHistory.append("System: Model not found!\n") }
                } else {
                    chatHistory.append("Agent: Thinking (Cloud API)...\n"); callAI(userText)
                }
            }
        }

        inputLayout.addView(inputField); inputLayout.addView(runButton)
        mainLayout.addView(btnAccess); mainLayout.addView(modeSwitch); mainLayout.addView(downloadButton); mainLayout.addView(scrollView); mainLayout.addView(inputLayout)
        setContentView(mainLayout)
    }

    private fun executeAndroidAction(jsonString: String) {
        try {
            val jsonStart = jsonString.indexOf("{"); val jsonEnd = jsonString.lastIndexOf("}")
            if (jsonStart != -1 && jsonEnd != -1) {
                val jsonObject = JSONObject(jsonString.substring(jsonStart, jsonEnd + 1))
                val action = jsonObject.optString("action", "")
                
                when (action) {
                    "open_app" -> {
                        val target = jsonObject.optString("target", "").lowercase()
                        val pm = packageManager
                        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        var launched = false
                        for (packageInfo in packages) {
                            val appName = pm.getApplicationLabel(packageInfo).toString().lowercase()
                            val pkgName = packageInfo.packageName.lowercase()
                            // FIX: अब यह नाम और पैकेज दोनों में ढूंढेगा (YouTube Bug Fix)
                            if (appName.contains(target) || pkgName.contains(target)) {
                                val intent = pm.getLaunchIntentForPackage(packageInfo.packageName)
                                if (intent != null) { chatHistory.append("System: 🟢 Opening $appName...\n"); startActivity(intent); launched = true; break }
                            }
                        }
                        if (!launched) chatHistory.append("System: 🔴 App '$target' not found.\n")
                    }
                    "toggle_flashlight" -> {
                        val state = jsonObject.optString("state", "on")
                        try {
                            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                            cameraManager.setTorchMode(cameraManager.cameraIdList[0], state == "on")
                            chatHistory.append("System: 🔦 Flashlight turned $state.\n")
                        } catch (e: Exception) { chatHistory.append("System: Error with flashlight.\n") }
                    }
                    "click" -> {
                        val text = jsonObject.optString("text", "")
                        chatHistory.append("System: 👆 Clicking on '$text'...\n")
                        val service = MyAccessibilityService.instance
                        if (service != null) {
                            val success = service.clickButtonByText(text)
                            if (!success) chatHistory.append("System: 🔴 Couldn't find '$text' on screen.\n")
                        } else { chatHistory.append("System: ⚠️ Accessibility OFF! Tap the Orange button.\n") }
                    }
                    "type" -> { // NEW ACTION: TYPING
                        val text = jsonObject.optString("text", "")
                        chatHistory.append("System: ⌨️ Typing '$text'...\n")
                        val service = MyAccessibilityService.instance
                        if (service != null) {
                            val success = service.typeText(text)
                            if (!success) chatHistory.append("System: 🔴 Couldn't find a text box on screen.\n")
                        } else { chatHistory.append("System: ⚠️ Accessibility OFF! Tap the Orange button.\n") }
                    }
                    "system_action" -> {
                        val target = jsonObject.optString("target", "")
                        chatHistory.append("System: 📱 Performing '$target'...\n")
                        val service = MyAccessibilityService.instance
                        if (service != null) {
                            when (target.lowercase()) {
                                "home" -> service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                                "back" -> service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                            }
                        } else { chatHistory.append("System: ⚠️ Accessibility OFF! Tap the Orange button.\n") }
                    }
                    "chat" -> chatHistory.append("Agent: ${jsonObject.optString("message", "")}\n")
                    else -> chatHistory.append("System: Unknown action.\n")
                }
            } else { chatHistory.append("Agent: $jsonString\n") }
        } catch (e: Exception) { chatHistory.append("System Error: ${e.message}\n") }
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun startLocalServerAndChat(modelFile: File, prompt: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val serverFile = File(applicationInfo.nativeLibraryDir, "libllama-server.so")
                if (!serverFile.exists()) return@launch
                if (llamaProcess == null) {
                    withContext(Dispatchers.Main) { chatHistory.append("System: 🚀 Starting AI Server...\n") }
                    val processBuilder = ProcessBuilder(serverFile.absolutePath, "-m", modelFile.absolutePath, "--port", "8080", "--host", "127.0.0.1", "-c", "1024")
                    processBuilder.directory(filesDir); processBuilder.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
                    processBuilder.redirectErrorStream(true); llamaProcess = processBuilder.start()
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(llamaProcess!!.inputStream))
                            var line: String?; while (reader.readLine().also { line = it } != null) {
                                if (line!!.contains("listening", true)) runOnUiThread { chatHistory.append("[Terminal]: Server Ready!\n") }
                            }
                        } catch (e: Exception) {}
                    }.start()
                    delay(8000) 
                }
                withContext(Dispatchers.Main) { chatHistory.append("Agent: Processing Command...\n") }
                callLocalAI(prompt)
            } catch (e: Exception) {}
        }
    }

    private fun callLocalAI(prompt: String) {
        try {
            // FIX: स्मार्ट सिस्टम प्रॉम्प्ट - अब यह हाय/हेलो पर सही रिप्लाई करेगा और टाइपिंग कर सकेगा!
            val systemPrompt = """
                You are a smart Android Assistant. Respond ONLY in valid JSON format.
                - To open an app: {"action": "open_app", "target": "youtube"}
                - To turn flashlight on/off: {"action": "toggle_flashlight", "state": "on"}
                - To click text: {"action": "click", "text": "Submit"}
                - To type text: {"action": "type", "text": "2555"}
                - To go home/back: {"action": "system_action", "target": "home"}
                - For greetings (hi, hello) or questions (tum kya kar sakte ho): {"action": "chat", "message": "Main aapke phone ki flashlight on/off kar sakta hu, apps open kar sakta hu, aur screen par type/click kar sakta hu!"}
            """.trimIndent()
            
            val messagesArray = JSONArray().apply { put(JSONObject().put("role", "system").put("content", systemPrompt)); put(JSONObject().put("role", "user").put("content", prompt)) }
            val jsonBody = JSONObject().apply { put("messages", messagesArray); put("temperature", 0.1) }
            val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url("http://127.0.0.1:8080/v1/chat/completions").post(body).build()

            localClient.newCall(request).execute().use { response ->
                val responseData = response.body?.string()
                if (response.isSuccessful && responseData != null) {
                    val aiReply = JSONObject(responseData).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                    runOnUiThread { executeAndroidAction(aiReply) }
                }
            }
        } catch (e: Exception) { runOnUiThread { chatHistory.append("Connection Error: Wait 5s.\n") } }
    }

    private fun callAI(prompt: String) {
        Thread {
            try {
                val systemPrompt = "You are an Android Controller. Reply ONLY in JSON. Format: {\"action\": \"open_app\", \"target\": \"<app>\"}, {\"action\": \"type\", \"text\": \"<text>\"}, {\"action\": \"chat\", \"message\": \"<reply>\"}"
                val messagesArray = JSONArray().apply { put(JSONObject().put("role", "system").put("content", systemPrompt)); put(JSONObject().put("role", "user").put("content", prompt)) }
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
                    if (!response.isSuccessful) return@Thread
                    val inputStream = response.body!!.byteStream(); val file = File(filesDir, "llama_model.gguf"); val outputStream = FileOutputStream(file)
                    val buffer = ByteArray(8192); var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) outputStream.write(buffer, 0, bytesRead)
                    outputStream.flush(); outputStream.close(); inputStream.close()
                    runOnUiThread { chatHistory.append("\nSystem: Model Downloaded!\n"); downloadButton.text = "MODEL ALREADY DOWNLOADED"; downloadButton.setBackgroundColor(Color.GRAY) }
                }
            } catch (e: Exception) {}
        }.start()
    }

    override fun onDestroy() { super.onDestroy(); llamaProcess?.destroy() }
}
