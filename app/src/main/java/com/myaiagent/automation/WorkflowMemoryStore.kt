package com.myaiagent.automation

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device/workflow-specific UI memory.
 *
 * Memory never becomes an unconditional coordinate macro. It stores stable UI
 * identifiers (resource id/text/content description/class) plus relative bounds.
 * Every remembered target is still revalidated against the live hierarchy.
 */
class WorkflowMemoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "workflow_ui_memory",
        Context.MODE_PRIVATE
    )

    fun remember(
        packageName: String,
        state: String,
        logicalLabel: String,
        node: AccessibilityNodeInfo,
        nextState: String? = null
    ) {
        val key = key(packageName, state, logicalLabel)
        val data = load()
        val existing = data.optJSONObject(key) ?: JSONObject()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        existing.put("package", packageName)
        existing.put("state", state)
        existing.put("label", logicalLabel)
        existing.put("text", node.text?.toString().orEmpty())
        existing.put("contentDescription", node.contentDescription?.toString().orEmpty())
        existing.put("resourceId", node.viewIdResourceName.orEmpty())
        existing.put("className", node.className?.toString().orEmpty())
        existing.put("left", bounds.left)
        existing.put("top", bounds.top)
        existing.put("right", bounds.right)
        existing.put("bottom", bounds.bottom)
        existing.put("xPct", if (context.resources.displayMetrics.widthPixels > 0) ((bounds.left + bounds.right) / 2f) / context.resources.displayMetrics.widthPixels else 0f)
        existing.put("yPct", if (context.resources.displayMetrics.heightPixels > 0) ((bounds.top + bounds.bottom) / 2f) / context.resources.displayMetrics.heightPixels else 0f)
        existing.put("enabled", node.isEnabled)
        existing.put("clickable", node.isClickable)
        existing.put("selected", node.isSelected)
        existing.put("nextState", nextState.orEmpty())
        existing.put("updatedAt", System.currentTimeMillis())
        existing.put("hits", existing.optInt("hits", 0) + 1)
        data.put(key, existing)
        save(data)
    }

    fun findNode(
        root: AccessibilityNodeInfo,
        state: String,
        labels: List<String>
    ): AccessibilityNodeInfo? {
        val packageName = root.packageName?.toString().orEmpty()
        val wanted = labels.map(::normalize)
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val remembered = load()

        val memories = (0 until remembered.length()).map { "" }
        // JSONObject keys are not index-addressable, so iterate directly.
        val iterator = remembered.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val item = remembered.optJSONObject(key) ?: continue
            if (item.optString("package") != packageName) continue
            if (item.optString("state") != state) continue
            if (wanted.none { w ->
                    val label = normalize(item.optString("label"))
                    val text = normalize(item.optString("text"))
                    val desc = normalize(item.optString("contentDescription"))
                    w == label || w == text || w == desc || label.contains(w) || text.contains(w) || desc.contains(w)
                }) continue
            findNodeByMemoryIdentity(root, item)?.let(candidates::add)
        }

        candidates.firstOrNull { isUsable(it) }?.let { return it }

        return findLiveNode(root, wanted)
    }

    private fun findNodeByMemoryIdentity(
        root: AccessibilityNodeInfo,
        memory: JSONObject
    ): AccessibilityNodeInfo? {
        val resourceId = memory.optString("resourceId")
        val text = normalize(memory.optString("text"))
        val description = normalize(memory.optString("contentDescription"))
        val className = memory.optString("className")
        val rememberedCenterX = (memory.optInt("left") + memory.optInt("right")) / 2
        val rememberedCenterY = (memory.optInt("top") + memory.optInt("bottom")) / 2

        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            var score = 0
            if (resourceId.isNotBlank() && node.viewIdResourceName == resourceId) score += 100
            if (text.isNotBlank() && normalize(node.text?.toString().orEmpty()) == text) score += 50
            if (description.isNotBlank() && normalize(node.contentDescription?.toString().orEmpty()) == description) score += 45
            if (className.isNotBlank() && node.className?.toString() == className) score += 10

            val b = Rect()
            node.getBoundsInScreen(b)
            val cx = (b.left + b.right) / 2
            val cy = (b.top + b.bottom) / 2
            if (rememberedCenterX > 0 && rememberedCenterY > 0) {
                val distance = kotlin.math.abs(cx - rememberedCenterX) + kotlin.math.abs(cy - rememberedCenterY)
                score += (20 - (distance / 100)).coerceAtLeast(0)
            }

            if (score > bestScore && score > 0) {
                bestScore = score
                best = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return best
    }

    private fun findLiveNode(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = normalize(node.text?.toString().orEmpty())
            val desc = normalize(node.contentDescription?.toString().orEmpty())
            if (labels.any { target ->
                    text == target || desc == target ||
                        text.contains(target) || desc.contains(target)
                } && isUsable(node)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun isUsable(node: AccessibilityNodeInfo?): Boolean =
        node != null && node.isVisibleToUser && node.isEnabled

    private fun load(): JSONObject =
        runCatching {
            JSONObject(prefs.getString("entries", "{}") ?: "{}")
        }.getOrDefault(JSONObject())

    private fun save(value: JSONObject) {
        prefs.edit().putString("entries", value.toString()).apply()
    }

    fun promptContext(packageName: String, state: String): String {
        val data = load()
        val lines = mutableListOf<String>()
        val iterator = data.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val item = data.optJSONObject(key) ?: continue
            if (item.optString("package") != packageName || item.optString("state") != state) continue
            val label = item.optString("label").ifBlank { item.optString("text") }
            if (label.isBlank()) continue
            val x = String.format("%.3f", item.optDouble("xPct", 0.0))
            val y = String.format("%.3f", item.optDouble("yPct", 0.0))
            lines += "- " + label + " at x=" + x + ", y=" + y
        }
        return lines.distinct().take(20).joinToString("\n")
    }

    private fun key(packageName: String, state: String, label: String): String =
        packageName + "|" + state + "|" + normalize(label)

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")
}
