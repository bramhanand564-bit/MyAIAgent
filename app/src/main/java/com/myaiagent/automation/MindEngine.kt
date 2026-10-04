package com.myaiagent.automation

import android.content.Context
import android.content.Intent

object MindEngine {
    fun onStart(context: Context, itemId: String, fileName: String, state: AutomationState) {
        MindStore(context).start(itemId, fileName, state)
    }

    fun onState(context: Context, state: AutomationState) {
        MindStore(context).setState(state)
        diagnoseState(context, state, 0)
    }

    fun onScreen(context: Context, packageName: String, observation: String = "") {
        val store = MindStore(context)
        store.observe(packageName, observation)
        val snapshot = store.snapshot()
        diagnoseState(
            context,
            runCatching { AutomationState.valueOf(snapshot.state) }.getOrDefault(AutomationState.IDLE),
            snapshot.retryCount
        )
    }

    fun onRetry(context: Context, attempt: Int) {
        val store = MindStore(context)
        store.retry(attempt)
        val snapshot = store.snapshot()
        diagnoseState(
            context,
            runCatching { AutomationState.valueOf(snapshot.state) }.getOrDefault(AutomationState.IDLE),
            attempt
        )
    }

    fun onError(context: Context, diagnosis: String, fix: String) {
        MindStore(context).diagnose(diagnosis, fix, "ERROR")
    }

    fun onSuccess(context: Context, note: String) {
        MindStore(context).diagnose(
            "Flow completed and passed final verification.",
            note,
            "SUCCESS"
        )
    }

    fun safeRecover(context: Context): String {
        val store = MindStore(context)
        val snapshot = store.snapshot()
        if (!snapshot.active) return "No active automation flow needs recovery."

        store.requestRecovery()
        val state = runCatching {
            AutomationState.valueOf(snapshot.state)
        }.getOrDefault(AutomationState.IDLE)

        return when (state) {
            AutomationState.WAITING_FOR_APP,
            AutomationState.FIND_CREATE,
            AutomationState.FIND_UPLOAD -> {
                val intent = context.packageManager
                    .getLaunchIntentForPackage("com.google.android.apps.youtube.creator")
                    ?: return "YouTube Studio is not installed. Install/update it, then retry."
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure {
                        return "Studio could not be opened: " + (it.message ?: "unknown error")
                    }
                "Safe recovery requested: YouTube Studio reopened and the agent will retry."
            }
            AutomationState.WAITING_USER ->
                "User action is still required. Security/login/CAPTCHA screens are never bypassed."
            else ->
                "Safe recovery requested: the active state will be retried without skipping verification."
        }
    }

    fun diagnosisForChat(context: Context): String {
        val s = MindStore(context).snapshot()
        val age = if (s.stateSince > 0) System.currentTimeMillis() - s.stateSince else 0L
        return buildString {
            appendLine("Flow active: " + s.active)
            appendLine("Video: " + s.fileName.ifBlank { "-" })
            appendLine("State: " + s.state)
            appendLine("State age: " + (age / 1000) + "s")
            appendLine("Retries: " + s.retryCount)
            appendLine("Visible package: " + s.packageName.ifBlank { "-" })
            appendLine("Last observation: " + s.lastObservation.ifBlank { "-" })
            appendLine("Diagnosis: " + s.diagnosis)
            appendLine("Recommended fix: " + s.fix)
        }
    }

    fun stateAgeMs(context: Context): Long {
        val since = MindStore(context).snapshot().stateSince
        return if (since > 0) System.currentTimeMillis() - since else 0L
    }

    private fun diagnoseState(context: Context, state: AutomationState, retries: Int) {
        val store = MindStore(context)
        val age = stateAgeMs(context)

        if (retries >= 5) {
            store.diagnose(
                "The current UI step is not matching after repeated attempts.",
                "Check the visible YouTube Studio screen. Use AI Vision or press Fix now to safely reopen/retry the current flow.",
                "ERROR"
            )
            return
        }

        when (state) {
            AutomationState.WAITING_FOR_APP -> store.diagnose(
                "Agent is waiting for the native YouTube Studio surface.",
                "Keep Studio installed and Accessibility enabled. The agent will reopen Studio when safe.",
                "INFO"
            )
            AutomationState.FIND_CREATE -> store.diagnose(
                "Agent is locating the Create action.",
                "If Create is missing, update YouTube Studio and keep its main screen visible.",
                "INFO"
            )
            AutomationState.FIND_UPLOAD -> store.diagnose(
                "Agent is locating the Upload action.",
                "Open the native Create menu. The agent accepts common Studio upload labels.",
                "INFO"
            )
            AutomationState.WAITING_FOR_PICKER -> store.diagnose(
                "Agent is waiting for Android's file picker and the selected video.",
                "Keep the target video accessible. If the picker changed, AI Vision can identify the visible file.",
                "INFO"
            )
            AutomationState.FILL_DETAILS -> store.diagnose(
                "Agent is filling upload metadata.",
                "Title is derived from the filename when no custom title exists; description is optional.",
                "INFO"
            )
            AutomationState.SET_VISIBILITY -> store.diagnose(
                "Agent is applying the requested privacy setting.",
                "Use Public, Unlisted or Private in Studio. The agent verifies before publishing.",
                "INFO"
            )
            AutomationState.PUBLISH -> store.diagnose(
                "Agent is waiting for the final publish/upload action.",
                "Do not leave the Studio flow. The agent only advances when a publish/save/upload control is visible.",
                "INFO"
            )
            AutomationState.MONITOR_UPLOAD -> store.diagnose(
                if (age > 90_000) "Upload monitor has seen a long quiet period."
                else "Agent is monitoring the real upload state.",
                if (age > 90_000) "Keep Studio in foreground. Check network and upload progress; the agent will continue polling."
                else "No action is needed while Sending/Uploading/Preparing is visible.",
                if (age > 90_000) "WARNING" else "INFO"
            )
            AutomationState.VERIFY -> store.diagnose(
                "Transfer is complete and final verification is still required.",
                "Wait for Published/Uploaded confirmation or visible YouTube content. Processing alone is not treated as success.",
                "INFO"
            )
            AutomationState.WAITING_USER -> store.diagnose(
                "Studio is blocked by a user-controlled security/login/verification screen.",
                "Complete the required Google/Studio action yourself. The agent will resume afterward.",
                "WARNING"
            )
            AutomationState.COMPLETE -> store.diagnose(
                "The flow completed normally.",
                "No fix required.",
                "SUCCESS"
            )
            AutomationState.ERROR -> Unit
            AutomationState.IDLE -> store.diagnose(
                "No automation flow is active.",
                "Start a test or scheduled upload to monitor it.",
                "INFO"
            )
        }
    }
}
