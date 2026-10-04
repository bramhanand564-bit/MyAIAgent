package com.myaiagent.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.myaiagent.automation.UploadRunnerService

class UploadAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val itemId = UploadAlarmScheduler.itemId(intent) ?: return
        val serviceIntent = Intent(context, UploadRunnerService::class.java).apply {
            putExtra(UploadRunnerService.EXTRA_ITEM_ID, itemId)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
