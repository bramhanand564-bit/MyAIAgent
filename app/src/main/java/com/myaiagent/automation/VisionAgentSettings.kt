package com.myaiagent.automation

import android.content.Context

class VisionAgentSettings(context: Context) {
    private val prefs = context.getSharedPreferences("vision_agent", Context.MODE_PRIVATE)

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
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var extraHeaders: String
        get() = prefs.getString("extra_headers", "") ?: ""
        set(value) = prefs.edit().putString("extra_headers", value).apply()

    companion object {
        const val PROVIDER_GEMINI = "GEMINI"
        const val PROVIDER_CUSTOM = "CUSTOM"
        const val PROVIDER_LOCAL = "LOCAL_OPENAI"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}
