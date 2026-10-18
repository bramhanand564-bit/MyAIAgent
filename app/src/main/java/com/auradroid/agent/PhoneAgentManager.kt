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

data class PhoneTaskProgressItem(
    val title: String,
    val state: String
)

object PhoneAgentManager {
    private const val PREFS_NAME = "auradroid_phone_calibration_prefs"
    private const val KEY_CALIBRATED_SKILLS = "calibrated_phone_skills_csv"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var agentJob: Job? = null
    private var isPhoneAgentRunning: Boolean = false
    private var onLog: ((String) -> Unit)? = null
    private var onStatus: ((String, String) -> Unit)? = null
    private var onProgress: ((String, List<PhoneTaskProgressItem>, Int, Int) -> Unit)? = null

    private fun missionStepKind(step: TaskMicroStep): String {
        val title = step.title.uppercase(Locale.US)
        val combined = (step.title + " " + step.goal).lowercase(Locale.US)
        return when {
            title.contains("BOTTOM SEND") || title.contains("TAP SEND BUTTON") -> "SHARE_SEND"
            title.contains("SHARE") && (title.contains("FRIEND") || title.contains("RECIPIENT")) -> "SHARE_RECIPIENT"
            title.contains("CLOSE COMMENTS") || title.contains("CLOSE COMMENT") -> "CLOSE_COMMENTS"
            title.contains("COMMENTS") && title.contains("POST") -> "COMMENT_VIDEO"
            title.contains("COMMENT") -> "COMMENT_VIDEO"
            title.contains("LIKE") || title.contains("HEART") -> "LIKE_VIDEO"
            title.contains("1ST VIDEO") || title.contains("FIRST VIDEO") || title.contains("PLAY_VIDEO") -> "PLAY_VIDEO"
            title.contains("TOP SEARCH") || title.contains("SEARCH QUERY") -> "SEARCH_QUERY"
            title.contains("NAVIGATE") && title.contains("SEARCH") -> "NAVIGATE_SEARCH"
            title.contains("OPEN INSTAGRAM") || title.contains("OPEN APP") -> "OPEN_APP"
            combined.contains("bottom send button") -> "SHARE_SEND"
            combined.contains("share sheet") && combined.contains("select the") -> "SHARE_RECIPIENT"
            combined.contains("close the comment sheet") -> "CLOSE_COMMENTS"
            combined.contains("comment") -> "COMMENT_VIDEO"
            combined.contains("like the") || combined.contains("heart button") -> "LIKE_VIDEO"
            combined.contains("first video") || combined.contains("first matching video") -> "PLAY_VIDEO"
            combined.contains("search bar") && combined.contains("type exactly") -> "SEARCH_QUERY"
            combined.contains("navigate to instagram search") -> "NAVIGATE_SEARCH"
            combined.contains("open the instagram") -> "OPEN_APP"
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
            line.contains("message sent") ||
                Regex("\\bsent\\b").containsMatchIn(line) ||
                line.contains("shared successfully")
        }
    }

    private fun isCommentSubmitTarget(targetLabel: String, reason: String): Boolean {
        val label = targetLabel.trim().lowercase(Locale.US)
        val rationale = reason.lowercase(Locale.US)
        return label == "post" || label == "send" ||
            label.contains("post comment") || label.contains("submit comment") ||
            rationale.contains("submit the comment") || rationale.contains("tap post button") ||
            rationale.contains("click post button")
    }

    private fun isShareRecipientTarget(targetLabel: String, requestedRecipient: String): Boolean {
        val label = targetLabel.trim().lowercase(Locale.US)
        val recipient = requestedRecipient.trim().lowercase(Locale.US)
        if (label.isBlank() || recipient.isBlank()) return false
        val shortName = recipient.substringBefore("_").substringBefore(".")
        return label.contains(recipient) || (shortName.length >= 3 && label.contains(shortName))
    }

    private fun extractCommentText(goal: String): String {
        val markerIndex = goal.indexOf("comment", ignoreCase = true)
        if (markerIndex >= 0) {
            val commentClause = goal.substring((markerIndex + "comment".length).coerceAtMost(goal.length))
            val match = Regex("""["']([^"']{1,100})["']""").find(commentClause)
            val quoted = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (quoted.isNotBlank()) return quoted
        }
        return if (markerIndex >= 0) "Nice" else ""
    }

    private fun extractSearchQuery(goal: String): String {
        val markerIndex = goal.indexOf("search", ignoreCase = true)
        if (markerIndex < 0) return ""
        var value = goal.substring((markerIndex + "search".length).coerceAtMost(goal.length)).trim()
        val first = value.firstOrNull()
        if (first == '"' || first == '\'') {
            val end = value.indexOf(first, 1)
            if (end > 1) return value.substring(1, end).trim()
        }
        val stops = listOf(",", " then ", " and then ", " play ", " watch ", " like ", " comment ", " share ")
        var end = value.length
        for (stop in stops) {
            val at = value.indexOf(stop, ignoreCase = true)
            if (at >= 0 && at < end) end = at
        }
        value = value.substring(0, end).trim().trimEnd('.', ':', ';')
        return value.trim().trim('"', '\'')
    }

    private fun extractShareTarget(goal: String): String {
        val shareIndex = goal.indexOf("share with", ignoreCase = true)
        val sendIndex = goal.indexOf("send to", ignoreCase = true)
        val markerIndex = if (shareIndex >= 0) shareIndex else sendIndex
        if (markerIndex < 0) return ""
        val markerLength = if (shareIndex >= 0) 10 else 7
        var value = goal.substring((markerIndex + markerLength).coerceAtMost(goal.length)).trim()
        val first = value.firstOrNull()
        if (first == '"' || first == '\'') {
            val end = value.indexOf(first, 1)
            if (end > 1) return value.substring(1, end).trim()
        }
        val stops = listOf(",", ".", ";", " then ", " and then ", " using ")
        var end = value.length
        for (stop in stops) {
            val at = value.indexOf(stop, ignoreCase = true)
            if (at >= 0 && at < end) end = at
        }
        return value.substring(0, end).trim().trim('"', '\'')
    }

    fun setListeners(logListener: (String) -> Unit, statusListener: (String, String) -> Unit) {
        onLog = logListener
        onStatus = statusListener
    }

    fun setProgressListener(
        listener: ((String, List<PhoneTaskProgressItem>, Int, Int) -> Unit)?
    ) {
        onProgress = listener
    }

    private fun emitMissionProgress(
        heading: String,
        steps: List<PhoneTaskProgressItem>,
        completed: Int,
        total: Int
    ) {
        mainHandler.post { onProgress?.invoke(heading, steps, completed, total) }
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
        AiOverlayHud.setPhoneVisionTaskActive(false)
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
            var commentTextTyped = false
            var searchQueryTyped = false
            var shareSendAttempted = false
            val performedToggleTypes = linkedSetOf<String>()
            val requestedCommentText = extractCommentText(goal).ifBlank { "Nice" }
            val requestedSearchQuery = extractSearchQuery(goal)
            val requestedShareTarget = extractShareTarget(goal)

            fun publishProgress(heading: String = "🎯 Current Mission") {
                val items = microPlan.mapIndexed { index, item ->
                    val state = when {
                        completedStepsSet.contains(index) -> "DONE"
                        index == activePlanIndex -> "RUNNING"
                        else -> "PENDING"
                    }
                    val displayTitle = if (index == 2 && requestedSearchQuery.isNotBlank()) {
                        "Search \"$requestedSearchQuery\""
                    } else {
                        item.title
                    }
                    PhoneTaskProgressItem(displayTitle, state)
                }
                emitMissionProgress(heading, items, completedStepsSet.size, microPlan.size)
            }

            fun finishPhoneTaskIfDone(): Boolean {
                if (activePlanIndex < microPlan.size ||
                    completedStepsSet.size < microPlan.size) return false
                taskFullyVerified = true
                markSkillCalibrated(context, skillKey)
                AiOverlayHud.updateTaskProgress("Task Complete", completedStepsSet.size, microPlan.size)
                publishProgress("🎯 Task Complete! ✅")
                emitLog("[PhoneAgent] All ${completedStepsSet.size} atomic step(s) are permanently latched complete.")
                emitStatus("Task Complete! ✅", "HAPPY")
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
                publishProgress()
                return finishPhoneTaskIfDone()
            }

            publishProgress()
            emitLog("[Plan] Created ${microPlan.size} atomic micro-step(s) for: ${goal.take(100)}")
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

                    var uiTree = touchService.dumpUiTreeSummary()

                    if (stepKind == "OPEN_APP" &&
                        touchService.currentForegroundPackageName().contains("instagram", ignoreCase = true)) {
                        if (latchPlanStep(activePlanIndex, "Instagram is already in the foreground")) break
                        delay(150)
                        continue
                    }

                    if (stepKind == "NAVIGATE_SEARCH") {
                        var searchSurfaceVisible = touchService.hasSearchNavigationOrBar()
                        if (!searchSurfaceVisible) {
                            for (backAttempt in 0 until 2) {
                                touchService.performGlobalBack()
                                delay(800)
                                uiTree = touchService.dumpUiTreeSummary()
                                searchSurfaceVisible = touchService.hasSearchNavigationOrBar()
                                if (searchSurfaceVisible) break
                            }
                        }
                        if (searchSurfaceVisible) {
                            touchService.clickBottomSearchTab()
                            delay(650)
                            uiTree = touchService.dumpUiTreeSummary()
                            if (touchService.hasSearchNavigationOrBar()) {
                                if (latchPlanStep(activePlanIndex, "Search or Explore surface is visible")) break
                                delay(200)
                                continue
                            }
                        }
                    }

                    if (stepKind == "PLAY_VIDEO") {
                        val firstTile = touchService.findFirstVideoGridTileCenter()
                        if (firstTile == null) {
                            emitLog("[Target] No eligible video grid card is visible below the 35 percent screen threshold. Refusing raw coordinates and Meta AI summary targets.")
                            delay(350)
                            continue
                        }
                        if (touchService.clickFirstVideoGridTile()) {
                            emitLog("[Target] First eligible video grid tile tapped at (${firstTile.x.toInt()},${firstTile.y.toInt()}).")
                            delay(800)
                            if (latchPlanStep(activePlanIndex, "First eligible grid thumbnail tapped using live node bounds")) break
                            continue
                        }
                        emitLog("[Target] Grid card tap could not be dispatched. Retrying node lookup without using Gemini coordinates.")
                        delay(350)
                        continue
                    }

                    if (stepKind == "CLOSE_COMMENTS") {
                        touchService.performGlobalBack()
                        delay(500)
                        if (latchPlanStep(activePlanIndex, "Comment sheet closed with one Back action")) break
                        continue
                    }

                    if (stepKind == "SEARCH_QUERY" && searchQueryTyped) {
                        val submitted = touchService.clickKeyboardSearchAction()
                        if (submitted) {
                            delay(900)
                            if (latchPlanStep(activePlanIndex, "Search query submitted using the keyboard Search action")) break
                            continue
                        }
                    }

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
                        if (latchPlanStep(activePlanIndex, "Submitted comment is visible; duplicate comment is blocked")) break
                        delay(250)
                        continue
                    }
                    if ((stepKind == "SHARE_SEND" || stepKind == "SHARE_VIDEO") &&
                        (hasSharedInCurrentTask || isShareSuccessVisible(uiTree))) {
                        hasSharedInCurrentTask = true
                        if (latchPlanStep(activePlanIndex, "Send has completed or Sent confirmation is visible")) break
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
                        if (stepKind == "SEARCH_QUERY" && searchQueryTyped) {
                            append("\nThe requested search query is already typed. Do not type it again; submit only with the visible keyboard Search or Go action.")
                        }
                        if (stepKind == "COMMENT_VIDEO" && commentTextTyped) {
                            append("\nThe requested comment text is already typed. Do not replace or type it again; find and tap Post once.")
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

                    // Live accessibility bounds win first. Scale legacy 540x960 coordinates only as fallback.
                    val targetNodeCenter = when (stepKind) {
                        "PLAY_VIDEO" -> touchService.findFirstVideoGridTileCenter()
                        "SHARE_SEND" -> touchService.findBottomNodeCenterByExactLabel("Send")
                        "LIKE_VIDEO" -> touchService.findNodeCenterByExactLabel("Like", "Like reel", "Like post")
                        else -> if (decision.targetNodeText.isNotBlank()) {
                            touchService.findNodeCenterByText(decision.targetNodeText)
                        } else null
                    }
                    val scaledStart = touchService.scaleAiCoordinates(decision.x, decision.y)
                    val scaledEnd = touchService.scaleAiCoordinates(decision.endX, decision.endY)
                    val selectedX = targetNodeCenter?.x ?: scaledStart.x
                    val selectedY = targetNodeCenter?.y ?: scaledStart.y
                    val safeX = selectedX.coerceIn(0.5f, (screenWidthPx - 0.5f).coerceAtLeast(0.5f))
                    val safeY = selectedY.coerceIn(0.5f, (screenHeightPx - 0.5f).coerceAtLeast(0.5f))
                    val safeEndX = scaledEnd.x.coerceIn(0.5f, (screenWidthPx - 0.5f).coerceAtLeast(0.5f))
                    val safeEndY = scaledEnd.y.coerceIn(0.5f, (screenHeightPx - 0.5f).coerceAtLeast(0.5f))
                    val deltaX = abs(safeEndX - safeX)
                    val deltaY = abs(safeEndY - safeY)
                    emitLog("[${decision.modelUsed} • PhoneSense] Step $step: ${decision.action} @(${safeX.toInt()},${safeY.toInt()}) -> ${decision.reason}")

                    if (decision.isDone || decision.action == "DONE") {
                        if (stepKind == "SHARE_SEND" || stepKind == "SHARE_VIDEO") {
                            if (hasSharedInCurrentTask || isShareSuccessVisible(uiTree)) {
                                hasSharedInCurrentTask = true
                                if (latchPlanStep(activePlanIndex, "Final share step has a local Send or Sent confirmation")) break
                                continue
                            }
                            emitLog("[Share Guard] Final-step DONE is ignored until the exact Send button is clicked.")
                            try { screenshot?.recycle() } catch (_: Exception) {}
                            delay(300)
                            continue
                        }
                        if (stepKind == "COMMENT_VIDEO" &&
                            !hasCommentedInCurrentTask && !isCommentVisible(uiTree, requestedCommentText)) {
                            emitLog("[Comment Guard] Model requested DONE before the comment is visible in the comment list.")
                            actionHistory.add("Step $step: premature COMMENT DONE rejected")
                            try { screenshot?.recycle() } catch (_: Exception) {}
                            delay(300)
                            continue
                        }
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
                                        if (stepKind == "OPEN_APP") {
                                            if (latchPlanStep(activePlanIndex, "Launch succeeded and foreground package was verified")) break
                                            continue
                                        }
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
                            val clickedByText = when {
                                stepKind == "SHARE_SEND" -> touchService.clickBottomExactLabel("Send")
                                stepKind == "PLAY_VIDEO" -> touchService.clickFirstVideoGridTile()
                                stepKind == "SEARCH_QUERY" && searchQueryTyped ->
                                    touchService.clickKeyboardSearchAction() ||
                                        (safeY >= screenHeightPx * 0.52f &&
                                            safeX >= screenWidthPx * 0.42f &&
                                            touchService.performHumanTap(safeX, safeY, 90L))
                                stepKind == "LIKE_VIDEO" ->
                                    touchService.clickExactLabel("Like", "Like reel", "Like post")
                                        .takeIf { it } ?: touchService.clickNodeByTextOrBounds(
                                            decision.targetNodeText.ifBlank { "Like" }, safeX, safeY
                                        )
                                decision.targetNodeText.isNotBlank() ->
                                    touchService.clickNodeByTextOrBounds(decision.targetNodeText, safeX, safeY)
                                else -> touchService.performHumanTap(safeX, safeY, 90L)
                            }
                            if (clickedByText && toggleKind != null) performedToggleTypes.add(toggleKind)

                            if (clickedByText && stepKind == "LIKE_VIDEO") {
                                hasLikedVideoInCurrentTask = true
                                performedToggleTypes.add("LIKE")
                                if (latchPlanStep(activePlanIndex, "Like action was performed once; repeat heart taps are forbidden")) break
                                delay(250)
                                continue
                            }

                            if (clickedByText && stepKind == "SHARE_SEND") {
                                if (!shareSendAttempted) {
                                    shareSendAttempted = true
                                    hasSharedInCurrentTask = true
                                    if (latchPlanStep(activePlanIndex, "Exact Send accessibility target was clicked once")) break
                                }
                                delay(250)
                                continue
                            }

                            if (clickedByText && stepKind == "SHARE_RECIPIENT" &&
                                isShareRecipientTarget(decision.targetNodeText, requestedShareTarget)) {
                                if (latchPlanStep(activePlanIndex, "Requested recipient was selected in the Share sheet")) break
                                delay(250)
                                continue
                            }

                            if (clickedByText && stepKind == "SEARCH_QUERY" && searchQueryTyped) {
                                delay(900)
                                if (latchPlanStep(activePlanIndex, "Search query submitted using the keyboard Search action")) break
                                continue
                            }

                            if (clickedByText && stepKind == "COMMENT_VIDEO" &&
                                isCommentSubmitTarget(decision.targetNodeText, decision.reason)) {
                                if (!commentSubmitAttempted) {
                                    commentSubmitAttempted = true
                                    commentSubmitWaitCount = 0
                                    delay(650)
                                    val afterSubmitTree = touchService.dumpUiTreeSummary()
                                    if (isCommentVisible(afterSubmitTree, requestedCommentText)) {
                                        hasCommentedInCurrentTask = true
                                        if (latchPlanStep(activePlanIndex, "Submitted comment is visible; comment sheet remains open for the next step")) break
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
                            if (stepKind == "SHARE_SEND") {
                                if (touchService.clickBottomExactLabel("Send")) {
                                    hasSharedInCurrentTask = true
                                    shareSendAttempted = true
                                    if (latchPlanStep(activePlanIndex, "Exact Send node was clicked; mission completes immediately")) break
                                } else {
                                    emitLog("[Share Guard] Exact Send node is not visible. Refusing to use the model coordinate.")
                                }
                                delay(300)
                                continue
                            }
                            if (stepKind == "PLAY_VIDEO") {
                                if (touchService.clickFirstVideoGridTile()) {
                                    delay(800)
                                    if (latchPlanStep(activePlanIndex, "First eligible grid tile tapped by exact node bounds")) break
                                } else {
                                    emitLog("[Target] No safe video grid node was found. Refusing the raw vision coordinate.")
                                }
                                delay(300)
                                continue
                            }
                            if (stepKind == "LIKE_VIDEO" &&
                                touchService.clickExactLabel("Like", "Like reel", "Like post")) {
                                hasLikedVideoInCurrentTask = true
                                performedToggleTypes.add("LIKE")
                                if (latchPlanStep(activePlanIndex, "Like accessibility node tapped once")) break
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
                                delay(250)
                                continue
                            }
                            if (tapped && stepKind == "SHARE_RECIPIENT" &&
                                isShareRecipientTarget(decision.targetNodeText, requestedShareTarget)) {
                                if (latchPlanStep(activePlanIndex, "Requested share recipient selected")) break
                                continue
                            }
                            actionHistory.add("Step $step: VISION_BOX_TAP box=(${decision.xmin},${decision.ymin},${decision.xmax},${decision.ymax}) center formula used=$tapped")
                        }
                        "TYPE" -> {
                            if (stepKind == "SEARCH_QUERY" && searchQueryTyped) {
                                emitLog("[Search Guard] Query is already typed; duplicate typing is blocked.")
                                delay(300)
                                continue
                            }
                            if (stepKind == "COMMENT_VIDEO" && commentTextTyped) {
                                emitLog("[Comment Guard] Comment text is already typed; duplicate typing is blocked.")
                                delay(300)
                                continue
                            }
                            emitStatus("Typing Text", "WORKING")
                            AiOverlayHud.hidePodForFocusedTextInput()
                            delay(250)
                            val textForThisAction = when (stepKind) {
                                "SEARCH_QUERY" -> requestedSearchQuery.ifBlank { decision.textToType.ifBlank { goal } }
                                "COMMENT_VIDEO" -> requestedCommentText
                                else -> decision.textToType.ifBlank { goal }
                            }
                            val typedOk = touchService.typeTextIntoFocusedOrTargetNode(
                                targetHint = decision.targetNodeText,
                                textToType = textForThisAction
                            )
                            delay(350)
                            AiOverlayHud.restorePodAfterTextInput()
                            actionHistory.add("Step $step: TYPE action completed=$typedOk")
                            if (typedOk && stepKind == "SEARCH_QUERY") {
                                searchQueryTyped = true
                                val submitted = touchService.clickKeyboardSearchAction()
                                if (submitted) {
                                    delay(900)
                                    if (latchPlanStep(activePlanIndex, "Exact search query typed and submitted")) break
                                }
                                continue
                            }
                            if (typedOk && stepKind == "COMMENT_VIDEO") {
                                commentTextTyped = true
                            }
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
