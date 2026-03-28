package com.hermie.assistant.modules.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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

    /** Called when a trigger fires */
    var onTriggerFired: ((packageName: String, minutesUsed: Long, limitMinutes: Int) -> Unit)? = null

    private var isTracking = false

    fun setTriggers(limits: Map<String, Int>) {
        triggers = limits
        _triggeredApps.value = emptySet() // Reset triggers for new config
    }

    /**
     * Query today's usage stats. Returns map of package name → minutes used.
     */
    fun queryTodayUsage(): Map<String, Long> {
        if (!hasPermission()) return emptyMap()

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        val usage = mutableMapOf<String, Long>()
        stats?.forEach { stat ->
            val minutes = stat.totalTimeInForeground / 60_000
            if (minutes > 0) {
                usage[stat.packageName] = minutes
            }
        }

        _appUsageToday.value = usage
        return usage
    }

    /**
     * Get the currently focused (foreground) app.
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
     * Continuous tracking loop — checks usage and fires triggers.
     * Call from a coroutine scope (e.g., background service).
     */
    suspend fun startTracking(checkIntervalMs: Long = 60_000) {
        isTracking = true
        while (isTracking) {
            try {
                val usage = queryTodayUsage()
                checkTriggers(usage)
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
            if (used >= limitMinutes && pkg !in alreadyTriggered) {
                Log.d(TAG, "Trigger fired: $pkg used ${used}m (limit: ${limitMinutes}m)")
                alreadyTriggered.add(pkg)
                onTriggerFired?.invoke(pkg, used, limitMinutes)
            }
        }

        _triggeredApps.value = alreadyTriggered
    }

    fun hasPermission(): Boolean {
        return try {
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                System.currentTimeMillis() - 60_000,
                System.currentTimeMillis()
            )
            stats != null && stats.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "ScreenTimeTracker"

        fun openUsageAccessSettings(context: Context) {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
