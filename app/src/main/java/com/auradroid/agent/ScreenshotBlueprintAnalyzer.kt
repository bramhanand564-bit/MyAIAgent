package com.auradroid.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Converts an ordered set of user-selected screenshots into a draft blueprint.
 * Images are sent to the configured Gemini model only after explicit user confirmation in UI.
 * This method never executes the generated actions.
 */
object ScreenshotBlueprintAnalyzer {
    suspend fun analyze(context: Context, screenshots: List<Uri>): AutomationBlueprint =
        withContext(Dispatchers.IO) {
            require(screenshots.isNotEmpty()) { "Select at least one screenshot." }
            require(screenshots.size <= 6) { "Select at most 6 ordered screenshots per analysis." }

            val prefs = context.getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
            val apiKey = prefs.getString("api_key", "").orEmpty().trim()
            require(apiKey.isNotBlank()) { "Gemini API key missing. Add it in the main Settings tab." }
            val configuredModel = prefs.getString("model_name", "gemini-2.5-flash").orEmpty().trim()
            val model = configuredModel.takeIf { it.matches(Regex("^[A-Za-z0-9._-]{1,120}$")) && it.startsWith("gemini-") }
                ?: "gemini-2.5-flash"

            val parts = JSONArray()
            parts.put(
                JSONObject().put(
                    "text",
                    """
                    You are analyzing an ORDERED sequence of Android screenshots to propose a user-approved automation blueprint.
                    Return ONLY JSON, no markdown, in this exact shape:
                    {"name":"...","version":1,"description":"...","steps":[{"action":"tap|scroll|wait|back|verify|ask_user","target":"visible label or empty","text":"","x":0.5,"y":0.5,"waitMs":300,"note":"why"}]}
                    Rules:
                    - Infer only visible UI transitions supported by the screenshots; do not invent hidden buttons, success states, credentials, or unseen results.
                    - Use at most 30 steps, in screenshot order. Prefer TAP with visible button/label text as target. When no stable label is visible, provide normalized x and y from 0.0 to 1.0.
                    - Use VERIFY to describe an expected visible result after an action.
                    - Never generate TYPE or OPEN_URL actions from a screenshot. For text entry, authentication, OTP, CAPTCHA, payments, purchases, deletion, identity checks, or ambiguous states, use ASK_USER and explain what the user must review.
                    - Never transcribe possible personal information, emails, phone numbers, passwords or codes from the screenshot.
                    - waitMs must be 0..60000; coordinates must be normalized 0..1.
                    - Name the result "Screenshot workflow draft". The result is a draft that a human will inspect before running.
                    """.trimIndent()
                )
            )

            screenshots.forEachIndexed { index, uri ->
                val encoded = encodeImage(context, uri)
                parts.put(JSONObject().put("text", "WORKFLOW SCREENSHOT ${index + 1} of ${screenshots.size} — preserve this order."))
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", encoded)
                    )
                )
            }

            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                    put("responseMimeType", "application/json")
                })
            }

            val url = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=" +
                    URLEncoder.encode(apiKey, "UTF-8")
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 20000
                readTimeout = 60000
                doOutput = true
            }
            try {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val message = try {
                        JSONObject(responseText).optJSONObject("error")?.optString("message")
                    } catch (_: Exception) { null }
                    throw IllegalStateException("Gemini screenshot analysis failed (HTTP $status): ${message ?: responseText.take(300)}")
                }

                val response = JSONObject(responseText)
                val candidates = response.optJSONArray("candidates")
                    ?: throw IllegalStateException("Gemini returned no candidates.")
                require(candidates.length() > 0) { "Gemini returned an empty response." }
                val responseParts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IllegalStateException("Gemini response had no content parts.")
                val answer = (0 until responseParts.length()).mapNotNull { i ->
                    responseParts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                }.joinToString("\n")
                val json = extractJson(answer)
                val parsed = AutomationBlueprint.fromJson(json)
                require(parsed.steps.size <= 30) { "Generated draft exceeded the 30-step safety limit." }

                val reviewedSteps = parsed.steps.map { step ->
                    if (step.action == BlueprintAction.TYPE || step.action == BlueprintAction.OPEN_URL) {
                        step.copy(
                            action = BlueprintAction.ASK_USER,
                            text = "",
                            note = "Human review required. This step was converted to ASK_USER by safety policy."
                        )
                    } else step
                }
                AutomationBlueprint(
                    name = parsed.name,
                    version = parsed.version,
                    steps = reviewedSteps,
                    description = (parsed.description + " Screenshot-derived draft; review every step before replay.")
                        .take(1000)
                )
            } finally {
                connection.disconnect()
            }
        }

    private fun encodeImage(context: Context, uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalArgumentException("Unable to open a selected screenshot.")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Selected file is not a readable image." }

        var sample = 1
        while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalArgumentException("Unable to decode screenshot.")
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
            val bytes = out.toByteArray()
            require(bytes.size <= 4 * 1024 * 1024) { "A screenshot is too large after compression." }
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    private fun extractJson(text: String): String {
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "Gemini did not return a JSON blueprint." }
        return cleaned.substring(start, end + 1)
    }
}
