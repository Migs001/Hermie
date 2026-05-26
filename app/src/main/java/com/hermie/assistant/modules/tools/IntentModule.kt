package com.hermie.assistant.modules.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.hermie.assistant.modules.*

/**
 * Tool module for launching Android intents — open apps, make calls, send SMS,
 * open URLs, share text, open maps, etc.
 */
class IntentModule : HermieModule, ToolModule {

    override val id = "intents"
    override val displayName = "App Launcher"
    override val description = "Open apps, make calls, send SMS, open URLs, navigate maps"
    override val iconName = "launch"
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
            name = "intent.open_app",
            description = "Open an app by name or package. Examples: 'Chrome', 'com.google.android.apps.maps'",
            parameters = mapOf(
                "app" to ToolParam("str", "App name or package name", required = true)
            )
        ),
        ToolDefinition(
            name = "intent.open_url",
            description = "Open a URL in the default browser.",
            parameters = mapOf(
                "url" to ToolParam("str", "URL to open (include https://)", required = true)
            )
        ),
        ToolDefinition(
            name = "intent.call",
            description = "Initiate a phone call. Opens dialer with number pre-filled.",
            parameters = mapOf(
                "number" to ToolParam("str", "Phone number to call", required = true)
            )
        ),
        ToolDefinition(
            name = "intent.sms",
            description = "Open SMS/messaging app with a pre-filled message.",
            parameters = mapOf(
                "number" to ToolParam("str", "Phone number to send to", required = true),
                "message" to ToolParam("str", "Message text", required = false)
            )
        ),
        ToolDefinition(
            name = "intent.maps",
            description = "Open maps app with a search query or directions.",
            parameters = mapOf(
                "query" to ToolParam("str", "Place to search or address", required = false),
                "from" to ToolParam("str", "Starting address (for directions)", required = false),
                "to" to ToolParam("str", "Destination address (for directions)", required = false)
            )
        ),
        ToolDefinition(
            name = "intent.share",
            description = "Open the share dialog with text content.",
            parameters = mapOf(
                "text" to ToolParam("str", "Text to share", required = true)
            )
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        val ctx = context ?: return ToolResult.Error("Intent module not initialized")

        return when (name) {
            "intent.open_app" -> openApp(ctx, params)
            "intent.open_url" -> openUrl(ctx, params)
            "intent.call" -> call(ctx, params)
            "intent.sms" -> sms(ctx, params)
            "intent.maps" -> maps(ctx, params)
            "intent.share" -> share(ctx, params)
            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    private fun openApp(ctx: Context, params: Map<String, String>): ToolResult {
        val app = params["app"] ?: return ToolResult.Error("Missing app parameter")

        // Try as package name first
        var intent = ctx.packageManager.getLaunchIntentForPackage(app)

        // If not found, search by label
        if (intent == null) {
            val pm = ctx.packageManager
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val match = allApps.firstOrNull {
                pm.getApplicationLabel(it).toString().equals(app, ignoreCase = true)
            } ?: allApps.firstOrNull {
                pm.getApplicationLabel(it).toString().contains(app, ignoreCase = true)
            }
            if (match != null) {
                intent = pm.getLaunchIntentForPackage(match.packageName)
            }
        }

        if (intent == null) {
            return ToolResult.Error("App not found: $app")
        }

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            ToolResult.Success("Opened $app")
        } catch (e: Exception) {
            ToolResult.Error("Failed to open $app: ${e.message}")
        }
    }

    private fun openUrl(ctx: Context, params: Map<String, String>): ToolResult {
        var url = params["url"] ?: return ToolResult.Error("Missing url parameter")
        if (!url.startsWith("http")) url = "https://$url"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            ToolResult.Success("Opened $url")
        } catch (e: Exception) {
            ToolResult.Error("Failed to open URL: ${e.message}")
        }
    }

    private fun call(ctx: Context, params: Map<String, String>): ToolResult {
        val number = params["number"] ?: return ToolResult.Error("Missing number parameter")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            ToolResult.Success("Opened dialer with $number")
        } catch (e: Exception) {
            ToolResult.Error("Failed to open dialer: ${e.message}")
        }
    }

    private fun sms(ctx: Context, params: Map<String, String>): ToolResult {
        val number = params["number"] ?: return ToolResult.Error("Missing number parameter")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            params["message"]?.let { putExtra("sms_body", it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            ToolResult.Success("Opened SMS to $number")
        } catch (e: Exception) {
            ToolResult.Error("Failed to open SMS: ${e.message}")
        }
    }

    private fun maps(ctx: Context, params: Map<String, String>): ToolResult {
        val from = params["from"]
        val to = params["to"]
        val query = params["query"]

        val uri = when {
            from != null && to != null -> {
                Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${Uri.encode(from)}&destination=${Uri.encode(to)}")
            }
            to != null -> {
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(to)}")
            }
            query != null -> {
                Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            }
            else -> return ToolResult.Error("Provide query, or from+to for directions")
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            val desc = when {
                from != null && to != null -> "directions from $from to $to"
                to != null -> "directions to $to"
                else -> "map search: $query"
            }
            ToolResult.Success("Opened maps: $desc")
        } catch (e: Exception) {
            ToolResult.Error("Failed to open maps: ${e.message}")
        }
    }

    private fun share(ctx: Context, params: Map<String, String>): ToolResult {
        val text = params["text"] ?: return ToolResult.Error("Missing text parameter")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(Intent.createChooser(intent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            ToolResult.Success("Opened share dialog")
        } catch (e: Exception) {
            ToolResult.Error("Failed to share: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "IntentModule"
    }
}
