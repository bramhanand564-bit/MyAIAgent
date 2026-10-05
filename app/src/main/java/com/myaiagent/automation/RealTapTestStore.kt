package com.myaiagent.automation

import android.content.Context
import org.json.JSONArray

/**
 * Persistent state for the isolated real-world tap + keyboard test.
 * It never touches the YouTube upload session.
 */
class RealTapTestStore(context: Context) {
    companion object {
        const val STEP_WAIT_APP = 1
        const val STEP_FIND_SEARCH = 2
        const val STEP_WAIT_KEYBOARD = 3
        const val STEP_TYPE = 4
        const val STEP_SUBMIT = 5
        const val STEP_VERIFY = 6
        const val STEP_PASS = 100
        const val STEP_FAIL = 99
    }

    private val prefs = context.getSharedPreferences("real_tap_test", Context.MODE_PRIVATE)

    fun start(targetPackage: String) {
        prefs.edit()
            .putBoolean("active", true)
            .putInt("step", STEP_WAIT_APP)
            .putInt("charIndex", 0)
            .putBoolean("shiftDone", false)
            .putBoolean("pending", false)
            .putString("targetPackage", targetPackage)
            .putLong("startedAt", System.currentTimeMillis())
            .putBoolean("success", false)
            .putLong("manualPauseSince", 0L)
            .putLong("manualPausedMs", 0L)
            .remove("message")
            .remove("events")
            .apply()
        log("TEST START • isolated real tap + keyboard")
        log("TARGET APP • $targetPackage")
    }

    fun isActive() = prefs.getBoolean("active", false)
    fun step() = prefs.getInt("step", STEP_WAIT_APP)
    fun setStep(value: Int) = prefs.edit().putInt("step", value).apply()

    fun charIndex() = prefs.getInt("charIndex", 0)
    fun advanceChar() = prefs.edit()
        .putInt("charIndex", charIndex() + 1)
        .putBoolean("shiftDone", false)
        .apply()

    fun shiftDone() = prefs.getBoolean("shiftDone", false)
    fun markShiftDone() = prefs.edit().putBoolean("shiftDone", true).apply()

    fun pending() = prefs.getBoolean("pending", false)
    fun setPending(value: Boolean) = prefs.edit().putBoolean("pending", value).apply()

    fun targetPackage() = prefs.getString("targetPackage", "com.android.chrome").orEmpty()
    fun startedAt() = prefs.getLong("startedAt", 0L)

    fun manualPauseSince() = prefs.getLong("manualPauseSince", 0L)
    fun isManualPaused() = manualPauseSince() > 0L
    fun manualPause(reason: String) {
        if (!isManualPaused()) {
            prefs.edit()
                .putLong("manualPauseSince", System.currentTimeMillis())
                .apply()
            log("PAUSE • manual action required • $reason")
        }
    }

    fun clearManualPause() {
        val since = manualPauseSince()
        if (since > 0L) {
            val now = System.currentTimeMillis()
            val added = (now - since).coerceAtLeast(0L)
            prefs.edit()
                .putLong("manualPausedMs", prefs.getLong("manualPausedMs", 0L) + added)
                .putLong("manualPauseSince", 0L)
                .apply()
            log("RESUME • manual interruption cleared")
        }
    }

    fun activeElapsedMs(): Long {
        val now = System.currentTimeMillis()
        val pausedNow = if (manualPauseSince() > 0L) now - manualPauseSince() else 0L
        return (now - startedAt() - prefs.getLong("manualPausedMs", 0L) - pausedNow)
            .coerceAtLeast(0L)
    }

    fun log(message: String) {
        val items = runCatching {
            JSONArray(prefs.getString("events", "[]") ?: "[]")
        }.getOrDefault(JSONArray())

        items.put(message)
        while (items.length() > 80) {
            items.remove(0)
        }
        prefs.edit().putString("events", items.toString()).apply()
    }

    fun events(): List<String> = runCatching {
        val items = JSONArray(prefs.getString("events", "[]") ?: "[]")
        buildList {
            for (i in 0 until items.length()) {
                add(items.optString(i))
            }
        }
    }.getOrDefault(emptyList())

    fun finish(success: Boolean, message: String) {
        log(message)
        prefs.edit()
            .putBoolean("active", false)
            .putBoolean("success", success)
            .putString("message", message)
            .putBoolean("pending", false)
            .apply()
    }

    fun success() = prefs.getBoolean("success", false)
    fun message() = prefs.getString("message", "").orEmpty()
    fun reset() = prefs.edit().clear().apply()
}
