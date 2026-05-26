package com.hermie.assistant.modules.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.hermie.assistant.modules.*
import com.hermie.assistant.service.HermieNotificationHelper
import com.hermie.assistant.ui.mascot.MascotMood
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tool module for setting reminders via AlarmManager.
 * Reminders fire as Hermie notifications at the scheduled time.
 */
class ReminderModule : HermieModule, ToolModule {

    override val id = "reminder"
    override val displayName = "Reminders"
    override val description = "Set timed reminders"
    override val iconName = "schedule"
    override val availableInChatMode = true
    override var isActive: Boolean = false
        private set

    private var context: Context? = null

    override suspend fun initialize(context: Context) {
        this.context = context
        isActive = true
    }

    override suspend fun start() { isActive = true }
    override suspend fun stop() { isActive = false }
    override fun release() { context = null }

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "reminder.set",
            description = "Set a reminder that fires at a specific date/time. Shows as a notification.",
            parameters = mapOf(
                "text" to ToolParam("str", "Reminder text to show", required = true),
                "datetime" to ToolParam("str", "When to fire: 'HH:MM' for today, or 'yyyy-MM-dd HH:MM' for a specific date", required = true)
            )
        ),
        ToolDefinition(
            name = "timer.set",
            description = "Set a countdown timer for a duration.",
            parameters = mapOf(
                "duration" to ToolParam("str", "Duration like '5m', '1h30m', '90s'", required = true),
                "label" to ToolParam("str", "Label for the timer", required = false)
            )
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        val ctx = context ?: return ToolResult.Error("Reminder module not initialized")

        return when (name) {
            "reminder.set" -> setReminder(ctx, params)
            "timer.set" -> setTimer(ctx, params)
            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    private fun setReminder(ctx: Context, params: Map<String, String>): ToolResult {
        val text = params["text"] ?: return ToolResult.Error("Missing text parameter")
        val datetime = params["datetime"] ?: return ToolResult.Error("Missing datetime parameter")

        val triggerTime = parseDatetime(datetime)
            ?: return ToolResult.Error("Invalid datetime format. Use 'HH:MM' or 'yyyy-MM-dd HH:MM'")

        if (triggerTime <= System.currentTimeMillis()) {
            return ToolResult.Error("Reminder time is in the past")
        }

        val requestCode = text.hashCode().and(0x7FFFFFFF)

        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_REMINDER_TEXT, text)
            putExtra(EXTRA_REMINDER_ID, requestCode)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = ctx.getSystemService(AlarmManager::class.java)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // Fallback to inexact alarm
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }

            val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            ToolResult.Success("Reminder set for ${fmt.format(Date(triggerTime))}: \"$text\"")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set reminder", e)
            ToolResult.Error("Failed to set reminder: ${e.message}")
        }
    }

    private fun setTimer(ctx: Context, params: Map<String, String>): ToolResult {
        val duration = params["duration"] ?: return ToolResult.Error("Missing duration parameter")
        val label = params["label"] ?: "Timer"

        val millis = parseDuration(duration)
            ?: return ToolResult.Error("Invalid duration. Use formats like '5m', '1h30m', '90s'")

        if (millis <= 0) return ToolResult.Error("Duration must be positive")

        // Use AlarmClock intent for timer (system timer UI)
        val seconds = (millis / 1000).toInt()
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            ctx.startActivity(intent)
            ToolResult.Success("Timer set: $label for ${formatDuration(millis)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set timer", e)
            ToolResult.Error("Failed to set timer: ${e.message}")
        }
    }

    private fun parseDatetime(input: String): Long? {
        // Try full format first: yyyy-MM-dd HH:MM
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return fmt.parse(input)?.time
        } catch (_: Exception) {}

        // Try time-only: HH:MM (assumes today)
        try {
            val parts = input.split(":")
            if (parts.size == 2) {
                val hour = parts[0].trim().toInt()
                val minute = parts[1].trim().toInt()
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                // If time already passed today, set for tomorrow
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                return cal.timeInMillis
            }
        } catch (_: Exception) {}

        return null
    }

    private fun parseDuration(input: String): Long? {
        var total = 0L
        val regex = Regex("(\\d+)([hms])")
        val matches = regex.findAll(input.lowercase())
        if (matches.none()) {
            // Try plain number (assume minutes)
            val mins = input.trim().toLongOrNull() ?: return null
            return mins * 60_000
        }
        for (match in matches) {
            val value = match.groupValues[1].toLong()
            when (match.groupValues[2]) {
                "h" -> total += value * 3_600_000
                "m" -> total += value * 60_000
                "s" -> total += value * 1_000
            }
        }
        return if (total > 0) total else null
    }

    private fun formatDuration(millis: Long): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (m > 0) append("${m}m ")
            if (s > 0 && h == 0L) append("${s}s")
        }.trim()
    }

    companion object {
        private const val TAG = "ReminderModule"
        const val ACTION_REMINDER = "com.hermie.assistant.ACTION_REMINDER"
        const val EXTRA_REMINDER_TEXT = "reminder_text"
        const val EXTRA_REMINDER_ID = "reminder_id"
    }
}

/**
 * Broadcast receiver for reminder alarms — fires a Hermie notification.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderModule.ACTION_REMINDER) return

        val text = intent.getStringExtra(ReminderModule.EXTRA_REMINDER_TEXT) ?: return
        val id = intent.getIntExtra(ReminderModule.EXTRA_REMINDER_ID, 0)

        HermieNotificationHelper.notify(
            context = context,
            title = "Reminder",
            message = text,
            mood = MascotMood.HAPPY,
            type = HermieNotificationHelper.NotificationType.REMINDER,
            notificationId = id
        )
    }
}
