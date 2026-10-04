package com.myaiagent.workflow

import android.content.Context

data class WorkflowConfig(
    val enabled: Boolean = false,
    val folderUri: String = "",
    val times: List<String> = listOf("07:00"),
    val dailyLimit: Int = 1,
    val visibility: String = "PRIVATE",
    val automationMode: String = "EMBEDDED_WEB"
)

class WorkflowStore(context: Context) {
    private val prefs = context.getSharedPreferences("workflow", Context.MODE_PRIVATE)

    fun load(): WorkflowConfig = WorkflowConfig(
        enabled = prefs.getBoolean("enabled", false),
        folderUri = prefs.getString("folder_uri", "") ?: "",
        times = (prefs.getString("times", "07:00") ?: "07:00").split(",").filter { it.isNotBlank() },
        dailyLimit = prefs.getInt("daily_limit", 1).coerceIn(1, 3),
        visibility = prefs.getString("visibility", "PRIVATE") ?: "PRIVATE",
        automationMode = prefs.getString("mode", "EMBEDDED_WEB") ?: "EMBEDDED_WEB"
    )

    fun save(config: WorkflowConfig) {
        prefs.edit()
            .putBoolean("enabled", config.enabled)
            .putString("folder_uri", config.folderUri)
            .putString("times", config.times.take(config.dailyLimit).joinToString(","))
            .putInt("daily_limit", config.dailyLimit.coerceIn(1, 3))
            .putString("visibility", config.visibility)
            .putString("mode", config.automationMode)
            .apply()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
    }
}