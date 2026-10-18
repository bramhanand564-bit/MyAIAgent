package com.auradroid.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskSchedulerReceiver : BroadcastReceiver() {

    companion object {
        private const val PREFS_TIMERS = "auradroid_scheduled_timers"
        private const val KEY_TIMER_LIST = "timers_json_array"

        const val EXTRA_TIMER_ID = "extra_timer_id"
        const val EXTRA_AUTO_START_GOAL = "extra_auto_start_goal"
        const val EXTRA_AUTO_START_MODE = "extra_auto_start_mode"

        fun scheduleExactTask(
            context: Context,
            triggerAtMillis: Long,
            goal: String,
            scheduleLabel: String,
            executionMode: String = "STEALTH_WEB"
        ): Int {
            val timerId = ((triggerAtMillis / 1000L) % 100000L).toInt().coerceAtLeast(100)
            saveTimerRecord(context, timerId, triggerAtMillis, goal, scheduleLabel, executionMode)
            registerAlarmWithOs(context, timerId, triggerAtMillis, goal, executionMode)
            return timerId
        }

        private fun registerAlarmWithOs(
            context: Context,
            timerId: Int,
            triggerAtMillis: Long,
            goal: String,
            executionMode: String
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, TaskSchedulerReceiver::class.java).apply {
                putExtra(EXTRA_TIMER_ID, timerId)
                putExtra(EXTRA_AUTO_START_GOAL, goal)
                putExtra(EXTRA_AUTO_START_MODE, executionMode)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getBroadcast(context, timerId, intent, flags)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } catch (_: Exception) {
                try {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } catch (_: Exception) {
                }
            }
        }

        private fun saveTimerRecord(
            context: Context,
            timerId: Int,
            triggerAtMillis: Long,
            goal: String,
            scheduleLabel: String,
            executionMode: String
        ) {
            val prefs = context.getSharedPreferences(PREFS_TIMERS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_TIMER_LIST, "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }

            val obj = JSONObject().apply {
                put("id", timerId)
                put("triggerAt", triggerAtMillis)
                put("goal", goal)
                put("label", scheduleLabel)
                put("mode", executionMode)
            }
            arr.put(obj)
            prefs.edit().putString(KEY_TIMER_LIST, arr.toString()).apply()
        }

        fun removeTimerById(context: Context, timerId: Int) {
            val prefs = context.getSharedPreferences(PREFS_TIMERS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_TIMER_LIST, "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
            val updated = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optInt("id") != timerId) updated.put(item)
            }
            prefs.edit().putString(KEY_TIMER_LIST, updated.toString()).apply()
        }

        fun clearAllTimers(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_TIMERS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_TIMER_LIST, "[]") ?: "[]"
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optInt("id")
                    val intent = Intent(context, TaskSchedulerReceiver::class.java)
                    val pi = PendingIntent.getBroadcast(
                        context,
                        id,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    alarmManager?.cancel(pi)
                }
            } catch (_: Exception) {
            }
            prefs.edit().putString(KEY_TIMER_LIST, "[]").apply()
        }

        fun getFormattedActiveTimers(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_TIMERS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_TIMER_LIST, "[]") ?: "[]"
            val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.US)
            return try {
                val arr = JSONArray(raw)
                if (arr.length() == 0) return "No active scheduled timers."
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val timeStr = sdf.format(Date(item.optLong("triggerAt")))
                    val goal = item.optString("goal", "Autonomous Task")
                    val mode = item.optString("mode", "STEALTH_WEB")
                    sb.append("• [$timeStr] ($mode) ➔ $goal\n")
                }
                sb.toString().trim()
            } catch (_: Exception) {
                "No active scheduled timers."
            }
        }

        fun rescheduleAllSavedTimers(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_TIMERS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_TIMER_LIST, "[]") ?: "[]"
            val now = System.currentTimeMillis()
            try {
                val arr = JSONArray(raw)
                val activeArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val triggerAt = item.optLong("triggerAt")
                    if (triggerAt > now) {
                        activeArr.put(item)
                        registerAlarmWithOs(
                            context = context,
                            timerId = item.optInt("id"),
                            triggerAtMillis = triggerAt,
                            goal = item.optString("goal"),
                            executionMode = item.optString("mode", "STEALTH_WEB")
                        )
                    }
                }
                prefs.edit().putString(KEY_TIMER_LIST, activeArr.toString()).apply()
            } catch (_: Exception) {
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val goal = intent?.getStringExtra(EXTRA_AUTO_START_GOAL) ?: return
        val mode = intent.getStringExtra(EXTRA_AUTO_START_MODE) ?: "STEALTH_WEB"
        val timerId = intent.getIntExtra(EXTRA_TIMER_ID, -1)
        if (timerId != -1) removeTimerById(context, timerId)

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraDroidOS::NaxBotAlarmWakeLock")
        try {
            wakeLock?.acquire(60_000L)
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_AUTO_START_GOAL, goal)
                putExtra(EXTRA_AUTO_START_MODE, mode)
            }
            context.startActivity(launchIntent)
        } finally {
            if (wakeLock?.isHeld == true) wakeLock.release()
        }
    }
}
