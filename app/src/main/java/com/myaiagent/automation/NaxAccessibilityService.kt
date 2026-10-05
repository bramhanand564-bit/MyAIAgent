package com.myaiagent.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import java.util.concurrent.Executors
import java.util.Locale
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
    private var pickerVisualInFlight = false
    private var lastVisionRequestAt = 0L
    private val visionExecutor = Executors.newSingleThreadExecutor()
    private val agentExecutor = Executors.newSingleThreadExecutor()
    private val agentLoop = AgentLoop()
    private lateinit var floatingCursor: NaxFloatingCursor
    @Volatile private var agentScreenshotInFlight = false
    @Volatile private var agentAiInFlight = false

    private val maxAttemptsPerState = 5
    private val sessionTimeoutMs = 10 * 60 * 1000L
    private val watchdogIntervalMs = 30 * 1000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        queueStore = UploadQueueStore(this)
        sessionStore = AutomationSessionStore(this)
        floatingCursor = NaxFloatingCursor(this)
        val settings = VisionAgentSettings(this)
        agentLoop.updateInterval(settings.observationIntervalSeconds * 1000L)
        agentLoop.start { captureAgentObservation() }
        startWatchdog()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::sessionStore.isInitialized) return

        val itemId = sessionStore.itemId() ?: return
        if (::floatingCursor.isInitialized && !floatingCursor.isShown()) {
            floatingCursor.showStatus("NAX • WORKING")
        }
        if (::floatingCursor.isInitialized) {
            floatingCursor.setState(sessionStore.state().name)
        }
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

        val mindSnapshot = MindStore(this).snapshot()
        if (mindSnapshot.packageName != packageName) {
            MindEngine.onScreen(this, packageName)
        }

        if (containsSecurityChallenge(root)) {
            floatingCursor.setMessage("🔐 manual action required")
            floatingCursor.setState("WAITING_USER", "resume after security check")
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
        if (::floatingCursor.isInitialized) {
            floatingCursor.setState(sessionStore.state().name)
        }

        if (MindStore(this).consumeRecovery()) {
            testLog("NAX MIND • Safe recovery consumed; retrying from current verified state.")
        }

        when (sessionStore.state()) {
            AutomationState.WAITING_FOR_APP -> {
                if (!isYouTube(root)) {
                    scheduleRetry(item)
                    return
                }

                testLog("Native YouTube Studio screen is active.")
                sessionStore.setState(AutomationState.FIND_CREATE)
                driveState(item, root)
            }

            AutomationState.FIND_CREATE -> {
                if (clickByLabels(root, listOf("Create", "Create a video"))) {
                    testLog("Clicked Create • verifying the next screen.")
                    sessionStore.setState(AutomationState.VERIFY_CREATE_MENU)
                    scheduleRetry(item)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.VERIFY_CREATE_MENU -> {
                val targets = if (item.contentType == "SHORT") {
                    listOf("Create a Short", "Short", "Upload videos", "Upload a video", "Upload video")
                } else {
                    listOf("Upload videos", "Upload a video", "Upload video", "Upload videos from device", "Create a Short")
                }
                if (containsAny(root, targets)) {
                    testLog("Create screen verified.")
                    sessionStore.setState(AutomationState.FIND_UPLOAD)
                    driveState(item, root)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.FIND_UPLOAD -> {
                val targets = if (item.contentType == "SHORT") {
                    listOf("Create a Short", "Short", "Upload videos", "Upload a video", "Upload video")
                } else {
                    listOf("Upload videos", "Upload a video", "Upload video", "Upload videos from device")
                }
                if (clickByLabels(root, targets)) {
                    testLog("Selected ${item.contentType.lowercase(Locale.getDefault())} upload path • verifying picker.")
                    sessionStore.setState(AutomationState.VERIFY_UPLOAD_PICKER)
                    scheduleRetry(item)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.VERIFY_UPLOAD_PICKER -> {
                if (isDocumentPicker(root.packageName?.toString().orEmpty()) ||
                    containsAny(root, listOf("Recent", "Browse", "Open", "Select", "Choose"))
                ) {
                    testLog("File picker screen verified.")
                    sessionStore.setState(AutomationState.WAITING_FOR_PICKER)
                    driveState(item, root)
                } else {
                    scheduleRetry(item)
                }
            }

            AutomationState.WAITING_FOR_PICKER -> {
                val picker = isDocumentPicker(root.packageName?.toString().orEmpty())
                if (!picker) {
                    if (containsAny(root, listOf(item.fileName))) {
                        testLog("Picker closed and selected file is visible in Studio: " + item.fileName)
                        sessionStore.setState(AutomationState.FILL_DETAILS)
                        driveState(item, root)
                    } else {
                        scheduleRetry(item)
                    }
                    return
                }

                // Never tap Open/Done before the target file has actually been selected.
                // Some Android pickers expose the action even while no file is selected.
                if (!sessionStore.isPickerSelectionPending()) {
                    val selected = clickFileIfVisible(root, item.fileName)
                    if (selected) {
                        sessionStore.markPickerSelectionPending()
                        testLog("Video row tapped in picker; waiting for selection confirmation: " + item.fileName)
                        scheduleRetry(item)
                        return
                    }

                    // Google/Android Photo Picker often exposes only generic media
                    // accessibility descriptions, not the original filename. Before blind
                    // scrolling, match the exact queued video's thumbnail against the visible
                    // picker grid and tap that cell.
                    if (requestPickerVisualMatch(item, root)) {
                        return
                    }

                    if (scrollPickerTowardsFile(root)) {
                        testLog("Target video is not visible yet; scrolling picker to find: " + item.fileName)
                        scheduleRetry(item)
                        return
                    }

                    scheduleRetry(item)
                    return
                }

                // A pending selection exists. Verify it before confirming with Open/Done.
                if (!isFileSelectionConfirmed(root, item.fileName)) {
                    testLog("Picker tap was not verified yet; waiting before Open: " + item.fileName)
                    scheduleRetry(item)
                    return
                }

                val openClicked = clickByLabels(root, listOf(
                    "Open", "Select", "Done", "Use this file", "Choose", "Select this file"
                ))
                if (openClicked) {
                    testLog("Confirmed selected video with picker action: " + item.fileName)
                    sessionStore.setState(AutomationState.FILL_DETAILS)
                    driveState(item, root)
                } else {
                    testLog("Selected video is verified but picker confirmation control is not clickable yet.")
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

                if (titleSet && !fieldContainsValue(root, listOf("Title", "Add a title", "Video title", "Enter a title", "Add title"), title)) {
                    testLog("Title write was not verified; retrying.")
                    scheduleRetry(item)
                    return
                }
                if (item.description.isNotBlank() &&
                    descriptionSet &&
                    !fieldContainsValue(root, listOf("Description", "Add a description", "Video description", "Add description"), item.description)
                ) {
                    testLog("Description write was not verified; retrying.")
                    scheduleRetry(item)
                    return
                }

                if (titleSet) testLog("Title field verified: " + title)
                if (item.description.isNotBlank()) testLog("Description field verified.")
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
                    testLog("Clicked Publish. Starting upload monitoring.")
                    sessionStore.setState(AutomationState.MONITOR_UPLOAD)
                    scheduleMonitorCheck(item)
                    return
                }
                if (clickByLabels(root, listOf("Next", "Continue"))) {
                    testLog("Clicked Next/Continue. Still waiting for final publish action.")
                    scheduleRetry(item)
                    return
                }
                if (clickByLabels(root, listOf("Save", "Upload", "Done"))) {
                    testLog("Clicked final Save/Upload/Done action. Starting upload monitoring.")
                    sessionStore.setState(AutomationState.MONITOR_UPLOAD)
                    scheduleMonitorCheck(item)
                    return
                }
                scheduleRetry(item)
            }

            AutomationState.MONITOR_UPLOAD -> {
                val observation = findUploadObservation(root)
                if (observation != null && observation != sessionStore.lastObservation()) {
                    sessionStore.recordObservation(observation)
                    MindEngine.onScreen(this, root.packageName?.toString().orEmpty(), observation)
                    testLog("Upload check • " + observation)
                }

                when {
                    containsAny(root, listOf(
                        "Upload failed",
                        "Couldn't upload",
                        "Couldn’t upload",
                        "Something went wrong",
                        "Try again"
                    )) -> {
                        finishSession(item, "Upload failed according to YouTube Studio")
                    }

                    isPublishedSignal(root) -> {
                        sessionStore.markTransferComplete()
                        testLog("Upload check • Published signal detected.")
                        sessionStore.setState(AutomationState.VERIFY)
                        driveState(item, root)
                    }

                    isSendingComplete(root) -> {
                        sessionStore.markTransferComplete()
                        testLog("Upload check • File transfer reached 100%. Moving to final verification.")
                        sessionStore.setState(AutomationState.VERIFY)
                        driveState(item, root)
                    }

                    containsAny(root, listOf(
                        "Sending file",
                        "Uploading",
                        "Preparing",
                        "% remaining",
                        "seconds remaining",
                        "minutes remaining"
                    )) -> {
                        scheduleMonitorCheck(item)
                    }

                    else -> {
                        scheduleMonitorCheck(item)
                    }
                }
            }

            AutomationState.VERIFY -> {
                when {
                    isPublishedSignal(root) -> {
                        testLog("Final verification passed: published/upload-complete signal found.")
                        finishSession(item, "Verified upload • Published signal detected")
                    }

                    sessionStore.isTransferComplete() &&
                        containsAny(root, listOf(item.fileName, item.fileName.substringBeforeLast('.'))) &&
                        containsAny(root, listOf("Shorts", "Content", "Videos")) -> {
                        testLog("Final verification passed: uploaded video is visible in YouTube.")
                        finishSession(item, "Verified upload • Video visible in YouTube")
                    }

                    containsAny(root, listOf("Processing", "Processing will continue in the background")) -> {
                        testLog(
                            if (sessionStore.isTransferComplete())
                                "Final check • YouTube is processing the transferred video; waiting for publish/content confirmation."
                            else
                                "Final check • Processing appeared before transfer completion; continuing to monitor."
                        )
                        scheduleMonitorCheck(item)
                    }

                    else -> scheduleMonitorCheck(item)
                }
            }

            AutomationState.WAITING_USER -> {
                val resume = sessionStore.waitingResumeState()
                if (resume != null && isAutomationPackage(root.packageName?.toString().orEmpty())) {
                    queueStore.update(
                        item.copy(
                            status = "RUNNING",
                            resultNote = "Security screen cleared; automation resumed"
                        )
                    )
                    testLog("User action cleared. Resuming " + resume.name + ".")
                    sessionStore.setState(resume)
                    driveState(item, root)
                }
            }

            AutomationState.COMPLETE,
            AutomationState.ERROR,
            AutomationState.IDLE -> Unit
        }
    }

    private fun setWaitingForUser(item: UploadItem) {
        val resumeState = sessionStore.state()
        sessionStore.enterWaitingForUser(resumeState)
        queueStore.update(
            item.copy(
                status = "NEEDS_USER_ACTION",
                lastRunAt = System.currentTimeMillis(),
                resultNote = "User action required on a security/login screen"
            )
        )
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null

        MindEngine.onWarning(
            this,
            "The flow is blocked by a user-controlled security/login/verification screen.",
            "Complete the Google/Studio step manually. Automation will resume after the screen clears."
        )

        if (sessionStore.isTestMode()) {
            TestRunStore(this).markWaitingForUser("Login, verification, CAPTCHA, or security screen detected.")
        }
    }

    /**
     * Continuous agent observation:
     * screenshot every configured interval (6s by default), detect screen changes,
     * and let Gemini describe the visible screen when analysis is due.
     *
     * The observation brain does not directly execute actions. Existing verified
     * Accessibility state transitions remain the action/verification authority.
     */
    private fun captureAgentObservation() {
        if (!::sessionStore.isInitialized || !sessionStore.isActive()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (agentScreenshotInFlight) return

        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString().orEmpty()
        if (!isAutomationPackage(packageName)) return

        val settings = VisionAgentSettings(this)
        val state = sessionStore.state()
        val observations = AgentObservationStore(this)
        agentScreenshotInFlight = true

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            visionExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardware = runCatching {
                        Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                    }.getOrNull()

                    val bitmap = hardware?.let {
                        runCatching {
                            it.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                    }

                    runCatching { screenshot.hardwareBuffer.close() }
                    if (hardware != null && hardware !== bitmap) {
                        runCatching { hardware.recycle() }
                    }

                    if (bitmap == null) {
                        agentScreenshotInFlight = false
                        return
                    }

                    val screenHash = screenSignature(bitmap)
                    val changed = screenHash != observations.lastHash()
                    val now = System.currentTimeMillis()

                    observations.record(
                        packageName = packageName,
                        state = state.name,
                        screenHash = screenHash
                    )

                    handler.post {
                        if (sessionStore.isActive()) {
                            testLog(
                                "AGENT OBS • screenshot captured • " +
                                    "state=" + state.name +
                                    " • changed=" + changed
                            )
                        }
                    }

                    val aiConfigured = settings.enabled &&
                        settings.provider == VisionAgentSettings.PROVIDER_GEMINI &&
                        settings.apiKey.isNotBlank() &&
                        changed &&
                        !agentAiInFlight &&
                        now - observations.lastAiAt() >= 15_000L

                    if (!aiConfigured) {
                        bitmap.recycle()
                        agentScreenshotInFlight = false
                        return
                    }

                    agentAiInFlight = true
                    agentScreenshotInFlight = false
                    observations.markAiAt(now)

                    agentExecutor.execute {
                        val memory = buildString {
                            val verified = AgentVerifiedMemoryStore(
                                this@NaxAccessibilityService
                            ).promptContext(packageName)
                            val selectorHints = WorkflowMemoryStore(
                                this@NaxAccessibilityService
                            ).promptContext(packageName, state.name)
                            if (verified.isNotBlank()) {
                                append("Verified learning memory:\n")
                                append(verified)
                            }
                            if (selectorHints.isNotBlank()) {
                                if (isNotBlank()) append("\n")
                                append("Selector hints (not proof):\n")
                                append(selectorHints)
                            }
                        }

                        val item = queueStore.load()
                            .firstOrNull { it.id == sessionStore.itemId() }

                        val decision = runCatching {
                            if (item == null) {
                                null
                            } else {
                                GeminiVisionAgent(
                                    settings.apiKey,
                                    settings.model
                                ).analyze(
                                    bitmap = bitmap,
                                    currentState = state,
                                    itemTitle = item.title.ifBlank { item.fileName },
                                    visibility = item.visibility,
                                    targetFileName = item.fileName,
                                    memoryContext = memory
                                )
                            }
                        }.getOrNull()

                        handler.post {
                            agentAiInFlight = false

                            if (decision == null) {
                                bitmap.recycle()
                                return@post
                            }

                            val action = decision.action
                            observations.record(
                                packageName = packageName,
                                state = state.name,
                                screenHash = screenHash,
                                aiScreen = action.screen,
                                aiAction = action.action,
                                aiConfidence = action.confidence,
                                aiReason = action.reason
                            )

                            val summary =
                                "AGENT THINK • screen=" + action.screen +
                                    " • action=" + action.action +
                                    " • confidence=" +
                                    String.format(
                                        Locale.US,
                                        "%.2f",
                                        action.confidence
                                    ) +
                                    " • reason=" + action.reason

                            testLog(summary)
                            MindEngine.onScreen(
                                this@NaxAccessibilityService,
                                packageName,
                                summary
                            )

                            bitmap.recycle()
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    agentScreenshotInFlight = false
                    handler.post {
                        if (::sessionStore.isInitialized && sessionStore.isActive()) {
                            testLog("AGENT OBS • screenshot failed • code=" + errorCode)
                        }
                    }
                }
            }
        )
    }

    private fun screenSignature(bitmap: Bitmap): String {
        val sample = runCatching {
            Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        }.getOrNull() ?: return "unavailable"

        var hash = 1125899906842597L
        val pixels = IntArray(16 * 16)
        sample.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        for (pixel in pixels) {
            hash = 31L * hash + pixel.toLong()
        }
        if (!sample.isRecycled && sample !== bitmap) {
            sample.recycle()
        }
        return java.lang.Long.toHexString(hash)
    }

    private fun testLog(message: String) {
        if (::sessionStore.isInitialized && sessionStore.isActive()) {
            MindStore(this).log(message)
            AutomationLiveStore(this).log(message)
            if (sessionStore.isTestMode()) {
                TestRunStore(this).log(message)
            }
        }
    }

    private fun containsSecurityChallenge(root: AccessibilityNodeInfo): Boolean {
        val packageName = root.packageName?.toString().orEmpty()

        // MyAIAgent contains instructional words such as "verify" and "security"
        // on its own test/dashboard screens. Only YouTube or the system picker
        // may raise a security/login stop.
        if (packageName != "com.google.android.youtube" &&
            packageName != "com.google.android.apps.youtube.creator" &&
            !isDocumentPicker(packageName)) {
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
        packageName == "com.google.android.youtube" ||
            packageName == "com.google.android.apps.youtube.creator" ||
            isDocumentPicker(packageName)

    private fun isDocumentPicker(packageName: String): Boolean =
        packageName == "com.google.android.documentsui" ||
            packageName == "com.google.android.providers.media.module" ||
            packageName.contains("documentsui") ||
            packageName == "com.google.android.apps.photos"
    private fun isYouTube(root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString() == "com.google.android.youtube" ||
            root.packageName?.toString() == "com.google.android.apps.youtube.creator"

    private fun isEmbedded(item: UploadItem): Boolean =
        item.automationMode == "EMBEDDED_WEB"

    private fun fieldExists(root: AccessibilityNodeInfo, labels: List<String>): Boolean =
        findNode(root, labels)?.let {
            it.isEditable || it.className?.toString()?.contains("EditText") == true
        } == true

    private fun requestPickerVisualMatch(item: UploadItem, root: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || pickerVisualInFlight) return false

        val packageName = root.packageName?.toString().orEmpty()
        if (!isDocumentPicker(packageName)) return false

        val candidateBounds = collectPickerMediaBounds(root)
        if (candidateBounds.isEmpty()) return false

        pickerVisualInFlight = true
        testLog("Photo Picker target not exposed by filename • starting local thumbnail match for " + item.fileName)

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            visionExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    }.getOrNull()

                    runCatching { screenshot.hardwareBuffer.close() }

                    if (bitmap == null) {
                        pickerVisualInFlight = false
                        handler.post {
                            testLog("Photo Picker screenshot unavailable; returning to normal picker retry.")
                            scheduleRetry(item)
                        }
                        return
                    }

                    visionExecutor.execute {
                        val targetFrames = loadTargetVideoFrames(item.uri)
                        val match = if (targetFrames.isNotEmpty()) {
                            findBestPickerMatch(bitmap, candidateBounds, targetFrames)
                        } else null

                        targetFrames.forEach { frame ->
                            if (!frame.isRecycled) frame.recycle()
                        }
                        if (!bitmap.isRecycled) bitmap.recycle()

                        handler.post {
                            pickerVisualInFlight = false
                            if (match != null && match.score >= 0.70f) {
                                val x = match.bounds.centerX().toFloat()
                                val y = match.bounds.centerY().toFloat()
                                if (dispatchTap(x, y)) {
                                    AgentVerifiedMemoryStore(this@NaxAccessibilityService)
                                        .rememberCandidate(
                                            packageName = packageName,
                                            state = sessionStore.state().name,
                                            action = "SELECT_FILE",
                                            target = item.fileName,
                                            value = "x=" + x + ",y=" + y
                                        )
                                    sessionStore.markPickerSelectionPending()
                                    testLog(
                                        "Local thumbnail match selected target video • score=" +
                                            String.format(Locale.US, "${match.score}") +
                                            " • x=$x y=$y"
                                    )
                                    scheduleRetry(item)
                                    return@post
                                }
                            }

                            if (match != null) {
                                testLog(
                                    "Local thumbnail match was ambiguous/weak • score=" +
                                        String.format(Locale.US, "${match.score}")
                                )
                            } else {
                                testLog("Local thumbnail match found no reliable target in the visible picker grid.")
                            }
                            scheduleRetry(item)
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    pickerVisualInFlight = false
                    handler.post {
                        testLog("Photo Picker screenshot failed • code=$errorCode")
                        scheduleRetry(item)
                    }
                }
            }
        )
        return true
    }

    private data class PickerVisualMatch(
        val bounds: android.graphics.Rect,
        val score: Float
    )

    private fun collectPickerMediaBounds(root: AccessibilityNodeInfo): List<android.graphics.Rect> {
        val result = mutableListOf<android.graphics.Rect>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)

        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val text = node.text?.toString().orEmpty().lowercase(Locale.getDefault())
            val desc = node.contentDescription?.toString().orEmpty().lowercase(Locale.getDefault())
            val combined = "$" + "text $desc"
            val looksLikeMedia = combined.contains("media") ||
                combined.contains("video taken on") ||
                combined.contains("photo taken on") ||
                combined.contains("duration")

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val validBounds = !bounds.isEmpty &&
                bounds.width() >= 48 &&
                bounds.height() >= 48 &&
                bounds.width() <= 700 &&
                bounds.height() <= 700 &&
                bounds.top > 120

            if (looksLikeMedia && validBounds && (node.isClickable || desc.contains("media") || desc.contains("video"))) {
                if (result.none { r -> r == bounds }) result.add(android.graphics.Rect(bounds))
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(pending::addLast)
            }
        }

        return result.distinctBy { r -> "${r.left},${r.top},${r.right},${r.bottom}" }.take(80)
    }

    private fun loadTargetVideoFrames(uriString: String): List<Bitmap> {
        val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull() ?: return emptyList()
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val durationUs = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()?.times(1000L) ?: 0L

            val positions = listOf(
                0L,
                (durationUs / 4L).coerceAtLeast(1L),
                (durationUs / 2L).coerceAtLeast(1L)
            ).distinct()

            positions.mapNotNull { position ->
                runCatching {
                    retriever.getFrameAtTime(
                        position,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                }.getOrNull()
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun findBestPickerMatch(
        screenshot: Bitmap,
        candidates: List<android.graphics.Rect>,
        targetFrames: List<Bitmap>
    ): PickerVisualMatch? {
        var best: PickerVisualMatch? = null

        for (bounds in candidates) {
            if (bounds.right > screenshot.width || bounds.bottom > screenshot.height) continue

            val left = bounds.left.coerceAtLeast(0)
            val top = bounds.top.coerceAtLeast(0)
            val width = bounds.width().coerceAtMost(screenshot.width - left)
            val height = bounds.height().coerceAtMost(screenshot.height - top)
            if (width < 8 || height < 8) continue

            val crop = runCatching {
                Bitmap.createBitmap(screenshot, left, top, width, height)
            }.getOrNull() ?: continue

            val score = targetFrames.maxOfOrNull { frame ->
                bitmapSimilarity(crop, frame)
            } ?: 0f

            crop.recycle()

            if (best == null || score > best!!.score) {
                best = PickerVisualMatch(android.graphics.Rect(bounds), score)
            }
        }

        return best
    }

    private fun bitmapSimilarity(a: Bitmap, b: Bitmap): Float {
        val size = 32
        val aa = centerSquare(a)
        val bb = centerSquare(b)
        val ra = Bitmap.createScaledBitmap(aa, size, size, true)
        val rb = Bitmap.createScaledBitmap(bb, size, size, true)

        var total = 0L
        var count = 0
        val pa = IntArray(size * size)
        val pb = IntArray(size * size)
        ra.getPixels(pa, 0, size, 0, 0, size, size)
        rb.getPixels(pb, 0, size, 0, 0, size, size)

        for (i in pa.indices) {
            val ca = pa[i]
            val cb = pb[i]
            val ar = (ca shr 16) and 0xff
            val ag = (ca shr 8) and 0xff
            val ab = ca and 0xff
            val br = (cb shr 16) and 0xff
            val bg = (cb shr 8) and 0xff
            val bbv = cb and 0xff

            val x = i % size
            val y = i / size
            if (x in 3 until size - 3 && y in 3 until size - 3) {
                total += kotlin.math.abs(ar - br) +
                    kotlin.math.abs(ag - bg) +
                    kotlin.math.abs(ab - bbv)
                count += 3 * 255
            }
        }

        if (!ra.isRecycled) ra.recycle()
        if (!rb.isRecycled) rb.recycle()
        if (!aa.isRecycled && aa !== a) aa.recycle()
        if (!bb.isRecycled && bb !== b) bb.recycle()

        return 1f - (total.toFloat() / count.toFloat()).coerceIn(0f, 1f)
    }

    private fun centerSquare(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        return if (left == 0 && top == 0 && side == source.width && side == source.height) {
            source
        } else {
            Bitmap.createBitmap(source, left, top, side, side)
        }
    }

    private fun clickFileIfVisible(root: AccessibilityNodeInfo, fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.')
        val node = findNode(root, listOf(fileName, base)) ?: return false
        val label = node.text?.toString().orEmpty().ifBlank {
            node.contentDescription?.toString().orEmpty().ifBlank { fileName }
        }

        // Prefer the closest enabled clickable node because DocumentsUI frequently
        // exposes the filename as a child of the actual selectable row/tile.
        var clickableTarget: AccessibilityNodeInfo? = null
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEnabled && current.isClickable) {
                clickableTarget = current
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    floatingCursor.setMessage("✓ tap sent • checking")
                    floatingCursor.tapFeedback()
                    WorkflowMemoryStore(this).remember(
                        root.packageName?.toString().orEmpty(),
                        sessionStore.state().name,
                        label,
                        node
                    )
                    testLog("Picker file ACTION_CLICK sent: $label")
                    return true
                }
                break
            }
            current = current.parent
        }

        // ACTION_CLICK may be exposed but not work on a Compose/custom row.
        // Gesture the actual selectable row when available, otherwise the filename bounds.
        val tapNode = clickableTarget ?: node
        val bounds = android.graphics.Rect()
        tapNode.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && bounds.width() >= 8 && bounds.height() >= 8) {
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()
            if (dispatchTap(x, y)) {
                WorkflowMemoryStore(this).remember(
                    root.packageName?.toString().orEmpty(),
                    sessionStore.state().name,
                    label,
                    node
                )
                testLog("Picker file gesture dispatched: $label • x=$x y=$y")
                return true
            }
        }

        return false
    }

    private fun isFileSelectionConfirmed(root: AccessibilityNodeInfo, fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.')
        val node = findNode(root, listOf(fileName, base))

        // Android Photo Picker does not have to expose the original filename.
        // Its accessibility semantics expose a generic "Selected" state and
        // a confirmation button after a media tile is selected.
        if (node == null && isDocumentPicker(root.packageName?.toString().orEmpty())) {
            val selected = containsAny(root, listOf("Selected"))
            val action = findNode(root, listOf("Done", "Add", "Open", "Select"))
            if (selected && action != null) {
                var candidate: AccessibilityNodeInfo? = action
                repeat(4) {
                    if (candidate == null) return@repeat
                    if (candidate!!.isEnabled && candidate!!.isClickable) return true
                    candidate = candidate!!.parent
                }
            }
        }

        if (node == null) return false

        // Native picker implementations vary. Accept any explicit selected/checked state
        // on the file node or a nearby ancestor.
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            if (current == null) return@repeat
            if (current!!.isSelected || current!!.isChecked) return true

            val text = current!!.text?.toString().orEmpty()
            val desc = current!!.contentDescription?.toString().orEmpty()
            val combined = "$text $desc".lowercase(Locale.getDefault())
            if (combined.contains("selected") || combined.contains("checked")) return true

            current = current!!.parent
        }

        // A picker can also expose an enabled confirmation action only after a valid
        // selection. Do not tap it here; merely use its enabled/clickable state as proof.
        val action = findNode(root, listOf(
            "Open", "Select", "Done", "Use this file", "Choose", "Select this file"
        ))
        if (action != null) {
            var candidate: AccessibilityNodeInfo? = action
            repeat(4) {
                if (candidate == null) return@repeat
                if (candidate!!.isEnabled && candidate!!.isClickable) return true
                candidate = candidate!!.parent
            }
        }

        return false
    }

    private fun scrollPickerTowardsFile(root: AccessibilityNodeInfo): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isScrollable && node.isEnabled) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(pending::addLast)
            }
        }
        return false
    }

    private fun clickByLabels(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val node = findNode(root, labels) ?: return false
        val label = node.text?.toString().orEmpty().ifBlank {
            node.contentDescription?.toString().orEmpty().ifBlank { labels.firstOrNull().orEmpty() }
        }
        floatingCursor.showForNode(node, "👆 " + label)

        // First use the accessibility click action. This is the safest path when
        // YouTube Studio exposes a real clickable node.
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    WorkflowMemoryStore(this).remember(
                        root.packageName?.toString().orEmpty(),
                        sessionStore.state().name,
                        label,
                        node
                    )
                    return true
                }
                break
            }
            current = current.parent
        }

        // Studio uses Compose/custom surfaces in some screens where text is visible
        // to Accessibility but ACTION_CLICK is not exposed. Prefer the nearest enabled
        // clickable ancestor's live bounds before falling back to the text node bounds.
        val tapNode = run {
            var candidate: AccessibilityNodeInfo? = node
            var best: AccessibilityNodeInfo? = null
            while (candidate != null) {
                if (candidate.isEnabled && candidate.isClickable) best = candidate
                candidate = candidate.parent
            }
            best ?: node
        }
        val bounds = android.graphics.Rect()
        tapNode.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && bounds.width() >= 4 && bounds.height() >= 4) {
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()
            if (dispatchTap(x, y)) {
                WorkflowMemoryStore(this).remember(
                    root.packageName?.toString().orEmpty(),
                    sessionStore.state().name,
                    label,
                    node
                )
                testLog("Gesture tap dispatched • $label • x=$x y=$y")
                return true
            }
        }

        return false
    }

    private fun setTextByLabels(root: AccessibilityNodeInfo, labels: List<String>, value: String): Boolean {
        if (value.isBlank()) return true
        val node = findNode(root, labels) ?: return false
        val target = if (node.isEditable) node else findEditableDescendant(node.parent ?: return false) ?: return false
        floatingCursor.showForNode(target, "✍️ entering text")
        val written = setText(target, value)
        if (written) {
            floatingCursor.setMessage("✓ text entered • verifying")
            WorkflowMemoryStore(this).remember(
                root.packageName?.toString().orEmpty(),
                sessionStore.state().name,
                node.text?.toString().orEmpty().ifBlank {
                    node.contentDescription?.toString().orEmpty().ifBlank { labels.firstOrNull().orEmpty() }
                },
                node
            )
        }
        return written
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        if (node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value
                    )
                }
            )
        ) return true

        // Compose/custom fields sometimes expose focus but not ACTION_SET_TEXT.
        // Focus the live field, then paste through the system clipboard.
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || bounds.width() < 4 || bounds.height() < 4) return false
        if (!dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())) return false

        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("NAX automation", value)
        )
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) return true

        // One more hierarchy-level paste attempt for fields wrapped by Compose.
        var parent = node.parent
        while (parent != null) {
            if (parent.isEditable && parent.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

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
        val remembered = runCatching {
            WorkflowMemoryStore(this).findNode(root, sessionStore.state().name, labels)
        }.getOrNull()
        if (remembered != null) return remembered

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

    private fun fieldContainsValue(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        expected: String
    ): Boolean {
        if (expected.isBlank()) return true
        val node = findNode(root, labels) ?: return false
        val editable = if (node.isEditable) node else findEditableDescendant(node.parent ?: return false) ?: return false
        return editable.text?.toString()?.trim() == expected.trim()
    }

    private fun containsAny(root: AccessibilityNodeInfo, labels: List<String>): Boolean =
        findNode(root, labels) != null

    private fun visibilityLabels(value: String): List<String> = when (value) {
        "PUBLIC" -> listOf("Public")
        "UNLISTED" -> listOf("Unlisted")
        else -> listOf("Private")
    }

    private fun isPublishedSignal(root: AccessibilityNodeInfo): Boolean =
        containsAny(root, listOf("Video published", "Published", "Upload complete", "Uploaded"))

    private fun isSendingComplete(root: AccessibilityNodeInfo): Boolean {
        val texts = collectNodeText(root)
        val hasSendingLabel = texts.any { it.lowercase().contains("sending file") }
        val has100 = texts.any { Regex("""100\s*%""").containsMatchIn(it.lowercase()) }
        return hasSendingLabel && has100
    }

    private fun findUploadObservation(root: AccessibilityNodeInfo): String? {
        val texts = collectNodeText(root)

        fun matches(value: String, keyword: String): Boolean =
            value.lowercase().contains(keyword)

        return texts.firstOrNull { matches(it, "sending file") }
            ?: texts.firstOrNull { matches(it, "uploading") }
            ?: texts.firstOrNull { matches(it, "preparing") }
            ?: texts.firstOrNull { matches(it, "remaining") }
            ?: texts.firstOrNull { Regex("""\d{1,3}\s*%""").containsMatchIn(it.lowercase()) }
    }

    private fun collectNodeText(root: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(result::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(result::add)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(pending::addLast)
            }
        }
        return result.distinct()
    }

    private fun scheduleMonitorCheck(item: UploadItem) {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = Runnable {
            if (!::sessionStore.isInitialized || sessionStore.itemId() != item.id) return@Runnable
            if (System.currentTimeMillis() - sessionStore.startedAt() > sessionTimeoutMs) {
                finishSession(item, "Automation timed out while monitoring upload")
                return@Runnable
            }
            val root = rootInActiveWindow
            val packageName = root?.packageName?.toString().orEmpty()
            if (root != null && isAutomationPackage(packageName)) {
                driveState(item, root)
            } else {
                handler.postDelayed({
                    if (::sessionStore.isInitialized && sessionStore.itemId() == item.id) {
                        scheduleMonitorCheck(item)
                    }
                }, 2000L)
            }
        }.also { handler.postDelayed(it, 2000L) }
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

        val elapsed = System.currentTimeMillis() - lastVisionRequestAt
        val waitMs = 15_000L - elapsed
        if (waitMs > 0L) {
            handler.postDelayed({ requestVisionFallback(item) }, waitMs)
            return
        }
        lastVisionRequestAt = System.currentTimeMillis()
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
                                item.visibility,
                                item.fileName
                            )
                            else -> GeminiVisionAgent(settings.apiKey, settings.model).analyze(
                                bitmap,
                                sessionStore.state(),
                                item.title.ifBlank { item.fileName },
                                item.visibility,
                                item.fileName,
                                WorkflowMemoryStore(this@NaxAccessibilityService).promptContext(
                                    rootInActiveWindow?.packageName?.toString().orEmpty(),
                                    sessionStore.state().name
                                )
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
                    if (!clicked) {
                        AgentVerifiedMemoryStore(this).rememberCandidate(
                            packageName = root.packageName?.toString().orEmpty(),
                            state = sessionStore.state().name,
                            action = action.action,
                            target = action.targetText ?: ("x=" + action.x + ",y=" + action.y),
                            value = action.value.orEmpty()
                        )
                    }

                    if (action.action == "SELECT_FILE" &&
                        sessionStore.state() == AutomationState.WAITING_FOR_PICKER
                    ) {
                        // A vision-selected file is not enough to advance immediately.
                        // Google Photos/DocumentsUI may require a separate Open/Done confirmation.
                        sessionStore.markPickerSelectionPending()
                        testLog("Vision selected target file; waiting for picker confirmation • " + item.fileName)
                        scheduleRetryWithoutVision(item)
                    } else {
                        advanceStateAfterVisionAction(action, clicked = true)
                    }
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
            AutomationState.FIND_CREATE -> sessionStore.setState(AutomationState.VERIFY_CREATE_MENU)
            AutomationState.VERIFY_CREATE_MENU -> sessionStore.setState(AutomationState.FIND_UPLOAD)
            AutomationState.FIND_UPLOAD -> sessionStore.setState(AutomationState.VERIFY_UPLOAD_PICKER)
            AutomationState.VERIFY_UPLOAD_PICKER -> sessionStore.setState(AutomationState.WAITING_FOR_PICKER)
            AutomationState.WAITING_FOR_PICKER -> sessionStore.setState(AutomationState.FILL_DETAILS)
            AutomationState.FILL_DETAILS -> sessionStore.setState(AutomationState.SET_VISIBILITY)
            AutomationState.SET_VISIBILITY -> sessionStore.setState(AutomationState.PUBLISH)
            AutomationState.PUBLISH -> {
                val target = action.targetText?.lowercase().orEmpty()
                if (target.contains("publish") || target.contains("upload") ||
                    target.contains("save") || target.contains("done")
                ) {
                    sessionStore.setState(AutomationState.MONITOR_UPLOAD)
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
        if (::floatingCursor.isInitialized) {
            floatingCursor.showAt(x, y, "👆 tapping here")
        }
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
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                floatingCursor.setMessage("✓ checking result")
                testLog("Gesture tap completed • x=$x y=$y")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                floatingCursor.setMessage("× tap cancelled • retrying")
                testLog("Gesture tap cancelled • x=$x y=$y")
            }
        }, handler)
        if (!accepted) {
            floatingCursor.setMessage("× tap rejected • retrying")
            testLog("Gesture tap rejected • x=$x y=$y")
        }
        return accepted
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
        val successful = message.startsWith("Verified upload", ignoreCase = true)
        if (item != null) {
            queueStore.update(
                item.copy(
                    status = if (successful) "UPLOADED" else "ERROR",
                    scheduledAt = if (successful) null else item.scheduledAt,
                    lastRunAt = System.currentTimeMillis(),
                    resultNote = message
                )
            )
        }
        val testMode = sessionStore.isTestMode()
        if (successful) {
            MindEngine.onSuccess(this, message)
        } else {
            MindEngine.onError(
                this,
                "Automation stopped: " + message,
                "Review NAX Mind timeline for the exact state and retry safely. No success is recorded without verification."
            )
        }
        AutomationLiveStore(this).finish(successful, message)
        if (testMode) {
            TestRunStore(this).finish(successful, message)
            // First-run test items are temporary and must not pollute the real queue.
            item?.id?.let { queueStore.remove(it) }
        }

        sessionStore.clear()
        floatingCursor.hide()
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null

        if (successful && !testMode) {
            val workflow = com.myaiagent.workflow.WorkflowStore(this).load()
            val candidates = if (workflow.enabled) {
                queueStore.load().filter { it.status == "QUEUED" || it.status == "SCHEDULED" }
            } else {
                queueStore.load()
            }
            val next = UploadQueueCoordinator.nextEligible(candidates)
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
        agentLoop.stop()
        if (::floatingCursor.isInitialized) floatingCursor.hide()
        visionExecutor.shutdownNow()
        agentExecutor.shutdownNow()
        super.onDestroy()
    }
}