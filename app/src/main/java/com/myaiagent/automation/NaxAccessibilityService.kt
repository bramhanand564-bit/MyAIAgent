package com.myaiagent.automation

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.myaiagent.model.UploadItem
import com.myaiagent.queue.UploadQueueCoordinator
import com.myaiagent.queue.UploadQueueStore
import androidx.core.content.ContextCompat

class NaxAccessibilityService : AccessibilityService() {
    private lateinit var queueStore: UploadQueueStore
    private lateinit var sessionStore: AutomationSessionStore
    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null

    private val maxAttemptsPerState = 5
    private val sessionTimeoutMs = 10 * 60 * 1000L
    private val watchdogIntervalMs = 30 * 1000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        queueStore = UploadQueueStore(this)
        sessionStore = AutomationSessionStore(this)
        startWatchdog()
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
        if (!isAutomationPackage(packageName)) return

        if (containsAny(root, listOf(
                "Sign in",
                "Sign in to continue",
                "Verify",
                "verification required",
                "CAPTCHA",
                "Security check"
            ))) {
            setWaitingForUser(item)
            return
        }

        driveState(item, root)
    }

    override fun onInterrupt() {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null
    }

    private fun driveState(item: UploadItem, root: AccessibilityNodeInfo) {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null

        when (sessionStore.state()) {
            AutomationState.WAITING_FOR_APP -> {
                if (!isYouTube(root) && !isEmbedded(item)) {
                    scheduleRetry(item)
                    return
                }

                if (item.automationMode == "EXTERNAL_APP" && isYouTube(root)) {
                    sessionStore.setState(AutomationState.FILL_DETAILS)
                } else {
                    sessionStore.setState(AutomationState.FIND_CREATE)
                }
                driveState(item, root)
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
                val picker = isDocumentPicker(root.packageName?.toString().orEmpty())
                if (picker && clickFileIfVisible(root, item.fileName)) {
                    scheduleRetry(item)
                    return
                }
                val openClicked = clickByLabels(root, listOf(
                    "Open", "Select", "Done", "Use this file", "Choose", "Select this file"
                ))
                if (picker && openClicked) {
                    sessionStore.setState(AutomationState.FILL_DETAILS)
                    driveState(item, root)
                } else if (!picker && containsAny(root, listOf(item.fileName))) {
                    sessionStore.setState(AutomationState.FILL_DETAILS)
                    driveState(item, root)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.FILL_DETAILS -> {
                val title = item.title.ifBlank { item.fileName.substringBeforeLast('.') }
                val titleSet = setTextByLabels(root, listOf("Title", "Add a title", "Video title", "Enter a title", "Add title"), title)
                val descriptionSet = if (item.description.isBlank()) {
                    true
                } else {
                    setTextByLabels(root, listOf("Description", "Add a description", "Video description", "Add description"), item.description)
                }

                if (!titleSet && !fieldExists(root, listOf("Title", "Add a title"))) {
                    scheduleRetry(item)
                    return
                }
                if (!descriptionSet) {
                    scheduleRetry(item)
                    return
                }

                sessionStore.setState(AutomationState.SET_VISIBILITY)
            }

            AutomationState.SET_VISIBILITY -> {
                if (clickByLabels(root, visibilityLabels(item.visibility))) {
                    sessionStore.setState(AutomationState.PUBLISH)
                    return
                }
                if (clickByLabels(root, listOf("Visibility", "Who can see this video", "Privacy", "Privacy setting"))) {
                    scheduleRetry(item)
                    return
                }
                scheduleRetry(item)
            }

            AutomationState.PUBLISH -> {
                if (clickByLabels(root, listOf("Publish", "Publish video"))) {
                    sessionStore.setState(AutomationState.VERIFY)
                    scheduleRetry(item)
                    return
                }
                if (clickByLabels(root, listOf("Next", "Continue"))) {
                    scheduleRetry(item)
                    return
                }
                if (clickByLabels(root, listOf("Save", "Upload", "Done"))) {
                    sessionStore.setState(AutomationState.VERIFY)
                    scheduleRetry(item)
                    return
                }
                scheduleRetry(item)
            }

            AutomationState.VERIFY -> {
                when {
                    containsAny(root, listOf("Video published", "Published", "Upload complete")) ->
                        finishSession(item, "Published signal detected")

                    containsAny(root, listOf("Processing", "Processing will continue in the background")) ->
                        finishSession(item, "Processing signal detected; final availability verification pending")

                    else -> scheduleRetry(item)
                }
            }

            AutomationState.WAITING_USER,
            AutomationState.COMPLETE,
            AutomationState.ERROR,
            AutomationState.IDLE -> Unit
        }
    }

    private fun setWaitingForUser(item: UploadItem) {
        sessionStore.setState(AutomationState.WAITING_USER)
        queueStore.update(
            item.copy(
                status = "NEEDS_USER_ACTION",
                lastRunAt = System.currentTimeMillis(),
                resultNote = "User action required on a security/login screen"
            )
        )
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null
    }

    private fun isAutomationPackage(packageName: String): Boolean =
        packageName == "com.google.android.youtube" ||
            packageName == "com.myaiagent" ||
            isDocumentPicker(packageName)

    private fun isDocumentPicker(packageName: String): Boolean =
        packageName == "com.google.android.documentsui" ||
            packageName == "com.google.android.providers.media.module" ||
            packageName.contains("documentsui")
    private fun isYouTube(root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString() == "com.google.android.youtube"

    private fun isEmbedded(item: UploadItem): Boolean =
        item.automationMode == "EMBEDDED_WEB"

    private fun fieldExists(root: AccessibilityNodeInfo, labels: List<String>): Boolean =
        findNode(root, labels)?.let {
            it.isEditable || it.className?.toString()?.contains("EditText") == true
        } == true

    private fun clickFileIfVisible(root: AccessibilityNodeInfo, fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.')
        val node = findNode(root, listOf(fileName, base)) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }

    private fun clickByLabels(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val node = findNode(root, labels) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }

    private fun setTextByLabels(root: AccessibilityNodeInfo, labels: List<String>, value: String): Boolean {
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

    private fun findNode(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
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
                    normalized.any { target -> current == target || current.contains(target) }
                }) return node

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

    private fun scheduleRetry(item: UploadItem) {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = Runnable {
            if (!::sessionStore.isInitialized) return@Runnable
            if (sessionStore.incrementAttempt() > maxAttemptsPerState) {
                finishSession(item, "UI step did not match after retries")
                return@Runnable
            }
            val root = rootInActiveWindow
            val packageName = root?.packageName?.toString().orEmpty()
            if (root != null &&
                isAutomationPackage(packageName)
            ) driveState(item, root)
        }.also { handler.postDelayed(it, 1200L) }
    }

    private fun startWatchdog() {
        watchdogRunnable?.let(handler::removeCallbacks)
        watchdogRunnable = object : Runnable {
            override fun run() {
                if (::sessionStore.isInitialized && sessionStore.itemId() != null) {
                    val elapsed = System.currentTimeMillis() - sessionStore.startedAt()
                    if (elapsed > sessionTimeoutMs) {
                        val id = sessionStore.itemId()
                        val item = id?.let { wanted ->
                            queueStore.load().firstOrNull { it.id == wanted }
                        }
                        finishSession(item, "Automation timed out")
                    }
                }
                handler.postDelayed(this, watchdogIntervalMs)
            }
        }
        handler.post(watchdogRunnable!!)
    }

    private fun finishSession(item: UploadItem?, message: String) {
        val successful = message.contains("signal detected")
        if (item != null) {
            queueStore.update(
                item.copy(
                    status = if (successful) "SUBMITTED" else "ERROR",
                    lastRunAt = System.currentTimeMillis(),
                    resultNote = message
                )
            )
        }
        sessionStore.clear()
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null

        if (successful) {
            val next = UploadQueueCoordinator.nextEligible(queueStore.load())
            if (next != null) {
                val intent = android.content.Intent(
                    this,
                    UploadRunnerService::class.java
                ).apply {
                    putExtra(UploadRunnerService.EXTRA_ITEM_ID, next.id)
                }
                ContextCompat.startForegroundService(this, intent)
            }
        }
    }

    override fun onDestroy() {
        retryRunnable?.let(handler::removeCallbacks)
        watchdogRunnable?.let(handler::removeCallbacks)
        super.onDestroy()
    }
}
