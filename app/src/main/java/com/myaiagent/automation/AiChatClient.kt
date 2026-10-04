package com.myaiagent.automation

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiChatClient(private val settings: VisionAgentSettings) {
    fun ask(message: String, context: String): String {
        val system = """
You are NAX Mind, a reliability assistant for a user-authorized Android automation app.
Track state-machine flows, explain exactly where a flow is stuck, suggest a safe repair, and help the user operate the app.
Never bypass Google login, CAPTCHA, security verification, rate limits, device protections, or access controls.
Never claim an upload succeeded unless final verification says so. Prefer evidence from the supplied flow context.
""".trimIndent()

        if (settings.provider == VisionAgentSettings.PROVIDER_GEMINI && settings.apiKey.isBlank()) {
            return localFallback(context, message)
        }
        if ((settings.provider == VisionAgentSettings.PROVIDER_CUSTOM ||
            settings.provider == VisionAgentSettings.PROVIDER_LOCAL) && settings.endpoint.isBlank()) {
            return localFallback(context, message)
        }

        return when (settings.provider) {
            VisionAgentSettings.PROVIDER_CUSTOM,
            VisionAgentSettings.PROVIDER_LOCAL -> askOpenAi(system, message, context)
            else -> askGemini(system, message, context)
        }
    }

    private fun localFallback(context: String, user: String): String {
        val lower = user.lowercase()
        val snapshot = context
        return when {
            lower.contains("where") || lower.contains("stuck") || lower.contains("kaha") || lower.contains("रुका") ->
                "Current flow diagnosis:\n" + snapshot
            lower.contains("fix") || lower.contains("repair") || lower.contains("thik") ->
                "Recommended safe repair:\n" + snapshot
            lower.contains("status") || lower.contains("state") ->
                "Current automation status:\n" + snapshot
            else ->
                "NAX Mind is online locally. I can already read the live state, retries, visible package, last observation, diagnosis and recommended fix. Configure an AI provider in AI Settings for deeper natural-language analysis."
        }
    }

    private fun askGemini(system: String, user: String, context: String): String {
        if (settings.apiKey.isBlank()) {
            return "Gemini API key is not configured. Open AI Settings and add the key."
        }
        val prompt = system + "\n\nCURRENT FLOW CONTEXT:\n" + context + "\n\nUSER:\n" + user
        val body = JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
        GeminiRequestGate.awaitTurn()
        return post(
            "https://generativelanguage.googleapis.com/v1beta/models/" + settings.model + ":generateContent",
            body.toString(),
            mapOf("x-goog-api-key" to settings.apiKey),
            true
        )
    }

    private fun askOpenAi(system: String, user: String, context: String): String {
        if (settings.endpoint.isBlank()) {
            return "OpenAI-compatible endpoint is not configured. Open AI Settings first."
        }
        val body = JSONObject()
            .put("model", settings.model)
            .put("temperature", 0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put(
                    "role", "user"
                ).put(
                    "content",
                    "CURRENT FLOW CONTEXT:\n" + context + "\n\nUSER:\n" + user
                ))
            )
        val headers = mutableMapOf<String, String>()
        if (settings.apiKey.isNotBlank()) headers["Authorization"] = "Bearer " + settings.apiKey
        if (settings.extraHeaders.isNotBlank()) runCatching {
            val j = JSONObject(settings.extraHeaders)
            j.keys().forEach { headers[it] = j.optString(it) }
        }
        return post(settings.endpoint, body.toString(), headers, false)
    }

    private fun post(
        urlValue: String,
        body: String,
        headers: Map<String, String>,
        gemini: Boolean
    ): String {
        val connection = (URL(urlValue).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return try {
            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return "AI request failed (HTTP " + code + "). Check provider, endpoint, model and network."
            }
            if (gemini) {
                JSONObject(response)
                    .optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "The AI provider returned no text."
            } else {
                JSONObject(response)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "The AI provider returned no text."
            }
        } catch (e: Exception) {
            "AI connection failed: " + (e.message ?: "network error")
        } finally {
            connection.disconnect()
        }
    }
}
