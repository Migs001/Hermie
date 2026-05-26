package com.hermie.assistant.modules.tools

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.hermie.assistant.modules.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tool module for reading and writing to the device calendar.
 * Requires READ_CALENDAR / WRITE_CALENDAR permissions.
 */
class CalendarModule : HermieModule, ToolModule {

    override val id = "calendar"
    override val displayName = "Calendar"
    override val description = "Check and add calendar events"
    override val iconName = "calendar_today"
    override var isActive: Boolean = false
        private set
    // Only the read tool (calendar.check) is safe for hands-free chat.
    // calendar.add writes to the calendar and should only run during deliberate task execution.
    override val availableInChatMode = true
    override val chatModeToolNames: Set<String> = setOf("calendar.check")

    override val requiredPermissions = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    private var context: Context? = null

    override suspend fun initialize(context: Context) {
        this.context = context
        isActive = hasPermission(context)
    }

    override suspend fun start() {
        isActive = context?.let { hasPermission(it) } ?: false
    }

    override suspend fun stop() { isActive = false }
    override fun release() { context = null }

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "calendar.check",
            description = "Check calendar events for a date. Returns list of events.",
            parameters = mapOf(
                "date" to ToolParam("str", "Date to check: 'today', 'tomorrow', or 'yyyy-MM-dd'. Default: today", required = false)
            )
        ),
        ToolDefinition(
            name = "calendar.add",
            description = "Add a new calendar event.",
            parameters = mapOf(
                "title" to ToolParam("str", "Event title", required = true),
                "datetime" to ToolParam("str", "Start time: 'yyyy-MM-dd HH:mm'", required = true),
                "duration" to ToolParam("str", "Duration like '1h', '30m', '1h30m'. Default: 1h", required = false),
                "location" to ToolParam("str", "Event location", required = false)
            )
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        val ctx = context ?: return ToolResult.Error("Calendar module not initialized")
        if (!hasPermission(ctx)) {
            return ToolResult.Error("Calendar permission not granted. Go to Settings > Apps > Hermie > Permissions.")
        }

        return when (name) {
            "calendar.check" -> checkCalendar(ctx, params)
            "calendar.add" -> addEvent(ctx, params)
            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    private fun checkCalendar(ctx: Context, params: Map<String, String>): ToolResult {
        val dateStr = params["date"] ?: "today"
        val (startMillis, endMillis) = parseDateRange(dateStr)
            ?: return ToolResult.Error("Invalid date format. Use 'today', 'tomorrow', or 'yyyy-MM-dd'")

        val events = mutableListOf<String>()
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY
        )

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?"
        val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())

        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection, selection, selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )

            val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            cursor?.let {
                while (it.moveToNext()) {
                    val title = it.getString(0) ?: "Untitled"
                    val start = it.getLong(1)
                    val end = it.getLong(2)
                    val location = it.getString(3)
                    val allDay = it.getInt(4) == 1

                    val entry = buildString {
                        if (allDay) {
                            append("All day: $title")
                        } else {
                            append("${timeFmt.format(Date(start))}")
                            if (end > 0) append("-${timeFmt.format(Date(end))}")
                            append(": $title")
                        }
                        if (!location.isNullOrBlank()) append(" @ $location")
                    }
                    events.add(entry)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query calendar", e)
            return ToolResult.Error("Failed to read calendar: ${e.message}")
        } finally {
            cursor?.close()
        }

        val dateFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        val dateLabel = dateFmt.format(Date(startMillis))

        return if (events.isEmpty()) {
            ToolResult.Success("No events on $dateLabel.")
        } else {
            ToolResult.Success("Events on $dateLabel:\n${events.joinToString("\n") { "- $it" }}")
        }
    }

    private fun addEvent(ctx: Context, params: Map<String, String>): ToolResult {
        val title = params["title"] ?: return ToolResult.Error("Missing title")
        val datetime = params["datetime"] ?: return ToolResult.Error("Missing datetime")
        val durationStr = params["duration"] ?: "1h"

        val startMillis = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(datetime)?.time
        } catch (_: Exception) { null }
            ?: return ToolResult.Error("Invalid datetime. Use 'yyyy-MM-dd HH:mm'")

        val durationMillis = parseDurationMillis(durationStr) ?: 3_600_000L

        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, startMillis + durationMillis)
            put(CalendarContract.Events.CALENDAR_ID, getDefaultCalendarId(ctx) ?: 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            params["location"]?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }

        return try {
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                ToolResult.Success("Event added: $title on ${fmt.format(Date(startMillis))}")
            } else {
                ToolResult.Error("Failed to insert calendar event")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add calendar event", e)
            ToolResult.Error("Failed to add event: ${e.message}")
        }
    }

    private fun parseDateRange(input: String): Pair<Long, Long>? {
        val cal = Calendar.getInstance()
        when (input.lowercase().trim()) {
            "today" -> { /* default: today */ }
            "tomorrow" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            else -> {
                try {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(input) ?: return null
                    cal.time = date
                } catch (_: Exception) { return null }
            }
        }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return start to cal.timeInMillis
    }

    private fun parseDurationMillis(input: String): Long? {
        var total = 0L
        val regex = Regex("(\\d+)([hm])")
        for (match in regex.findAll(input.lowercase())) {
            val value = match.groupValues[1].toLong()
            when (match.groupValues[2]) {
                "h" -> total += value * 3_600_000
                "m" -> total += value * 60_000
            }
        }
        return if (total > 0) total else null
    }

    private fun getDefaultCalendarId(ctx: Context): Long? {
        var cursor: Cursor? = null
        try {
            // Try primary calendar (API 17+)
            try {
                cursor = ctx.contentResolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(CalendarContract.Calendars._ID),
                    "isPrimary = 1",
                    null, null
                )
                if (cursor?.moveToFirst() == true) return cursor.getLong(0)
            } catch (_: Exception) { /* IS_PRIMARY not available on this device */ }

            // Fallback: first writable calendar
            cursor?.close()
            cursor = ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null, null
            )
            if (cursor?.moveToFirst() == true) return cursor.getLong(0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find calendar", e)
        } finally {
            cursor?.close()
        }
        return null
    }

    companion object {
        private const val TAG = "CalendarModule"

        fun hasPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }
}
