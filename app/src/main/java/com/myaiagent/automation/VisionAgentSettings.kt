package com.myaiagent.automation

import android.content.Context

class VisionAgentSettings(context: Context) {
    private val prefs = context.getSharedPreferences("vision_agent", Context.MODE_PRIVATE)

    init {
        // Earlier builds used Flash-Lite as the default. Migrate that legacy
        // default to the user's newly requested Gemini 3.1 Flash primary.
        if (!prefs.getBoolean("model_user_set", false) &&
            prefs.getString("model", null) == GEMINI_FALLBACK_MODEL
        ) {
            prefs.edit().putString("model", REQUESTED_GEMINI_MODEL).apply()
        }
    }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var provider: String
        get() = prefs.getString("provider", PROVIDER_GEMINI) ?: PROVIDER_GEMINI
        set(value) = prefs.edit().putString("provider", value).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(value) = prefs.edit().putString("endpoint", value.trim().removeSuffix("/")).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit()
            .putString("model", value.trim())
            .putBoolean("model_user_set", true)
            .apply()

    var extraHeaders: String
        get() = prefs.getString("extra_headers", "") ?: ""
        set(value) = prefs.edit().putString("extra_headers", value).apply()

    var observationIntervalSeconds: Int
        get() = prefs.getInt("observation_interval_seconds", DEFAULT_OBSERVATION_SECONDS)
        set(value) = prefs.edit()
            .putInt("observation_interval_seconds", value.coerceIn(2, 60))
            .apply()

    fun geminiModelCandidates(): List<String> {
        val selected = model.ifBlank { DEFAULT_MODEL }
        return if (selected == REQUESTED_GEMINI_MODEL) {
            listOf(selected, GEMINI_FALLBACK_MODEL).distinct()
        } else {
            listOf(selected)
        }
    }

    companion object {
        const val PROVIDER_GEMINI = "GEMINI"
        const val PROVIDER_CUSTOM = "CUSTOM"
        const val PROVIDER_LOCAL = "LOCAL_OPENAI"

        // User-selected primary model. The current public Gemini model catalog may
        // not expose this exact non-live model id; callers should use the candidate
        // list so the stable 3.1 Flash-Lite fallback remains functional.
        const val REQUESTED_GEMINI_MODEL = "gemini-3.1-flash"
        const val GEMINI_FALLBACK_MODEL = "gemini-3.1-flash-lite"
        const val DEFAULT_MODEL = REQUESTED_GEMINI_MODEL
        const val DEFAULT_OBSERVATION_SECONDS = 6
    }
}
