package com.myaiapp

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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    // क्लाउड API के लिए OkHttpClient
    private val client = OkHttpClient()
    private val apiKey = "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"
    private val apiUrl = "https://openrouter.ai/api/v1/chat/completions"

    // लोकल AI सर्वर के लिए खास क्लाइंट (ताकि फोन धीमा सोचे तो ऐप क्रैश न हो)
    private val localClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var llamaProcess: Process? = null // बैकग्राउंड सर्वर प्रोसेस

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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
        }

        chatHistory = TextView(this).apply {
            text = "System: Hello Brahamanand! Agent Ready.\n"
            textSize = 15f
            setTextColor(Color.BLACK)
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
            )
            addView(chatHistory)
        }

        downloadButton = Button(this).apply {
            text = "DOWNLOAD LOCAL AI MODEL"
            setBackgroundColor(Color.parseColor("#28A745"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        inputField = EditText(this).apply {
            hint = "Kuch bhi poohein..."
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
                chatHistory.append("\nBrahamanand: $userText\n")
                inputField.text.clear()
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }

                if (modeSwitch.isChecked) {
                    if (modelFile.exists()) {
                        startLocalServerAndChat(modelFile, userText)
                    } else {
                        chatHistory.append("System: Model not found! Please download it first.\n")
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

    // --- असली लोकल AI इंजन फंक्शन ---
    private fun startLocalServerAndChat(modelFile: File, prompt: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Assets से सर्वर फाइल बाहर निकालना
                val serverFile = File(filesDir, "llama-server")
                if (!serverFile.exists()) {
                    withContext(Dispatchers.Main) { chatHistory.append("System: Extracting AI Engine...\n") }
                    try {
                        assets.open("llama-server").use { input ->
                            FileOutputStream(serverFile).use { output -> input.copyTo(output) }
                        }
                        serverFile.setExecutable(true)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { 
                            chatHistory.append("System Error: C++ Engine abhi APK mein pack nahi hua hai. Kripya GitHub Actions build poora hone dein!\n") 
                            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                        return@launch
                    }
                }

                // 2. पहली बार लोकल सर्वर चालू करना
                if (llamaProcess == null) {
                    withContext(Dispatchers.Main) {
                        chatHistory.append("System: 🚀 Starting Local AI Server... (Loading 770MB RAM, please wait 15 seconds)\n")
                        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                    }

                    val processBuilder = ProcessBuilder(
                        serverFile.absolutePath,
                        "-m", modelFile.absolutePath,
                        "--port", "8080",
                        "--host", "127.0.0.1",
                        "-c", "512" // Context size
                    )
                    processBuilder.directory(filesDir)
                    processBuilder.redirectErrorStream(true)
                    llamaProcess = processBuilder.start()

                    delay(12000) // सर्वर को चालू होने और मॉडल लोड करने का समय देना
                }

                // 3. लोकल सर्वर को API की तरह कॉल करना
                withContext(Dispatchers.Main) {
                    chatHistory.append("Agent (Local): Thinking...\n")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
                callLocalAI(prompt)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    chatHistory.append("System Error: ${e.localizedMessage}\n")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    // लोकल सर्वर से बात करने वाला API कॉल
    private fun callLocalAI(prompt: String) {
        try {
            val jsonBody = JSONObject().apply {
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            }

            val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("http://127.0.0.1:8080/v1/chat/completions") // फोन का अपना लोकल एड्रेस
                .post(body)
                .build()

            localClient.newCall(request).execute().use { response ->
                val responseData = response.body?.string()
                if (response.isSuccessful && responseData != null) {
                    val jsonResponse = JSONObject(responseData)
                    val choices = jsonResponse.getJSONArray("choices")
                    val aiReply = choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
                    
                    runOnUiThread {
                        chatHistory.append("Agent (Local): $aiReply\n")
                        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                } else {
                    runOnUiThread {
                        chatHistory.append("System: Engine is still warming up. Send message again in 5 seconds.\n")
                    }
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                chatHistory.append("System: Engine is still warming up. Send message again in 5 seconds.\n")
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    // --- क्लाउड (OpenRouter) कॉल फंक्शन ---
    private fun callAI(prompt: String) {
        Thread {
            try {
                val jsonBody = JSONObject().apply {
                    put("model", "nvidia/nemotron-3-ultra-550b-a55b:free")
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                }

                val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("HTTP-Referer", "https://github.com/bramhanand564-bit")
                    .addHeader("X-Title", "MyAIAgent")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseData = response.body?.string()
                    if (response.isSuccessful && responseData != null) {
                        try {
                            val jsonResponse = JSONObject(responseData)
                            val choices = jsonResponse.getJSONArray("choices")
                            val aiReply = choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
                            runOnUiThread {
                                chatHistory.append("Agent: $aiReply\n")
                                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                            }
                        } catch (e: Exception) {
                            showError("Agent: Parse Error: ${e.localizedMessage}\n")
                        }
                    } else {
                        showError("Agent: Server Error: $responseData\n")
                    }
                }
            } catch (e: Exception) {
                showError("Agent: Error: ${e.localizedMessage}\n")
            }
        }.start()
    }

    private fun showError(msg: String) {
        runOnUiThread {
            chatHistory.append(msg)
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
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
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                    runOnUiThread {
                        chatHistory.append("\nSystem: Model Downloaded Successfully! Saved locally.\n")
                        downloadButton.text = "MODEL ALREADY DOWNLOADED"
                        downloadButton.setBackgroundColor(Color.GRAY)
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        llamaProcess?.destroy() // ऐप बंद होने पर सर्वर भी बंद कर दो
    }
}
