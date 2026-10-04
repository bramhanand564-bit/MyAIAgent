package com.myaiagent.automation

import android.content.Context
import com.myaiagent.model.UploadItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeneratedMetadata(
    val title: String,
    val description: String
)

class AiContentGenerator(private val context: Context) {
    fun generate(item: UploadItem): GeneratedMetadata? {
        val settings = VisionAgentSettings(context)
        if (settings.provider != VisionAgentSettings.PROVIDER_GEMINI || settings.apiKey.isBlank()) return null

        GeminiRequestGate.awaitTurn()

        val prompt = """
You generate YouTube metadata for a user-authorized upload workflow.
File name: ${item.fileName}
Content type: ${item.contentType}
Visibility: ${item.visibility}

Return ONLY JSON:
{"title":"concise natural YouTube title","description":"useful description, no fake claims"}

Rules:
- Do not invent specific facts that are not inferable from the file name.
- Keep the title concise and readable.
- Keep the description useful but compact.
- Do not include markdown fences.
""".trimIndent()

        val body = JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))

        val connection = (URL(
            "https://generativelanguage.googleapis.com/v1beta/models/${settings.model}:generateContent"
        ).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", settings.apiKey)
        }

        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return null

            val text = JSONObject(response)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()

            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null

            val json = JSONObject(text.substring(start, end + 1))
            val title = json.optString("title").trim()
            val description = json.optString("description").trim()
            if (title.isBlank() && description.isBlank()) null
            else GeneratedMetadata(title, description)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
