package com.myaiagent.automation

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.myaiagent.model.UploadItem
import com.myaiagent.queue.UploadQueueStore

class NaxAccessibilityService : AccessibilityService() {
    private lateinit var queueStore: UploadQueueStore
    private lateinit var sessionStore: AutomationSessionStore
    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null

    private val maxAttempts = 3
    private val sessionTimeoutMs = 10 * 60 * 1000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        queueStore = UploadQueueStore(this)
        sessionStore = AutomationSessionStore(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::sessionStore.isInitialized) return

        val itemId = sessionStore.itemId() ?: return
        val item = queueStore.load().firstOrNull { it.id == itemId } ?: run {
            finishSession(null, "Queue item not found")
            return
        }

        if (System.currentTimeMillis() - sessionStore.startedAt() > sessionTimeoutMs) {
            finishSession(item, "Automation timed out")
            return
        }

        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName != "com.google.android.youtube" &&
            packageName != "com.myaiagent"
        ) return

        driveState(item, root)
    }

    override fun onInterrupt() {
        scheduleRetry()
    }

    private fun driveState(item: UploadItem, root: AccessibilityNodeInfo) {
        when (sessionStore.state()) {
            AutomationState.WAITING_FOR_APP -> {
                if (isEmbedded(item) || isYouTube(root)) {
                    sessionStore.setState(AutomationState.FIND_CREATE)
                    driveState(item, root)
                }
            }

            AutomationState.FIND_CREATE -> {
                if (clickByLabels(root, listOf("Create", "Create a video", "create"))) {
                    sessionStore.setState(AutomationState.FIND_UPLOAD)
                    scheduleRetry()
                } else {
                    scheduleRetry()
                }
            }

            AutomationState.FIND_UPLOAD -> {
                if (clickByLabels(root, listOf("Upload a video", "Upload video", "Upload"))) {
                    sessionStore.setState(AutomationState.WAITING_FOR_PICKER)
                    scheduleRetry()
                } else {
                    scheduleRetry()
                }
            }

            AutomationState.WAITING_FOR_PICKER -> {
                // A system picker may expose a selected file by its display name.
                if (clickByLabels(root, listOf(item.fileName, "Open", "Select", "Done"))) {
                    sessionStore.setState(AutomationState.FILL_DETAILS)
                    scheduleRetry()
                } else {
                    scheduleRetry()
                }
            }

            AutomationState.FILL_DETAILS -> {
                var changed = false
                changed = setTextByLabels(
                    root,
                    listOf("Title", "Add a title"),
                    item.title.ifBlank { item.fileName.substringBeforeLast('.') }
                ) || changed
                changed = setTextByLabels(
                    root,
                    listOf("Description", "Add a description"),
                    item.description
                ) || changed

                if (item.visibility != "PRIVATE") {
                    clickByLabels(root, listOf("Visibility", "Who can see this video"))
                    scheduleRetry()
                }

                if (changed || clickByLabels(root, visibilityLabels(item.visibility))) {
                    sessionStore.setState(AutomationState.PUBLISH)
                    scheduleRetry()
                } else {
                    scheduleRetry()
                }
            }

            AutomationState.PUBLISH -> {
                if (clickByLabels(root, listOf("Publish", "Save", "Upload"))) {
                    sessionStore.setState(AutomationState.VERIFY)
                    scheduleRetry()
                } else {
                    scheduleRetry()
                }
            }

            AutomationState.VERIFY -> {
                if (containsAny(root, listOf("Video published", "Published", "Upload complete", "Processing"))) {
                    finishSession(item, "Upload submitted")
                } else {
                    scheduleRetry()
                }
            }

            AutomationState.RETRY -> {
                sessionStore.setState(previousState())
                driveState(item, root)
            }

            else -> Unit
        }
    }

    private fun isYouTube(root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString() == "com.google.android.youtube"

    private fun isEmbedded(item: UploadItem): Boolean =
        item.automationMode == "EMBEDDED_WEB"

    private fun clickByLabels(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val node = findNode(root, labels) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }

    private fun setTextByLabels(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        value: String
    ): Boolean {
        if (value.isBlank()) return false
        val node = findNode(root, labels) ?: return false
        if (!node.isEditable) return false
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        labels: List<String>
    ): AccessibilityNodeInfo? {
        val normalized = labels.map { it.trim().lowercase() }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val texts = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            ).map { it.trim().lowercase() }
            if (texts.any { current -> normalized.any { target -> current == target || current.contains(target) } }) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun containsAny(root: AccessibilityNodeInfo, labels: List<String>): Boolean =
        findNode(root, labels) != null

    private fun visibilityLabels(value: String): List<String> = when (value) {
        "PUBLIC" -> listOf("Public")
        "UNLISTED" -> listOf("Unlisted")
        else -> listOf("Private")
    }

    private fun previousState(): AutomationState = when (sessionStore.state()) {
        AutomationState.RETRY -> AutomationState.WAITING_FOR_APP
        else -> AutomationState.WAITING_FOR_APP
    }

    private fun scheduleRetry() {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = Runnable {
            if (!::sessionStore.isInitialized) return@Runnable
            val attempts = sessionStore.incrementAttempt()
            if (attempts > maxAttempts) {
                val id = sessionStore.itemId()
                val item = id?.let { queueStore.load().firstOrNull { x -> x.id == it } }
                finishSession(item, "Automation step did not match the current UI")
            } else {
                sessionStore.setState(AutomationState.RETRY)
            }
        }.also { handler.postDelayed(it, 1200L) }
    }

    private fun finishSession(item: UploadItem?, message: String) {
        if (item != null) {
            queueStore.update(
                item.copy(
                    status = if (message == "Upload submitted") "SUBMITTED" else "ERROR"
                )
            )
        }
        sessionStore.clear()
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null
    }

    override fun onDestroy() {
        retryRunnable?.let(handler::removeCallbacks)
        super.onDestroy()
    }
}
