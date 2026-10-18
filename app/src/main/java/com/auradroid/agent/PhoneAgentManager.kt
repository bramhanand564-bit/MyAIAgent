package com.auradroid.agent

import android.content.Context
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
 * Thin VLA executor: Nax Bot observes the screen, asks Gemini for one action,
 * executes that action, waits two seconds, and observes again.
 */
object PhoneAgentManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var agentJob: Job? = null
    @Volatile private var isPhoneAgentRunning = false

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

    fun stopPhoneAgent() {
        isPhoneAgentRunning = false
        agentJob?.cancel()
        agentJob = null
        AiOverlayHud.restorePodAfterTextInput()
        AiOverlayHud.setCursorOverlayVisible(false)
        AiOverlayHud.setPhoneVisionTaskActive(false)
        AiOverlayHud.collapseToCompactStage()
        emitStatus("Phone Agent Idle", "IDLE")
        emitLog("[VLA] Stopped by user.")
    }

    fun startAutonomousPhoneTask(context: Context, goal: String, skillKey: String = "general_phone_skill") {
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
        AiOverlayHud.setCursorOverlayVisible(true)
        AiOverlayHud.setPhoneVisionTaskActive(true)
        AiOverlayHud.collapseToCompactStage()
        emitStatus("Nax Bot is observing", "THINKING")
        emitLog("[VLA] Goal: ${goal.take(240)}")

        agentJob = scope.launch(Dispatchers.Main) {
            val history = mutableListOf<String>()
            var iteration = 0

            try {
                while (isPhoneAgentRunning) {
                    iteration += 1
                    emitStatus("Observing screen • $iteration", "THINKING")

                    val screenshot = touchService.captureScreenBitmapSuspend()
                    val uiTree = touchService.dumpUiTreeSummary()
                    val dimensions = touchService.getRealScreenDimensions()
                    val screenWidth = dimensions.first.toInt().coerceAtLeast(1)
                    val screenHeight = dimensions.second.toInt().coerceAtLeast(1)

                    val decision = try {
                        GeminiBrain.decideNextUiAction(
                            context = context,
                            goal = goal,
                            uiTreeSummary = uiTree,
                            screenshot = screenshot,
                            historySummary = history.takeLast(5).joinToString("\n"),
                            ragLessons = "",
                            escalateToPro = false,
                            screenWidthPx = screenWidth,
                            screenHeightPx = screenHeight
                        )
                    } finally {
                        try {
                            if (screenshot != null && !screenshot.isRecycled) screenshot.recycle()
                        } catch (_: Exception) {
                        }
                    }

                    if (!isPhoneAgentRunning) break

                    val action = decision.action.trim().uppercase(Locale.US)
                    if (action == "DONE" || decision.isDone) {
                        history.add("DONE: ${decision.reason}")
                        emitLog("[VLA] Goal completed by visual assessment: ${decision.reason}")
                        emitStatus("Task Complete!", "HAPPY")
                        break
                    }

                    val x = decision.x.coerceIn(0.5f, (screenWidth - 0.5f).coerceAtLeast(0.5f))
                    val y = decision.y.coerceIn(0.5f, (screenHeight - 0.5f).coerceAtLeast(0.5f))
                    val endX = decision.endX.coerceIn(0.5f, (screenWidth - 0.5f).coerceAtLeast(0.5f))
                    val endY = decision.endY.coerceIn(0.5f, (screenHeight - 0.5f).coerceAtLeast(0.5f))

                    emitLog("[VLA] $action at (${x.toInt()}, ${y.toInt()}): ${decision.reason}")

                    val succeeded = when (action) {
                        "CLICK" -> {
                            AiOverlayHud.moveBotAndShowTap(x, y, "TAP")
                            touchService.performHumanTap(x, y, 90L)
                        }

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

                        "SWIPE" -> {
                            AiOverlayHud.animateSwipe(x, y, endX, endY)
                            touchService.performHumanSwipe(x, y, endX, endY, 320L)
                        }

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
                AiOverlayHud.restorePodAfterTextInput()
                AiOverlayHud.setCursorOverlayVisible(false)
                AiOverlayHud.setPhoneVisionTaskActive(false)
                AiOverlayHud.collapseToCompactStage()
                if (agentJob?.isActive == true) {
                    emitStatus("Phone Agent Idle", "IDLE")
                }
            }
        }
    }
}
