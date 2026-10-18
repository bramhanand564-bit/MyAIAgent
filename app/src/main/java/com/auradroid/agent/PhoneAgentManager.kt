package com.auradroid.agent

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Thin Vision-Language-Action executor. It keeps only one in-memory reference frame
 * for a possible human correction. App memory is read only after a stuck signal.
 */
object PhoneAgentManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var agentJob: Job? = null
    @Volatile private var isPhoneAgentRunning = false
    @Volatile private var taskStuck = false

    private var activeContext: Context? = null
    private var activeGoal: String = ""
    private var activePackageName: String = ""
    private var latestObservedFrame: Bitmap? = null
    private var lastFrameFingerprint: Int? = null
    private var unchangedFrameCount = 0
    private var repeatedActionSignature = ""
    private var repeatedActionCount = 0
    private var failedActionCount = 0
    private var oneShotMemoryHints = ""
    private val pendingCorrectionFrames = mutableListOf<Bitmap>()
    private val pendingCorrectionNotes = mutableListOf<String>()

    private var onLog: ((String) -> Unit)? = null
    private var onStatus: ((String, String) -> Unit)? = null

    fun setListeners(
        logListener: (String) -> Unit,
        statusListener: (String, String) -> Unit
    ) {
        onLog = logListener
        onStatus = statusListener
    }

    fun isTaskRunning(): Boolean = isPhoneAgentRunning

    fun isTaskStuck(): Boolean = isPhoneAgentRunning && taskStuck

    private fun emitLog(message: String) {
        val line = "[${System.currentTimeMillis() % 100000}] $message"
        mainHandler.post { onLog?.invoke(line) }
    }

    private fun emitStatus(status: String, emotion: String) {
        mainHandler.post {
            onStatus?.invoke(status, emotion)
            AiOverlayHud.updateStatus(status, emotion)
        }
    }

    private fun updateLatestObservedFrame(frame: Bitmap?) {
        val replacement = try {
            if (frame != null && !frame.isRecycled) frame.copy(Bitmap.Config.ARGB_8888, false) else null
        } catch (_: Exception) {
            null
        }
        synchronized(pendingCorrectionFrames) {
            val old = latestObservedFrame
            latestObservedFrame = replacement
            try {
                if (old != null && !old.isRecycled) old.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun sampledFingerprint(bitmap: Bitmap?): Int? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width < 1 || bitmap.height < 1) return null
        return try {
            var value = 17
            val stepsX = 12
            val stepsY = 20
            for (iy in 0 until stepsY) {
                for (ix in 0 until stepsX) {
                    val x = ((ix + 0.5f) * bitmap.width / stepsX).toInt().coerceIn(0, bitmap.width - 1)
                    val y = ((iy + 0.5f) * bitmap.height / stepsY).toInt().coerceIn(0, bitmap.height - 1)
                    value = value * 31 + bitmap.getPixel(x, y)
                }
            }
            value
        } catch (_: Exception) {
            null
        }
    }

    private fun resetTaskMemoryState() {
        taskStuck = false
        activePackageName = ""
        lastFrameFingerprint = null
        unchangedFrameCount = 0
        repeatedActionSignature = ""
        repeatedActionCount = 0
        failedActionCount = 0
        oneShotMemoryHints = ""
        synchronized(pendingCorrectionFrames) {
            pendingCorrectionFrames.forEach {
                try { if (!it.isRecycled) it.recycle() } catch (_: Exception) {}
            }
            pendingCorrectionFrames.clear()
            pendingCorrectionNotes.clear()
            try {
                latestObservedFrame?.let { if (!it.isRecycled) it.recycle() }
            } catch (_: Exception) {
            }
            latestObservedFrame = null
        }
    }

    fun stopPhoneAgent() {
        isPhoneAgentRunning = false
        taskStuck = false
        agentJob?.cancel()
        agentJob = null
        AiOverlayHud.restorePodAfterTextInput()
        AiOverlayHud.setCursorOverlayVisible(false)
        AiOverlayHud.setPhoneVisionTaskActive(false)
        AiOverlayHud.collapseToCompactStage()
        emitStatus("Phone Agent Idle", "IDLE")
        emitLog("[VLA] Stopped by user.")
    }

    /**
     * Invoked from AccessibilityService only after repeated failure or a static screen.
     * TYPE_VIEW_CLICKED exposes the activated node bounds, not the raw physical finger point.
     */
    fun recordHumanCorrection(
        context: Context,
        packageName: String,
        centerX: Float,
        centerY: Float,
        boundsText: String,
        buttonLabel: String
    ) {
        if (!isTaskRunning() || !isTaskStuck()) return
        val before = synchronized(pendingCorrectionFrames) {
            try {
                latestObservedFrame?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {
                null
            }
        } ?: return

        activePackageName = packageName
        taskStuck = false
        unchangedFrameCount = 0
        val touchService = AutoTouchService.instance
        if (touchService == null) {
            try { before.recycle() } catch (_: Exception) {}
            return
        }

        scope.launch {
            try {
                delay(450L)
                val after = touchService.captureScreenBitmapSuspend()
                if (after == null) {
                    try { before.recycle() } catch (_: Exception) {}
                    emitLog("[Memory] Human correction detected but the after-screen could not be captured.")
                    return@launch
                }

                val saved = AutomationReliability.saveHumanCorrection(
                    context = context.applicationContext,
                    packageName = packageName,
                    beforeScreenshot = before,
                    targetCenterX = centerX,
                    targetCenterY = centerY,
                    targetLabel = buttonLabel,
                    targetBounds = boundsText,
                    afterScreenshot = after
                )
                if (saved) {
                    synchronized(pendingCorrectionFrames) {
                        pendingCorrectionFrames.add(before)
                        pendingCorrectionFrames.add(after.copy(Bitmap.Config.ARGB_8888, false))
                        pendingCorrectionNotes.add(
                            "HUMAN_CORRECTION app=$packageName target=$buttonLabel at node-center " +
                                "(${centerX.toInt()}, ${centerY.toInt()}) bounds=$boundsText"
                        )
                    }
                    oneShotMemoryHints = AutomationReliability.getAppSpecificMemoryHints(
                        context.applicationContext,
                        packageName,
                        activeGoal
                    )
                    emitLog("[Memory] Saved HUMAN_CORRECTION screenshots and node-center coordinates for $packageName.")
                } else {
                    try { before.recycle() } catch (_: Exception) {}
                }
                try { after.recycle() } catch (_: Exception) {}
            } catch (error: Exception) {
                try { if (!before.isRecycled) before.recycle() } catch (_: Exception) {}
                emitLog("[Memory] Human correction capture failed: ${error.message ?: "unknown error"}")
            }
        }
    }

    private fun scheduleGoldenFixAnalysis(context: Context, completionReason: String) {
        val frames: List<Bitmap>
        val notes: String
        synchronized(pendingCorrectionFrames) {
            frames = pendingCorrectionFrames.mapNotNull {
                try { if (!it.isRecycled) it.copy(Bitmap.Config.ARGB_8888, false) else null } catch (_: Exception) { null }
            }
            notes = pendingCorrectionNotes.joinToString("\n")
        }
        if (frames.isEmpty()) return
        val appName = activePackageName.ifBlank { "UnknownApp" }
        val goal = activeGoal
        scope.launch {
            try {
                val analysis = GeminiBrain.analyzeTrainingVideoFrames(
                    context = context.applicationContext,
                    appName = appName,
                    userDescription = "Completed task: $goal. Completion evidence: $completionReason. " +
                        "Human correction details: $notes",
                    frames = frames
                )
                val saved = AutomationReliability.saveGoldenFix(
                    context.applicationContext,
                    appName,
                    goal,
                    analysis
                )
                emitLog(if (saved) "[Memory] Post-task Golden Fix saved for $appName."
                    else "[Memory] Post-task analysis ran but Golden Fix could not be saved.")
            } catch (error: Exception) {
                emitLog("[Memory] Post-task Golden Fix analysis failed: ${error.message ?: "unknown error"}")
            } finally {
                frames.forEach {
                    try { if (!it.isRecycled) it.recycle() } catch (_: Exception) {}
                }
                synchronized(pendingCorrectionFrames) {
                    pendingCorrectionFrames.forEach {
                        try { if (!it.isRecycled) it.recycle() } catch (_: Exception) {}
                    }
                    pendingCorrectionFrames.clear()
                    pendingCorrectionNotes.clear()
                }
            }
        }
    }

    fun startAutonomousPhoneTask(context: Context, goal: String) {
        if (isPhoneAgentRunning) {
            emitLog("[VLA] A phone task is already running. Stop it before starting another.")
            return
        }
        if (goal.isBlank()) {
            emitStatus("Enter a task goal", "ERROR")
            return
        }
        val touchService = AutoTouchService.instance
        if (touchService == null) {
            emitLog("[VLA] Accessibility Service is OFF. Enable AutoTouchService in Tab 5.")
            emitStatus("Enable Accessibility!", "ERROR")
            return
        }

        resetTaskMemoryState()
        activeContext = context.applicationContext
        activeGoal = goal
        isPhoneAgentRunning = true
        AiOverlayHud.setCursorOverlayVisible(true)
        AiOverlayHud.setPhoneVisionTaskActive(true)
        AiOverlayHud.collapseToCompactStage()
        emitStatus("Nax Bot is observing", "THINKING")
        emitLog("[VLA] Goal: ${goal.take(240)}")

        agentJob = scope.launch(Dispatchers.Main) {
            val history = mutableListOf<String>()
            var iteration = 0
            var completionReason = ""
            try {
                while (isPhoneAgentRunning) {
                    iteration += 1
                    emitStatus("Observing screen ${iteration}", "THINKING")

                    // Collect full-screen pixel geometry and safe-area metadata before capturing.
                    val dimensions = touchService.getRealScreenDimensions()
                    val screenWidth = dimensions.first.toInt().coerceAtLeast(1)
                    val screenHeight = dimensions.second.toInt().coerceAtLeast(1)
                    val screenCalibration = touchService.getScreenCalibrationSummary()
                    val screenshot = touchService.captureScreenBitmapSuspend()
                    activePackageName = touchService.getActivePackageName().ifBlank { activePackageName }
                    val uiTree = touchService.dumpUiTreeSummary()
                    val fingerprint = sampledFingerprint(screenshot)
                    if (fingerprint != null && fingerprint == lastFrameFingerprint) {
                        unchangedFrameCount += 1
                    } else {
                        unchangedFrameCount = 0
                    }
                    lastFrameFingerprint = fingerprint
                    if (unchangedFrameCount >= 2 || failedActionCount >= 2 || repeatedActionCount >= 3) {
                        if (!taskStuck) emitLog("[Memory] Stuck signal detected. Loading app-specific memory on demand.")
                        taskStuck = true
                    }
                    updateLatestObservedFrame(screenshot)

                    val memoryHints = when {
                        oneShotMemoryHints.isNotBlank() -> oneShotMemoryHints.also { oneShotMemoryHints = "" }
                        taskStuck -> AutomationReliability.getAppSpecificMemoryHints(
                            context.applicationContext,
                            activePackageName,
                            goal
                        )
                        else -> ""
                    }
                    val memoryScreenshots = if (taskStuck || memoryHints.isNotBlank()) {
                        AutomationReliability.getAppMemoryScreenshots(
                            context.applicationContext,
                            activePackageName,
                            3
                        )
                    } else {
                        emptyList()
                    }
                    val decision = try {
                        GeminiBrain.decideNextUiAction(
                            context = context,
                            goal = goal,
                            uiTreeSummary = uiTree,
                            screenshot = screenshot,
                            historySummary = history.takeLast(5).joinToString("\n"),
                            ragLessons = memoryHints,
                            escalateToPro = false,
                            screenWidthPx = screenWidth,
                            screenHeightPx = screenHeight,
                            screenCalibration = touchService.getScreenCalibrationSummary(),
                            memoryScreenshots = memoryScreenshots
                        )
                    } finally {
                        try { if (screenshot != null && !screenshot.isRecycled) screenshot.recycle() } catch (_: Exception) {}
                        memoryScreenshots.forEach {
                            try { if (!it.isRecycled) it.recycle() } catch (_: Exception) {}
                        }
                    }

                    if (!isPhoneAgentRunning) break
                    val action = decision.action.trim().uppercase(Locale.US)
                    if (action == "DONE") {
                        completionReason = decision.reason
                        history.add("DONE: ${decision.reason}")
                        emitLog("[VLA] Goal completed by visual assessment: ${decision.reason}")
                        emitStatus("Task Complete!", "HAPPY")
                        scheduleGoldenFixAnalysis(context, completionReason)
                        break
                    }
                    if (action == "WAIT") {
                        emitLog("[VLA] No safe action parsed. Observing again.")
                        delay(2000L)
                        continue
                    }

                    val x = decision.x.coerceIn(0.5f, (screenWidth - 0.5f).coerceAtLeast(0.5f))
                    val y = decision.y.coerceIn(0.5f, (screenHeight - 0.5f).coerceAtLeast(0.5f))
                    val endX = decision.endX.coerceIn(0.5f, (screenWidth - 0.5f).coerceAtLeast(0.5f))
                    val endY = decision.endY.coerceIn(0.5f, (screenHeight - 0.5f).coerceAtLeast(0.5f))
                    emitLog("[VLA] $action at (${x.toInt()}, ${y.toInt()}): ${decision.reason}")

                    val succeeded = when (action) {
                        "CLICK" -> touchService.performHumanTap(x, y, 90L)
                        "TYPE" -> {
                            AiOverlayHud.hidePodForFocusedTextInput()
                            val typed = try {
                                touchService.typeTextIntoFocusedOrTargetNode("", decision.textToType)
                            } finally {
                                delay(250L)
                                AiOverlayHud.restorePodAfterTextInput()
                            }
                            typed
                        }
                        "SWIPE" -> touchService.performHumanSwipe(x, y, endX, endY, 320L)
                        "BACK" -> touchService.performGlobalBack()
                        "HOME" -> touchService.performGlobalHome()
                        else -> false
                    }

                    val signature = "$action:${(x / 8f).toInt()}:${(y / 8f).toInt()}:${decision.reason.lowercase(Locale.US).take(40)}"
                    if (signature == repeatedActionSignature) repeatedActionCount += 1
                    else {
                        repeatedActionSignature = signature
                        repeatedActionCount = 1
                    }
                    if (succeeded) failedActionCount = 0 else failedActionCount += 1
                    val historyLine = "$action success=$succeeded reason=${decision.reason.take(180)}"
                    history.add(historyLine)
                    while (history.size > 5) history.removeAt(0)
                    emitStatus(
                        when (action) {
                            "CLICK" -> "Tapped screen"
                            "TYPE" -> "Typed text"
                            "SWIPE" -> "Swiped screen"
                            "BACK" -> "Pressed Back"
                            "HOME" -> "Pressed Home"
                            else -> "Observing screen"
                        },
                        "WORKING"
                    )
                    delay(2000L)
                }
            } catch (_: CancellationException) {
                // User stop is expected and should not be surfaced as an agent error.
            } catch (error: Exception) {
                emitLog("[VLA] Error: ${error.message ?: error.javaClass.simpleName}")
                emitStatus("Phone Agent Error", "ERROR")
            } finally {
                isPhoneAgentRunning = false
                taskStuck = false
                AiOverlayHud.restorePodAfterTextInput()
                AiOverlayHud.setCursorOverlayVisible(false)
                AiOverlayHud.setPhoneVisionTaskActive(false)
                AiOverlayHud.collapseToCompactStage()
            }
        }
    }
}
