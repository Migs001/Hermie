package com.hermie.assistant.modules.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.hermie.assistant.modules.*

/**
 * Tool module for setting and canceling alarms via Android's AlarmClock intents.
 */
class AlarmModule : HermieModule, ToolModule {

    override val id = "alarm"
    override val displayName = "Alarms"
    override val description = "Set and cancel alarms"
    override val iconName = "alarm"
    override var isActive: Boolean = false
        private set
    override val availableInChatMode = true

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
            name = "alarm.set",
            description = "Set an alarm at a specific time. Time format: HH:MM (24h).",
            parameters = mapOf(
                "time" to ToolParam("str", "Time in HH:MM format (24h)", required = true),
                "label" to ToolParam("str", "Label for the alarm", required = false),
                "days" to ToolParam("str", "Days to repeat: mon,tue,wed,thu,fri,sat,sun — or leave empty for one-time", required = false)
            )
        ),
        ToolDefinition(
            name = "alarm.cancel",
            description = "Cancel/dismiss alarms. Searches by label if provided.",
            parameters = mapOf(
                "label" to ToolParam("str", "Label of alarm to cancel", required = false)
            )
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        val ctx = context ?: return ToolResult.Error("Alarm module not initialized")

        return when (name) {
            "alarm.set" -> setAlarm(ctx, params)
            "alarm.cancel" -> cancelAlarm(ctx, params)
            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    private fun setAlarm(ctx: Context, params: Map<String, String>): ToolResult {
        val time = params["time"] ?: return ToolResult.Error("Missing time parameter")
        val parts = time.split(":")
        if (parts.size != 2) return ToolResult.Error("Invalid time format. Use HH:MM")

        val hour = parts[0].toIntOrNull() ?: return ToolResult.Error("Invalid hour")
        val minute = parts[1].toIntOrNull() ?: return ToolResult.Error("Invalid minute")

        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult.Error("Time out of range (00:00-23:59)")
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            params["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }

            params["days"]?.let { daysStr ->
                val dayMap = mapOf(
                    "mon" to java.util.Calendar.MONDAY,
                    "tue" to java.util.Calendar.TUESDAY,
                    "wed" to java.util.Calendar.WEDNESDAY,
                    "thu" to java.util.Calendar.THURSDAY,
                    "fri" to java.util.Calendar.FRIDAY,
                    "sat" to java.util.Calendar.SATURDAY,
                    "sun" to java.util.Calendar.SUNDAY
                )
                val days = daysStr.split(",").mapNotNull { dayMap[it.trim().lowercase()] }
                if (days.isNotEmpty()) {
                    putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            ctx.startActivity(intent)
            val label = params["label"]?.let { " '$it'" } ?: ""
            ToolResult.Success("Alarm$label set for ${"%02d:%02d".format(hour, minute)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm", e)
            ToolResult.Error("Failed to set alarm: ${e.message}")
        }
    }

    private fun cancelAlarm(ctx: Context, params: Map<String, String>): ToolResult {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            params["label"]?.let { putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL) }
            params["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            ctx.startActivity(intent)
            ToolResult.Success("Alarm dismissed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel alarm", e)
            ToolResult.Error("Failed to dismiss alarm: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AlarmModule"
    }
}
