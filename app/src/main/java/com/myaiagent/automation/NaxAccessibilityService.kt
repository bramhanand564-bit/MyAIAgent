package com.myaiagent.automation

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import com.myaiagent.queue.UploadQueueStore

class NaxAccessibilityService : AccessibilityService() {
    private lateinit var queueStore: UploadQueueStore
    private lateinit var sessionStore: AutomationSessionStore
    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null

    private val maxAttemptsPerState = 5
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
        if (packageName != "com.google.android.youtube" && packageName != "com.myaiagent") {
            return
        }

        driveState(item, root)
    }

    override fun onInterrupt() {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null
    }

    private fun driveState(item: com.myaiagent.model.UploadItem, root: AccessibilityNodeInfo) {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null

        when (sessionStore.state()) {
            AutomationState.WAITING_FOR_APP -> {
                if (isEmbedded(item) || isYouTube(root)) {
                    sessionStore.setState(AutomationState.FIND_CREATE)
                    driveState(item, root)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.FIND_CREATE -> {
                if (clickByLabels(root, listOf("Create", "Create a video"))) {
                    sessionStore.setState(AutomationState.FIND_UPLOAD)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.FIND_UPLOAD -> {
                if (clickByLabels(root, listOf("Upload a video", "Upload video"))) {
                    sessionStore.setState(AutomationState.WAITING_FOR_PICKER)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.WAITING_FOR_PICKER -> {
                if (containsAny(root, listOf(item.fileName)) &&
                    clickByLabels(root, listOf("Open", "Select", "Done"))
                ) {
                    sessionStore.setState(AutomationState.FILL_DETAILS)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.FILL_DETAILS -> {
                val title = item.title.ifBlank { item.fileName.substringBeforeLast('.') }
                val titleSet = setTextByLabels(root, listOf("Title", "Add a title"), title)
                val descriptionSet = if (item.description.isBlank()) {
                    true
                } else {
                    setTextByLabels(root, listOf("Description", "Add a description"), item.description)
                }

                if (!titleSet && !fieldExists(root, listOf("Title", "Add a title"))) {
                    scheduleRetry(item)
                    return
                }

                if (!descriptionSet) {
                    scheduleRetry(item)
                    return
                }

                if (item.visibility == "PRIVATE") {
                    sessionStore.setState(AutomationState.PUBLISH)
                } else {
                    sessionStore.setState(AutomationState.SET_VISIBILITY)
                }
            }

            AutomationState.SET_VISIBILITY -> {
                if (clickByLabels(root, listOf("Visibility", "Who can see this video"))) {
                    scheduleRetry(item)
                    return
                }

                if (clickByLabels(root, visibilityLabels(item.visibility))) {
                    sessionStore.setState(AutomationState.PUBLISH)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.PUBLISH -> {
                if (clickByLabels(root, listOf("Publish", "Save", "Upload"))) {
                    sessionStore.setState(AutomationState.VERIFY)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.VERIFY -> {
                if (containsAny(root, listOf(
                        "Video published",
                        "Published",
                        "Upload complete",
                        "Processing"
                    ))) {
                    finishSession(item, "Upload submitted")
                } else {
                    scheduleRetry(item)
                }
            }

            else -> Unit
        }
    }

    private fun isYouTube(root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString() == "com.google.android.youtube"

    private fun isEmbedded(item: com.myaiagent.model.UploadItem): Boolean =
        item.automationMode == "EMBEDDED_WEB"

    private fun fieldExists(root: AccessibilityNodeInfo, labels: List<String>): Boolean =
        findNode(root, labels)?.let { it.isEditable || it.className?.toString()?.contains("EditText") == true } == true

    private fun clickByLabels(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val node = findNode(root, labels) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    private fun setTextByLabels(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        value: String
    ): Boolean {
        if (value.isBlank()) return true
        val node = findNode(root, labels) ?: return false
        if (!node.isEditable) return false

        return node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    value
                )
            }
        )
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

            if (texts.any { current ->
                    normalized.any { target ->
                        current == target || current.contains(target)
                    }
                }) {
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

    private fun scheduleRetry(item: com.myaiagent.model.UploadItem) {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = Runnable {
            if (!::sessionStore.isInitialized) return@Runnable

            val attempts = sessionStore.incrementAttempt()
            if (attempts > maxAttemptsPerState) {
                finishSession(item, "UI step did not match after retries")
                return@Runnable
            }

            val root = rootInActiveWindow
            val packageName = root?.packageName?.toString().orEmpty()
            if (root != null &&
                (packageName == "com.google.android.youtube" || packageName == "com.myaiagent")
            ) {
                driveState(item, root)
            }
        }.also { handler.postDelayed(it, 1200L) }
    }

    private fun finishSession(item: com.myaiagent.model.UploadItem?, message: String) {
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
