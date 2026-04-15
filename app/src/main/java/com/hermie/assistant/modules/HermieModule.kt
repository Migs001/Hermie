package com.hermie.assistant.modules

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * Base interface for all Hermie modules.
 * Modules are self-contained features that can be registered, started, and stopped.
 * Some modules are tools the assistant can invoke; others are standalone sub-assistants.
 */
interface HermieModule {
    /** Unique identifier for this module */
    val id: String

    /** Human-readable name shown in the UI */
    val displayName: String

    /** Short description of what this module does */
    val description: String

    /** Icon resource name (material icon name) */
    val iconName: String

    /** Whether this module is currently active/running */
    val isActive: Boolean

    /** Whether this module requires special permissions */
    val requiredPermissions: List<String> get() = emptyList()

    /** Initialize the module (called once on registration) */
    suspend fun initialize(context: Context)

    /** Start the module (begin background work if any) */
    suspend fun start()

    /** Stop the module (clean up resources) */
    suspend fun stop()

    /** Release all resources (called on app destroy) */
    fun release()
}

/**
 * A module that provides tools the LLM can call.
 */
interface ToolModule : HermieModule {
    /** Tool definitions this module exposes to the LLM */
    val toolDefinitions: List<ToolDefinition>

    /** Execute a tool call and return the result */
    suspend fun executeTool(name: String, params: Map<String, String>): ToolResult

    /**
     * Whether this module's tools should be included in the chat-mode tool list.
     * Default: false (tools are available in Tasks mode only).
     * Override to true for tools that are safe and useful during hands-free chat.
     */
    val availableInChatMode: Boolean get() = false

    /**
     * If non-null, only the named tools from this module are exposed in chat mode.
     * If null and [availableInChatMode] is true, all tools from this module are exposed.
     * Ignored when [availableInChatMode] is false.
     *
     * Example: CalendarModule sets this to setOf("calendar.check") so only the
     * read-only tool is available in chat, not the write calendar.add tool.
     */
    val chatModeToolNames: Set<String>? get() = null
}

/**
 * A module that has its own UI screen accessible from the home page.
 */
interface ScreenModule : HermieModule {
    /** Composable content for this module's screen */
    @Composable
    fun Screen(onBack: () -> Unit)
}

/**
 * A module that can run in the background (via foreground service).
 */
interface BackgroundModule : HermieModule {
    /** Whether this module needs to keep running when app is in background */
    val needsBackgroundExecution: Boolean

    /** Called periodically while running in background */
    suspend fun onBackgroundTick()
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParam>
)

data class ToolParam(
    val type: String,      // "str", "int", "bool"
    val description: String,
    val required: Boolean = true
)

sealed class ToolResult {
    data class Success(val message: String) : ToolResult()
    data class Error(val message: String) : ToolResult()
}
