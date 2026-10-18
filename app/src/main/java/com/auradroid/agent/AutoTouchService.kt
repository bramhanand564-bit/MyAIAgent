package com.auradroid.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class AutoTouchService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoTouchService? = null
            private set
        private const val GESTURE_TAP_MS = 90L
        private const val GESTURE_HOLD_MS = 750L
        private const val GESTURE_SWIPE_MS = 320L
    }

    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var lastAutomatedGestureAt: Long = 0L

    private fun markAutomatedGesture() {
        lastAutomatedGestureAt = System.currentTimeMillis()
    }

    private fun wasRecentAutomatedGesture(): Boolean =
        System.currentTimeMillis() - lastAutomatedGestureAt < 500L

    fun getActivePackageName(): String {
        val root = rootInActiveWindow ?: return ""
        return try {
            root.packageName?.toString().orEmpty()
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank() || packageName == this.packageName) return

        val source = event.source
        var root: AccessibilityNodeInfo? = null
        try {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                captureHumanCorrectionIfStuck(event, packageName, source)
            }

            if (!AutomationReliability.isPassiveTeachModeEnabled(this)) return
            val eventKind = when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
                AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
                else -> return
            }

            root = rootInActiveWindow
            val node = source ?: root ?: return
            // Never learn typed contents from password, editable, email, phone, or payment fields.
            val sourceClass = node.className?.toString().orEmpty()
            val rawLabel = (node.text?.toString().orEmpty() + " " +
                node.contentDescription?.toString().orEmpty()).trim()
            val sensitive = node.isPassword || node.isEditable ||
                sourceClass.contains("EditText", ignoreCase = true) ||
                containsSensitiveLabel(rawLabel)
            if (sensitive) return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return
            val centerX = ((rect.left + rect.right) / 2.0f).toFloat()
            val centerY = ((rect.top + rect.bottom) / 2.0f).toFloat()
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
            // Accessibility memory is best-effort and must never interrupt normal interaction.
        } finally {
            try { source?.recycle() } catch (_: Exception) {}
            try { root?.recycle() } catch (_: Exception) {}
        }
    }

    private fun captureHumanCorrectionIfStuck(
        event: AccessibilityEvent,
        packageName: String,
        source: AccessibilityNodeInfo?
    ) {
        if (!PhoneAgentManager.isTaskRunning() || !PhoneAgentManager.isTaskStuck()) return
        if (wasRecentAutomatedGesture()) return
        val node = source ?: return
        val className = node.className?.toString().orEmpty()
        val rawLabel = (node.text?.toString().orEmpty() + " " +
            node.contentDescription?.toString().orEmpty()).trim()
        if (node.isPassword || node.isEditable ||
            className.contains("EditText", ignoreCase = true) ||
            containsSensitiveLabel(rawLabel)) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        // TYPE_VIEW_CLICKED reports the activated node and its bounds, not the raw finger location.
        val centerX = ((bounds.left + bounds.right) / 2.0f).toFloat()
        val centerY = ((bounds.top + bounds.bottom) / 2.0f).toFloat()
        val safeLabel = rawLabel.replace(Regex("\\s+"), " ").take(100)
        PhoneAgentManager.recordHumanCorrection(
            context = applicationContext,
            packageName = packageName,
            centerX = centerX,
            centerY = centerY,
            boundsText = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
            buttonLabel = safeLabel.ifBlank { className.take(80) }
        )
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
                    !containsSensitiveLabel(label)) {
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
        return "${root.packageName ?: "unknown"}:${fragments.joinToString(",").hashCode().toUInt().toString(16)}"
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
        return try {
            val point = Point()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(point)
            if (point.x > 0 && point.y > 0) {
                Pair(point.x.toFloat(), point.y.toFloat())
            } else {
                val metrics = resources.displayMetrics
                Pair(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
            }
        } catch (_: Exception) {
            val metrics = resources.displayMetrics
            Pair(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
        }
    }

    fun getScreenCalibrationSummary(): String {
        val (realWidth, realHeight) = getRealScreenDimensions()
        var statusTop = 0
        var navigationBottom = 0
        var cutoutSummary = "none"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val manager = getSystemService(WINDOW_SERVICE) as WindowManager
                val metrics = manager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
                statusTop = insets.top
                navigationBottom = insets.bottom
                val cutoutRects = metrics.windowInsets.displayCutout?.boundingRects.orEmpty()
                if (cutoutRects.isNotEmpty()) {
                    cutoutSummary = cutoutRects.joinToString(";") {
                        "${it.left},${it.top},${it.right},${it.bottom}"
                    }
                }
            } catch (_: Exception) {
                // Keep best-effort metrics if vendor WindowInsets are inaccessible.
            }
        } else {
            statusTop = readSystemDimension("status_bar_height")
            navigationBottom = readSystemDimension("navigation_bar_height")
        }
        return "model=${Build.MODEL}; realWidth=${realWidth.toInt()}; realHeight=${realHeight.toInt()}; statusBarTopPx=$statusTop; navigationBarBottomPx=$navigationBottom; displayCutoutRects=$cutoutSummary. Coordinates are full-screen physical pixels."
    }

    private fun readSystemDimension(name: String): Int {
        return try {
            val resourceId = resources.getIdentifier(name, "dimen", "android")
            if (resourceId == 0) 0 else resources.getDimensionPixelSize(resourceId)
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Gemini may return the historical 540 by 960 design-space coordinates.
     * Callers must prefer node bounds before using this fallback conversion.
     */
    fun scaleAiCoordinates(aiX: Float, aiY: Float): PointCenter {
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        if (screenWidth <= 0f || screenHeight <= 0f) return PointCenter(aiX, aiY)
        val isLegacyDesignSpace = screenWidth != 540f && screenHeight != 960f &&
            aiX in 0f..540f && aiY in 0f..960f
        return if (isLegacyDesignSpace) {
            PointCenter(
                (aiX / 540f * screenWidth).coerceIn(0.5f, screenWidth - 0.5f),
                (aiY / 960f * screenHeight).coerceIn(0.5f, screenHeight - 0.5f)
            )
        } else {
            PointCenter(
                aiX.coerceIn(0.5f, screenWidth - 0.5f),
                aiY.coerceIn(0.5f, screenHeight - 0.5f)
            )
        }
    }

    fun findNodeCenterByExactLabel(vararg labels: String): PointCenter? {
        val targetLabels = labels.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.toSet()
        if (targetLabels.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        return try {
            val node = findExactNodeRecursive(root, targetLabels, requireClickable = true)
                ?: findExactNodeRecursive(root, targetLabels, requireClickable = false)
                ?: return null
            try {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) null else PointCenter(
                    (rect.left + rect.right) / 2f,
                    (rect.top + rect.bottom) / 2f
                )
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    fun clickExactLabel(vararg labels: String): Boolean {
        val targetLabels = labels.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.toSet()
        if (targetLabels.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        try {
            val matched = findExactNodeRecursive(root, targetLabels, requireClickable = true)
                ?: findExactNodeRecursive(root, targetLabels, requireClickable = false)
                ?: return false
            try {
                val rect = Rect()
                matched.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) return false
                val cx = (rect.left + rect.right) / 2f
                val cy = (rect.top + rect.bottom) / 2f
                AiOverlayHud.moveBotAndShowTap(cx, cy, labels.firstOrNull().orEmpty().take(18))
                if (matched.isClickable && matched.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                return performHumanTap(cx, cy, GESTURE_TAP_MS)
            } finally {
                matched.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    fun findBottomNodeCenterByExactLabel(
        label: String,
        minimumTopFraction: Float = 0.65f
    ): PointCenter? {
        val target = label.trim().lowercase(Locale.US)
        if (target.isBlank()) return null
        val root = rootInActiveWindow ?: return null
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        fun findBottom(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 14) return null
            val text = node.text?.toString().orEmpty().trim().lowercase(Locale.US)
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase(Locale.US)
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val matches = text == target || desc == target
            if (matches && rect.top >= screenHeight * minimumTopFraction &&
                rect.width() > 8 && rect.height() > 8) {
                return AccessibilityNodeInfo.obtain(node)
            }
            for (index in 0 until node.childCount) {
                val child = try { node.getChild(index) } catch (_: Exception) { null }
                if (child != null) {
                    try {
                        val found = findBottom(child, depth + 1)
                        if (found != null) return found
                    } finally {
                        child.recycle()
                    }
                }
            }
            return null
        }
        return try {
            val node = findBottom(root, 0) ?: return null
            try {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) null
                else PointCenter((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    fun clickBottomExactLabel(
        label: String,
        minimumTopFraction: Float = 0.65f
    ): Boolean {
        val target = label.trim().lowercase(Locale.US)
        if (target.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val (_, screenHeight) = getRealScreenDimensions()
        fun findBottom(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 14) return null
            val text = node.text?.toString().orEmpty().trim().lowercase(Locale.US)
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase(Locale.US)
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if ((text == target || desc == target) &&
                rect.top >= screenHeight * minimumTopFraction &&
                rect.width() > 8 && rect.height() > 8) {
                return AccessibilityNodeInfo.obtain(node)
            }
            for (index in 0 until node.childCount) {
                val child = try { node.getChild(index) } catch (_: Exception) { null }
                if (child != null) {
                    try {
                        val found = findBottom(child, depth + 1)
                        if (found != null) return found
                    } finally {
                        child.recycle()
                    }
                }
            }
            return null
        }
        try {
            val node = findBottom(root, 0) ?: return false
            try {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val cx = (rect.left + rect.right) / 2f
                val cy = (rect.top + rect.bottom) / 2f
                AiOverlayHud.moveBotAndShowTap(cx, cy, label.take(18))
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                return performHumanTap(cx, cy, GESTURE_TAP_MS)
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun findExactNodeRecursive(
        node: AccessibilityNodeInfo?,
        labels: Set<String>,
        requireClickable: Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString().orEmpty().trim().lowercase(Locale.US)
        val description = node.contentDescription?.toString().orEmpty().trim().lowercase(Locale.US)
        val exactMatch = text in labels || description in labels
        if (exactMatch && (!requireClickable || node.isClickable)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = try { node.getChild(index) } catch (_: Exception) { null }
            if (child != null) {
                try {
                    val found = findExactNodeRecursive(child, labels, requireClickable)
                    if (found != null) return found
                } finally {
                    child.recycle()
                }
            }
        }
        return null
    }

    fun hasSearchNavigationOrBar(): Boolean {
        val root = rootInActiveWindow ?: return false
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        fun walk(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 12) return false
            val text = node.text?.toString().orEmpty().trim().lowercase(Locale.US)
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase(Locale.US)
            val combined = "$text $desc"
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val inBottomNavigation = rect.top >= screenHeight * 0.70f
            val isSearchEntry = node.isEditable &&
                (combined.contains("search") || combined.contains("find"))
            val isSearchLabel = text == "search" || desc == "search" ||
                text == "explore" || desc == "explore" ||
                text == "search and explore" || desc == "search and explore" ||
                text == "search & explore" || desc == "search & explore"
            if (isSearchEntry || isSearchLabel || (inBottomNavigation &&
                    (combined.contains("search") || combined.contains("explore")))) return true
            for (index in 0 until node.childCount) {
                val child = try { node.getChild(index) } catch (_: Exception) { null }
                if (child != null) {
                    try {
                        if (walk(child, depth + 1)) return true
                    } finally {
                        child.recycle()
                    }
                }
            }
            return false
        }
        return try { walk(root, 0) } finally { root.recycle() }
    }

    fun clickKeyboardSearchAction(): Boolean {
        val root = rootInActiveWindow ?: return false
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        try {
            // Use the actual visible keyboard Search or Go accessibility node instead of a guessed key constant.

            fun findImeAction(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
                if (node == null || depth > 12) return null
                val text = node.text?.toString().orEmpty().trim().lowercase(Locale.US)
                val desc = node.contentDescription?.toString().orEmpty().trim().lowercase(Locale.US)
                val isSearchAction = text in setOf("search", "go", "enter", "submit") ||
                    desc in setOf("search", "go", "enter", "submit")
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val inKeyboardActionZone = rect.top >= screenHeight * 0.52f &&
                    rect.left >= screenWidth * 0.42f && rect.width() < screenWidth * 0.42f &&
                    rect.width() > 8 && rect.height() > 8
                if (isSearchAction && inKeyboardActionZone) {
                    return AccessibilityNodeInfo.obtain(node)
                }
                for (index in 0 until node.childCount) {
                    val child = try { node.getChild(index) } catch (_: Exception) { null }
                    if (child != null) {
                        try {
                            val found = findImeAction(child, depth + 1)
                            if (found != null) return found
                        } finally {
                            child.recycle()
                        }
                    }
                }
                return null
            }
            val actionNode = findImeAction(root, 0) ?: return false
            try {
                val rect = Rect()
                actionNode.getBoundsInScreen(rect)
                val cx = (rect.left + rect.right) / 2f
                val cy = (rect.top + rect.bottom) / 2f
                AiOverlayHud.moveBotAndShowTap(cx, cy, "Search")
                if (actionNode.isClickable &&
                    actionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                return performHumanTap(cx, cy, GESTURE_TAP_MS)
            } finally {
                actionNode.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    fun clickBottomSearchTab(): Boolean {
        val root = rootInActiveWindow ?: return false
        val (_, screenHeight) = getRealScreenDimensions()
        try {
            fun findBottomSearchNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
                if (node == null || depth > 12) return null
                val text = node.text?.toString().orEmpty().trim().lowercase(Locale.US)
                val desc = node.contentDescription?.toString().orEmpty().trim().lowercase(Locale.US)
                val labelMatches = text == "search" || desc == "search" ||
                    text == "explore" || desc == "explore" ||
                    text == "search and explore" || desc == "search and explore" ||
                    text == "search & explore" || desc == "search & explore"
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (labelMatches && rect.top >= screenHeight * 0.70f &&
                    rect.width() > 8 && rect.height() > 8) {
                    return AccessibilityNodeInfo.obtain(node)
                }
                for (index in 0 until node.childCount) {
                    val child = try { node.getChild(index) } catch (_: Exception) { null }
                    if (child != null) {
                        try {
                            val found = findBottomSearchNode(child, depth + 1)
                            if (found != null) return found
                        } finally {
                            child.recycle()
                        }
                    }
                }
                return null
            }
            val node = findBottomSearchNode(root, 0) ?: return false
            try {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val cx = (rect.left + rect.right) / 2f
                val cy = (rect.top + rect.bottom) / 2f
                AiOverlayHud.moveBotAndShowTap(cx, cy, "Search")
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                return performHumanTap(cx, cy, GESTURE_TAP_MS)
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    fun findFirstVideoGridTileCenter(): PointCenter? {
        val root = rootInActiveWindow ?: return null
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        data class GridCandidate(val top: Int, val area: Long, val node: AccessibilityNodeInfo)
        val candidates = mutableListOf<GridCandidate>()
        fun excludedContent(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 3) return false
            val label = (node.text?.toString().orEmpty() + " " +
                node.contentDescription?.toString().orEmpty()).lowercase(Locale.US)
            if (label.contains("ask anything") || label == "more" ||
                label.contains("meta ai") || label.contains("ask meta ai")) return true
            for (index in 0 until node.childCount) {
                val child = try { node.getChild(index) } catch (_: Exception) { null }
                if (child != null) {
                    try {
                        if (excludedContent(child, depth + 1)) return true
                    } finally {
                        child.recycle()
                    }
                }
            }
            return false
        }
        fun hasImageDescendant(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 3) return false
            if (node.className?.toString().orEmpty().contains("ImageView", true)) return true
            for (index in 0 until node.childCount) {
                val child = try { node.getChild(index) } catch (_: Exception) { null }
                if (child != null) {
                    try {
                        if (hasImageDescendant(child, depth + 1)) return true
                    } finally {
                        child.recycle()
                    }
                }
            }
            return false
        }
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 14 || candidates.size >= 40) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val className = node.className?.toString().orEmpty()
            val isGridVisual = className.contains("ImageView", true) ||
                (node.isClickable && hasImageDescendant(node, 0))
            if (isGridVisual && rect.top > screenHeight * 0.35f &&
                rect.width() >= screenWidth * 0.40f && rect.height() >= 60 &&
                rect.height() <= screenHeight * 0.42f &&
                !excludedContent(node, 0)) {
                candidates.add(GridCandidate(
                    rect.top,
                    rect.width().toLong() * rect.height().toLong(),
                    AccessibilityNodeInfo.obtain(node)
                ))
            }
            for (index in 0 until node.childCount) {
                if (candidates.size >= 40) break
                val child = try { node.getChild(index) } catch (_: Exception) { null }
                if (child != null) {
                    try { walk(child, depth + 1) } finally { child.recycle() }
                }
            }
        }
        try {
            walk(root, 0)
            val candidate = candidates.minWithOrNull(
                compareBy<GridCandidate> { it.top }.thenBy { it.area }
            ) ?: return null
            val rect = Rect()
            candidate.node.getBoundsInScreen(rect)
            return PointCenter((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
        } finally {
            candidates.forEach { try { it.node.recycle() } catch (_: Exception) {} }
            root.recycle()
        }
    }

    fun clickFirstVideoGridTile(): Boolean {
        val center = findFirstVideoGridTileCenter() ?: return false
        AiOverlayHud.moveBotAndShowTap(center.x, center.y, "First Video")
        return performHumanTap(center.x, center.y, GESTURE_TAP_MS)
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
            outLines.add("$tag[\"$label\"]@($cx,$cy) selected=${node.isSelected} checked=${node.isChecked}")
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
                            val toggleKind = toggleKindForLabel(targetText)
                            val visibleState = (matched.text?.toString().orEmpty() + " " +
                                matched.contentDescription?.toString().orEmpty()).lowercase(Locale.US)
                            val alreadyActive = when (toggleKind) {
                                "LIKE" -> matched.isSelected || matched.isChecked ||
                                    visibleState.contains("liked") || visibleState.contains("unlike")
                                "FOLLOW" -> matched.isSelected || matched.isChecked ||
                                    visibleState.contains("following") || visibleState.contains("unfollow")
                                "SUBSCRIBE" -> matched.isSelected || matched.isChecked ||
                                    visibleState.contains("subscribed") || visibleState.contains("unsubscribe")
                                else -> false
                            }
                            if (toggleKind != null && alreadyActive) {
                                // Report success without toggling an already-active control off.
                                return true
                            }
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

    private fun containsSensitiveLabel(rawLabel: String): Boolean {
        val label = rawLabel.lowercase(Locale.US)
        val sensitiveTerms = listOf(
            "password", "passcode", "email", "phone", "bank", "account number",
            "credit card", "debit card", "cvv", "security code"
        )
        if (sensitiveTerms.any { label.contains(it) }) return true
        return Regex("\\bpin\\b").containsMatchIn(label) ||
            Regex("\\botp\\b").containsMatchIn(label)
    }

    private fun toggleKindForLabel(rawLabel: String): String? {
        val label = rawLabel.lowercase(Locale.US)
        return when {
            label.contains("like") || label.contains("heart") -> "LIKE"
            label.contains("follow") -> "FOLLOW"
            label.contains("subscribe") -> "SUBSCRIBE"
            else -> null
        }
    }

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

    fun performHumanTap(x: Float, y: Float, holdMs: Long = GESTURE_TAP_MS): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val (screenWidth, screenHeight) = getRealScreenDimensions()
        if (screenWidth <= 0f || screenHeight <= 0f) return false
        val centerX = x.coerceIn(0.5f, screenWidth - 0.5f)
        val centerY = y.coerceIn(0.5f, screenHeight - 0.5f)
        val safeX = centerX
        val safeY = centerY
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
        markAutomatedGesture()
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
        markAutomatedGesture()
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
                                ?.copy(Bitmap.Config.ARGB_8888, true)
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
