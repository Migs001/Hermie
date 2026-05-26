package com.hermie.assistant.modules.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.hermie.assistant.modules.*

/**
 * Tool module for reading and writing the system clipboard.
 */
class ClipboardModule : HermieModule, ToolModule {

    override val id = "clipboard"
    override val displayName = "Clipboard"
    override val description = "Read and write the system clipboard"
    override val iconName = "content_paste"
    override var isActive: Boolean = false
        private set
    // clipboard.read is safe in chat. clipboard.write should only run during task execution.
    override val availableInChatMode = true
    override val chatModeToolNames: Set<String> = setOf("clipboard.read")

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
            name = "clipboard.read",
            description = "Read the current clipboard text content.",
            parameters = emptyMap()
        ),
        ToolDefinition(
            name = "clipboard.write",
            description = "Copy text to the clipboard.",
            parameters = mapOf(
                "text" to ToolParam("str", "Text to copy to clipboard", required = true)
            )
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        val ctx = context ?: return ToolResult.Error("Clipboard module not initialized")

        return when (name) {
            "clipboard.read" -> readClipboard(ctx)
            "clipboard.write" -> writeClipboard(ctx, params)
            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    private fun readClipboard(ctx: Context): ToolResult {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        return if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(ctx).toString()
            if (text.isBlank()) {
                ToolResult.Success("Clipboard is empty.")
            } else {
                ToolResult.Success("Clipboard content: $text")
            }
        } else {
            ToolResult.Success("Clipboard is empty.")
        }
    }

    private fun writeClipboard(ctx: Context, params: Map<String, String>): ToolResult {
        val text = params["text"] ?: return ToolResult.Error("Missing text parameter")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Hermie", text))
        return ToolResult.Success("Copied to clipboard: \"${text.take(50)}${if (text.length > 50) "..." else ""}\"")
    }

    companion object {
        private const val TAG = "ClipboardModule"
    }
}
