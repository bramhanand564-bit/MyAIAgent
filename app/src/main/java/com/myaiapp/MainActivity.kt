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
    // 👇 सिर्फ यह लिंक बदला है 👇
    private val apiKey = "jarvis-private-server"
    private val apiUrl = "https://sift-tightly-plunging.ngrok-free.dev/v1/chat/completions"

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

        chatHistory = TextView(this).apply { text = "System: Multi-Step AI Agent Ready!\n"; textSize = 15f; setTextColor(Color.BLACK) }
        scrollView = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f); addView(chatHistory) }
        
        val modelFile = File(filesDir, "llama_model.gguf")
        if (modelFile.exists()) {
            downloadButton.text = "MODEL ALREADY DOWNLOADED"; downloadButton.setBackgroundColor(Color.GRAY); downloadButton.isEnabled = false
        }

        downloadButton.setOnClickListener {
            downloadButton.isEnabled = false; chatHistory.append("\nSystem: Downloading Llama Model...\n"); scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            downloadModelFile()
        }

        val inputLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        inputField = EditText(this).apply { hint = "e.g. Chrome kholo aur cats search karo"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f) }
        val runButton = Button(this).apply { text = "SEND"; setBackgroundColor(Color.parseColor("#007BFF")); setTextColor(Color.WHITE) }

        runButton.setOnClickListener {
            val userText = inputField.text.toString().trim()
            if (userText.isNotEmpty()) {
                chatHistory.append("\nUser: $userText\n"); inputField.text.clear(); scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                if (modeSwitch.isChecked) {
                    if (modelFile.exists()) { startLocalServerAndChat(modelFile, userText) } else { chatHistory.append("System: Model not found!\n") }
                } else { chatHistory.append("Agent: Thinking (Cloud API)...\n"); callAI(userText) }
            }
        }

        inputLayout.addView(inputField); inputLayout.addView(runButton)
        mainLayout.addView(btnAccess); mainLayout.addView(modeSwitch); mainLayout.addView(downloadButton); mainLayout.addView(scrollView); mainLayout.addView(inputLayout)
        setContentView(mainLayout)
    }

    private fun executeAndroidAction(jsonString: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val arrayStart = jsonString.indexOf("[")
                val arrayEnd = jsonString.lastIndexOf("]")
                
                val jsonArray = if (arrayStart != -1 && arrayEnd != -1) {
                    JSONArray(jsonString.substring(arrayStart, arrayEnd + 1))
                } else {
                    val objStart = jsonString.indexOf("{")
                    val objEnd = jsonString.lastIndexOf("}")
                    if (objStart != -1 && objEnd != -1) {
                        JSONArray().put(JSONObject(jsonString.substring(objStart, objEnd + 1)))
                    } else {
                        chatHistory.append("Agent: $jsonString\n")
                        return@launch
                    }
                }

                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
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
                                if (appName.contains(target) || pkgName.contains(target)) {
                                    val intent = pm.getLaunchIntentForPackage(packageInfo.packageName)
                                    if (intent != null) { chatHistory.append("System: 🟢 Step ${i+1}: Opening $appName...\n"); startActivity(intent); launched = true; break }
                                }
                            }
                            if (!launched) chatHistory.append("System: 🔴 App '$target' not found.\n")
                            delay(3000) 
                        }
                        "wait" -> {
                            val duration = jsonObject.optLong("duration", 2000)
                            chatHistory.append("System: ⏳ Step ${i+1}: Waiting for ${duration}ms...\n")
                            delay(duration)
                        }
                        "click" -> {
                            val text = jsonObject.optString("text", "")
                            chatHistory.append("System: 👆 Step ${i+1}: Clicking '$text'...\n")
                            MyAccessibilityService.instance?.clickButtonByText(text)
                            delay(1000)
                        }
                        "type" -> {
                            val text = jsonObject.optString("text", "")
                            chatHistory.append("System: ⌨️ Step ${i+1}: Typing '$text'...\n")
                            MyAccessibilityService.instance?.typeText(text)
                            delay(1000)
                        }
                        "press_enter" -> {
                            chatHistory.append("System: 🎯 Step ${i+1}: Pressing Enter/Search...\n")
                            MyAccessibilityService.instance?.pressEnter()
                            delay(1000)
                        }
                        "toggle_flashlight" -> {
                            val state = jsonObject.optString("state", "on")
                            try {
                                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                                cameraManager.setTorchMode(cameraManager.cameraIdList[0], state == "on")
                                chatHistory.append("System: 🔦 Flashlight $state.\n")
                            } catch (e: Exception) {}
                        }
                        "system_action" -> {
                            val target = jsonObject.optString("target", "")
                            chatHistory.append("System: 📱 Step ${i+1}: $target...\n")
                            when (target.lowercase()) {
                                "home" -> MyAccessibilityService.instance?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                                "back" -> MyAccessibilityService.instance?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                            }
                            delay(1000)
                        }
                        "chat" -> chatHistory.append("Agent: ${jsonObject.optString("message", "")}\n")
                    }
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            } catch (e: Exception) { chatHistory.append("System Error: ${e.message}\n") }
        }
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
                withContext(Dispatchers.Main) { chatHistory.append("Agent: Planning Multi-Step Action...\n") }
                callLocalAI(prompt)
            } catch (e: Exception) {}
        }
    }

    private fun callLocalAI(prompt: String) {
        try {
            val systemPrompt = """
                You are an advanced Android Agent capable of Multi-Step Planning.
                You MUST respond ONLY with a JSON ARRAY of actions.
                Example if user says "Chrome kholo aur cats search karo":
                [
                  {"action": "open_app", "target": "chrome"},
                  {"action": "wait", "duration": 2000},
                  {"action": "type", "text": "cats"},
                  {"action": "press_enter"}
                ]
                Example if user says "Flashlight on karo":
                [ {"action": "toggle_flashlight", "state": "on"} ]
                Example if user just chats:
                [ {"action": "chat", "message": "Hello!"} ]
                
                Available actions: open_app, toggle_flashlight, click, type, press_enter, wait, system_action, chat.
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
                val systemPrompt = "You are a Multi-Step Android Agent. Reply ONLY with a JSON ARRAY of actions. Example: [{\"action\": \"open_app\", \"target\": \"chrome\"}, {\"action\": \"type\", \"text\": \"cats\"}, {\"action\": \"press_enter\"}]"
                val messagesArray = JSONArray().apply { put(JSONObject().put("role", "system").put("content", systemPrompt)); put(JSONObject().put("role", "user").put("content", prompt)) }
                
                // 👇 यहाँ मॉडल का नाम 'glm-4' कर दिया है 👇
                val jsonBody = JSONObject().apply { put("model", "glm-4"); put("messages", messagesArray) }
                
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
