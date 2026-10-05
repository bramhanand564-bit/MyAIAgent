package com.myaiagent.automation

import android.content.Context

class VisionAgentSettings(context: Context) {
    private val prefs = context.getSharedPreferences("vision_agent", Context.MODE_PRIVATE)

    init {
        // Earlier builds used Flash-Lite as the default. Migrate that legacy
        // default to the user's newly requested Gemini 3.1 Flash primary.
        val storedModel = prefs.getString("model", null)
        if (!prefs.getBoolean("model_user_set", false) &&
            (storedModel == GEMINI_FALLBACK_MODEL || storedModel == LEGACY_REQUESTED_MODEL)
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

    companion object {
        const val PROVIDER_GEMINI = "GEMINI"
        const val PROVIDER_CUSTOM = "CUSTOM"
        const val PROVIDER_LOCAL = "LOCAL_OPENAI"

        // Current default model for the agent. The legacy 3.1 Flash identifier is
        // migrated when it was only an older app default.
        const val REQUESTED_GEMINI_MODEL = "gemini-3.5-flash"
        const val LEGACY_REQUESTED_MODEL = "gemini-3.1-flash"
        const val GEMINI_FALLBACK_MODEL = "gemini-3.1-flash-lite"
        const val DEFAULT_MODEL = REQUESTED_GEMINI_MODEL
        const val DEFAULT_OBSERVATION_SECONDS = 6
    }
}
