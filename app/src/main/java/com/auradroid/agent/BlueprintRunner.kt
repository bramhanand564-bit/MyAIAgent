package com.auradroid.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class BlueprintRunResult(val success: Boolean, val summary: String, val completedSteps: Int)

/**
 * Replays only a blueprint that the user previewed and explicitly approved.
 * Sensitive gates are not bypassed; an ASK_USER step stops replay for a human.
 */
object BlueprintRunner {
    suspend fun run(
        context: Context,
        blueprint: AutomationBlueprint,
        onProgress: (String) -> Unit
    ): BlueprintRunResult {
        val service = AutoTouchService.instance
            ?: return BlueprintRunResult(false, "Enable the AuraDroid Accessibility Service first.", 0)
        if (AutoTouchService.isJobRunning) {
            return BlueprintRunResult(false, "Another agent job is running. Stop it before replay.", 0)
        }

        var completed = 0
        for ((index, step) in blueprint.steps.withIndex()) {
            if (step.action == BlueprintAction.ASK_USER) {
                val msg = "Paused at step ${index + 1}: human confirmation is required. Resume manually after reviewing the screen."
                TrainingMemoryStore(context).saveTaskEpisode("BLUEPRINT_PAUSED", msg, blueprint.name)
                return BlueprintRunResult(false, msg, completed)
            }
            onProgress("Step ${index + 1}/${blueprint.steps.size}: ${step.action.wireValue}")
            var result = perform(context, service, step)
            var attempt = 1
            while (!result.first) {
                val advice = SelfCorrectionPolicy.advise(result.second, attempt, maxRetries = 2)
                onProgress("Recovery: ${advice.message}")
                when (advice.action) {
                    CorrectionAction.RETRY_AFTER_WAIT -> {
                        delay(900L * attempt)
                        attempt++
                        result = perform(context, service, step)
                    }
                    CorrectionAction.REOBSERVE_AND_RETRY -> {
                        delay(600L)
                        attempt++
                        result = perform(context, service, step)
                    }
                    CorrectionAction.ASK_USER, CorrectionAction.STOP -> {
                        val summary = "Stopped at step ${index + 1}: ${advice.message} Detail: ${result.second}"
                        TrainingMemoryStore(context).saveTaskEpisode("BLUEPRINT_STOPPED", summary, blueprint.name)
                        return BlueprintRunResult(false, summary, completed)
                    }
                }
            }
            completed++
            delay(200L)
        }

        val summary = "Completed ${completed} approved step(s) in '${blueprint.name}'. Check the target app to confirm the result."
        TrainingMemoryStore(context).saveTaskEpisode("BLUEPRINT_COMPLETE", summary, blueprint.toJson())
        return BlueprintRunResult(true, summary, completed)
    }

    private suspend fun perform(
        context: Context,
        service: AutoTouchService,
        step: BlueprintStep
    ): Pair<Boolean, String> {
        return try {
            when (step.action) {
                BlueprintAction.WAIT -> {
                    delay(step.waitMs.coerceIn(0L, 60_000L))
                    true to "wait complete"
                }
                BlueprintAction.BACK -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) to "Android Back action failed."
                }
                BlueprintAction.SCROLL -> {
                    val root = service.rootInActiveWindow
                    val didScroll = root?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
                    if (didScroll) true to "scrolled"
                    else {
                        val (sw, sh) = service.getRealScreenDimensions()
                        val gestureOk = swipe(service, sw * 0.5f, sh * 0.78f, sw * 0.5f, sh * 0.34f)
                        gestureOk to if (gestureOk) "scrolled by gesture" else "scroll failed; screen may be locked or gesture unavailable"
                    }
                }
                BlueprintAction.TAP -> {
                    if (step.target.isNotBlank()) {
                        val root = service.rootInActiveWindow
                        val node = root?.let { findTarget(it, step.target, editable = false) }
                        val clickable = findClickableAncestor(node)
                        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            true to "clicked target"
                        } else {
                            false to "button not found: ${step.target}"
                        }
                    } else if (step.x == 0.0 && step.y == 0.0) {
                        false to "target missing: coordinate TAP needs explicit x and y; (0,0) is refused as an unsafe default"
                    } else {
                        val (sw, sh) = service.getRealScreenDimensions()
                        val x = (step.x * sw).coerceIn(1f, sw - 1f)
                        val y = (step.y * sh).coerceIn(1f, sh - 1f)
                        tap(service, x, y) to "coordinate tap failed"
                    }
                }
                BlueprintAction.TYPE -> {
                    if (step.text.isBlank()) return false to "target missing: TYPE step has no text"
                    val root = service.rootInActiveWindow ?: return false to "unexpected page: no active UI tree"
                    val node = findTarget(root, step.target, editable = true)
                    if (node == null) {
                        false to "element not found: editable field ${step.target.ifBlank { "(focused field)" }}"
                    } else {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.text)
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) to "could not type into field"
                    }
                }
                BlueprintAction.OPEN_URL -> {
                    val uri = Uri.parse(step.text.ifBlank { step.target })
                    if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                        false to "target missing: OPEN_URL requires a valid https/http URL"
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        delay(700L)
                        true to "opened URL"
                    }
                }
                BlueprintAction.VERIFY -> {
                    val root = service.rootInActiveWindow
                    val found = root != null && findTarget(root, step.target, editable = false) != null
                    found to if (found) "expected element is visible" else "expected element not found: ${step.target}"
                }
                BlueprintAction.ASK_USER -> false to "human verification required"
            }
        } catch (e: SecurityException) {
            false to "permission denied: ${e.message ?: "Android permission is missing"}"
        } catch (e: Exception) {
            false to "unexpected page or operation failure: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun findTarget(
        root: AccessibilityNodeInfo,
        query: String,
        editable: Boolean
    ): AccessibilityNodeInfo? {
        if (editable && query.isBlank()) {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        }
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val hint = node.hintText?.toString().orEmpty()
            val match = query.isNotBlank() &&
                (text.contains(query, true) || desc.contains(query, true) || hint.contains(query, true))
            if (editable && node.isEditable && (query.isBlank() || match)) return node
            if (!editable && match) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = visit(child)
                if (found != null) return found
            }
            return null
        }
        return visit(root)
    }

    private fun findClickableAncestor(start: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = start
        repeat(9) {
            if (current == null) return null
            if (current.isClickable && current.isEnabled) return current
            current = current?.parent
        }
        return start?.takeIf { it.isEnabled }
    }

    private suspend fun tap(service: AutoTouchService, x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 90L))
                    .build()
                val accepted = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription) {
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!accepted && cont.isActive) cont.resume(false)
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }

    private suspend fun swipe(
        service: AutoTouchService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 420L))
                .build()
            val accepted = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!accepted && cont.isActive) cont.resume(false)
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }
}
