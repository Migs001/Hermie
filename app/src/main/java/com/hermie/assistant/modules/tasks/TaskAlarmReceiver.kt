package com.hermie.assistant.modules.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hermie.assistant.service.HermieBackgroundService

/**
 * Broadcast receiver for scheduled task alarms.
 *
 * When AlarmManager fires, this starts [HermieBackgroundService] with
 * ACTION_FIRE_TASK + the task ID. The service handles queuing if Brain
 * is currently busy (sleep mode, generating, etc.).
 */
class TaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TaskScheduler.EXTRA_FIRE_TASK_ID) ?: run {
            Log.w(TAG, "TaskAlarmReceiver: no task ID in intent")
            return
        }

        Log.d(TAG, "Task alarm fired for task: $taskId")

        val serviceIntent = Intent(context, HermieBackgroundService::class.java).apply {
            action = HermieBackgroundService.ACTION_FIRE_TASK
            putExtra(TaskScheduler.EXTRA_FIRE_TASK_ID, taskId)
        }
        context.startForegroundService(serviceIntent)
    }

    companion object {
        private const val TAG = "TaskAlarmReceiver"
    }
}
