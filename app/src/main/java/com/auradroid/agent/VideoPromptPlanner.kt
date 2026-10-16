package com.auradroid.agent

/** Creates a timing-aware prompt. It cannot force a remote video model to support a duration. */
object VideoPromptPlanner {
    val supportedDurationsSeconds = listOf(8, 10, 12, 15)

    fun buildTimedPrompt(
        topic: String,
        durationSeconds: Int,
        storyContext: String = "",
        hindiDialogue: String = "",
        storyMode: String = "SERIAL",
        characterLock: String = "Cute rounded white companion robot with large solid color-changing eyes"
    ): String {
        require(durationSeconds in 5..60) {
            "Choose a duration between 5 and 60 seconds."
        }
        require(topic.isNotBlank()) { "Enter a video topic or scene." }

        val firstBeatEnd = (durationSeconds * 0.28).toInt().coerceIn(1, durationSeconds - 2)
        val secondBeatEnd = (durationSeconds * 0.70).toInt().coerceIn(firstBeatEnd + 1, durationSeconds - 1)
        val beats = listOf(0 to firstBeatEnd, firstBeatEnd to secondBeatEnd, secondBeatEnd to durationSeconds)
        val modeInstruction = when (storyMode.uppercase()) {
            "SERIAL" -> "Continue previous plot threads and avoid repeating saved scenes/dialogue."
            "SIDE_STORY" -> "Create a distinct side incident in the same visual universe."
            else -> "Create a self-contained standalone scene."
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
            appendLine("STORY MODE: ${storyMode.uppercase()} — $modeInstruction")
            appendLine("MAIN CHARACTER VISUAL LOCK: ${characterLock.trim().take(1200)}")
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
