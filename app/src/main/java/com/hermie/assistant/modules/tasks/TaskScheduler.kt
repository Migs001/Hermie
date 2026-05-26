package com.hermie.assistant.modules.tasks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Schedules task execution via AlarmManager.
 *
 * Fires [TaskAlarmReceiver] at the requested time; the receiver starts
 * [com.hermie.assistant.service.HermieBackgroundService] with the task ID.
 * The service handles queuing if Brain is busy.
 *
 * Requires SCHEDULE_EXACT_ALARM (already in manifest). On Android 12+,
 * check [canScheduleExactAlarms] before scheduling.
 */
class TaskScheduler(private val context: Context) {

    companion object {
        private const val TAG = "TaskScheduler"
        const val EXTRA_FIRE_TASK_ID = "fire_task_id"
    }

    /**
     * Schedule [taskId] to fire at [triggerAtMs] (epoch-milliseconds).
     * Uses setExactAndAllowWhileIdle so it fires even in Doze mode.
     * No-ops if SCHEDULE_EXACT_ALARM is not granted (logs a warning).
     */
    fun schedule(taskId: String, triggerAtMs: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarm — SCHEDULE_EXACT_ALARM not granted for task $taskId")
            return
        }

        val pendingIntent = buildPendingIntent(taskId) ?: return
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        Log.d(TAG, "Scheduled task $taskId at ${java.util.Date(triggerAtMs)}")
    }

    /** Cancel a previously scheduled task alarm. */
    fun cancel(taskId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = buildPendingIntent(taskId, flagNoCreate = true) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        Log.d(TAG, "Cancelled scheduled task $taskId")
    }

    private fun buildPendingIntent(taskId: String, flagNoCreate: Boolean = false): PendingIntent? {
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            putExtra(EXTRA_FIRE_TASK_ID, taskId)
        }
        val flags = (if (flagNoCreate) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT) or
            PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flags)
    }
}
