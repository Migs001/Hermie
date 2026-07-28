package com.hermie.assistant.modules.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ─── Routing model output ────────────────────────────────────────────────────

data class RoutedToolCall(
    val tool: String?,
    val function: String?,
    val content: String?,
    val params: Map<String, Any?>?
)

data class RoutedToolResult(
    val success: Boolean,
    val message: String?,
    val data: Map<String, Any?>? = null,
    val intentFired: Boolean = false
)

// ─── Router ──────────────────────────────────────────────────────────────────

class ToolRouter(private val context: Context) {

    /**
     * Parse routing model JSON output → RoutedToolCall.
     * Returns null on non-JSON or when tool field is absent/null (→ pass to Brain).
     */
    fun parse(json: String): RoutedToolCall? {
        return try {
            val obj = JSONObject(json.trim())
            val tool = if (obj.isNull("tool")) null
                       else obj.optString("tool").takeIf { it.isNotBlank() && it != "null" }
            RoutedToolCall(
                tool = tool,
                function = obj.optString("function").takeIf { it.isNotBlank() && it != "null" },
                content = obj.optString("content").takeIf { it.isNotBlank() && it != "null" },
                params = obj.optJSONObject("params")?.let { p ->
                    val map = mutableMapOf<String, Any?>()
                    p.keys().forEach { key -> map[key] = p.get(key) }
                    map
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Execute a routed tool call. Returns a result with success=false and null message
     * when the call should fall through to the Brain LLM.
     */
    suspend fun route(call: RoutedToolCall): RoutedToolResult {
        val tool = call.tool ?: return fallthrough()
        if (tool == "noop") return RoutedToolResult(true, null)
        val fn   = call.function ?: return RoutedToolResult(false, "Missing function for tool: $tool")

        return when ("$tool.$fn") {
            "phone.call"           -> executePhoneCall(call)
            "phone.text"           -> executePhoneText(call)
            "whatsapp.send"        -> executeWhatsApp(call)
            "email.compose"        -> executeEmail(call)
            "web.open"             -> executeWebOpen(call)
            "app.open"             -> executeAppOpen(call)
            "maps.search"          -> executeMapsSearch(call)
            "navigate.directions"  -> executeNavigateDirections(call)
            "camera.photo"         -> executeCameraPhoto(call)
            "music.play"           -> executeMusicPlay(call)
            "music.pause"          -> executeMusicPause()
            "music.resume"         -> executeMusicResume()
            "music.skip"           -> executeMusicSkip()
            "music.previous"       -> executeMusicPrevious()
            "volume.set"           -> executeVolumeSet(call)
            "alarm.set"            -> executeAlarmSet(call)
            "alarm.cancel"         -> executeAlarmCancel(call)
            "timer.set"            -> executeTimerSet(call)
            "timer.cancel"         -> executeTimerCancel()
            "reminder.set"         -> executeReminderSet(call)
            "calendar.view"        -> executeCalendarView(call)
            "calendar.create"      -> executeCalendarCreate(call)
            "flashlight.toggle"    -> executeFlashlightToggle(call)
            "wifi.toggle"          -> executeWifiToggle()
            "bluetooth.toggle"     -> executeBluetoothToggle()
            else                   -> fallthrough()
        }
    }

    private fun fallthrough() = RoutedToolResult(false, null)

    // ─── Phone ───────────────────────────────────────────────────────────────

    private fun executePhoneCall(call: RoutedToolCall): RoutedToolResult {
        val contact = call.p("contact") ?: call.content
            ?: return RoutedToolResult(false, "No contact specified")
        val number = resolveOrRaw(contact)
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).newTask()
        return fireIntent(intent, "Calling $contact")
    }

    private fun executePhoneText(call: RoutedToolCall): RoutedToolResult {
        val contact = call.p("contact")
            ?: return RoutedToolResult(false, "No contact specified")
        val number = resolveOrRaw(contact)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).newTask().apply {
            call.content?.let { putExtra("sms_body", it) }
        }
        return fireIntent(intent, "Opened SMS to $contact")
    }

    private fun executeWhatsApp(call: RoutedToolCall): RoutedToolResult {
        val contact = call.p("contact")
            ?: return RoutedToolResult(false, "No contact specified")

        val number = if (contact.matches(Regex("[0-9+]+"))) {
            contact.replace("+", "")
        } else {
            val raw = resolveContactPhone(contact)
                ?: return RoutedToolResult(false, "Contact not found: $contact")
            raw.replace(Regex("[^0-9]"), "").trimStart('0')
        }

        val encodedBody = call.content?.let { Uri.encode(it) } ?: ""
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=$encodedBody")).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            RoutedToolResult(true, "Opened WhatsApp to $contact", intentFired = true)
        } catch (_: Exception) {
            // WhatsApp not installed — open wa.me in browser
            val fallback = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/$number?text=$encodedBody")).newTask()
            fireIntent(fallback, "Opened WhatsApp web to $contact")
        }
    }

    private fun executeEmail(call: RoutedToolCall): RoutedToolResult {
        val to = call.p("to") ?: return RoutedToolResult(false, "No recipient specified")
        val email = if (to.contains("@")) to else resolveContactEmail(to) ?: to
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(email)}")).newTask().apply {
            call.p("subject")?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            call.content?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
        return fireIntent(intent, "Opened email to $to")
    }

    // ─── Web / Apps ──────────────────────────────────────────────────────────

    private fun executeWebOpen(call: RoutedToolCall): RoutedToolResult {
        var url = call.content ?: return RoutedToolResult(false, "No URL specified")
        if (!url.startsWith("http")) url = "https://$url"
        return fireIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)).newTask(), "Opened $url")
    }

    private fun executeAppOpen(call: RoutedToolCall): RoutedToolResult {
        val app = call.content ?: return RoutedToolResult(false, "No app specified")
        val pm = context.packageManager

        var intent = pm.getLaunchIntentForPackage(app)
        if (intent == null) {
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val match = allApps.firstOrNull {
                pm.getApplicationLabel(it).toString().equals(app, ignoreCase = true)
            } ?: allApps.firstOrNull {
                pm.getApplicationLabel(it).toString().contains(app, ignoreCase = true)
            }
            if (match != null) intent = pm.getLaunchIntentForPackage(match.packageName)
        }

        if (intent == null) return RoutedToolResult(false, "App not found: $app")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return fireIntent(intent, "Opened $app")
    }

    private fun executeMapsSearch(call: RoutedToolCall): RoutedToolResult {
        val query = call.content ?: return RoutedToolResult(false, "No query specified")
        return fireIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")).newTask(),
            "Searching maps for $query"
        )
    }

    private fun executeNavigateDirections(call: RoutedToolCall): RoutedToolResult {
        val dest = call.content ?: return RoutedToolResult(false, "No destination specified")
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(dest)}")
        return fireIntent(Intent(Intent.ACTION_VIEW, uri).newTask(), "Getting directions to $dest")
    }

    // ─── Camera ──────────────────────────────────────────────────────────────

    private fun executeCameraPhoto(call: RoutedToolCall): RoutedToolResult {
        if (call.p("delay") != null || call.p("snaps") != null) {
            Log.d(TAG, "camera.photo: delay/snaps not yet supported, opening default camera")
        }
        return fireIntent(
            Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).newTask(),
            "Camera opened"
        )
    }

    // ─── Music ───────────────────────────────────────────────────────────────

    private fun executeMusicPlay(call: RoutedToolCall): RoutedToolResult {
        val query = call.content
        if (query != null) {
            val spotifyIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("spotify:search:${Uri.encode(query)}")).newTask()
            try {
                context.startActivity(spotifyIntent)
                return RoutedToolResult(true, "Playing $query on Spotify", intentFired = true)
            } catch (_: Exception) {}

            val mediaIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return fireIntent(mediaIntent, "Playing $query")
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        return RoutedToolResult(true, "Play", intentFired = false)
    }

    private fun executeMusicPause()    = mediaKeyResult(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused")
    private fun executeMusicResume()   = mediaKeyResult(KeyEvent.KEYCODE_MEDIA_PLAY,  "Resumed")
    private fun executeMusicSkip()     = mediaKeyResult(KeyEvent.KEYCODE_MEDIA_NEXT,  "Skipped")
    private fun executeMusicPrevious() = mediaKeyResult(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous track")

    private fun mediaKeyResult(keyCode: Int, msg: String): RoutedToolResult {
        dispatchMediaKey(keyCode)
        return RoutedToolResult(true, msg, intentFired = false)
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // ─── Volume ──────────────────────────────────────────────────────────────

    private fun executeVolumeSet(call: RoutedToolCall): RoutedToolResult {
        val level = call.p("level") ?: call.content
            ?: return RoutedToolResult(false, "No level specified")
        val am    = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val flags = AudioManager.FLAG_SHOW_UI

        when (level.lowercase()) {
            "up"   -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flags)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            "max"  -> am.setStreamVolume(
                AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), flags)
            else   -> {
                val pct = level.filter { it.isDigit() }.toIntOrNull()
                    ?: return RoutedToolResult(false, "Invalid volume level: $level")
                val max    = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = pct.coerceIn(0, 100) * max / 100
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, flags)
            }
        }
        return RoutedToolResult(true, "Volume: $level", intentFired = false)
    }

    // ─── Alarm / Timer / Reminder ────────────────────────────────────────────

    private fun executeAlarmSet(call: RoutedToolCall): RoutedToolResult {
        val timeExpr = call.content ?: return RoutedToolResult(false, "No time specified")
        val cal = parseTimeExpr(timeExpr)
            ?: return RoutedToolResult(false, "Couldn't parse time: $timeExpr")

        val hour   = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            call.p("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return fireIntent(intent, "Alarm set for %02d:%02d".format(hour, minute))
    }

    private fun executeAlarmCancel(call: RoutedToolCall): RoutedToolResult {
        val label = call.content ?: call.p("label")
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            if (label != null) {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return fireIntent(intent, "Alarm dismissed")
    }

    private fun executeTimerSet(call: RoutedToolCall): RoutedToolResult {
        val expr  = call.content ?: return RoutedToolResult(false, "No duration specified")
        val label = call.p("label") ?: "Timer"
        val ms    = parseDuration(expr)
            ?: return RoutedToolResult(false, "Couldn't parse duration: $expr")

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, (ms / 1000).toInt())
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return fireIntent(intent, "Timer set: ${formatDuration(ms)}")
    }

    private fun executeTimerCancel(): RoutedToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return RoutedToolResult(false, "Timer cancel requires Android 12+")
        }
        return fireIntent(
            Intent(AlarmClock.ACTION_DISMISS_TIMER).newTask(),
            "Timer cancelled"
        )
    }

    private fun executeReminderSet(call: RoutedToolCall): RoutedToolResult {
        val text     = call.content ?: return RoutedToolResult(false, "No reminder text")
        val timeExpr = call.p("time") ?: return RoutedToolResult(false, "No time specified")
        val cal      = parseTimeExpr(timeExpr)
            ?: return RoutedToolResult(false, "Couldn't parse time: $timeExpr")

        val triggerMs = cal.timeInMillis
        if (triggerMs <= System.currentTimeMillis()) {
            return RoutedToolResult(false, "Reminder time is in the past")
        }

        val reqCode = (text + triggerMs).hashCode().and(0x7FFFFFFF)
        val bi = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderModule.ACTION_REMINDER
            putExtra(ReminderModule.EXTRA_REMINDER_TEXT, text)
            putExtra(ReminderModule.EXTRA_REMINDER_ID, reqCode)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context, reqCode, bi,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(android.app.AlarmManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.set(android.app.AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } else {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
            val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            RoutedToolResult(true, "Reminder set for ${fmt.format(Date(triggerMs))}: \"$text\"")
        } catch (e: Exception) {
            RoutedToolResult(false, "Failed to set reminder: ${e.message}")
        }
    }

    // ─── Calendar ────────────────────────────────────────────────────────────

    private fun executeCalendarView(call: RoutedToolCall): RoutedToolResult {
        val timeExpr = call.content ?: "today"
        val cal      = parseTimeExpr(timeExpr) ?: Calendar.getInstance()
        val startMs  = cal.timeInMillis
        val endMs    = startMs + 24 * 60 * 60 * 1000L

        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time").appendPath(startMs.toString()).build()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return fireIntent(intent, "Opened calendar for $timeExpr")
    }

    private fun executeCalendarCreate(call: RoutedToolCall): RoutedToolResult {
        val title = call.content ?: return RoutedToolResult(false, "No event title specified")
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            call.p("time")?.let { expr ->
                parseTimeExpr(expr)?.let { cal ->
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 3_600_000L)
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return fireIntent(intent, "Creating calendar event: $title")
    }

    // ─── Flashlight ──────────────────────────────────────────────────────────

    private fun executeFlashlightToggle(call: RoutedToolCall): RoutedToolResult {
        val enable = (call.p("state") ?: "on").lowercase() != "off"
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull()
                ?: return RoutedToolResult(false, "No camera found")
            cm.setTorchMode(id, enable)
            RoutedToolResult(true, "Flashlight ${if (enable) "on" else "off"}")
        } catch (e: Exception) {
            RoutedToolResult(false, "Flashlight error: ${e.message}")
        }
    }

    // ─── Connectivity ────────────────────────────────────────────────────────

    private fun executeWifiToggle() = fireIntent(
        Intent(Settings.ACTION_WIFI_SETTINGS).newTask(), "Opened WiFi settings"
    )

    private fun executeBluetoothToggle() = fireIntent(
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).newTask(), "Opened Bluetooth settings"
    )

    // ─── Contact resolution ──────────────────────────────────────────────────

    private fun resolveOrRaw(contact: String): String {
        if (contact.matches(Regex("[0-9+\\-() ]+"))) return contact
        return resolveContactPhone(contact) ?: contact
    }

    private fun resolveContactPhone(name: String): String? {
        if (!hasContactsPerm()) return null
        return queryContact(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            name
        )
    }

    private fun resolveContactEmail(name: String): String? {
        if (!hasContactsPerm()) return null
        return queryContact(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
            name
        )
    }

    private fun queryContact(uri: android.net.Uri, valueCol: String, nameCol: String, name: String): String? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri, arrayOf(valueCol, nameCol),
                "$nameCol LIKE ?", arrayOf("%$name%"), null
            )
            if (cursor?.moveToFirst() == true) cursor.getString(0) else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun hasContactsPerm(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    // ─── Time expression parser ──────────────────────────────────────────────
    //
    // Handles: "tomorrow", "next monday", "in 2 hours", "5pm", "10:30",
    //          "the 15th", "march 5th", "march 5th at 10am", "2025-03-05 14:30"

    private fun parseTimeExpr(expr: String): Calendar? {
        val input = expr.trim().lowercase()
        val now   = Calendar.getInstance()
        val cal   = Calendar.getInstance().also {
            it.set(Calendar.SECOND, 0); it.set(Calendar.MILLISECOND, 0)
        }

        // ISO: 2025-03-05 14:30
        Regex("""(\d{4})-(\d{2})-(\d{2})\s+(\d{1,2}):(\d{2})""").find(input)?.let { m ->
            cal.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1,
                m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt())
            return cal
        }

        // HH:MM
        if (Regex("""^\d{1,2}:\d{2}$""").matches(input)) {
            val (h, min) = input.split(":").map { it.toInt() }
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
            if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal
        }

        // 5pm / 5:30pm
        Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)""").find(input)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (m.groupValues[3] == "pm" && h < 12) h += 12
            if (m.groupValues[3] == "am" && h == 12) h = 0
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
            if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal
        }

        // "in X hours/minutes/days"
        Regex("""in\s+(\d+)\s+(minute|hour|day)s?""").find(input)?.let { m ->
            val n = m.groupValues[1].toInt()
            when (m.groupValues[2]) {
                "minute" -> cal.add(Calendar.MINUTE, n)
                "hour"   -> cal.add(Calendar.HOUR_OF_DAY, n)
                "day"    -> cal.add(Calendar.DAY_OF_YEAR, n)
            }
            return cal
        }

        // "tomorrow [at ...]"
        if (input.startsWith("tomorrow")) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            Regex("""tomorrow\s+at\s+(.+)""").find(input)?.let { m ->
                parseTimeExpr(m.groupValues[1])?.let { t ->
                    cal.set(Calendar.HOUR_OF_DAY, t.get(Calendar.HOUR_OF_DAY))
                    cal.set(Calendar.MINUTE, t.get(Calendar.MINUTE))
                }
            }
            if (cal.get(Calendar.HOUR_OF_DAY) == now.get(Calendar.HOUR_OF_DAY) &&
                cal.get(Calendar.MINUTE) == now.get(Calendar.MINUTE)) {
                cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0)
            }
            return cal
        }

        // "today [at ...]"
        if (input.startsWith("today")) {
            Regex("""today\s+at\s+(.+)""").find(input)?.let { m ->
                return parseTimeExpr(m.groupValues[1])
            }
            return cal
        }

        // Day names: "monday", "next friday", "friday at 3pm"
        val dayMap = mapOf(
            "sunday" to Calendar.SUNDAY,   "monday" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY
        )
        for ((name, dayOfWeek) in dayMap) {
            if (!input.contains(name)) continue
            val isNext    = input.startsWith("next")
            val curDay    = cal.get(Calendar.DAY_OF_WEEK)
            var daysAhead = dayOfWeek - curDay
            if (daysAhead <= 0 || isNext) daysAhead += 7
            cal.add(Calendar.DAY_OF_YEAR, daysAhead)
            Regex("""$name\s+at\s+(.+)""").find(input)?.let { m ->
                parseTimeExpr(m.groupValues[1])?.let { t ->
                    cal.set(Calendar.HOUR_OF_DAY, t.get(Calendar.HOUR_OF_DAY))
                    cal.set(Calendar.MINUTE, t.get(Calendar.MINUTE))
                }
            }
            return cal
        }

        // Month names: "march 5th [at 10am]"
        val monthMap = mapOf(
            "january" to 0,  "february" to 1,  "march" to 2,      "april" to 3,
            "may" to 4,      "june" to 5,       "july" to 6,       "august" to 7,
            "september" to 8,"october" to 9,    "november" to 10,  "december" to 11,
            "jan" to 0,      "feb" to 1,        "mar" to 2,        "apr" to 3,
            "jun" to 5,      "jul" to 6,        "aug" to 7,        "sep" to 8,
            "oct" to 9,      "nov" to 10,       "dec" to 11
        )
        for ((mName, mIdx) in monthMap) {
            Regex("""$mName\s+(\d{1,2})(?:st|nd|rd|th)?(?:\s+at\s+(.+))?""").find(input)?.let { m ->
                val day = m.groupValues[1].toIntOrNull() ?: return@let
                cal.set(Calendar.MONTH, mIdx); cal.set(Calendar.DAY_OF_MONTH, day)
                if (cal.timeInMillis < now.timeInMillis) cal.add(Calendar.YEAR, 1)
                m.groupValues[2].takeIf { it.isNotBlank() }?.let { t ->
                    parseTimeExpr(t)?.let { tc ->
                        cal.set(Calendar.HOUR_OF_DAY, tc.get(Calendar.HOUR_OF_DAY))
                        cal.set(Calendar.MINUTE, tc.get(Calendar.MINUTE))
                    }
                }
                return cal
            }
        }

        // "the 15th" / "on the 3rd"
        Regex("""(?:the|on the)?\s*(\d{1,2})(?:st|nd|rd|th)""").find(input)?.let { m ->
            cal.set(Calendar.DAY_OF_MONTH, m.groupValues[1].toInt())
            if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.MONTH, 1)
            return cal
        }

        return null
    }

    // ─── Duration parser ─────────────────────────────────────────────────────
    //
    // Handles: "10 minutes", "90 seconds", "an hour and a half", "1h30m", "2h"

    private fun parseDuration(expr: String): Long? {
        val input = expr.trim().lowercase()
        var total = 0L

        if (input == "an hour" || input == "a hour") return 3_600_000L
        if (input == "a minute")                      return 60_000L
        if (input == "a second")                      return 1_000L
        if (input.contains("hour") && input.contains("half")) return 5_400_000L
        if (input.contains("minute") && input.contains("half")) return 90_000L

        // Short codes: 1h30m, 5m, 90s
        val shortMatches = Regex("""(\d+)\s*([hms])""").findAll(input)
        if (shortMatches.any()) {
            shortMatches.forEach { m ->
                val v = m.groupValues[1].toLong()
                when (m.groupValues[2]) {
                    "h" -> total += v * 3_600_000
                    "m" -> total += v * 60_000
                    "s" -> total += v * 1_000
                }
            }
            return if (total > 0) total else null
        }

        // Written: "10 minutes", "2 hours", "90 seconds"
        val wordPattern = Regex("""(\d+|an?|one|two|three|four|five|ten|fifteen|twenty|thirty|forty|fifty|sixty|ninety)\s+(hour|minute|second)s?""")
        for (m in wordPattern.findAll(input)) {
            val v = wordToNum(m.groupValues[1]) ?: continue
            when (m.groupValues[2]) {
                "hour"   -> total += v * 3_600_000
                "minute" -> total += v * 60_000
                "second" -> total += v * 1_000
            }
        }
        if (total > 0) return total

        // Plain number → minutes
        return input.trim().toLongOrNull()?.times(60_000)
    }

    private fun wordToNum(w: String): Long? = when (w.lowercase()) {
        "a", "an", "one" -> 1L;  "two" -> 2L;      "three" -> 3L
        "four" -> 4L;             "five" -> 5L;      "ten" -> 10L
        "fifteen" -> 15L;         "twenty" -> 20L;   "thirty" -> 30L
        "forty" -> 40L;           "fifty" -> 50L;    "sixty" -> 60L
        "ninety" -> 90L;          else -> w.toLongOrNull()
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (m > 0) append("${m}m ")
            if (sec > 0 && h == 0L) append("${sec}s")
        }.trim()
    }

    // ─── Utilities ───────────────────────────────────────────────────────────

    private fun fireIntent(intent: Intent, successMsg: String): RoutedToolResult {
        return try {
            context.startActivity(intent)
            RoutedToolResult(true, successMsg, intentFired = true)
        } catch (e: Exception) {
            Log.e(TAG, "Intent failed: ${e.message}")
            RoutedToolResult(false, "Failed: ${e.message}")
        }
    }

    private fun Intent.newTask() = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // Short param accessor; handles String/Int/Double/Boolean from JSONObject
    private fun RoutedToolCall.p(key: String): String? =
        params?.get(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }

    companion object {
        private const val TAG = "ToolRouter"
    }
}
