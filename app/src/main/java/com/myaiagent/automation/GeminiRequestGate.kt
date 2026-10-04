package com.myaiagent.automation

/**
 * Keeps Gemini calls intentionally spaced. This is an application pacing guard,
 * not a quota-bypass mechanism.
 */
object GeminiRequestGate {
    private const val MIN_INTERVAL_MS = 15_000L
    private var lastRequestAt = 0L

    @Synchronized
    fun awaitTurn() {
        val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0L) {
            runCatching { Thread.sleep(wait) }
        }
        lastRequestAt = System.currentTimeMillis()
    }

    @Synchronized
    fun reset() {
        lastRequestAt = 0L
    }
}
