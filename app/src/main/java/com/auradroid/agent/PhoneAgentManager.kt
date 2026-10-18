package com.auradroid.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

object PhoneAgentManager {
    private const val PREFS_NAME = "auradroid_phone_calibration_prefs"
    private const val KEY_CALIBRATED_SKILLS = "calibrated_phone_skills_csv"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var agentJob: Job? = null
    private var isPhoneAgentRunning: Boolean = false
    private var onLog: ((String) -> Unit)? = null
    private var onStatus: ((String, String) -> Unit)? = null

    fun setListeners(logListener: (String) -> Unit, statusListener: (String, String) -> Unit) {
        onLog = logListener
        onStatus = statusListener
    }

    private fun emitLog(message: String) {
        val line = "[${System.currentTimeMillis() % 100000}] $message"
        mainHandler.post { onLog?.invoke(line) }
    }

    private fun emitStatus(statusText: String, emotionName: String) {
        mainHandler.post {
            onStatus?.invoke(statusText, emotionName)
            AiOverlayHud.updateStatus(statusText, emotionName)
        }
    }

    fun isSkillCalibrated(context: Context, skillKey: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CALIBRATED_SKILLS, "") ?: ""
        return raw.split(",").map { it.trim() }.contains(skillKey.trim().lowercase(Locale.US))
    }

    fun markSkillCalibrated(context: Context, skillKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CALIBRATED_SKILLS, "") ?: ""
        val set = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
        set.add(skillKey.trim().lowercase(Locale.US))
        prefs.edit().putString(KEY_CALIBRATED_SKILLS, set.joinToString(",")).apply()
    }

    fun stopPhoneAgent() {
        agentJob?.cancel()
        agentJob = null
        isPhoneAgentRunning = false
        AiOverlayHud.restorePodAfterTextInput()
        AiOverlayHud.setCursorOverlayVisible(false)
        emitStatus("Phone Agent Idle", "IDLE")
        emitLog("[PhoneAgent] Stopped.")
    }

    fun startAutonomousPhoneTask(context: Context, goal: String, skillKey: String = "general_phone_skill") {
        if (isPhoneAgentRunning) {
            emitLog("[PhoneAgent] Already running a task. Stop current task first.")
            return
        }

        val touchService = AutoTouchService.instance
        if (touchService == null) {
            emitLog("[PhoneAgent] ERROR: Accessibility Service (AutoTouchService) is OFF. Please enable it in Tab 5.")
            emitStatus("Enable Accessibility!", "ERROR")
            return
        }

        isPhoneAgentRunning = true
        AiOverlayHud.setCursorOverlayVisible(true)
        val calibrated = isSkillCalibrated(context, skillKey)
        if (!calibrated) {
            emitLog("[Calibration] First-Run for '$skillKey' -> Opening Live Calibration HUD...")
            AiOverlayHud.openForFirstRunLiveCalibration()
        } else {
            emitLog("[Calibration] '$skillKey' verified -> Running in Compact/Stealth HUD mode.")
            AiOverlayHud.collapseToCompactStage()
        }
        emitStatus("Phone Agent • Active", "WORKING")

        agentJob = scope.launch(Dispatchers.Main) {
            val actionHistory = mutableListOf<String>()
            var stuckCounter = 0
            var lastTreeSignature = ""
            var lastVerificationHint = ""
            var verificationFailures = 0
            var lastAnnouncedPlanIndex = -1
            var taskFullyVerified = false
            val microPlan = try {
                val planningTree = touchService.dumpUiTreeSummary()
                val planningScreenshot = touchService.captureScreenBitmapSuspend()
                GeminiBrain.buildMicroStepPlan(context, goal, planningTree, planningScreenshot)
                    .ifEmpty { listOf(TaskMicroStep("Complete requested task", goal, "Visible result matches the user request")) }
            } catch (e: Exception) {
                emitLog("[Plan] Planner fallback: ${e.message ?: "unavailable"}")
                listOf(TaskMicroStep("Complete requested task", goal, "Visible result matches the user request"))
            }
            var activePlanIndex = 0
            val completedPlanSteps = linkedSetOf<Int>()
            emitLog("[Plan] Created ${microPlan.size} micro-step(s) for: ${goal.take(100)}")
            microPlan.forEachIndexed { idx, item ->
                emitLog("[Plan] ${idx + 1}. ${item.title}")
            }

            try {
                for (step in 1..30) {
                    if (!isPhoneAgentRunning) break
                    if (activePlanIndex >= microPlan.size) {
                        taskFullyVerified = true
                        break
                    }
                    val currentMicroStep = microPlan[activePlanIndex]
                    if (lastAnnouncedPlanIndex != activePlanIndex) {
                        lastAnnouncedPlanIndex = activePlanIndex
                        emitLog("[Checklist] ${completedPlanSteps.size}/${microPlan.size} complete • NOW: ${currentMicroStep.title}")
                    }
                    AiOverlayHud.updateTaskProgress(currentMicroStep.title, completedPlanSteps.size, microPlan.size)

                    val uiTree = touchService.dumpUiTreeSummary()
                    val currentSignature = "${uiTree.hashCode()}"
                    if (currentSignature == lastTreeSignature) stuckCounter++ else stuckCounter = 0
                    lastTreeSignature = currentSignature

                    val escalatePro = stuckCounter >= 2
                    if (escalatePro) emitStatus("High-Brain Pro", "HIGH_BRAIN")
                    else emitStatus("Step $step • Thinking", "THINKING")

                    val screenshot = touchService.captureScreenBitmapSuspend()
                    val safeScreenSignature = touchService.currentPrivacySafeScreenSignature().ifBlank { currentSignature }
                    val ragLessons = AutomationReliability.retrieveRelevantRagLessons(context, goal, safeScreenSignature)
                    val focusedStepGoal = buildString {
                        append("OVERALL USER GOAL: ").append(goal)
                        append("\nCURRENT MICRO-STEP (").append(activePlanIndex + 1).append("/").append(microPlan.size).append("): ")
                            .append(currentMicroStep.title)
                        append("\nTARGET FOR THIS STEP: ").append(currentMicroStep.goal)
                        if (currentMicroStep.verificationHint.isNotBlank()) {
                            append("\nVISIBLE SUCCESS EVIDENCE: ").append(currentMicroStep.verificationHint)
                        }
                        if (lastVerificationHint.isNotBlank()) {
                            append("\nPREVIOUS VERIFICATION CORRECTION: ").append(lastVerificationHint)
                        }
                    }
                    val decision = GeminiBrain.decideNextUiAction(
                        context = context,
                        goal = focusedStepGoal,
                        uiTreeSummary = uiTree,
                        screenshot = screenshot,
                        historySummary = actionHistory.takeLast(8).joinToString("\n"),
                        ragLessons = ragLessons,
                        escalateToPro = escalatePro
                    )

                    val safeX = decision.x.toFloat()
                    val safeY = decision.y.toFloat()
                    val safeEndX = decision.endX.toFloat()
                    val safeEndY = decision.endY.toFloat()
                    val deltaX = abs(safeEndX - safeX)
                    val deltaY = abs(safeEndY - safeY)
                    emitLog("[${decision.modelUsed} • PhoneSense] Step $step: ${decision.action} @(${safeX.toInt()},${safeY.toInt()}) -> ${decision.reason}")

                    if (decision.isDone || decision.action == "DONE") {
                        val verify = GeminiBrain.verifyPhoneTaskCompletion(
                            context = context,
                            goal = currentMicroStep.goal + "\nSuccess evidence: " + currentMicroStep.verificationHint,
                            uiTreeSummary = uiTree,
                            screenshot = screenshot
                        )
                        val verified = verify.isCompleted && verify.confidence >= 80
                        if (verified) {
                            completedPlanSteps.add(activePlanIndex)
                            emitLog("[Checklist] ☑ ${currentMicroStep.title} (${verify.confidence}% confidence)")
                            actionHistory.add("VERIFIED: ${currentMicroStep.title} — ${verify.feedback}")
                            activePlanIndex++
                            verificationFailures = 0
                            lastVerificationHint = ""
                            AiOverlayHud.updateTaskProgress(
                                if (activePlanIndex < microPlan.size) microPlan[activePlanIndex].title else "Verified",
                                completedPlanSteps.size,
                                microPlan.size
                            )
                            if (activePlanIndex >= microPlan.size) {
                                taskFullyVerified = true
                                markSkillCalibrated(context, skillKey)
                                AutomationReliability.recordSuccessfulLesson(
                                    context = context,
                                    goal = goal,
                                    stateSignature = safeScreenSignature,
                                    lessonFix = "All ${completedPlanSteps.size} micro-step(s) verified; last action: ${decision.reason}"
                                )
                                emitLog("[PhoneAgent] Entire task verified (${verify.confidence}%): ${verify.feedback}")
                                emitStatus("Phone Task Done!", "HAPPY")
                                break
                            }
                            delay(350)
                            continue
                        } else {
                            verificationFailures++
                            lastVerificationHint = verify.nextHint.ifBlank { verify.feedback }
                            emitLog("[Verify] NOT complete (${verify.confidence}%): ${verify.feedback}. Retry ${verificationFailures}/2.")
                            actionHistory.add("VERIFY FAILED: ${lastVerificationHint}")
                            if (verificationFailures >= 2) {
                                emitLog("[PhoneAgent] Stopped safely: completion could not be verified; task was NOT marked successful.")
                                emitStatus("Verification Needed", "ERROR")
                                break
                            }
                            delay(500)
                            continue
                        }
                    }

                    when (decision.action) {
                        "LAUNCH_APP", "OPEN_APP" -> {
                            val requestedApp = decision.targetNodeText.ifBlank { decision.textToType }.trim()
                            val launchPackage = touchService.resolveLaunchablePackage(requestedApp)
                            if (launchPackage == null) {
                                emitLog("[Launch] Couldn't resolve installed app '$requestedApp'. No app was opened.")
                                actionHistory.add("Step $step: LAUNCH failed to resolve '$requestedApp'")
                            } else {
                                emitStatus("Opening $requestedApp", "WORKING")
                                val launchStarted = touchService.launchAppDirectly(launchPackage)
                                if (launchStarted) {
                                    delay(2500)
                                    val visiblePackage = touchService.currentForegroundPackageName()
                                    val launchVerified = visiblePackage == launchPackage ||
                                        visiblePackage.startsWith("$launchPackage.")
                                    if (launchVerified) {
                                        emitLog("[Launch] Verified '$requestedApp' in foreground after 2500 ms.")
                                        emitStatus("$requestedApp Open", "HAPPY")
                                        actionHistory.add("Step $step: LAUNCH $launchPackage verified foreground=$visiblePackage")
                                    } else {
                                        emitLog("[Launch] Launched '$launchPackage', but foreground verification saw '$visiblePackage'. Will re-check the screen before acting.")
                                        actionHistory.add("Step $step: LAUNCH started; foreground not yet verified ($visiblePackage)")
                                    }
                                } else {
                                    emitLog("[Launch] Android did not return a launch intent for '$launchPackage'.")
                                    actionHistory.add("Step $step: LAUNCH failed for $launchPackage")
                                }
                            }
                        }
                        "CLICK" -> {
                            emitStatus("Tapping UI", "WORKING")
                            val clickedByText = if (decision.targetNodeText.isNotBlank()) {
                                touchService.clickNodeByTextOrBounds(decision.targetNodeText, safeX, safeY)
                            } else touchService.performHumanTap(safeX, safeY, 90L)
                            actionHistory.add("Step $step: CLICK '${decision.targetNodeText}' @(${safeX.toInt()},${safeY.toInt()}) ok=$clickedByText")
                        }
                        "TYPE" -> {
                            emitStatus("Typing Text", "WORKING")
                            AiOverlayHud.hidePodForFocusedTextInput()
                            delay(250)
                            val typedOk = touchService.typeTextIntoFocusedOrTargetNode(
                                targetHint = decision.targetNodeText,
                                textToType = decision.textToType.ifBlank { goal }
                            )
                            delay(350)
                            AiOverlayHud.restorePodAfterTextInput()
                            actionHistory.add("Step $step: TYPE '${decision.textToType}' ok=$typedOk")
                        }
                        "SWIPE" -> {
                            emitStatus("Swiping Screen", "WORKING")
                            val endXVal = if (deltaX < 10f && deltaY < 10f) safeX else safeEndX
                            val endYVal = if (deltaX < 10f && deltaY < 10f) (safeY - 500f).coerceAtLeast(150f) else safeEndY
                            touchService.performHumanSwipe(safeX, safeY, endXVal, endYVal, 340L)
                            actionHistory.add("Step $step: SWIPE from (${safeX.toInt()},${safeY.toInt()}) to (${endXVal.toInt()},${endYVal.toInt()})")
                        }
                        "BACK" -> {
                            touchService.performGlobalBack()
                            actionHistory.add("Step $step: GLOBAL_BACK")
                        }
                        "HOME" -> {
                            touchService.performGlobalHome()
                            actionHistory.add("Step $step: GLOBAL_HOME")
                        }
                        else -> {
                            delay(1200)
                            actionHistory.add("Step $step: WAIT")
                        }
                    }
                    delay(1100)
                }
                if (!taskFullyVerified && isPhoneAgentRunning && activePlanIndex < microPlan.size) {
                    emitLog("[PhoneAgent] Step budget ended without verified completion; task was NOT marked successful.")
                    emitStatus("Task Incomplete", "ERROR")
                }
            } catch (e: Exception) {
                emitLog("[PhoneAgent] Error: ${e.message}")
                emitStatus("Phone Agent Error", "ERROR")
            } finally {
                isPhoneAgentRunning = false
                AiOverlayHud.restorePodAfterTextInput()
                AiOverlayHud.setCursorOverlayVisible(false)
            }
        }
    }
}
