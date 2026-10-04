package com.myaiagent.model

data class UploadItem(
    val id: String,
    val uri: String,
    val fileName: String,
    val title: String = "",
    val description: String = "",
    val thumbnailUri: String? = null,
    val visibility: String = "PRIVATE",
    val scheduledAt: Long? = null,
    val status: String = "QUEUED",
    val automationMode: String = "NATIVE_STUDIO",
    val lastRunAt: Long? = null,
    val resultNote: String = ""
)
