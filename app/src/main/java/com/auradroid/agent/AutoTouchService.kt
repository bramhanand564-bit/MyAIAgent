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
        @Volatile var instance: AutoTouchService? = null
            private set
        private const val CENTER_JITTER_PX = 1.5f
        private const val GESTURE_TAP_MS = 90L
        private const val GESTURE_HOLD_MS = 750L
        private const val GESTURE_SWIPE_MS = 320L
    }

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!AutomationReliability.isPassiveTeachModeEnabled(this)) return

        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank() || packageName == this.packageName) return
        val eventKind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            else -> return
        }

        val source = event.source
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow
            val node = source ?: root ?: return
            // Never learn typed contents from password, editable, email, phone, or payment fields.
            val sourceClass = node.className?.toString().orEmpty()
            val rawLabel = (node.text?.toString().orEmpty() + " " +
                node.contentDescription?.toString().orEmpty()).trim()
            val sensitive = node.isPassword || node.isEditable ||
                sourceClass.contains("EditText", ignoreCase = true) ||
                Regex("(?i)(password|passcode|\\bpin\\b|\\botp\\b|email|phone|bank|account number|credit card|debit card|cvv|security code)")
                    .containsMatchIn(rawLabel)
            if (sensitive) return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return
            val centerX = (rect.left + rect.right) / 2f
            val centerY = (rect.top + rect.bottom) / 2f
            val safeLabel = rawLabel.replace(Regex("\\s+"), " ").take(80)
            val screenSignature = buildPrivacySafeScreenSignature(root ?: node)
            AutomationReliability.recordShadowTeachEvent(
                context = this,
                packageName = packageName,
                eventType = eventKind,
                screenSignature = screenSignature,
                boundsText = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
                centerX = centerX,
                centerY = centerY,
                labelText = safeLabel
            )
        } catch (_: Exception) {
            // Passive training is best-effort and must never interrupt normal accessibility.
        } finally {
            try { source?.recycle() } catch (_: Exception) {}
            try { root?.recycle() } catch (_: Exception) {}
        }
    }

    private fun buildPrivacySafeScreenSignature(root: AccessibilityNodeInfo): String {
        val fragments = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 7 || fragments.size >= 40) return
            fragments.add(node.className?.toString().orEmpty())
            fragments.add(if (node.isClickable) "click" else "")
            fragments.add(if (node.isScrollable) "scroll" else "")
            if (!node.isPassword && !node.isEditable) {
                val label = (node.text?.toString().orEmpty() + " " +
                    node.contentDescription?.toString().orEmpty()).trim()
                if (label.isNotBlank() &&
                    !Regex("(?i)(password|passcode|\\bpin\\b|\\botp\\b|email|phone|bank|account number|credit card|debit card|cvv|security code)")
                        .containsMatchIn(label)) {
                    // Keep only a hash: raw screen text is never written to training storage.
                    fragments.add(label.hashCode().toString())
                }
            }
            for (i in 0 until node.childCount) {
                if (fragments.size >= 40) break
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                try { walk(child, depth + 1) } finally {
                    try { child?.recycle() } catch (_: Exception) {}
                }
            }
        }
        walk(root, 0)
        return "${root.packageName ?: "unknown"}:${fragments.joinToString("|").hashCode().toUInt().toString(16)}"
    }

    fun currentPrivacySafeScreenSignature(): String {
        val root = rootInActiveWindow ?: return ""
        return try {
            buildPrivacySafeScreenSignature(root)
        } catch (_: Exception) {
            ""
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    fun getRealScreenDimensions(): Pair<Float, Float> {
        val metrics = resources.displayMetrics
        return Pair(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
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
        val text = node.text?.toString().orEmpty().trim().replace("\n", " ")
        val desc = node.contentDescription?.toString().orEmpty().trim().replace("\n", " ")
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
            val cx = (rect.left.toFloat() + rect.right.toFloat()) / 2.0f
            val cy = (rect.top.toFloat() + rect.bottom.toFloat()) / 2.0f
            outLines.add("$tag[\"$label\"]@($cx,$cy)")
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
                                val cx = (rect.left.toFloat() + rect.right.toFloat()) / 2.0f
                                val cy = (rect.top.toFloat() + rect.bottom.toFloat()) / 2.0f
                                AiOverlayHud.moveBotAndShowTap(cx, cy, targetText.take(18))
                                if (matched.isClickable && matched.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                                return performHumanTap(cx, cy, GESTURE_TAP_MS)
                            }
                        } finally {
                            matched.recycle()
                        }
                    }
                }
            } finally {
                root.recycle()
            }
        }
        return performHumanTap(fallbackX, fallbackY, GESTURE_TAP_MS)
    }

    fun findNodeCenterByText(targetText: String): PointCenter? {
        if (targetText.isBlank()) return null
        val root = rootInActiveWindow ?: return null
        return try {
            val node = findNodeByTextRecursive(root, targetText.lowercase(Locale.US)) ?: return null
            try {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) null
                else PointCenter(
                    (rect.left.toFloat() + rect.right.toFloat()) / 2.0f,
                    (rect.top.toFloat() + rect.bottom.toFloat()) / 2.0f
                )
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    data class PointCenter(val x: Float, val y: Float)

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo?, targetLower: String): AccessibilityNodeInfo? {
        if (node == null || targetLower.isBlank()) return null
        val t = node.text?.toString().orEmpty().lowercase(Locale.US)
        val d = node.contentDescription?.toString().orEmpty().lowercase(Locale.US)
        if ((t.isNotBlank() && t.contains(targetLower)) || (d.isNotBlank() && d.contains(targetLower))) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                try {
                    val found = findNodeByTextRecursive(child, targetLower)
                    if (found != null) return found
                } finally {
                    child.recycle()
                }
            }
        }
        return null
    }

    private fun jitterCenter(value: Float): Float = value + Random.nextFloat() * (CENTER_JITTER_PX * 2.0f) - CENTER_JITTER_PX

    fun performHumanTap(x: Float, y: Float, holdMs: Long = GESTURE_TAP_MS): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        if (screenWidth <= 0f || screenHeight <= 0f) return false
        val centerX = x.coerceIn(0.5f, screenWidth - 0.5f)
        val centerY = y.coerceIn(0.5f, screenHeight - 0.5f)
        val safeX = jitterCenter(centerX).coerceIn(0.5f, screenWidth - 0.5f)
        val safeY = jitterCenter(centerY).coerceIn(0.5f, screenHeight - 0.5f)
        AiOverlayHud.animateHandTo(safeX, safeY, "TAP")
        val path = Path().apply {
            moveTo(safeX, safeY)
            lineTo(safeX, safeY)
        }
        val duration = holdMs.coerceIn(45L, 1200L)
        return dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0L, duration)
        ).build(), null, null)
    }

    fun performCenterHold(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        if (screenWidth <= 0f || screenHeight <= 0f) return false
        val centerX = x.coerceIn(0.5f, screenWidth - 0.5f)
        val centerY = y.coerceIn(0.5f, screenHeight - 0.5f)
        AiOverlayHud.animateHandTo(centerX, centerY, "HOLD")
        val path = Path().apply { moveTo(centerX, centerY); lineTo(centerX, centerY) }
        return dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0L, GESTURE_HOLD_MS)
        ).build(), null, null)
    }

    fun performVisionBoxTap(ymin: Float, xmin: Float, ymax: Float, xmax: Float): Boolean {
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        if (screenWidth <= 0f || screenHeight <= 0f) return false
        val top = ymin.coerceIn(0f, 1000f) / 1000.0f * screenHeight
        val left = xmin.coerceIn(0f, 1000f) / 1000.0f * screenWidth
        val bottom = ymax.coerceIn(0f, 1000f) / 1000.0f * screenHeight
        val right = xmax.coerceIn(0f, 1000f) / 1000.0f * screenWidth
        if (right <= left || bottom <= top) return false
        val exactCenterX = (left + right) / 2.0f
        val exactCenterY = (top + bottom) / 2.0f
        return performHumanTap(exactCenterX, exactCenterY, GESTURE_TAP_MS)
    }

    fun performHumanSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = GESTURE_SWIPE_MS): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        if (screenWidth <= 0f || screenHeight <= 0f) return false
        val sx = startX.coerceIn(1f, screenWidth - 1f)
        val sy = startY.coerceIn(1f, screenHeight - 1f)
        val ex = endX.coerceIn(1f, screenWidth - 1f)
        val ey = endY.coerceIn(1f, screenHeight - 1f)
        AiOverlayHud.animateSwipe(sx, sy, ex, ey)
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        return dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(120L, 1200L))
        ).build(), null, null)
    }

    fun swipeScrollDown(): Boolean {
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        val safeStartX = screenWidth * 0.72f
        return performHumanSwipe(safeStartX, screenHeight * 0.75f, safeStartX, screenHeight * 0.24f, GESTURE_SWIPE_MS)
    }

    fun swipeScrollUp(): Boolean {
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        val safeStartX = screenWidth * 0.72f
        return performHumanSwipe(safeStartX, screenHeight * 0.25f, safeStartX, screenHeight * 0.75f, GESTURE_SWIPE_MS)
    }

    fun resolveLaunchablePackage(appPackageOrLabel: String): String? {
        val needle = appPackageOrLabel.trim().lowercase(Locale.US)
            .removePrefix("open ").removePrefix("launch ").trim()
        if (needle.isBlank()) return null

        val aliases = listOf(
            "chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "youtube studio" to "com.google.android.apps.youtube.creator",
            "whatsapp" to "com.whatsapp",
            "google photos" to "com.google.android.apps.photos",
            "photos" to "com.google.android.apps.photos",
            "gmail" to "com.google.android.gm",
            "settings" to "com.android.settings",
            "play store" to "com.android.vending",
            "telegram" to "org.telegram.messenger"
        )
        val normalizedPackage = aliases.firstOrNull { (label, _) -> needle == label || needle.endsWith(" $label") }
            ?.second ?: needle
        try {
            if (normalizedPackage.contains('.') &&
                packageManager.getLaunchIntentForPackage(normalizedPackage) != null) {
                return normalizedPackage
            }
        } catch (_: Exception) {}

        return try {
            val apps = packageManager.getInstalledApplications(0)
            val launchable = apps.mapNotNull { info ->
                val pkg = info.packageName ?: return@mapNotNull null
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                val label = try { packageManager.getApplicationLabel(info).toString().lowercase(Locale.US) }
                    catch (_: Exception) { "" }
                Triple(pkg, label, launchIntent)
            }
            launchable.firstOrNull { it.first.equals(needle, true) || it.second == needle }?.first
                ?: launchable.firstOrNull { it.second.isNotBlank() && needle.length >= 3 && it.second.contains(needle) }?.first
        } catch (_: Exception) {
            null
        }
    }

    fun launchAppDirectly(packageNameOrLabel: String): Boolean {
        val resolvedPackage = resolveLaunchablePackage(packageNameOrLabel) ?: return false
        return try {
            val intent = packageManager.getLaunchIntentForPackage(resolvedPackage) ?: return false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun currentForegroundPackageName(): String {
        val root = rootInActiveWindow ?: return ""
        return try {
            root.packageName?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
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
        } finally {
            root.recycle()
        }
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
                } finally {
                    child.recycle()
                }
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
                        } finally {
                            hardwareBuffer.close()
                        }
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
