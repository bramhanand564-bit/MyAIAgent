package com.auradroid.agent

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Opt-in live teach recorder. It records semantic clicks and scrolls, but never raw typed
 * text or password fields. The user must review and edit text steps before replay.
 */
object TrainingRecorder {
    private const val PREFS = "myai_training_recorder"
    private const val KEY_ACTIVE = "recording"
    private const val KEY_JSON = "blueprint_json"
    private const val MAX_STEPS = 500

    @Volatile private var lastTypeField = ""
    @Volatile private var lastAction = ""
    @Volatile private var lastScrollAtMs = 0L

    fun start(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_JSON, "")
            .apply()
        lastTypeField = ""
        lastAction = ""
        lastScrollAtMs = 0L
    }

    fun isRecording(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)

    fun stopAndGetSteps(context: Context): List<BlueprintStep> {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, false).apply()
        lastTypeField = ""
        lastAction = ""
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return try {
            val root = JSONObject(raw)
            val array = root.optJSONArray("steps") ?: JSONArray()
            if (array.length() == 0) emptyList()
            else AutomationBlueprint.fromJson(raw).steps
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordAccessibilityEvent(context: Context, event: AccessibilityEvent) {
        if (!isRecording(context)) return
        if (event.packageName?.toString() == context.packageName) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) return

        try {
            val source = event.source ?: return
            if (source.isPassword) return

            val pkg = event.packageName?.toString().orEmpty().take(200)
            val sourceId = source.viewIdResourceName.orEmpty().take(250)
            val hint = source.hintText?.toString().orEmpty().take(250)
            val description = source.contentDescription?.toString().orEmpty().take(250)
            val visibleText = if (source.isEditable) "" else source.text?.toString().orEmpty().take(250)
            val target = when {
                type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> hint.ifBlank { description }
                description.isNotBlank() -> description
                visibleText.isNotBlank() -> visibleText
                else -> ""
            }
            val bounds = Rect()
            source.getBoundsInScreen(bounds)
            val (screenW, screenH) = Pair(context.resources.displayMetrics.widthPixels.toFloat(), context.resources.displayMetrics.heightPixels.toFloat())
            val nx = if (screenW > 0) ((bounds.centerX().toFloat() / screenW).coerceIn(0.01f, 0.99f)).toDouble() else 0.5
            val ny = if (screenH > 0) ((bounds.centerY().toFloat() / screenH).coerceIn(0.01f, 0.99f)).toDouble() else 0.5

            val step = when (type) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> BlueprintStep(
                    action = BlueprintAction.TAP,
                    target = target,
                    x = nx,
                    y = ny,
                    note = "Live Teach • app=$pkg • viewId=$sourceId"
                )
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> BlueprintStep(
                    action = BlueprintAction.TYPE,
                    target = target,
                    text = "",
                    note = "Live Teach • app=$pkg • fieldId=$sourceId • typed text omitted for privacy; add non-sensitive text manually"
                )
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> BlueprintStep(
                    action = BlueprintAction.SCROLL,
                    note = "Live Teach • app=$pkg • viewId=$sourceId"
                )
                else -> return
            }
            appendStep(
                context,
                step,
                type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                sourceId.ifBlank { hint },
                type == AccessibilityEvent.TYPE_VIEW_SCROLLED
            )
        } catch (_: SecurityException) {
            // Some apps don't expose node content. Keep the existing automation unaffected.
        } catch (_: Exception) {
            // Recorder failures must never interrupt the accessibility service.
        }
    }

    @Synchronized
    private fun appendStep(
        context: Context,
        step: BlueprintStep,
        isType: Boolean,
        fieldKey: String,
        isScroll: Boolean
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_JSON, "").orEmpty()
        val previous = try {
            val arr = JSONObject(raw).optJSONArray("steps") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                BlueprintStep(
                    action = BlueprintAction.fromWireValue(item.optString("action", "")),
                    target = item.optString("target", ""),
                    text = item.optString("text", ""),
                    x = item.optDouble("x", 0.5),
                    y = item.optDouble("y", 0.5),
                    waitMs = item.optLong("waitMs", 0L),
                    note = item.optString("note", "")
                )
            }
        } catch (_: Exception) { emptyList() }.toMutableList()

        val typeKey = fieldKey + "|" + step.target
        if (isType && lastAction == "type" && lastTypeField == typeKey) return
        val now = System.currentTimeMillis()
        if (isScroll && lastAction == "scroll" && now - lastScrollAtMs < 800L) return
        if (previous.size >= MAX_STEPS) {
            prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
            return
        }
        previous.add(step)
        lastAction = step.action.wireValue
        lastTypeField = if (isType) typeKey else ""
        if (isScroll) lastScrollAtMs = now
        val blueprint = AutomationBlueprint(
            name = "Live Teach Recording",
            steps = previous,
            description = "Recorded UI actions. Raw typed text is excluded for privacy; review before replay."
        )
        prefs.edit().putString(KEY_JSON, blueprint.toJson()).apply()
    }
}
