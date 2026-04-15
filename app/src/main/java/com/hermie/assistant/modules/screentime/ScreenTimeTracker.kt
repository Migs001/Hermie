package com.hermie.assistant.modules.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * Tracks screen time per app using UsageStatsManager.
 * Requires PACKAGE_USAGE_STATS permission (user must grant in Settings).
 *
 * Can set triggers: "if user spends > X minutes on app Y, fire callback".
 * Supports graduated escalation — up to [MAX_REMINDERS] reminders per app per day:
 *   1st at limit, 2nd after 5 more minutes, 3rd after 5 more minutes, then stops.
 *
 * Also tracks app foreground/background events to detect when a monitored app
 * is closed (for congratulations) or re-opened (for disappointment).
 * Detection uses the event stream rather than polling, so nothing is missed between ticks.
 */
class ScreenTimeTracker(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val _appUsageToday = MutableStateFlow<Map<String, Long>>(emptyMap())
    val appUsageToday: StateFlow<Map<String, Long>> = _appUsageToday.asStateFlow()

    private val _triggeredApps = MutableStateFlow<Set<String>>(emptySet())
    val triggeredApps: StateFlow<Set<String>> = _triggeredApps.asStateFlow()

    /** Package name → limit in minutes */
    private var triggers: Map<String, Int> = emptyMap()

    data class EscalationState(
        val reminderCount: Int = 0,
        val lastTriggerMinute: Long = 0
    )
    private val escalationState = mutableMapOf<String, EscalationState>()

    /** Set of monitored apps that were closed after a trigger (to detect reopens) */
    private val closedAfterTrigger = mutableSetOf<String>()

    /**
     * Per-package timestamp when the app was added to closedAfterTrigger.
     * Used to distinguish "FOREGROUND event before the close" from "FOREGROUND event after".
     */
    private val closedAtTimestamp = mutableMapOf<String, Long>()

    /**
     * Timestamp of the most recent event processed for close/reopen detection.
     * Initialized to now so the first tick only fires callbacks for truly new events.
     */
    private var lastEventProcessedTs: Long = System.currentTimeMillis()

    /**
     * Called when a trigger fires.
     * escalationLevel: 0 = first warning, 1 = second (annoyed), 2 = third (firm/final)
     */
    var onTriggerFired: ((packageName: String, minutesUsed: Long, limitMinutes: Int, escalationLevel: Int) -> Unit)? = null

    /** Called when a monitored app is closed after being over limit */
    var onMonitoredAppClosed: ((packageName: String) -> Unit)? = null

    /** Called when a monitored app is re-opened after being closed */
    var onMonitoredAppReopened: ((packageName: String, minutesUsed: Long, limitMinutes: Int) -> Unit)? = null

    private var isTracking = false

    fun setTriggers(limits: Map<String, Int>) {
        val oldTriggers = triggers
        triggers = limits

        val removedApps = oldTriggers.keys - limits.keys
        removedApps.forEach { pkg ->
            escalationState.remove(pkg)
            closedAfterTrigger.remove(pkg)
            closedAtTimestamp.remove(pkg)
        }

        for ((pkg, newLimit) in limits) {
            val oldLimit = oldTriggers[pkg]
            if (oldLimit != null && oldLimit != newLimit) {
                escalationState.remove(pkg)
            }
        }

        _triggeredApps.value = _triggeredApps.value.filter { it in limits }.toSet()
    }

    /**
     * Query today's usage by reconstructing foreground intervals from the event stream.
     *
     * Pairs MOVE_TO_FOREGROUND/MOVE_TO_BACKGROUND events (and ACTIVITY_RESUMED/
     * ACTIVITY_PAUSED on API 29+) to compute exact per-package foreground duration
     * clipped to midnight–now. Open intervals (app currently in foreground) are
     * closed at [now].
     *
     * Close/reopen detection runs on the same event stream but only fires callbacks
     * for events newer than [lastEventProcessedTs], preventing duplicate callbacks
     * across ticks.
     */
    fun queryTodayUsage(): Map<String, Long> {
        if (!hasPermission()) return emptyMap()

        val now = System.currentTimeMillis()
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val prevLastTs = lastEventProcessedTs
        var maxEventTs = prevLastTs

        // Per-package timestamp of most recent FOREGROUND event (null = not in foreground)
        val foregroundSince = mutableMapOf<String, Long>()
        // Per-package accumulated foreground milliseconds
        val foregroundMs = mutableMapOf<String, Long>()

        val triggered = _triggeredApps.value

        // Deferred callbacks — fired after usageMinutes is computed so we can pass the value
        data class PendingClose(val pkg: String)
        data class PendingReopen(val pkg: String)
        val pendingCloses = mutableListOf<PendingClose>()
        val pendingReopens = mutableListOf<PendingReopen>()

        val events = usageStatsManager.queryEvents(startOfToday, now)
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            val ts = event.timeStamp
            if (ts > maxEventTs) maxEventTs = ts

            val isFg = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                 event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            val isBg = event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                 event.eventType == UsageEvents.Event.ACTIVITY_PAUSED)

            when {
                isFg -> {
                    // Start a new foreground interval if not already open
                    if (foregroundSince[pkg] == null) foregroundSince[pkg] = ts

                    // Reopen detection: new event for a package that was closed after trigger,
                    // and the event timestamp is AFTER the close timestamp (avoids re-firing
                    // for the FG event that preceded the stored close event on replay).
                    if (ts > prevLastTs) {
                        val closeTs = closedAtTimestamp[pkg]
                        if (closeTs != null && ts > closeTs) {
                            pendingReopens.add(PendingReopen(pkg))
                        }
                    }
                }
                isBg -> {
                    // Close the open foreground interval and accumulate duration
                    val fgTs = foregroundSince.remove(pkg)
                    if (fgTs != null) {
                        foregroundMs[pkg] = (foregroundMs[pkg] ?: 0L) + (ts - fgTs)
                    }

                    // Close detection: new event for a triggered app, newer than the last
                    // known close for this package (avoids re-adding on replay).
                    if (ts > prevLastTs && pkg in triggered) {
                        val prevCloseTs = closedAtTimestamp[pkg]
                        if (prevCloseTs == null || ts > prevCloseTs) {
                            // Update close timestamp eagerly so reopen detection in the same
                            // tick can correctly compare against it.
                            closedAtTimestamp[pkg] = ts
                            pendingCloses.add(PendingClose(pkg))
                        }
                    }
                }
            }
        }

        // Close any intervals still open (app is currently in foreground) at now
        for ((pkg, fgTs) in foregroundSince) {
            foregroundMs[pkg] = (foregroundMs[pkg] ?: 0L) + (now - fgTs)
        }

        lastEventProcessedTs = maxEventTs

        // Convert milliseconds → minutes (round to nearest)
        val usageMinutes = mutableMapOf<String, Long>()
        for ((pkg, ms) in foregroundMs) {
            val minutes = (ms + 30_000) / 60_000
            if (minutes > 0) usageMinutes[pkg] = minutes
        }
        _appUsageToday.value = usageMinutes

        // Fire close callbacks first so closedAfterTrigger is populated before reopen checks
        for ((pkg) in pendingCloses.distinctBy { it.pkg }) {
            if (pkg !in closedAfterTrigger) {
                closedAfterTrigger.add(pkg)
                Log.d(TAG, "Monitored app closed: $pkg")
                onMonitoredAppClosed?.invoke(pkg)
            }
        }
        for ((pkg) in pendingReopens.distinctBy { it.pkg }) {
            if (pkg in closedAfterTrigger) {
                closedAfterTrigger.remove(pkg)
                closedAtTimestamp.remove(pkg)
                val used = usageMinutes[pkg] ?: 0
                val limit = triggers[pkg] ?: 0
                Log.d(TAG, "Monitored app reopened: $pkg (${used}m used)")
                onMonitoredAppReopened?.invoke(pkg, used, limit)
            }
        }

        checkTriggers(usageMinutes)
        return usageMinutes
    }

    /**
     * Query usage for a specific time range.
     * Uses INTERVAL_BEST — week/month/year aggregates don't need single-day precision.
     */
    fun queryUsageForRange(startTimeMs: Long, endTimeMs: Long): Map<String, Long> {
        if (!hasPermission()) return emptyMap()

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            startTimeMs,
            endTimeMs
        )

        val msUsage = mutableMapOf<String, Long>()
        stats?.forEach { stat ->
            if (stat.totalTimeInForeground > 0) {
                val pkg = stat.packageName
                msUsage[pkg] = (msUsage[pkg] ?: 0L) + stat.totalTimeInForeground
            }
        }

        val usage = mutableMapOf<String, Long>()
        for ((pkg, ms) in msUsage) {
            val minutes = (ms + 30_000) / 60_000
            if (minutes > 0) usage[pkg] = minutes
        }
        return usage
    }

    /**
     * Get usage history for a specific app: last 7 days, 30 days, and year.
     */
    fun getAppUsageHistory(packageName: String): UsageHistory {
        if (!hasPermission()) return UsageHistory(0, 0, 0)

        val now = System.currentTimeMillis()
        val weekMinutes = queryUsageForRange(now - 7L * 24 * 60 * 60 * 1000, now)[packageName] ?: 0
        val monthMinutes = queryUsageForRange(now - 30L * 24 * 60 * 60 * 1000, now)[packageName] ?: 0
        val yearMinutes = queryUsageForRange(now - 365L * 24 * 60 * 60 * 1000, now)[packageName] ?: 0
        return UsageHistory(weekMinutes, monthMinutes, yearMinutes)
    }

    /**
     * Get the currently focused (foreground) app.
     * Point-in-time query — used for the level-2 home action check.
     */
    fun getCurrentForegroundApp(): String? {
        if (!hasPermission()) return null

        val endTime = System.currentTimeMillis()
        val startTime = endTime - 60_000 // Last minute

        val events = usageStatsManager.queryEvents(startTime, endTime)
        var lastApp: String? = null
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastApp = event.packageName
            }
        }

        return lastApp
    }

    /**
     * Continuous tracking loop — checks usage and fires triggers once per interval.
     */
    suspend fun startTracking(checkIntervalMs: Long = 60_000) {
        isTracking = true
        while (isTracking) {
            try {
                queryTodayUsage()
            } catch (e: Exception) {
                Log.e(TAG, "Error querying usage stats", e)
            }
            delay(checkIntervalMs)
        }
    }

    fun stopTracking() {
        isTracking = false
    }

    private fun checkTriggers(usage: Map<String, Long>) {
        val alreadyTriggered = _triggeredApps.value.toMutableSet()

        for ((pkg, limitMinutes) in triggers) {
            val used = usage[pkg] ?: 0
            if (used < limitMinutes) continue

            val state = escalationState[pkg]

            if (state == null) {
                Log.d(TAG, "Trigger fired: $pkg used ${used}m (limit: ${limitMinutes}m)")
                alreadyTriggered.add(pkg)
                escalationState[pkg] = EscalationState(reminderCount = 1, lastTriggerMinute = used)
                onTriggerFired?.invoke(pkg, used, limitMinutes, 0)
            } else if (state.reminderCount < MAX_REMINDERS) {
                val interval = ESCALATION_INTERVALS.getOrElse(state.reminderCount - 1) { 5 }
                if (used - state.lastTriggerMinute >= interval) {
                    val level = state.reminderCount // 1 = annoyed, 2 = firm
                    Log.d(TAG, "Escalation trigger (level $level): $pkg used ${used}m (last at ${state.lastTriggerMinute}m)")
                    escalationState[pkg] = EscalationState(
                        reminderCount = state.reminderCount + 1,
                        lastTriggerMinute = used
                    )
                    onTriggerFired?.invoke(pkg, used, limitMinutes, level)
                }
            }
            // If reminderCount >= MAX_REMINDERS, stop nagging until midnight
        }

        _triggeredApps.value = alreadyTriggered
    }

    fun hasPermission(): Boolean {
        return try {
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                System.currentTimeMillis() - 86_400_000,
                System.currentTimeMillis()
            )
            stats != null && stats.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    data class UsageHistory(
        val lastWeekMinutes: Long,
        val lastMonthMinutes: Long,
        val lastYearMinutes: Long
    ) {
        fun lastMonthFormatted(): String = formatMinutes(lastMonthMinutes)
        fun lastYearFormatted(): String = formatMinutes(lastYearMinutes)
        fun lastWeekFormatted(): String = formatMinutes(lastWeekMinutes)

        private fun formatMinutes(m: Long): String {
            val hours = m / 60
            val days = hours / 24
            val remainingHours = hours % 24
            return when {
                days > 0 -> "${days}d ${remainingHours}h"
                hours > 0 -> "${hours}h ${m % 60}m"
                else -> "${m}m"
            }
        }
    }

    companion object {
        private const val TAG = "ScreenTimeTracker"

        /** Max number of reminders per app per day */
        const val MAX_REMINDERS = 3

        /**
         * Minutes of additional usage to wait before each escalation.
         * Index 0 = wait after 1st reminder, index 1 = wait after 2nd.
         * 1st reminder: fires immediately at limit.
         * 2nd reminder: +5 minutes after 1st.
         * 3rd reminder: +5 minutes after 2nd.
         */
        val ESCALATION_INTERVALS = listOf(5, 5)

        fun openUsageAccessSettings(context: Context) {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun openOverlayPermissionSettings(context: Context) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Direct overlay settings failed, opening general page", e)
                try {
                    val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(fallback)
                } catch (e2: Exception) {
                    Log.e(TAG, "Overlay settings not available", e2)
                    val appSettings = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                    appSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(appSettings)
                }
            }
        }

        fun hasOverlayPermission(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }
    }
}
