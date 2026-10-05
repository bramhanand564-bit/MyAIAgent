package com.myaiagent.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.view.Display
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Isolated real-world tap test. It deliberately avoids the YouTube session,
 * queue, Gemini decision loop, and upload state machine.
 */
class RealTapTestEngineV2(private val service: AccessibilityService) {
    private val store = RealTapTestStore(service)
    private val handler = Handler(Looper.getMainLooper())
    private val cursor = NaxFloatingCursor(service)
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private var pump: Runnable? = null
    private var stopped = false

    fun onAccessibilityEvent(event: AccessibilityEvent?): Boolean {
        if (!store.isActive() || stopped) return false
        try {
            if (store.activeElapsedMs() > 60_000L) {
                finish(false, "Test timed out after 60s of active interaction")
                return true
            }

            if (store.pending()) return true

            val root = service.rootInActiveWindow ?: run {
                schedule(500L)
                return true
            }

            val interruption = UiInterruptionDetector.detect(root)
            if (interruption.type != UiInterruptionDetector.Type.NONE) {
                store.manualPause(interruption.label)
                show("🔐 MANUAL ACTION", listOf(
                    "⏸ Automation paused safely",
                    "Detected: ${interruption.label}",
                    "NAX will not bypass it",
                    "Next: complete it manually"
                ))
                capture("manual interruption")
                schedule(900L)
                return true
            } else if (store.isManualPaused()) {
                store.clearManualPause()
            }
            when (store.step()) {
                RealTapTestStore.STEP_WAIT_APP -> waitForApp(root)
                RealTapTestStore.STEP_FIND_SEARCH -> findSearch(root)
                RealTapTestStore.STEP_WAIT_KEYBOARD -> waitForKeyboard(root)
                RealTapTestStore.STEP_TYPE -> typeNextKey(root)
                RealTapTestStore.STEP_SUBMIT -> submit(root)
                RealTapTestStore.STEP_VERIFY -> verify(root)
                else -> Unit
            }
            return true
        } catch (t: Throwable) {
            finish(false, "Test exception: ${t.message ?: t.javaClass.simpleName}")
            return true
        }
    }

    fun start(targetPackage: String) {
        stopped = false
        store.start(targetPackage)
        show("🧪 EXPERIMENT", listOf(
            "✓ Test started",
            "Target: $targetPackage",
            "No copy/paste",
            "Next: open target"
        ))
        schedule(250L)
    }

    fun stop() {
        stopped = true
        pump?.let(handler::removeCallbacks)
        pump = null
        screenshotExecutor.shutdownNow()
        runCatching { cursor.hide() }
    }

    private fun waitForApp(root: AccessibilityNodeInfo) {
        val expected = store.targetPackage()
        val current = root.packageName?.toString().orEmpty()

        if (current != expected) {
            show("🧪 EXPERIMENT", listOf(
                "⏳ Waiting for target app",
                "Target: $expected",
                "Current: ${current.ifBlank { "unknown" }}",
                "Next: app screen"
            ))
            schedule(700L)
            return
        }

        store.log("SCREEN • target app opened")
        store.log("SCREEN READ • ${read(root)}")
        store.log("NEXT TARGET • Search field")
        show("🧪 EXPERIMENT", listOf(
            "✓ App opened",
            "📸 Screenshot requested",
            "👁 Screen read complete",
            "🎯 Next: Search field"
        ))
        capture("target app opened")
        store.setStep(RealTapTestStore.STEP_FIND_SEARCH)
        schedule(400L)
    }

    private fun findSearch(root: AccessibilityNodeInfo) {
        if (root.packageName?.toString().orEmpty() != store.targetPackage()) {
            store.setStep(RealTapTestStore.STEP_WAIT_APP)
            schedule(400L)
            return
        }

        val target = findSearchField(root)
        if (target == null) {
            show("🧪 EXPERIMENT", listOf(
                "📸 Screen read",
                "❌ Search field not found",
                "Waiting for target UI",
                "Next: retry scan"
            ))
            schedule(700L)
            return
        }

        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        if (bounds.isEmpty || bounds.width() < 8 || bounds.height() < 8) {
            schedule(700L)
            return
        }

        cursor.showForNode(target, "👆 Search")
        show("🧪 EXPERIMENT", listOf(
            "✓ Search target found",
            "🎯 Live bounds located",
            "👆 Real tap requested",
            "⌨️ Next: keyboard"
        ))
        store.log("ACTION • real tap on search field")
        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "Search field") { ok ->
            if (!ok) {
                finish(false, "Search field tap failed")
                return@tap
            }
            store.log("ACTION • search field tap completed")
            store.setStep(RealTapTestStore.STEP_WAIT_KEYBOARD)
            capture("search field tapped")
            show("🧪 EXPERIMENT", listOf(
                "✓ Tap completed",
                "📸 Screenshot captured",
                "👁 Re-reading screen",
                "⌨️ Next: real keyboard"
            ))
            schedule(900L)
        }
    }

    private fun waitForKeyboard(root: AccessibilityNodeInfo) {
        val keyboardRoot = findKeyboardRoot()
        val pkg = keyboardRoot?.packageName?.toString().orEmpty()
        if (keyboardRoot == null || !isKeyboard(pkg)) {
            show("🧪 EXPERIMENT", listOf(
                "✓ Search tapped",
                "⏳ Keyboard opening",
                "Current: ${pkg.ifBlank { "unknown" }}",
                "Next: detect keyboard"
            ))
            schedule(500L)
            return
        }

        store.log("KEYBOARD • $pkg detected")
        store.log("SCREEN READ • ${read(root)}")
        store.log("NEXT TARGET • Shift then I")
        capture("keyboard opened")
        show("🧪 EXPERIMENT", listOf(
            "✓ Keyboard detected",
            "📸 Screenshot captured",
            "👁 Keys are readable",
            "🎯 Next: Shift + I"
        ))
        store.setStep(RealTapTestStore.STEP_TYPE)
        schedule(500L)
    }

    private fun typeNextKey(root: AccessibilityNodeInfo) {
        val keyboardRoot = findKeyboardRoot() ?: run {
            schedule(500L)
            return
        }
        val pkg = keyboardRoot.packageName?.toString().orEmpty()
        if (!isKeyboard(pkg)) {
            schedule(500L)
            return
        }

        val phrase = "I love you"
        val index = store.charIndex()
        if (index >= phrase.length) {
            store.log("TEXT • all characters tapped key-by-key")
            store.setStep(RealTapTestStore.STEP_SUBMIT)
            schedule(650L)
            return
        }

        if (index == 0 && !store.shiftDone()) {
            val shift = findSpecialKey(keyboardRoot, "shift")
            if (shift == null) {
                show("🧪 EXPERIMENT", listOf(
                    "⌨️ Keyboard visible",
                    "❌ Shift key not found",
                    "No text injection",
                    "Next: retry Shift"
                ))
                schedule(600L)
                return
            }

            cursor.showForNode(shift, "⇧ Shift")
            store.log("KEY • Shift target found")
            store.setPending(true)
            val b = Rect()
            shift.getBoundsInScreen(b)
            tap(b.centerX().toFloat(), b.centerY().toFloat(), "Shift") { ok ->
                store.setPending(false)
                if (!ok) {
                    finish(false, "Shift tap failed")
                    return@tap
                }
                store.markShiftDone()
                store.log("KEY TAP • Shift • completed")
                schedule(450L)
            }
            return
        }

        val ch = phrase[index]
        val key = findLetterKey(keyboardRoot, ch)
        if (key == null) {
            show("🧪 EXPERIMENT", listOf(
                "⌨️ Keyboard readable",
                "❌ Key '$ch' not found",
                "No copy/paste",
                "Next: retry this key"
            ))
            schedule(650L)
            return
        }

        val b = Rect()
        key.getBoundsInScreen(b)
        cursor.showForNode(key, "👆 $ch")
        show("🧪 EXPERIMENT", listOf(
            "🎯 Target key: '$ch'",
            "👆 Real keyboard tap",
            "⏳ Waiting for completion",
            "Progress: ${index + 1}/${phrase.length}"
        ))
        store.setPending(true)
        tap(b.centerX().toFloat(), b.centerY().toFloat(), "Key '$ch'") { ok ->
            store.setPending(false)
            if (!ok) {
                finish(false, "Key '$ch' tap failed")
                return@tap
            }
            store.log("KEY TAP • '$ch' • completed")
            store.advanceChar()
            capture("key '$ch'")
            if (store.charIndex() >= phrase.length) {
                store.log("TEXT • I love you completed")
                store.setStep(RealTapTestStore.STEP_SUBMIT)
                schedule(700L)
            } else {
                schedule(450L)
            }
        }
    }

    private fun submit(root: AccessibilityNodeInfo) {
        val keyboardRoot = findKeyboardRoot() ?: run {
            schedule(500L)
            return
        }
        if (!isKeyboard(keyboardRoot.packageName?.toString().orEmpty())) {
            schedule(500L)
            return
        }

        val key = findSubmitKey(keyboardRoot)
        if (key == null) {
            show("🧪 EXPERIMENT", listOf(
                "✓ Text complete",
                "❌ Search/Enter not found",
                "No copy/paste",
                "Next: retry submit"
            ))
            schedule(600L)
            return
        }

        val b = Rect()
        key.getBoundsInScreen(b)
        cursor.showForNode(key, "🔎 Search")
        show("🧪 EXPERIMENT", listOf(
            "✓ I love you entered",
            "🎯 Search/Enter found",
            "👆 Real tap requested",
            "⏳ Waiting for results"
        ))
        store.setPending(true)
        tap(b.centerX().toFloat(), b.centerY().toFloat(), "Search/Enter") { ok ->
            store.setPending(false)
            if (!ok) {
                finish(false, "Search/Enter tap failed")
                return@tap
            }
            store.log("ACTION • Search/Enter completed")
            capture("submitted")
            store.setStep(RealTapTestStore.STEP_VERIFY)
            schedule(1200L)
        }
    }

    private fun verify(root: AccessibilityNodeInfo) {
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != store.targetPackage()) {
            schedule(700L)
            return
        }

        val queryVisible = collectText(root).any {
            it.trim().equals("I love you", ignoreCase = true)
        }
        store.log(if (queryVisible) {
            "VERIFY • query 'I love you' visible"
        } else {
            "VERIFY • query not yet visible"
        })

        show("🧪 EXPERIMENT", listOf(
            if (queryVisible) "✅ Query visible" else "⏳ Checking query",
            "📸 Screenshot/readback",
            "🎯 Target: I love you",
            if (queryVisible) "✓ Result verified" else "Waiting for result"
        ))

        if (queryVisible) {
            capture("results verified")
            finish(true, "Real tap + keyboard test passed")
        } else if (store.activeElapsedMs() > 45_000L) {
            finish(false, "Search results could not be verified after 45s of active interaction")
        } else {
            schedule(900L)
        }
    }

    private fun tap(
        x: Float,
        y: Float,
        label: String,
        done: (Boolean) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            done(false)
            return
        }

        // Safety gate: never dispatch a real touch while the target app/keyboard
        // is not actually foreground. Previously a delayed gesture could land on
        // the launcher/system UI after the task changed, which can look like an
        // accidental Back/Home action. A stale gesture is now rejected locally.
        val targetVisible = service.rootInActiveWindow?.packageName?.toString()
            ?.let { it == store.targetPackage() } == true
        val keyboardVisible = findKeyboardRoot() != null
        if (!targetVisible && !keyboardVisible) {
            store.log("GESTURE • $label blocked • target/keyboard not foreground")
            done(false)
            return
        }

        val width = service.resources.displayMetrics.widthPixels
        val height = service.resources.displayMetrics.heightPixels
        val density = service.resources.displayMetrics.density
        // Keep injected taps away from Android's gesture/navigation edge. A bad or
        // stale coordinate must fail the test rather than become a system Back/Home gesture.
        val edge = (28f * density).toInt()
        if (x < edge || y < edge || x >= width - edge || y >= height - edge) {
            store.log("GESTURE • $label blocked • navigation-edge coordinate")
            done(false)
            return
        }

        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 90L))
            .build()

        val accepted = runCatching {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        handler.post { done(true) }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        handler.post { done(false) }
                    }
                },
                handler
            )
        }.getOrDefault(false)

        if (!accepted) done(false)
        else store.log("GESTURE • $label dispatched")
    }

    private fun findSearchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val preferred = listOf(
            "Search or type web address",
            "Search or type a web address",
            "Address bar",
            "Search"
        )
        val nodes = bfs(root)
        nodes.firstOrNull { node ->
            val labels = nodeLabels(node)
            node.isEditable && labels.any { label ->
                preferred.any { target -> label.contains(target, ignoreCase = true) }
            }
        }?.let { return it }

        return nodes.firstOrNull { node ->
            node.isEditable || node.className?.toString()
                ?.contains("EditText", ignoreCase = true) == true
        }
    }

    private fun findSpecialKey(root: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? =
        findKeyboardNode(root) { text, desc ->
            val joined = "$text $desc"
            joined == wanted || joined.contains(wanted)
        }

    private fun findLetterKey(root: AccessibilityNodeInfo, char: Char): AccessibilityNodeInfo? {
        if (char == ' ') {
            return findKeyboardNode(root) { text, desc ->
                text == "space" || desc == "space" ||
                    desc.contains("space bar") || desc.contains("spacebar")
            }
        }

        val wanted = char.lowercaseChar().toString()
        return findKeyboardNode(root) { text, desc ->
            text == wanted || desc == wanted ||
                desc.contains("letter $wanted") ||
                desc.contains("$wanted key")
        }
    }

    private fun findSubmitKey(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val labels = listOf("search", "go", "enter", "done", "return")
        return findKeyboardNode(root) { text, desc ->
            labels.any { text.contains(it) || desc.contains(it) }
        }
    }

    private fun findKeyboardNode(
        root: AccessibilityNodeInfo,
        match: (String, String) -> Boolean
    ): AccessibilityNodeInfo? {
        val h = service.resources.displayMetrics.heightPixels
        var best: AccessibilityNodeInfo? = null
        var scoreBest = Int.MIN_VALUE
        for (node in bfs(root)) {
            val b = Rect()
            node.getBoundsInScreen(b)
            if (b.isEmpty || b.top < h * 0.50f || b.width() !in 20..700 || b.height() !in 20..220) {
                continue
            }
            val text = node.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
            if (!match(text, desc)) continue
            var score = if (node.isClickable) 20 else 0
            if (desc.contains("key")) score += 10
            if (b.top > h * 0.62f) score += 5
            if (score > scoreBest) {
                scoreBest = score
                best = node
            }
        }
        return best
    }

    private fun bfs(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return result
    }

    private fun collectText(root: AccessibilityNodeInfo): List<String> =
        bfs(root).flatMap { nodeLabels(it) }.filter { it.isNotBlank() }.distinct()

    private fun nodeLabels(node: AccessibilityNodeInfo): List<String> =
        listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString()
        ).map { it.trim() }.filter { it.isNotBlank() }

    private fun read(root: AccessibilityNodeInfo): String =
        collectText(root).filter { it.length in 1..40 }.take(4).joinToString(" | ")

    private fun findKeyboardRoot(): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        runCatching { service.rootInActiveWindow?.let(roots::add) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                service.windows.forEach { window ->
                    window.root?.let(roots::add)
                }
            }
        }
        return roots.firstOrNull { isKeyboard(it.packageName?.toString().orEmpty()) }
    }

    private fun isKeyboard(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.getDefault())
        return p.contains("inputmethod") ||
            p.contains("keyboard") ||
            p.contains("swiftkey") ||
            p.contains("honeyboard") ||
            p.contains("latin") ||
            p.contains("ime")
    }

    private fun schedule(delay: Long) {
        pump?.let(handler::removeCallbacks)
        pump = Runnable {
            if (store.isActive()) {
                onAccessibilityEvent(null)
            }
        }.also { handler.postDelayed(it, delay) }
    }

    private fun show(title: String, lines: List<String>) {
        runCatching {
            cursor.setExperiment(title, lines)
        }
    }

    private fun capture(stage: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            store.log("SCREENSHOT • unavailable below Android 11 • $stage")
            return
        }

        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        runCatching { screenshot.hardwareBuffer.close() }
                        handler.post {
                            store.log("SCREENSHOT • captured • $stage")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        handler.post {
                            store.log("SCREENSHOT • failed • code=$errorCode • $stage")
                        }
                    }
                }
            )
        }.onFailure {
            store.log("SCREENSHOT • exception • ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun finish(success: Boolean, message: String) {
        store.finish(success, message)
        show("🧪 EXPERIMENT", listOf(
            if (success) "✅ PASS" else "❌ FAIL",
            message.take(40),
            "📸 screenshot/read log saved",
            "No copy/paste or text injection"
        ))
        pump?.let(handler::removeCallbacks)
        pump = null
        handler.postDelayed({
            runCatching { cursor.hide() }
        }, 1800L)
    }
}
