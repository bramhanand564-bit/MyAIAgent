package com.localaiagent

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class SystemSettingsModule(
    private val appContext: ReactApplicationContext
) : ReactContextBaseJavaModule(appContext) {

    override fun getName(): String = "AgentSystemSettings"

    @ReactMethod
    fun requestBatteryOptimizationExemption(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                promise.resolve(true)
                return
            }
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            if (powerManager.isIgnoringBatteryOptimizations(appContext.packageName)) {
                promise.resolve(true)
                return
            }
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:" + appContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            promise.resolve(false)
        } catch (error: Exception) {
            promise.reject("BATTERY_SETTINGS_UNAVAILABLE", error.message, error)
        }
    }

    @ReactMethod
    fun requestExactAlarmAccess(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                promise.resolve(true)
                return
            }
            val alarmManager = appContext.getSystemService(AlarmManager::class.java)
            if (alarmManager.canScheduleExactAlarms()) {
                promise.resolve(true)
                return
            }
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:" + appContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            promise.resolve(false)
        } catch (error: Exception) {
            promise.reject("EXACT_ALARM_SETTINGS_UNAVAILABLE", error.message, error)
        }
    }
}
