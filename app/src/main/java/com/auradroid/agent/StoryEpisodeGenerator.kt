package com.auradroid.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GeneratedStoryEpisode(
    val title: String,
    val storySummary: String,
    val storyText: String,
    val hindiDialogue: String,
    val cliffhanger: String,
    val nextPartOutline: String
)

/**
 * Generates a reviewable next-episode draft using locally saved SQLite story context.
 * It does not save or publish anything automatically; the user reviews and presses Save.
 */
object StoryEpisodeGenerator {
    suspend fun generate(
        context: Context,
        seriesName: String,
        partNumber: Int,
        seedIdea: String,
        previousContext: String,
        extraTwist: String = ""
    ): GeneratedStoryEpisode = withContext(Dispatchers.IO) {
        require(seriesName.isNotBlank()) { "Enter a story / series name." }
        require(partNumber in 1..10000) { "Part number must be between 1 and 10000." }
        require(seedIdea.isNotBlank()) { "Enter the base story idea or current scene." }

        val prefs = context.getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "").orEmpty().trim()
        require(apiKey.isNotBlank()) { "Gemini API key missing. Add it in the main Settings tab." }
        val configuredModel = prefs.getString("model_name", "gemini-2.5-flash").orEmpty().trim()
        val model = configuredModel.takeIf {
            it.matches(Regex("^[A-Za-z0-9._-]{1,120}$")) && it.startsWith("gemini-")
        } ?: "gemini-2.5-flash"

        val prompt = """
            You are writing Part $partNumber of a continuing animated sci-fi/fantasy story series called "$seriesName".
            Create a fresh coherent Hindi story episode for a short animated video.
            BASE IDEA / SEED:
            ${seedIdea.trim().take(4000)}
            PREVIOUS EPISODE CONTEXT (oldest to newest):
            ${previousContext.trim().take(10000).ifBlank { "No previous part saved; establish the premise and main character." }}
            USER'S NEW TWIST:
            ${extraTwist.trim().take(1000).ifBlank { "No extra twist specified." }}

            Requirements:
            - Maintain established names, personalities, motivations, world rules and unresolved plot threads from prior parts.
            - Never retell an earlier part. Move the plot forward in this part.
            - Output original content, Hindi dialogue in Devanagari, vivid but concise actions and one strong ending hook.
            - Keep this self-contained episode suitable for a short video; avoid unsafe instructions or personal data.
            - Return ONLY JSON with keys: title, storySummary, storyText, hindiDialogue, cliffhanger, nextPartOutline.
            - storySummary <= 800 characters; storyText <= 8000 characters; hindiDialogue <= 4000 characters; other fields <= 800 characters.
            - The dialogue should be speakable naturally, not overloaded. Keep the story and dialogue consistent with each other.
        """.trimIndent()

        val payload = JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.75)
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
                throw IllegalStateException("Gemini story generation failed (HTTP $status): ${message ?: responseText.take(300)}")
            }
            val root = JSONObject(responseText)
            val candidates = root.optJSONArray("candidates")
                ?: throw IllegalStateException("Gemini returned no candidates.")
            require(candidates.length() > 0) { "Gemini returned an empty response." }
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini returned no content.")
            val answer = (0 until parts.length()).mapNotNull { i ->
                parts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
            }.joinToString("\n").trim()
            val json = answer.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val start = json.indexOf('{')
            val end = json.lastIndexOf('}')
            require(start >= 0 && end > start) { "Gemini did not return a valid JSON story draft." }
            val result = JSONObject(json.substring(start, end + 1))
            GeneratedStoryEpisode(
                title = result.optString("title", "Part $partNumber").take(300),
                storySummary = result.optString("storySummary", "").take(800),
                storyText = result.optString("storyText", "").take(8000),
                hindiDialogue = result.optString("hindiDialogue", "").take(4000),
                cliffhanger = result.optString("cliffhanger", "").take(800),
                nextPartOutline = result.optString("nextPartOutline", "").take(800)
            ).also {
                require(it.storyText.isNotBlank()) { "Generated story text was empty. Try again." }
            }
        } finally {
            connection.disconnect()
        }
    }
}
