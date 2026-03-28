package com.hermie.assistant.modules.notifications

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.hermie.assistant.modules.*

/**
 * Module that reads notifications from other apps.
 * Uses NotificationListenerService (requires user to grant access in Settings).
 */
class NotificationModule : HermieModule, ToolModule {

    override val id = "notifications"
    override val displayName = "Notifications"
    override val description = "Read and summarize notifications from other apps"
    override val iconName = "notifications"
    override var isActive: Boolean = false
        private set

    override val requiredPermissions = listOf("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")

    private var context: Context? = null

    override suspend fun initialize(context: Context) {
        this.context = context
        isActive = isNotificationAccessGranted(context)
    }

    override suspend fun start() {
        isActive = context?.let { isNotificationAccessGranted(it) } ?: false
    }

    override suspend fun stop() {
        isActive = false
    }

    override fun release() {
        context = null
    }

    // ── Tool interface ──────────────────────────────────────

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "notification.recent",
            description = "Get recent notifications",
            parameters = mapOf(
                "count" to ToolParam("int", "Number of recent notifications to return", required = false)
            )
        ),
        ToolDefinition(
            name = "notification.from",
            description = "Get notifications from a specific app",
            parameters = mapOf(
                "app" to ToolParam("str", "App name or package name")
            )
        ),
        ToolDefinition(
            name = "notification.summary",
            description = "Get a summary of unread notifications",
            parameters = emptyMap()
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        if (!isActive) {
            return ToolResult.Error("Notification access not granted. Please enable it in Settings > Notifications > Notification access.")
        }

        val notifications = HermieNotificationListener.recentNotifications.value

        return when (name) {
            "notification.recent" -> {
                val count = params["count"]?.toIntOrNull() ?: 5
                val recent = notifications.take(count)
                if (recent.isEmpty()) {
                    ToolResult.Success("No recent notifications.")
                } else {
                    val text = recent.joinToString("\n") { n ->
                        "• ${n.title}: ${n.text} (${n.packageName})"
                    }
                    ToolResult.Success(text)
                }
            }
            "notification.from" -> {
                val app = params["app"] ?: return ToolResult.Error("Missing app parameter")
                val matching = notifications.filter {
                    it.packageName.contains(app, ignoreCase = true) ||
                    it.title.contains(app, ignoreCase = true)
                }
                if (matching.isEmpty()) {
                    ToolResult.Success("No notifications from $app.")
                } else {
                    val text = matching.joinToString("\n") { "• ${it.title}: ${it.text}" }
                    ToolResult.Success(text)
                }
            }
            "notification.summary" -> {
                if (notifications.isEmpty()) {
                    ToolResult.Success("No notifications to summarize.")
                } else {
                    val byApp = notifications.groupBy { it.packageName }
                    val summary = byApp.entries.joinToString("\n") { (pkg, notifs) ->
                        "• $pkg: ${notifs.size} notification(s) — latest: ${notifs.first().title}"
                    }
                    ToolResult.Success("${notifications.size} recent notifications:\n$summary")
                }
            }
            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    companion object {
        fun isNotificationAccessGranted(context: Context): Boolean {
            val cn = ComponentName(context, HermieNotificationListener::class.java)
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabledListeners.contains(cn.flattenToString())
        }

        fun openNotificationAccessSettings(context: Context) {
            val intent = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
