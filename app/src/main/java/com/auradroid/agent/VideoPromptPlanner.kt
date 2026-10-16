package com.auradroid.agent

/** Creates a timing-aware prompt. It cannot force a remote video model to support a duration. */
object VideoPromptPlanner {
    val supportedDurationsSeconds = listOf(8, 10, 12, 15)

    fun buildTimedPrompt(
        topic: String,
        durationSeconds: Int,
        storyContext: String = "",
        hindiDialogue: String = ""
    ): String {
        require(durationSeconds in supportedDurationsSeconds) {
            "Choose one of: ${supportedDurationsSeconds.joinToString(", ")} seconds."
        }
        require(topic.isNotBlank()) { "Enter a video topic or scene." }

        val beats = when (durationSeconds) {
            8 -> listOf(0 to 2, 2 to 5, 5 to 8)
            10 -> listOf(0 to 3, 3 to 7, 7 to 10)
            12 -> listOf(0 to 3, 3 to 8, 8 to 12)
            else -> listOf(0 to 4, 4 to 10, 10 to 15)
        }
        val timeline = beats.joinToString("\n") { (start, end) ->
            "${start}–${end}s: one clear visual beat; maintain character, costume, lighting and camera continuity."
        }
        val dialogueWords = hindiDialogue.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val dialogueNote = when {
            hindiDialogue.isBlank() -> "No spoken dialogue requested; prioritize clean visual storytelling."
            dialogueWords > durationSeconds * 2 -> "Dialogue may be too dense for ${durationSeconds}s; shorten naturally and preserve pauses."
            else -> "Deliver the supplied Hindi dialogue clearly within the duration, with natural pauses."
        }
        return buildString {
            appendLine("Create a coherent cinematic video with target duration ${durationSeconds} seconds.")
            appendLine("TOPIC / SCENE: ${topic.trim()}")
            if (storyContext.isNotBlank()) {
                appendLine("STORY CONTINUITY — treat this as the next episode; do not repeat prior events:")
                appendLine(storyContext.trim().take(5000))
            }
            appendLine("TIMED SHOT PLAN:")
            appendLine(timeline)
            if (hindiDialogue.isNotBlank()) appendLine("HINDI DIALOGUE: \"${hindiDialogue.trim()}\"")
            appendLine(dialogueNote)
            appendLine("Keep one consistent protagonist and location, stable visual anatomy, smooth transitions, intelligible audio, and a complete ending.")
            appendLine("If the video generator does not offer ${durationSeconds}s, do not pretend the duration is exact; use a supported duration or ask the user to choose.")
        }
    }
}
