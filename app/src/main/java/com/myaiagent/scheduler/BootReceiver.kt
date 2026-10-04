package com.myaiagent.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.myaiagent.queue.UploadQueueStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" ->
                UploadAlarmScheduler.rescheduleAll(context, UploadQueueStore(context).load())
        }
    }
}