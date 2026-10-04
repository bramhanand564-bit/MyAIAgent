package com.myaiagent.automation

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class OpenAiCompatibleVisionAgent(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val extraHeaders: String = ""
) {
    fun analyze(bitmap: Bitmap, currentState: AutomationState, itemTitle: String, visibility: String): VisionDecision? {
        if (endpoint.isBlank() || model.isBlank()) return null
        val base64 = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 65, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        val prompt = """
You are the visual fallback planner for an Android YouTube upload automation agent.
Analyze only the visible screen. Never bypass login, CAPTCHA, verification, security checks,
rate limits or access controls.
Current state: \${currentState.name}
Desired title: \$itemTitle
Desired visibility: \$visibility
Return ONLY JSON:
{"screen":"UPLOAD|PICKER|DETAILS|VISIBILITY|PUBLISH|PROCESSING|SECURITY|UNKNOWN",
"action":"CLICK|SET_TEXT|SELECT_FILE|WAIT|NEEDS_USER",
"targetText":"exact visible text or null","x":0,"y":0,"value":"text or null",
"confidence":0.0,"reason":"short reason"}
If security/login/CAPTCHA/verification is visible, use NEEDS_USER.
If confidence < 0.80, use WAIT. Never invent a target.
""".trimIndent()

        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put(
                "image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64")
            ))
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", userContent)
            ))

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            parseHeaders(this)
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) return null
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val text = JSONObject(response).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            parseDecision(text, response)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseHeaders(connection: HttpURLConnection) {
        if (extraHeaders.isBlank()) return
        runCatching {
            val headers = JSONObject(extraHeaders)
            headers.keys().forEach { key ->
                connection.setRequestProperty(key, headers.optString(key))
            }
        }
    }

    private fun parseDecision(text: String, raw: String): VisionDecision? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val json = JSONObject(text.substring(start, end + 1))
            VisionDecision(
                action = VisionAction(
                    screen = json.optString("screen", "UNKNOWN"),
                    action = json.optString("action", "WAIT"),
                    targetText = json.optString("targetText").takeIf { it.isNotBlank() },
                    x = if (json.has("x")) json.optDouble("x").toFloat() else null,
                    y = if (json.has("y")) json.optDouble("y").toFloat() else null,
                    value = json.optString("value").takeIf { it.isNotBlank() },
                    confidence = json.optDouble("confidence", 0.0).toFloat(),
                    reason = json.optString("reason", "")
                ),
                rawResponse = raw
            )
        }.getOrNull()
    }
}
