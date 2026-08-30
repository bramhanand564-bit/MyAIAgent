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
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.coroutines.* // नई लाइब्रेरी जो फोन को हैंग होने से बचाएगी

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val apiKey = "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"
    private val apiUrl = "https://openrouter.ai/api/v1/chat/completions"

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
                    // लोकल मोड (Offline)
                    if (modelFile.exists()) {
                        chatHistory.append("System: Accessing Local Model File...\n")
                        runLocalInference(modelFile, userText)
                    } else {
                        chatHistory.append("System: Model not found! Please download it first.\n")
                    }
                } else {
                    // क्लाउड मोड (API)
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

    // नया लोकल इंजन फंक्शन
    private fun runLocalInference(file: File, prompt: String) {
        // Coroutine का इस्तेमाल ताकि फोन हैंग न हो
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // फाइल को पढ़ना और चेक करना
                val inputStream = FileInputStream(file)
                val header = ByteArray(4)
                inputStream.read(header)
                inputStream.close()
                
                val magicString = String(header)
                val fileSizeMB = file.length() / (1024 * 1024)

                withContext(Dispatchers.Main) {
                    if (magicString == "GGUF" || magicString == "FUGG") {
                        chatHistory.append("System: ✅ Valid GGUF Llama Model Verified! (Size: $fileSizeMB MB)\n")
                        chatHistory.append("Agent (Local): Hello! Maine storage se GGUF file read kar li hai. (C++ NDK Engine processing baaki hai).\n")
                    } else {
                        chatHistory.append("System: ❌ Error - Not a valid GGUF file. Header is $magicString\n")
                    }
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    chatHistory.append("System: File Error - ${e.localizedMessage}\n")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun downloadModelFile() {
        val modelUrl = "https://huggingface.co/unsloth/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        Thread {
            try {
                val request = Request.Builder().url(modelUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        showError("\nSystem: Download Failed: ${response.code}\n")
                        return@Thread
                    }

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
                        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                        downloadButton.text = "MODEL ALREADY DOWNLOADED"
                        downloadButton.setBackgroundColor(Color.GRAY)
                    }
                }
            } catch (e: Exception) {
                showError("\nSystem: Download Error: ${e.localizedMessage}\n")
                runOnUiThread { 
                    downloadButton.isEnabled = true 
                    downloadButton.text = "RETRY DOWNLOAD"
                }
            }
        }.start()
    }

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
                            if (jsonResponse.has("choices")) {
                                val choices = jsonResponse.getJSONArray("choices")
                                if (choices.length() > 0) {
                                    val messageObj = choices.getJSONObject(0).getJSONObject("message")
                                    val aiReply = messageObj.getString("content").trim()
                                    runOnUiThread {
                                        chatHistory.append("Agent: $aiReply\n")
                                        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                                    }
                                } else {
                                    showError("Agent: Empty response from AI.\n")
                                }
                            } else {
                                showError("Agent: Server Error: $responseData\n")
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
}
