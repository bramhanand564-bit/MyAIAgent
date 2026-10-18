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

    private fun missionStepKind(step: TaskMicroStep): String {
        val title = step.title.uppercase(Locale.US)
        val combined = (step.title + " " + step.goal).lowercase(Locale.US)
        return when {
            title.contains("SHARE") -> "SHARE_VIDEO"
            title.contains("COMMENT") -> "COMMENT_VIDEO"
            title.contains("LIKE") || title.contains("HEART") -> "LIKE_VIDEO"
            title.contains("PLAY") -> "PLAY_VIDEO"
            title.contains("SEARCH") || title.contains("FIND") -> "SEARCH"
            title.contains("OPEN_APP") || title.contains("LAUNCH") -> "OPEN_APP"
            combined.contains("share with") || combined.contains("send the video") -> "SHARE_VIDEO"
            combined.contains("comment") -> "COMMENT_VIDEO"
            combined.contains("like the") || combined.contains("heart button") -> "LIKE_VIDEO"
            combined.contains("play") || combined.contains("playing") -> "PLAY_VIDEO"
            combined.contains("search") || combined.contains("find") -> "SEARCH"
            else -> "OTHER"
        }
    }

    private fun toggleKindForLabel(rawLabel: String): String? {
        val label = rawLabel.lowercase(Locale.US)
        return when {
            label.contains("like") || label.contains("heart") || label.contains("unlike") -> "LIKE"
            label.contains("follow") || label.contains("unfollow") -> "FOLLOW"
            label.contains("subscribe") || label.contains("unsubscribe") -> "SUBSCRIBE"
            else -> null
        }
    }

    private fun isLikeControlAlreadyActive(uiTree: String): Boolean {
        return uiTree.lineSequence().any { rawLine ->
            val line = rawLine.lowercase(Locale.US)
            val isLikeControl = line.startsWith("btn[") &&
                (line.contains("like") || line.contains("heart") || line.contains("unlike"))
            isLikeControl && (
                line.contains("liked") || line.contains("unlike") ||
                    line.contains("selected=true") || line.contains("checked=true")
                )
        }
    }

    private fun isCommentVisible(uiTree: String, commentText: String): Boolean {
        val expected = commentText.trim()
        if (expected.isBlank()) return false
        return uiTree.lineSequence().any { line ->
            !line.trimStart().startsWith("INPUT[", ignoreCase = true) &&
                line.contains(expected, ignoreCase = true) &&
                (line.startsWith("TXT[", ignoreCase = true) ||
                    line.startsWith("BTN[", ignoreCase = true))
        }
    }

    private fun isShareSuccessVisible(uiTree: String): Boolean {
        return uiTree.lineSequence().any { rawLine ->
            val line = rawLine.lowercase(Locale.US)
            line.contains("message sent") || line.contains("sent") ||
                line.contains("shared successfully") || line.contains("shared with")
        }
    }

    private fun isCommentSubmitTarget(labelAndReason: String): Boolean {
        val text = labelAndReason.lowercase(Locale.US)
        return text.contains("post") || text.contains("send comment") ||
            text.contains("submit comment") || text.contains("publish comment")
    }

    private fun isShareSendTarget(labelAndReason: String): Boolean {
        val text = labelAndReason.lowercase(Locale.US)
        return text.trim() == "send" || text.contains("send button") ||
            text.contains("send to") || text.contains("tap send") ||
            text.contains("share send")
    }

    private fun extractCommentText(goal: String): String {
        val match = Regex("[\"']([^\"']{1,100})[\"']").find(goal)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank {
            if (goal.contains("comment", ignoreCase = true)) "Nice" else ""
        }
    }

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
        AiOverlayHud.setPhoneVisionTaskActive(true)
        val calibrated = isSkillCalibrated(context, skillKey)
        if (!calibrated) {
            emitLog("[Calibration] First run for '$skillKey'; phone vision stays in compact pod mode.")
        } else {
            emitLog("[Calibration] '$skillKey' verified; continuing in compact pod mode.")
        }
        AiOverlayHud.collapseToCompactStage()
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
                try {
                    GeminiBrain.buildMicroStepPlan(context, goal, planningTree, planningScreenshot)
                        .ifEmpty { listOf(TaskMicroStep("Complete requested task", goal, "Visible result matches the user request")) }
                } finally {
                    try { planningScreenshot?.recycle() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                emitLog("[Plan] Planner fallback: ${e.message ?: "unavailable"}")
                listOf(TaskMicroStep("Complete requested task", goal, "Visible result matches the user request"))
            }
            var activePlanIndex = 0
            val completedStepsSet = linkedSetOf<Int>()
            val completedPlanSteps = completedStepsSet
            var hasLikedVideoInCurrentTask = false
            var hasCommentedInCurrentTask = false
            var hasSharedInCurrentTask = false
            var commentSubmitAttempted = false
            var commentSubmitWaitCount = 0
            var commentBackPressed = false
            var shareSendAttempted = false
            val performedToggleTypes = linkedSetOf<String>()
            val requestedCommentText = extractCommentText(goal)

            fun finishPhoneTaskIfDone(): Boolean {
                if (activePlanIndex < microPlan.size ||
                    completedStepsSet.size < microPlan.size) return false
                taskFullyVerified = true
                markSkillCalibrated(context, skillKey)
                AiOverlayHud.updateTaskProgress("Task Complete", completedStepsSet.size, microPlan.size)
                emitLog("[PhoneAgent] All ${completedStepsSet.size} micro-step(s) are permanently latched complete.")
                emitStatus("Task Complete (${completedStepsSet.size}/${microPlan.size} ✅)", "HAPPY")
                return true
            }

            fun latchPlanStep(index: Int, detail: String): Boolean {
                if (index !in completedStepsSet) {
                    completedStepsSet.add(index)
                    emitLog("[Checklist] DONE ${index + 1}/${microPlan.size}: ${microPlan[index].title}. ${detail}")
                    actionHistory.add("LATCHED: ${microPlan[index].title} - ${detail}")
                }
                if (index == activePlanIndex) activePlanIndex = index + 1
                while (activePlanIndex < microPlan.size && completedStepsSet.contains(activePlanIndex)) {
                    activePlanIndex++
                }
                verificationFailures = 0
                lastVerificationHint = ""
                lastAnnouncedPlanIndex = -1
                val nextTitle = if (activePlanIndex < microPlan.size) {
                    microPlan[activePlanIndex].title
                } else {
                    "Task Complete"
                }
                AiOverlayHud.updateTaskProgress(nextTitle, completedStepsSet.size, microPlan.size)
                return finishPhoneTaskIfDone()
            }

            emitLog("[Plan] Created ${microPlan.size} micro-step(s) for: ${goal.take(100)}")
            microPlan.forEachIndexed { idx, item ->
                emitLog("[Plan] ${idx + 1}. ${item.title}")
            }

            try {
                for (step in 1..30) {
                    if (!isPhoneAgentRunning) break
                    while (activePlanIndex < microPlan.size && completedStepsSet.contains(activePlanIndex)) {
                        activePlanIndex++
                    }
                    if (activePlanIndex >= microPlan.size) {
                        finishPhoneTaskIfDone()
                        break
                    }
                    val currentMicroStep = microPlan[activePlanIndex]
                    val stepKind = missionStepKind(currentMicroStep)
                    if (lastAnnouncedPlanIndex != activePlanIndex) {
                        lastAnnouncedPlanIndex = activePlanIndex
                        val remainingSteps = (microPlan.size - completedPlanSteps.size).coerceAtLeast(0)
                        emitLog("[Checklist] ${completedPlanSteps.size}/${microPlan.size} complete • ${remainingSteps} left • NOW: ${currentMicroStep.title}")
                    }
                    AiOverlayHud.updateTaskProgress(currentMicroStep.title, completedPlanSteps.size, microPlan.size)

                    val uiTree = touchService.dumpUiTreeSummary()

                    // A completed side effect is latched before requesting another model action.
                    if (stepKind == "LIKE_VIDEO" &&
                        (hasLikedVideoInCurrentTask || isLikeControlAlreadyActive(uiTree))) {
                        hasLikedVideoInCurrentTask = true
                        performedToggleTypes.add("LIKE")
                        if (latchPlanStep(activePlanIndex, "Like is already active or was performed once")) break
                        delay(250)
                        continue
                    }
                    if (stepKind == "COMMENT_VIDEO" &&
                        (hasCommentedInCurrentTask || isCommentVisible(uiTree, requestedCommentText))) {
                        hasCommentedInCurrentTask = true
                        if (!commentBackPressed) {
                            touchService.performGlobalBack()
                            commentBackPressed = true
                        }
                        if (latchPlanStep(activePlanIndex, "Submitted comment is visible; comment sheet closed once")) break
                        delay(250)
                        continue
                    }
                    if (stepKind == "SHARE_VIDEO" &&
                        (hasSharedInCurrentTask || isShareSuccessVisible(uiTree))) {
                        hasSharedInCurrentTask = true
                        if (latchPlanStep(activePlanIndex, "Share send completed or Sent confirmation is visible")) break
                        delay(250)
                        continue
                    }
                    if (stepKind == "COMMENT_VIDEO" && commentSubmitAttempted && !hasCommentedInCurrentTask) {
                        if (commentSubmitWaitCount < 5) {
                            commentSubmitWaitCount++
                            emitLog("[Comment] Submit was already tapped; waiting for the visible comment confirmation without tapping again.")
                            delay(650)
                            continue
                        }
                        emitLog("[Comment] Comment was submitted but could not be verified in the visible list. Stopping instead of duplicating it.")
                        emitStatus("Comment Not Verified", "ERROR")
                        break
                    }

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
                    val screenDimensions = touchService.getRealScreenDimensions()
                    val screenWidthPx = screenDimensions.first.toInt().coerceAtLeast(1)
                    val screenHeightPx = screenDimensions.second.toInt().coerceAtLeast(1)
                    val decision = GeminiBrain.decideNextUiAction(
                        context = context,
                        goal = focusedStepGoal,
                        uiTreeSummary = uiTree,
                        screenshot = screenshot,
                        historySummary = actionHistory.takeLast(8).joinToString("\n"),
                        ragLessons = ragLessons,
                        escalateToPro = escalatePro,
                        screenWidthPx = screenWidthPx,
                        screenHeightPx = screenHeightPx
                    )

                    // Clamp every model-provided coordinate to current device pixel bounds.
                    val safeX = decision.x.coerceIn(0.5f, (screenWidthPx - 0.5f).coerceAtLeast(0.5f))
                    val safeY = decision.y.coerceIn(0.5f, (screenHeightPx - 0.5f).coerceAtLeast(0.5f))
                    val safeEndX = decision.endX.coerceIn(0.5f, (screenWidthPx - 0.5f).coerceAtLeast(0.5f))
                    val safeEndY = decision.endY.coerceIn(0.5f, (screenHeightPx - 0.5f).coerceAtLeast(0.5f))
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
                        try { screenshot?.recycle() } catch (_: Exception) {}
                        val verified = verify.isCompleted && verify.confidence >= 80
                        if (verified) {
                            actionHistory.add("VERIFIED: ${currentMicroStep.title} - ${verify.feedback}")
                            val taskComplete = latchPlanStep(
                                activePlanIndex,
                                "AI verification confidence ${verify.confidence} percent: ${verify.feedback}"
                            )
                            if (taskComplete) {
                                AutomationReliability.recordSuccessfulLesson(
                                    context = context,
                                    goal = goal,
                                    stateSignature = safeScreenSignature,
                                    lessonFix = "All ${completedStepsSet.size} micro-step(s) verified; last action: ${decision.reason}"
                                )
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
                    if (!decision.isDone && decision.action != "DONE") {
                        try { screenshot?.recycle() } catch (_: Exception) {}
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
                            val toggleKind = toggleKindForLabel(decision.targetNodeText)
                            if (toggleKind != null && performedToggleTypes.contains(toggleKind)) {
                                emitLog("[Toggle Guard] Skipped a second ${toggleKind.lowercase(Locale.US)} tap in this task.")
                                actionHistory.add("Step $step: repeat toggle blocked for ${toggleKind}")
                                delay(250)
                                continue
                            }
                            emitStatus("Tapping UI", "WORKING")
                            val clickedByText = if (decision.targetNodeText.isNotBlank()) {
                                touchService.clickNodeByTextOrBounds(decision.targetNodeText, safeX, safeY)
                            } else {
                                touchService.performHumanTap(safeX, safeY, 90L)
                            }
                            if (clickedByText && toggleKind != null) performedToggleTypes.add(toggleKind)

                            if (clickedByText && stepKind == "LIKE_VIDEO") {
                                hasLikedVideoInCurrentTask = true
                                performedToggleTypes.add("LIKE")
                                if (latchPlanStep(activePlanIndex, "Like action was performed once; repeat heart taps are forbidden")) break
                                delay(300)
                                continue
                            }

                            if (clickedByText && stepKind == "COMMENT_VIDEO" &&
                                isCommentSubmitTarget(decision.targetNodeText + " " + decision.reason)) {
                                if (!commentSubmitAttempted) {
                                    commentSubmitAttempted = true
                                    commentSubmitWaitCount = 0
                                    delay(650)
                                    val afterSubmitTree = touchService.dumpUiTreeSummary()
                                    if (isCommentVisible(afterSubmitTree, requestedCommentText)) {
                                        hasCommentedInCurrentTask = true
                                        if (!commentBackPressed) {
                                            touchService.performGlobalBack()
                                            commentBackPressed = true
                                        }
                                        if (latchPlanStep(activePlanIndex, "Submitted comment is visible; comment sheet closed once")) break
                                    } else {
                                        emitLog("[Comment] Submit clicked once; waiting for visible confirmation and blocking duplicate submission.")
                                    }
                                    delay(250)
                                    continue
                                }
                                emitLog("[Comment Guard] Comment submission already attempted; duplicate submit blocked.")
                                delay(250)
                                continue
                            }

                            if (clickedByText && stepKind == "SHARE_VIDEO" &&
                                isShareSendTarget(decision.targetNodeText + " " + decision.reason)) {
                                if (!shareSendAttempted) {
                                    shareSendAttempted = true
                                    hasSharedInCurrentTask = true
                                    if (latchPlanStep(activePlanIndex, "Send clicked once for the selected recipient")) break
                                    delay(300)
                                    continue
                                }
                                emitLog("[Share Guard] Duplicate Send action blocked.")
                                delay(250)
                                continue
                            }

                            actionHistory.add("Step $step: CLICK '${decision.targetNodeText}' @(${safeX.toInt()},${safeY.toInt()}) ok=$clickedByText")
                        }
                        "HOLD", "LONG_PRESS" -> {
                            emitStatus("Long-press UI", "WORKING")
                            val held = touchService.performCenterHold(safeX, safeY)
                            actionHistory.add("Step $step: HOLD center=(${safeX.toInt()},${safeY.toInt()}) ok=$held duration=750ms")
                        }
                        "VISION_BOX_TAP" -> {
                            val toggleKind = toggleKindForLabel(decision.targetNodeText)
                            if (toggleKind != null && performedToggleTypes.contains(toggleKind)) {
                                emitLog("[Toggle Guard] Skipped a second ${toggleKind.lowercase(Locale.US)} vision tap.")
                                actionHistory.add("Step $step: repeat vision toggle blocked for ${toggleKind}")
                                delay(250)
                                continue
                            }
                            emitStatus("Vision center tap", "WORKING")
                            val boxValid = decision.xmax > decision.xmin && decision.ymax > decision.ymin
                            val tapped = boxValid && touchService.performVisionBoxTap(
                                ymin = decision.ymin,
                                xmin = decision.xmin,
                                ymax = decision.ymax,
                                xmax = decision.xmax
                            )
                            if (tapped && toggleKind != null) performedToggleTypes.add(toggleKind)
                            if (tapped && stepKind == "LIKE_VIDEO") {
                                hasLikedVideoInCurrentTask = true
                                performedToggleTypes.add("LIKE")
                                if (latchPlanStep(activePlanIndex, "Like vision tap was performed once; repeat heart taps are forbidden")) break
                                delay(300)
                                continue
                            }
                            actionHistory.add("Step $step: VISION_BOX_TAP box=(${decision.xmin},${decision.ymin},${decision.xmax},${decision.ymax}) center formula used=$tapped")
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
                            val swiped = touchService.performHumanSwipe(safeX, safeY, endXVal, endYVal, 320L)
                            actionHistory.add("Step $step: SWIPE 320ms from (${safeX.toInt()},${safeY.toInt()}) to (${endXVal.toInt()},${endYVal.toInt()}) ok=$swiped")
                        }
                        "SCROLL_DOWN" -> {
                            emitStatus("Safe-zone scroll down", "WORKING")
                            val swiped = touchService.swipeScrollDown()
                            actionHistory.add("Step $step: SCROLL_DOWN safe zone x=72%, y=75%-24%, 320ms ok=$swiped")
                        }
                        "SCROLL_UP" -> {
                            emitStatus("Safe-zone scroll up", "WORKING")
                            val swiped = touchService.swipeScrollUp()
                            actionHistory.add("Step $step: SCROLL_UP safe zone x=72%, y=25%-75%, 320ms ok=$swiped")
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
                AiOverlayHud.setPhoneVisionTaskActive(false)
                AiOverlayHud.collapseToCompactStage()
            }
        }
    }
}
