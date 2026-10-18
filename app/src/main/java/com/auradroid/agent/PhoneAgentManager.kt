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
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Thin VLA executor: Nax Bot observes the screen, asks Gemini for one action,
 * executes that action, waits two seconds, and observes again.
 */
object PhoneAgentManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var agentJob: Job? = null
    @Volatile private var isPhoneAgentRunning = false
    @Volatile private var taskIsStuck = false
    @Volatile private var activeTaskGoal: String = ""
    @Volatile private var activeAppPackage: String = ""
    @Volatile private var lastHumanCorrectionAt: Long = 0L
    private var lastObservedFrame: Bitmap? = null

    private var onLog: ((String) -> Unit)? = null
    private var onStatus: ((String, String) -> Unit)? = null

    fun setListeners(
        logListener: (String) -> Unit,
        statusListener: (String, String) -> Unit
    ) {
        onLog = logListener
        onStatus = statusListener
    }

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

    fun isTaskRunning(): Boolean = isPhoneAgentRunning

    fun isTaskStuck(): Boolean = isPhoneAgentRunning && taskIsStuck

    private fun rememberObservedFrame(bitmap: Bitmap?) {
        val copy = try {
            bitmap?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: Exception) {
            null
        }
        val old = lastObservedFrame
        lastObservedFrame = copy
        try { old?.recycle() } catch (_: Exception) {}
    }

    private fun clearObservedFrame() {
        val old = lastObservedFrame
        lastObservedFrame = null
        try { old?.recycle() } catch (_: Exception) {}
    }

    /**
     * Accessibility's TYPE_VIEW_CLICKED event gives the activated node bounds rather than raw
     * finger coordinates. Persist the bounds center with that limitation made explicit.
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
        val now = System.currentTimeMillis()
        if (now - lastHumanCorrectionAt < 1500L) return
        lastHumanCorrectionAt = now

        val before = try {
            lastObservedFrame?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: Exception) { null }
        val goal = activeTaskGoal
        scope.launch {
            var after: Bitmap? = null
            try {
                delay(450L)
                after = AutoTouchService.instance?.captureScreenBitmapSuspend()
                val saved = withContext(Dispatchers.IO) {
                    AutomationReliability.saveHumanCorrectionEpisode(
                        context = context.applicationContext,
                        packageName = packageName,
                        taskGoal = goal,
                        beforeFrame = before,
                        afterFrame = after,
                        tapX = centerX,
                        tapY = centerY,
                        boundsText = boundsText,
                        buttonLabel = buttonLabel
                    )
                }
                if (saved) {
                    emitLog("[VisualMemory] Saved HUMAN_CORRECTION for ${packageName} at (${centerX.toInt()}, ${centerY.toInt()}). The coordinate is the accessibility node center.")
                } else {
                    emitLog("[VisualMemory] Could not save the human-correction screenshots.")
                }
            } catch (error: Exception) {
                emitLog("[VisualMemory] Capture error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                try { before?.recycle() } catch (_: Exception) {}
                try { after?.recycle() } catch (_: Exception) {}
            }
        }
    }

    fun stopPhoneAgent() {
        isPhoneAgentRunning = false
        taskIsStuck = false
        activeTaskGoal = ""
        activeAppPackage = ""
        agentJob?.cancel()
        agentJob = null
        clearObservedFrame()
        AiOverlayHud.restorePodAfterTextInput()
        AiOverlayHud.setCursorOverlayVisible(false)
        AiOverlayHud.setPhoneVisionTaskActive(false)
        AiOverlayHud.collapseToCompactStage()
        emitStatus("Phone Agent Idle", "IDLE")
        emitLog("[VLA] Stopped by user.")
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

        isPhoneAgentRunning = true
        taskIsStuck = false
        activeTaskGoal = goal.trim()
        activeAppPackage = touchService.currentForegroundPackageName()
        lastHumanCorrectionAt = 0L
        clearObservedFrame()
        AiOverlayHud.setCursorOverlayVisible(true)
        AiOverlayHud.setPhoneVisionTaskActive(true)
        AiOverlayHud.collapseToCompactStage()
        emitStatus("Nax Bot is observing", "THINKING")
        emitLog("[VLA] Goal: ${goal.take(240)}")

        agentJob = scope.launch(Dispatchers.Main) {
            val history = mutableListOf<String>()
            var iteration = 0
            var lastSignature = ""
            var repeatedScreenCount = 0
            var memoryLoadedForSignature = ""

            try {
                while (isPhoneAgentRunning) {
                    iteration += 1
                    emitStatus("Observing screen • $iteration", "THINKING")

                    val screenshot = touchService.captureScreenBitmapSuspend()
                    if (screenshot != null) rememberObservedFrame(screenshot)
                    val uiTree = touchService.dumpUiTreeSummary()
                    val dimensions = touchService.getRealScreenDimensions()
                    val screenWidth = dimensions.first.toInt().coerceAtLeast(1)
                    val screenHeight = dimensions.second.toInt().coerceAtLeast(1)
                    val screenSignature = touchService.currentPrivacySafeScreenSignature()
                    if (screenSignature.isNotBlank() && screenSignature == lastSignature) {
                        repeatedScreenCount += 1
                    } else {
                        repeatedScreenCount = 0
                        taskIsStuck = false
                        memoryLoadedForSignature = ""
                    }
                    if (screenSignature.isNotBlank()) lastSignature = screenSignature
                    if (repeatedScreenCount >= 3) {
                        taskIsStuck = true
                    }

                    val ragLessons = if (taskIsStuck) {
                        if (memoryLoadedForSignature.isBlank()) {
                            memoryLoadedForSignature = withContext(Dispatchers.IO) {
                                AutomationReliability.retrieveVisualMemoryForStuck(
                                    context.applicationContext,
                                    touchService.currentForegroundPackageName(),
                                    goal
                                )
                            }
                            if (memoryLoadedForSignature.isNotBlank()) {
                                emitLog("[VisualMemory] Loaded app-specific Golden Fix because the screen appears stuck.")
                            }
                        }
                        memoryLoadedForSignature
                    } else {
                        ""
                    }

                    val calibration = touchService.getScreenCalibrationSummary()
                    val decision = try {
                        GeminiBrain.decideNextUiAction(
                            context = context,
                            goal = goal,
                            uiTreeSummary = uiTree,
                            screenshot = screenshot,
                            historySummary = history.takeLast(5).joinToString("\n"),
                            ragLessons = ragLessons,
                            escalateToPro = false,
                            screenWidthPx = screenWidth,
                            screenHeightPx = screenHeight,
                            screenCalibration = calibration
                        )
                    } finally {
                        try {
                            if (screenshot != null && !screenshot.isRecycled) screenshot.recycle()
                        } catch (_: Exception) {
                        }
                    }

                    if (!isPhoneAgentRunning) break

                    val action = decision.action.trim().uppercase(Locale.US)
                    if (action == "DONE") {
                        history.add("DONE: ${decision.reason}")
                        emitLog("[VLA] Goal completed by visual assessment: ${decision.reason}")
                        emitStatus("Task Complete!", "HAPPY")
                        val completedPackage = touchService.currentForegroundPackageName()
                            .ifBlank { activeAppPackage }
                        val completedGoal = goal
                        scope.launch(Dispatchers.IO) {
                            val goldenFix = AutomationReliability.analyzeHumanCorrectionsAndSaveGoldenFix(
                                context.applicationContext,
                                completedPackage,
                                completedGoal
                            )
                            if (goldenFix.isNotBlank()) {
                                emitLog("[VisualMemory] Post-task deep analysis saved a Golden Fix for $completedPackage.")
                            }
                        }
                        break
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
                            val typed = touchService.typeTextIntoFocusedOrTargetNode(
                                targetHint = "",
                                textToType = decision.textToType
                            )
                            delay(250)
                            AiOverlayHud.restorePodAfterTextInput()
                            typed
                        }

                        "SWIPE" -> touchService.performHumanSwipe(x, y, endX, endY, 320L)

                        "BACK" -> touchService.performGlobalBack()
                        "HOME" -> touchService.performGlobalHome()
                        else -> {
                            emitLog("[VLA] Unsupported action '$action'. Asking Gemini to observe again.")
                            false
                        }
                    }

                    val historyLine = "$action success=$succeeded reason=${decision.reason.take(200)}"
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
                taskIsStuck = false
                activeTaskGoal = ""
                activeAppPackage = ""
                clearObservedFrame()
                AiOverlayHud.restorePodAfterTextInput()
                AiOverlayHud.setCursorOverlayVisible(false)
                AiOverlayHud.setPhoneVisionTaskActive(false)
                AiOverlayHud.collapseToCompactStage()
            }
        }
    }
}
