package com.myaiagent.automation

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class GeminiVisionAgent(
    private val apiKey: String,
    private val model: String
) {
    fun analyze(
        bitmap: Bitmap,
        currentState: AutomationState,
        itemTitle: String,
        visibility: String
    ): VisionDecision? {
        if (apiKey.isBlank()) return null

        val image = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 65, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        val prompt = """
You are the visual fallback planner for an Android YouTube upload automation agent.
Analyze only the visible app screen. Do not bypass login, CAPTCHA, verification, security checks,
rate limits, or any other access control.

Current automation state: ${currentState.name}
Desired title: $itemTitle
Desired visibility: $visibility

Return ONLY one compact JSON object:
{
  "screen":"UPLOAD|PICKER|DETAILS|VISIBILITY|PUBLISH|PROCESSING|SECURITY|UNKNOWN",
  "action":"CLICK|SET_TEXT|SELECT_FILE|WAIT|NEEDS_USER",
  "targetText":"exact visible button/field text or null",
  "x":123.0,
  "y":456.0,
  "value":"text to enter or null",
  "confidence":0.0,
  "reason":"short reason"
}

Rules:
- Use x/y only when the target is visibly identifiable by position.
- For SET_TEXT, targetText identifies the visible field and value is the desired text.
- For SELECT_FILE, targetText should identify the visible filename when possible.
- If a security/login/CAPTCHA/verification screen is visible, action must be NEEDS_USER.
- Never invent a button that is not visible.
- If confidence is below 0.80, use WAIT.
""".trimIndent()

        GeminiRequestGate.awaitTurn()

        val body = JSONObject()
            .put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(
                        JSONObject()
                            .put("inline_data", JSONObject()
                                .put("mime_type", "image/jpeg")
                                .put("data", image)
                            )
                    )
                )
            ))

        val connection = (URL(
            "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent"
        ).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) return null

            val root = JSONObject(response)
            val text = root
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()

            parseDecision(text, response)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseDecision(text: String, raw: String): VisionDecision? {
        val cleaned = text.trim()
        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return null

        return runCatching {
            val json = JSONObject(cleaned.substring(jsonStart, jsonEnd + 1))
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
