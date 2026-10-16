package com.auradroid.agent

enum class CorrectionAction {
    RETRY_AFTER_WAIT,
    REOBSERVE_AND_RETRY,
    ASK_USER,
    STOP
}

data class CorrectionAdvice(
    val action: CorrectionAction,
    val message: String
)

/**
 * Bounded recovery only. Authentication, permissions, payments, destructive actions and
 * human verification always require the user; the agent must not try to bypass them.
 */
object SelfCorrectionPolicy {
    private val humanRequiredSignals = listOf(
        "captcha", "two-factor", "2fa", "otp", "password", "sign in", "login required",
        "permission denied", "accessibility disabled", "payment", "purchase", "checkout",
        "delete permanently", "identity verification", "human verification", "consent required"
    )
    private val reobserveSignals = listOf(
        "element not found", "button not found", "target missing", "unexpected page",
        "wrong page", "selector", "stale node", "expected element"
    )
    private val transientSignals = listOf(
        "timeout", "timed out", "loading", "network", "temporarily unavailable",
        "connection reset", "busy", "not ready"
    )

    fun advise(error: String, attempt: Int, maxRetries: Int = 2): CorrectionAdvice {
        val message = error.trim().lowercase()
        if (humanRequiredSignals.any { message.contains(it) }) {
            return CorrectionAdvice(
                CorrectionAction.ASK_USER,
                "Human action required. The agent will stop and ask you; it will not bypass permissions, sign-in, verification or payment."
            )
        }
        if (attempt > maxRetries) {
            return CorrectionAdvice(CorrectionAction.STOP, "Retry limit reached. Stop and show the error for manual review.")
        }
        if (reobserveSignals.any { message.contains(it) }) {
            return CorrectionAdvice(CorrectionAction.REOBSERVE_AND_RETRY, "Re-read the current UI and retry once using the fresh state.")
        }
        if (transientSignals.any { message.contains(it) }) {
            return CorrectionAdvice(CorrectionAction.RETRY_AFTER_WAIT, "Wait briefly, check the current state, and retry within the limit.")
        }
        return CorrectionAdvice(CorrectionAction.STOP, "Failure type is not safely recoverable automatically; stop and ask the user.")
    }

    fun runSelfCheck(): Boolean {
        return advise("button not found", 1).action == CorrectionAction.REOBSERVE_AND_RETRY &&
            advise("network timeout", 1).action == CorrectionAction.RETRY_AFTER_WAIT &&
            advise("login required", 1).action == CorrectionAction.ASK_USER &&
            advise("unknown failure", 1).action == CorrectionAction.STOP &&
            advise("network timeout", 3).action == CorrectionAction.STOP
    }
}
