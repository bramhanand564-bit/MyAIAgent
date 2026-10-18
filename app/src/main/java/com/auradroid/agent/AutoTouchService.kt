package com.auradroid.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.random.Random

class AutoTouchService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoTouchService? = null
            private set
    }

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    fun dumpUiTreeSummary(): String {
        val root = rootInActiveWindow ?: return "NO_ACTIVE_WINDOW"
        return try {
            val lines = mutableListOf<String>()
            traverseNode(root, lines, 0)
            if (lines.isEmpty()) "EMPTY_UI_TREE" else lines.take(55).joinToString("\n")
        } finally {
            root.recycle()
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, outLines: MutableList<String>, depth: Int) {
        if (node == null || outLines.size >= 55 || depth > 14) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val hasSize = rect.width() > 12 && rect.height() > 12
        val text = (node.text?.toString() ?: "").trim().replace("\n", " ")
        val desc = (node.contentDescription?.toString() ?: "").trim().replace("\n", " ")
        val label = when {
            text.isNotBlank() -> text.take(50)
            desc.isNotBlank() -> desc.take(50)
            else -> ""
        }
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable || label.isNotBlank()
        if (hasSize && isInteractive) {
            val tag = when {
                node.isEditable -> "INPUT"
                node.isClickable -> "BTN"
                else -> "TXT"
            }
            outLines.add("$tag[\"$label\"]@(\${rect.centerX()},\${rect.centerY()})")
        }
        for (i in 0 until node.childCount) {
            if (outLines.size >= 55) break
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                try { traverseNode(child, outLines, depth + 1) } finally { child.recycle() }
            }
        }
    }

    fun clickNodeByTextOrBounds(targetText: String, fallbackX: Float, fallbackY: Float): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            try {
                if (targetText.isNotBlank()) {
                    val matched = findNodeByTextRecursive(root, targetText.lowercase(Locale.US))
                    if (matched != null) {
                        try {
                            val rect = Rect()
                            matched.getBoundsInScreen(rect)
                            if (rect.width() > 8 && rect.height() > 8) {
                                val cx = rect.centerX().toFloat()
                                val cy = rect.centerY().toFloat()
                                AiOverlayHud.moveBotAndShowTap(cx, cy, targetText.take(18))
                                if (matched.isClickable && matched.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                                return performHumanTap(cx, cy, 90L)
                            }
                        } finally { matched.recycle() }
                    }
                }
            } finally { root.recycle() }
        }
        return performHumanTap(fallbackX, fallbackY, 95L)
    }

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo?, targetLower: String): AccessibilityNodeInfo? {
        if (node == null || targetLower.isBlank()) return null
        val t = (node.text?.toString() ?: "").lowercase(Locale.US)
        val d = (node.contentDescription?.toString() ?: "").lowercase(Locale.US)
        if ((t.isNotBlank() && t.contains(targetLower)) || (d.isNotBlank() && d.contains(targetLower))) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                try {
                    val found = findNodeByTextRecursive(child, targetLower)
                    if (found != null) return found
                } finally { child.recycle() }
            }
        }
        return null
    }

    fun performHumanTap(x: Float, y: Float, holdMs: Long = 90L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val jitterX = Random.nextFloat() * 4.8f - 2.4f
        val jitterY = Random.nextFloat() * 4.8f - 2.4f
        val safeX = (x + jitterX).coerceAtLeast(5f)
        val safeY = (y + jitterY).coerceAtLeast(5f)
        AiOverlayHud.moveBotAndShowTap(safeX, safeY, "Tap")
        val path = Path().apply {
            moveTo(safeX, safeY)
            lineTo(safeX + 1.1f, safeY - 0.8f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, holdMs.coerceIn(45L, 400L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performHumanSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 320L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        AiOverlayHud.moveBotAndShowTap(startX, startY, "Swipe")
        val path = Path().apply {
            moveTo(startX.coerceAtLeast(5f), startY.coerceAtLeast(5f))
            lineTo(endX.coerceAtLeast(5f), endY.coerceAtLeast(5f))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(120L, 1200L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun typeTextIntoFocusedOrTargetNode(targetHint: String, textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            var targetNode: AccessibilityNodeInfo? = null
            if (targetHint.isNotBlank()) targetNode = findNodeByTextRecursive(root, targetHint.lowercase(Locale.US))
            if (targetNode == null) targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (targetNode == null) targetNode = findFirstEditableNode(root)
            if (targetNode == null) return false
            try {
                val focused = targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
                }
                return (focused || targetNode.isFocused) && targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } finally {
                if (targetNode !== root) targetNode.recycle()
            }
        } finally { root.recycle() }
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                try {
                    val found = findFirstEditableNode(child)
                    if (found != null) return found
                } finally { child.recycle() }
            }
        }
        return null
    }

    fun performGlobalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performGlobalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    suspend fun captureScreenBitmapSuspend(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val bmp = try {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        try {
                            Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally { hardwareBuffer.close() }
                    } catch (_: Exception) { null }
                    if (cont.isActive) cont.resume(bmp) else bmp?.recycle()
                }
                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }
}
