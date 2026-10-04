package com.myaiagent.workflow

import android.content.Context

data class WorkflowConfig(
    val enabled: Boolean = false,
    val folderUri: String = "",
    val times: List<String> = listOf("07:00"),
    val dailyLimit: Int = 1,
    val visibility: String = "PRIVATE",
    val automationMode: String = "NATIVE_STUDIO",
    val contentType: String = "VIDEO"
)

class WorkflowStore(context: Context) {
    private val prefs = context.getSharedPreferences("workflow", Context.MODE_PRIVATE)

    fun load(): WorkflowConfig = WorkflowConfig(
        enabled = prefs.getBoolean("enabled", false),
        folderUri = prefs.getString("folder_uri", "") ?: "",
        times = (prefs.getString("times", "07:00") ?: "07:00").split(",").filter { it.isNotBlank() },
        dailyLimit = prefs.getInt("daily_limit", 1).coerceIn(1, 3),
        visibility = prefs.getString("visibility", "PRIVATE") ?: "PRIVATE",
        automationMode = prefs.getString("mode", "NATIVE_STUDIO") ?: "NATIVE_STUDIO",
        contentType = prefs.getString("content_type", "VIDEO") ?: "VIDEO"
    )

    fun save(config: WorkflowConfig) {
        prefs.edit()
            .putBoolean("enabled", config.enabled)
            .putString("folder_uri", config.folderUri)
            .putString("times", config.times.take(config.dailyLimit).joinToString(","))
            .putInt("daily_limit", config.dailyLimit.coerceIn(1, 3))
            .putString("visibility", config.visibility)
            .putString("mode", config.automationMode)
            .putString("content_type", config.contentType)
            .apply()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
    }
}