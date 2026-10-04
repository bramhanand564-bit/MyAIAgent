package com.myaiagent.automation

data class VisionAction(
    val screen: String = "UNKNOWN",
    val action: String = "WAIT",
    val targetText: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val value: String? = null,
    val confidence: Float = 0f,
    val reason: String = ""
)

data class VisionDecision(
    val action: VisionAction,
    val rawResponse: String = ""
)
