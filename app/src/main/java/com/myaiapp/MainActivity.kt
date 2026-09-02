package com.myaiapp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
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

    private val client = OkHttpClient.Builder().connectTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    private val localClient = OkHttpClient.Builder().connectTimeout(300, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).writeTimeout(300, TimeUnit.SECONDS).build()
    private var llamaProcess: Process? = null

    private lateinit var chatHistory: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var downloadButton: Button
    
    // SharedPreferences to save API Keys permanently
    private lateinit var sharedPref: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPref = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)

        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 30, 30, 30); setBackgroundColor(Color.parseColor("#F5F5F5")) }
        
        // --- API CONFIGURATION UI ---
        val apiConfigLayout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
        }
        
        val titleApi = TextView(this).apply { text = "☁️ CLOUD API SETTINGS"; textSize = 14f; setTextColor(Color.DKGRAY); setTypeface(null, android.graphics.Typeface.BOLD) }
        val urlInput = EditText(this).apply { hint = "API URL (e.g., Groq/OpenAI)"; setText(sharedPref.getString("API_URL", "")); textSize = 14f }
        val keyInput = EditText(this).apply { hint = "API Key"; setText(sharedPref.getString("API_KEY", "")); textSize = 14f }
        val saveApiBtn = Button(this).apply { 
            text = "SAVE CLOUD SETTINGS"; setBackgroundColor(Color.parseColor("#6C757D")); setTextColor(Color.WHITE); textSize = 12f 
            setOnClickListener {
                sharedPref.edit().putString("API_URL", urlInput.text.toString().trim()).putString("API_KEY", keyInput.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "Cloud Settings Saved!", Toast.LENGTH_SHORT).show()
            }
        }
        apiConfigLayout.addView(titleApi); apiConfigLayout.addView(urlInput); apiConfigLayout.addView(keyInput); apiConfigLayout.addView(saveApiBtn)
        // ----------------------------

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

        chatHistory = TextView(this).apply { text = "System: Independent J.A.R.V.I.S Ready!\n"; textSize = 15f; setTextColor(Color.BLACK) }
        scrollView = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f); addView(chatHistory) }
        
        val modelFile = File(filesDir, "llama_model.gguf")
        if (modelFile.exists()) {
            downloadButton.text = "MODEL ALREADY DOWNLOADED"; downloadButton.setBackgroundColor(Color.GRAY); downloadButton.isEnabled = false
        }

        downloadButton.setOnClickListener {
            downloadButton.isEnabled = false; chatHistory.append("\nSystem: Downloading Llama 3.2 (1B) Model...\n"); scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            downloadModelFile()
        }

        val inputLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        inputField = EditText(this).apply { hint = "e.g. Chrome kholo"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f) }
        val runButton = Button(this).apply { text = "SEND"; setBackgroundColor(Color.parseColor("#007BFF")); setTextColor(Color.WHITE) }

        runButton.setOnClickListener {
            val userText = inputField.text.toString().trim()
            if (userText.isNotEmpty()) {
                chatHistory.append("\nUser: $userText\n"); inputField.text.clear(); scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                if (modeSwitch.isChecked) {
                    if (modelFile.exists()) { startLocalServerAndChat(modelFile, userText) } else { chatHistory.append("System: Local Model not found!\n") }
                } else { 
                    chatHistory.append("Agent: Thinking (Cloud API)...\n"); callAI(userText) 
                }
            }
        }

        inputLayout.addView(inputField); inputLayout.addView(runButton)
        
        // Adding all views to Main Layout
        mainLayout.addView(apiConfigLayout); mainLayout.addView(btnAccess); mainLayout.addView(modeSwitch); mainLayout.addView(downloadButton); mainLayout.addView(scrollView); mainLayout.addView(inputLayout)
        setContentView(mainLayout)
    }

    private fun executeAndroidAction(jsonString: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val arrayStart = jsonString.indexOf("[")
                val arrayEnd = jsonString.lastIndexOf("]")
                
                val jsonArray = if (arrayStart != -1 && arrayEnd != -1) {
                    try { JSONArray(jsonString.substring(arrayStart, arrayEnd + 1)) } catch (e: Exception) { null }
                } else null

                if (jsonArray != null && jsonArray.length() > 0) {
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
                                        if (intent != null) { 
                                            chatHistory.append("System: 🟢 Opening $appName...\n"); startActivity(intent); launched = true; break 
                                        }
                                    }
                                }
                                if (!launched) chatHistory.append("System: 🔴 App '$target' not found.\n")
                                delay(3000) 
                            }
                            "wait" -> {
                                val duration = jsonObject.optLong("duration", 2000)
                                delay(duration)
                            }
                            "toggle_flashlight" -> {
                                val state = jsonObject.optString("state", "on").lowercase()
                                try {
                                    val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                                    val cameraId = cameraManager.cameraIdList[0]
                                    if (state == "off") {
                                        cameraManager.setTorchMode(cameraId, false)
                                        chatHistory.append("System: 🔦 Flashlight OFF.\n")
                                    } else {
                                        cameraManager.setTorchMode(cameraId, true)
                                        chatHistory.append("System: 🔦 Flashlight ON.\n")
                                    }
                                } catch (e: Exception) { chatHistory.append("System: 🔦 Flashlight Error.\n") }
                            }
                            "chat" -> chatHistory.append("Agent: ${jsonObject.optString("message", "")}\n")
                        }
                    }
                } else {
                    chatHistory.append("Agent: $jsonString\n")
                }
            } catch (e: Exception) { chatHistory.append("Agent: $jsonString\n") }
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startLocalServerAndChat(modelFile: File, prompt: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val serverFile = File(applicationInfo.nativeLibraryDir, "libllama-server.so")
                if (!serverFile.exists()) return@launch
                if (llamaProcess == null) {
                    withContext(Dispatchers.Main) { chatHistory.append("System: 🚀 Starting Llama 1B Engine...\n") }
                    val processBuilder = ProcessBuilder(serverFile.absolutePath, "-m", modelFile.absolutePath, "--port", "8080", "--host", "127.0.0.1", "-c", "2048")
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
                    delay(5000) 
                }
                withContext(Dispatchers.Main) { chatHistory.append("Agent: Processing (Offline)...\n") }
                callLocalAI(prompt)
            } catch (e: Exception) {}
        }
    }

    private fun callLocalAI(prompt: String) {
        try {
            // 👇 STRICT SYSTEM PROMPT FOR LLAMA 1B
            val systemPrompt = """
                You are a strict JSON Android Assistant. 
                RULES:
                1. You must ONLY output a valid JSON array.
                2. If asked to turn OFF flashlight/light, output {"action": "toggle_flashlight", "state": "off"}.
                3. If asked to turn ON flashlight/light, output {"action": "toggle_flashlight", "state": "on"}.
                4. If asked a question or normal chat (e.g. "hi", "how are you"), output {"action": "chat", "message": "YOUR REPLY"}.

                EXAMPLES:
                User: Chrome kholo
                [{"action": "open_app", "target": "chrome"}]
                User: turn off light
                [{"action": "toggle_flashlight", "state": "off"}]
                User: hi tum kaun ho
                [{"action": "chat", "message": "Main J.A.R.V.I.S hoon, aapki madad ke liye taiyar."}]
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
                } else {
                    runOnUiThread { chatHistory.append("System ❌ Local API Error.\n") }
                }
            }
        } catch (e: Exception) { runOnUiThread { chatHistory.append("System ❌ Server Loading. Wait 5s.\n") } }
    }

    private fun callAI(prompt: String) {
        Thread {
            try {
                // Read API credentials directly from SharedPreferences
                val savedUrl = sharedPref.getString("API_URL", "") ?: ""
                val savedKey = sharedPref.getString("API_KEY", "") ?: ""

                if (savedUrl.isEmpty() || savedKey.isEmpty()) {
                    runOnUiThread { chatHistory.append("\nSystem ❌ Please enter API URL and Key at the top first.\n") }
                    return@Thread
                }

                val systemPrompt = "You are a JSON-only Android Agent. Reply ONLY with a JSON ARRAY of actions. Ex: [{\"action\": \"open_app\", \"target\": \"chrome\"}]"
                val messagesArray = JSONArray().apply { put(JSONObject().put("role", "system").put("content", systemPrompt)); put(JSONObject().put("role", "user").put("content", prompt)) }
                
                val jsonBody = JSONObject().apply { 
                    put("model", "llama3-8b-8192") // Optional: specific Groq model format, API dependent
                    put("messages", messagesArray) 
                }
                val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                
                val request = Request.Builder()
                    .url(savedUrl)
                    .addHeader("Authorization", "Bearer $savedKey")
                    .post(body)
                    .build()
                    
                client.newCall(request).execute().use { response ->
                    val responseData = response.body?.string()
                    if (response.isSuccessful && responseData != null) {
                        try {
                            val aiReply = JSONObject(responseData).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                            runOnUiThread { executeAndroidAction(aiReply) }
                        } catch (e: Exception) {
                            runOnUiThread { chatHistory.append("\nSystem ❌ JSON Parse Error: ${e.message}\n") }
                        }
                    } else {
                        runOnUiThread { chatHistory.append("\nSystem ❌ Cloud API Error: Code ${response.code}\n") }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { chatHistory.append("\nSystem ❌ Connection Error. Check Network or API link.\n") }
            }
            runOnUiThread { scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) } }
        }.start()
    }

    private fun downloadModelFile() {
        // Llama 3.2 (1B) Model
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
                    runOnUiThread { chatHistory.append("\nSystem: Llama 1B Downloaded! 🦙\n"); downloadButton.text = "MODEL ALREADY DOWNLOADED"; downloadButton.setBackgroundColor(Color.GRAY) }
                }
            } catch (e: Exception) {}
        }.start()
    }

    override fun onDestroy() { super.onDestroy(); llamaProcess?.destroy() }
}
