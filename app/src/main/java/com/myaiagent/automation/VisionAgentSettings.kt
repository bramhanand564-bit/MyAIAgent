package com.myaiagent.automation

import android.content.Context

class VisionAgentSettings(context: Context) {
    private val prefs = context.getSharedPreferences("vision_agent", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}
