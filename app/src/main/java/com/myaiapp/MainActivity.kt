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

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val apiKey = "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"
    private val apiUrl = "https://openrouter.ai/api/v1/chat/completions"

    private lateinit var chatHistory: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        chatHistory = TextView(this).apply {
            text = "System: Hello Brahamanand! AI Agent Ready.\n"
            textSize = 16f
            setTextColor(Color.BLACK)
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
            )
            addView(chatHistory)
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
                chatHistory.append("Agent: Thinking...\n")
                inputField.text.clear()
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }

                callAI(userText)
            }
        }

        inputLayout.addView(inputField)
        inputLayout.addView(runButton)
        mainLayout.addView(scrollView)
        mainLayout.addView(inputLayout)

        setContentView(mainLayout)
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
                        val jsonResponse = JSONObject(responseData)
                        val choices = jsonResponse.getJSONArray("choices")
                        val messageObj = choices.getJSONObject(0).getJSONObject("message")
                        val aiReply = messageObj.getString("content").trim()

                        runOnUiThread {
                            chatHistory.append("Agent: $aiReply\n")
                            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                    } else {
                        runOnUiThread {
                            chatHistory.append("Agent: Server Error: $responseData\n")
                            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    chatHistory.append("Agent: Error: ${e.localizedMessage}\n")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }.start()
    }
}
