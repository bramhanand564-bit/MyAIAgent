package com.myaiagent.automation

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Detects UI interruptions that must be handled by the user, not bypassed by NAX.
 *
 * The detector is intentionally semantic rather than tied to one provider/package.
 * It recognizes common CAPTCHA / human-verification / security-check variants and
 * returns a manual-action signal so the workflow can pause and resume safely.
 */
object UiInterruptionDetector {

    enum class Type {
        NONE,
        CAPTCHA,
        SECURITY_CHECK,
        LOGIN_REQUIRED
    }

    data class Result(
        val type: Type,
        val label: String
    )

    fun detect(root: AccessibilityNodeInfo?): Result {
        if (root == null) return Result(Type.NONE, "")

        val text = collectText(root)
            .joinToString(" ")
            .lowercase(Locale.getDefault())
            .replace("\\s+".toRegex(), " ")
            .trim()

        if (text.isBlank()) return Result(Type.NONE, "")

        val strongCaptcha = listOf(
            "captcha",
            "recaptcha",
            "re-captcha",
            "hcaptcha",
            "turnstile",
            "cloudflare",
            "i'm not a robot",
            "im not a robot",
            "verify you are human",
            "verify that you are human",
            "prove you're human",
            "prove you are human",
            "select all images",
            "select all squares",
            "security challenge"
        )
        if (strongCaptcha.any { text.contains(it) }) {
            return Result(Type.CAPTCHA, "CAPTCHA / human verification")
        }

        val humanWords = listOf("human", "robot", "security", "challenge", "verification")
        val actionWords = listOf("verify", "continue", "confirm", "complete", "check")
        if (humanWords.any { text.contains(it) } &&
            actionWords.any { text.contains(it) }) {
            return Result(Type.SECURITY_CHECK, "Human/security verification")
        }

        val strongLoginSignals = listOf(
            "login required",
            "enter your password",
            "password required",
            "verification code",
            "enter password"
        )
        val signInWithAccountContext =
            (text.contains("sign in") || text.contains("log in")) &&
                listOf("email", "account", "username", "password").any { text.contains(it) }

        if (strongLoginSignals.any { text.contains(it) } || signInWithAccountContext) {
            return Result(Type.LOGIN_REQUIRED, "Login / verification required")
        }

        return Result(Type.NONE, "")
    }

    private fun collectText(root: AccessibilityNodeInfo): List<String> {
        val result = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(result::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(result::add)

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return result
    }
}
