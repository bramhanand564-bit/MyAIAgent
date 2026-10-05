package com.myaiagent.automation

import android.content.Context
import org.json.JSONArray

class TapDiagnosticStore(context: Context) {
    private val prefs = context.getSharedPreferences("tap_diagnostic", Context.MODE_PRIVATE)
    fun start() {
        prefs.edit().putBoolean("active", true).putInt("step", 0)
            .putBoolean("success", false).remove("message").remove("events").apply()
        log("Tap diagnostic started")
    }
    fun isActive() = prefs.getBoolean("active", false)
    fun step() = prefs.getInt("step", 0)
    fun setStep(value: Int) { prefs.edit().putInt("step", value).apply() }
    fun log(message: String) {
        val items = runCatching { JSONArray(prefs.getString("events", "[]") ?: "[]") }.getOrDefault(JSONArray())
        items.put(message)
        prefs.edit().putString("events", items.toString()).apply()
    }
    fun events(): List<String> = runCatching {
        val items = JSONArray(prefs.getString("events", "[]") ?: "[]")
        buildList { for (i in 0 until items.length()) add(items.optString(i)) }
    }.getOrDefault(emptyList())
    fun finish(success: Boolean, message: String) {
        log(message)
        prefs.edit().putBoolean("active", false).putBoolean("success", success).putString("message", message).apply()
    }
    fun success() = prefs.getBoolean("success", false)
    fun message() = prefs.getString("message", "").orEmpty()
}