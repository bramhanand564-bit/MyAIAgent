package com.myaiagent.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import java.util.concurrent.Executors
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
    private var visionInFlight = false
    private val visionExecutor = Executors.newSingleThreadExecutor()

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

        if (containsSecurityChallenge(root)) {
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

                testLog("YouTube screen is active.")
                sessionStore.setState(AutomationState.FIND_CREATE)
                driveState(item, root)
            }

            AutomationState.FIND_CREATE -> {
                if (clickByLabels(root, listOf("Create", "Create a video"))) {
                    testLog("Clicked Create.")
                    sessionStore.setState(AutomationState.FIND_UPLOAD)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.FIND_UPLOAD -> {
                if (clickByLabels(root, listOf("Upload a video", "Upload video"))) {
                    testLog("Clicked Upload a video.")
                    sessionStore.setState(AutomationState.WAITING_FOR_PICKER)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.WAITING_FOR_PICKER -> {
                val picker = isDocumentPicker(root.packageName?.toString().orEmpty())
                if (picker && clickFileIfVisible(root, item.fileName)) {
                    testLog("Selected video in the file picker: " + item.fileName)
                    scheduleRetry(item)
                    return
                }
                val openClicked = clickByLabels(root, listOf(
                    "Open", "Select", "Done", "Use this file", "Choose", "Select this file"
                ))
                if (picker && openClicked) {
                    testLog("Confirmed the selected video.")
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

                if (titleSet) testLog("Title field completed: " + title)
                if (item.description.isNotBlank()) testLog("Description field completed.")
                sessionStore.setState(AutomationState.SET_VISIBILITY)
            }

            AutomationState.SET_VISIBILITY -> {
                if (clickByLabels(root, visibilityLabels(item.visibility))) {
                    testLog("Visibility selected: " + item.visibility)
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
                    testLog("Clicked Publish.")
                    sessionStore.setState(AutomationState.VERIFY)
                    scheduleRetry(item)
                    return
                }
                if (clickByLabels(root, listOf("Next", "Continue"))) {
                    testLog("Clicked Next/Continue.")
                    scheduleRetry(item)
                    return
                }
                if (clickByLabels(root, listOf("Save", "Upload", "Done"))) {
                    testLog("Clicked final Save/Upload/Done action.")
                    sessionStore.setState(AutomationState.VERIFY)
                    scheduleRetry(item)
                    return
                }
                scheduleRetry(item)
            }

            AutomationState.VERIFY -> {
                when {
                    containsAny(root, listOf("Video published", "Published", "Upload complete")) -> {
                        testLog("Verification found the published/upload-complete signal.")
                        finishSession(item, "Published signal detected")
                    }

                    containsAny(root, listOf("Processing", "Processing will continue in the background")) -> {
                        testLog("Verification found the processing signal.")
                        finishSession(item, "Processing signal detected; final availability verification pending")
                    }

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

        if (sessionStore.isTestMode()) {
            TestRunStore(this).markWaitingForUser("Login, verification, CAPTCHA, or security screen detected.")
        }
    }

    private fun testLog(message: String) {
        if (::sessionStore.isInitialized && sessionStore.isTestMode()) {
            TestRunStore(this).log(message)
        }
    }

    private fun containsSecurityChallenge(root: AccessibilityNodeInfo): Boolean {
        val packageName = root.packageName?.toString().orEmpty()

        // MyAIAgent contains instructional words such as "verify" and "security"
        // on its own test/dashboard screens. Only YouTube or the system picker
        // may raise a security/login stop.
        if (packageName != "com.google.android.youtube" && !isDocumentPicker(packageName)) {
            return false
        }

        return containsAny(
            root,
            listOf(
                "Sign in to continue",
                "Google sign in",
                "verification required",
                "CAPTCHA",
                "I'm not a robot",
                "Security check",
                "Confirm your identity",
                "Account verification required"
            )
        )
    }

    private fun isAutomationPackage(packageName: String): Boolean =
        packageName == "com.google.android.youtube" || packageName == "com.google.android.apps.youtube.creator" ||
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
        if (node.isEditable) return setText(node, value)
        val parent = node.parent ?: return false
        val editable = findEditableDescendant(parent) ?: return false
        return setText(editable, value)
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean =
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    value
                )
            }
        )

    private fun findEditableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableDescendant(child)
            if (found != null) return found
        }
        return null
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
            val attempt = sessionStore.incrementAttempt()
            if (attempt >= 3 && !visionInFlight && VisionAgentSettings(this).enabled) {
                requestVisionFallback(item)
                return@Runnable
            }
            if (attempt > maxAttemptsPerState) {
                finishSession(item, "UI step did not match after retries")
                return@Runnable
            }
            val root = rootInActiveWindow
            val packageName = root?.packageName?.toString().orEmpty()
            if (root != null && isAutomationPackage(packageName)) {
                driveState(item, root)
            }
        }.also { handler.postDelayed(it, 1200L) }
    }

    private fun requestVisionFallback(item: UploadItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            scheduleRetryWithoutVision(item)
            return
        }

        val settings = VisionAgentSettings(this)
        if (!settings.enabled || (settings.provider != VisionAgentSettings.PROVIDER_GEMINI && settings.endpoint.isBlank()) ||
            (settings.provider == VisionAgentSettings.PROVIDER_GEMINI && settings.apiKey.isBlank()) || visionInFlight) {
            scheduleRetryWithoutVision(item)
            return
        }

        visionInFlight = true
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            visionExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )
                    if (bitmap == null) {
                        visionInFlight = false
                        handler.post { scheduleRetryWithoutVision(item) }
                        return
                    }

                    visionExecutor.execute {
                        val decision = when (settings.provider) {
                            VisionAgentSettings.PROVIDER_CUSTOM,
                            VisionAgentSettings.PROVIDER_LOCAL -> OpenAiCompatibleVisionAgent(
                                settings.endpoint,
                                settings.apiKey,
                                settings.model,
                                settings.extraHeaders
                            ).analyze(
                                bitmap,
                                sessionStore.state(),
                                item.title.ifBlank { item.fileName },
                                item.visibility
                            )
                            else -> GeminiVisionAgent(settings.apiKey, settings.model).analyze(
                                bitmap,
                                sessionStore.state(),
                                item.title.ifBlank { item.fileName },
                                item.visibility
                            )
                        }
                        handler.post {
                            visionInFlight = false
                            bitmap.recycle()
                            if (decision == null) {
                                scheduleRetryWithoutVision(item)
                            } else {
                                applyVisionDecision(item, decision.action)
                            }
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    visionInFlight = false
                    handler.post { scheduleRetryWithoutVision(item) }
                }
            }
        )
    }

    private fun scheduleRetryWithoutVision(item: UploadItem) {
        handler.postDelayed({
            if (::sessionStore.isInitialized && sessionStore.itemId() == item.id) {
                driveState(item, rootInActiveWindow ?: return@postDelayed)
            }
        }, 1000L)
    }

    private fun applyVisionDecision(item: UploadItem, action: VisionAction) {
        if (action.confidence < 0.80f) {
            scheduleRetryWithoutVision(item)
            return
        }

        if (action.action == "NEEDS_USER" || action.screen == "SECURITY") {
            setWaitingForUser(item)
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            scheduleRetryWithoutVision(item)
            return
        }

        when (action.action) {
            "SET_TEXT" -> {
                val target = action.targetText
                val value = action.value
                if (target != null && value != null &&
                    setTextByLabels(root, listOf(target), value)
                ) {
                    advanceStateAfterVisionAction(action, clicked = false)
                } else {
                    scheduleRetryWithoutVision(item)
                }
            }

            "SELECT_FILE", "CLICK" -> {
                val clicked = action.targetText?.let {
                    clickByLabels(root, listOf(it))
                } ?: false
                val coordinateClicked = if (!clicked && action.x != null && action.y != null) {
                    dispatchTap(action.x, action.y)
                } else {
                    clicked
                }

                if (coordinateClicked) {
                    advanceStateAfterVisionAction(action, clicked = true)
                } else {
                    scheduleRetryWithoutVision(item)
                }
            }

            else -> scheduleRetryWithoutVision(item)
        }
    }

    private fun advanceStateAfterVisionAction(action: VisionAction, clicked: Boolean) {
        if (!clicked && sessionStore.state() == AutomationState.FILL_DETAILS) {
            return
        }

        when (sessionStore.state()) {
            AutomationState.FIND_CREATE -> sessionStore.setState(AutomationState.FIND_UPLOAD)
            AutomationState.FIND_UPLOAD -> sessionStore.setState(AutomationState.WAITING_FOR_PICKER)
            AutomationState.WAITING_FOR_PICKER -> sessionStore.setState(AutomationState.FILL_DETAILS)
            AutomationState.FILL_DETAILS -> sessionStore.setState(AutomationState.SET_VISIBILITY)
            AutomationState.SET_VISIBILITY -> sessionStore.setState(AutomationState.PUBLISH)
            AutomationState.PUBLISH -> {
                if (action.targetText?.lowercase()?.contains("publish") == true) {
                    sessionStore.setState(AutomationState.VERIFY)
                }
            }
            else -> Unit
        }
        handler.postDelayed({
            val root = rootInActiveWindow
            if (root != null) {
                val id = sessionStore.itemId()
                if (id != null) {
                    queueStore.load().firstOrNull { it.id == id }?.let { driveState(it, root) }
                }
            }
        }, 800L)
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path,
                    0L,
                    80L
                )
            )
            .build()
        return dispatchGesture(gesture, null, handler)
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
        val testMode = sessionStore.isTestMode()
        if (testMode) {
            TestRunStore(this).finish(successful, message)
        }

        sessionStore.clear()
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null

        if (successful && !testMode) {
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
        visionExecutor.shutdownNow()
        super.onDestroy()
    }
}
