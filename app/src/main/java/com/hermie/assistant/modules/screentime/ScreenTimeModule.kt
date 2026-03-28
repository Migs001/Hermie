package com.hermie.assistant.modules.screentime

import android.content.Context
import com.hermie.assistant.data.HermieSettings
import com.hermie.assistant.modules.*
import com.hermie.assistant.modules.overlay.OverlayService
import com.hermie.assistant.ui.mascot.MascotMood

/**
 * Module that monitors screen time per app and fires triggers.
 * Uses UsageStatsManager (requires user grant in Settings).
 * When a trigger fires, shows the mascot overlay with a warning.
 */
class ScreenTimeModule : HermieModule, ToolModule, BackgroundModule {

    override val id = "screentime"
    override val displayName = "Screen Time"
    override val description = "Monitor app usage and set screen time limits"
    override val iconName = "timer"
    override var isActive: Boolean = false
        private set
    override val needsBackgroundExecution = true

    override val requiredPermissions = listOf("android.permission.PACKAGE_USAGE_STATS")

    private var tracker: ScreenTimeTracker? = null
    private var context: Context? = null
    private var settings: HermieSettings? = null

    override suspend fun initialize(context: Context) {
        this.context = context
        this.settings = HermieSettings(context)
        tracker = ScreenTimeTracker(context)

        // Set up trigger callback → show overlay
        tracker?.onTriggerFired = { pkg, minutesUsed, limit ->
            val appName = getAppName(context, pkg)
            OverlayService.showFullCharacter(
                context,
                MascotMood.ANNOYED,
                "You've been on $appName for ${minutesUsed} minutes.\nMaybe take a break?"
            )
        }

        isActive = tracker?.hasPermission() ?: false
    }

    override suspend fun start() {
        if (tracker?.hasPermission() != true) return
        isActive = true

        // Load saved triggers
        val triggerMap = settings?.getScreenTimeTriggers() ?: emptyMap()
        tracker?.setTriggers(triggerMap)
    }

    override suspend fun stop() {
        tracker?.stopTracking()
        isActive = false
    }

    override fun release() {
        tracker?.stopTracking()
        tracker = null
        context = null
    }

    override suspend fun onBackgroundTick() {
        tracker?.queryTodayUsage()
    }

    // ── Tool interface ──────────────────────────────────────

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "screentime.today",
            description = "Get today's screen time usage by app",
            parameters = emptyMap()
        ),
        ToolDefinition(
            name = "screentime.app",
            description = "Get screen time for a specific app",
            parameters = mapOf(
                "app" to ToolParam("str", "App name or package name")
            )
        ),
        ToolDefinition(
            name = "screentime.limit",
            description = "Set a screen time limit for an app (triggers a warning)",
            parameters = mapOf(
                "app" to ToolParam("str", "App package name"),
                "minutes" to ToolParam("int", "Time limit in minutes")
            )
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        if (!isActive) {
            return ToolResult.Error("Screen time access not granted. Please enable Usage Access in Settings.")
        }

        return when (name) {
            "screentime.today" -> {
                val usage = tracker?.queryTodayUsage() ?: return ToolResult.Error("Tracker not available")
                if (usage.isEmpty()) {
                    ToolResult.Success("No usage data available for today.")
                } else {
                    val sorted = usage.entries.sortedByDescending { it.value }.take(10)
                    val text = sorted.joinToString("\n") { (pkg, minutes) ->
                        val appName = context?.let { getAppName(it, pkg) } ?: pkg
                        "• $appName: ${minutes}m"
                    }
                    ToolResult.Success("Today's screen time (top 10):\n$text")
                }
            }
            "screentime.app" -> {
                val app = params["app"] ?: return ToolResult.Error("Missing app parameter")
                val usage = tracker?.queryTodayUsage() ?: return ToolResult.Error("Tracker not available")
                val matching = usage.entries.filter {
                    it.key.contains(app, ignoreCase = true)
                }
                if (matching.isEmpty()) {
                    ToolResult.Success("No usage found for $app today.")
                } else {
                    val text = matching.joinToString("\n") { (pkg, minutes) ->
                        "• $pkg: ${minutes}m"
                    }
                    ToolResult.Success(text)
                }
            }
            "screentime.limit" -> {
                val app = params["app"] ?: return ToolResult.Error("Missing app parameter")
                val minutes = params["minutes"]?.toIntOrNull()
                    ?: return ToolResult.Error("Invalid minutes parameter")
                settings?.setScreenTimeTrigger(app, minutes)
                tracker?.setTriggers(settings?.getScreenTimeTriggers() ?: emptyMap())
                ToolResult.Success("Set ${minutes}m limit for $app. Hermie will warn when exceeded.")
            }
            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    private fun getAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }
}
