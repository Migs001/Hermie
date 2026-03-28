package com.hermie.assistant.modules

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central registry that manages all Hermie modules.
 * Modules register themselves here and can be discovered by the UI and LLM.
 */
class ModuleRegistry(private val context: Context) {

    private val _modules = MutableStateFlow<Map<String, HermieModule>>(emptyMap())
    val modules: StateFlow<Map<String, HermieModule>> = _modules.asStateFlow()

    /** All registered tool modules (for LLM tool calling) */
    val toolModules: List<ToolModule>
        get() = _modules.value.values.filterIsInstance<ToolModule>()

    /** All modules with their own screens (for home page cards) */
    val screenModules: List<ScreenModule>
        get() = _modules.value.values.filterIsInstance<ScreenModule>()

    /** All modules that need background execution */
    val backgroundModules: List<BackgroundModule>
        get() = _modules.value.values.filterIsInstance<BackgroundModule>()

    suspend fun register(module: HermieModule) {
        try {
            module.initialize(context)
            _modules.value = _modules.value + (module.id to module)
            Log.d(TAG, "Registered module: ${module.id} (${module.displayName})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register module ${module.id}", e)
        }
    }

    fun unregister(moduleId: String) {
        val module = _modules.value[moduleId] ?: return
        module.release()
        _modules.value = _modules.value - moduleId
        Log.d(TAG, "Unregistered module: $moduleId")
    }

    fun getModule(id: String): HermieModule? = _modules.value[id]

    fun <T : HermieModule> getModuleAs(id: String, type: Class<T>): T? =
        _modules.value[id]?.let { type.cast(it) }

    /** Get all tool definitions across all tool modules */
    fun getAllToolDefinitions(): List<ToolDefinition> =
        toolModules.flatMap { it.toolDefinitions }

    /** Execute a tool call, routing to the correct module */
    suspend fun executeTool(toolName: String, params: Map<String, String>): ToolResult {
        for (module in toolModules) {
            val hasTool = module.toolDefinitions.any { it.name == toolName }
            if (hasTool) {
                return module.executeTool(toolName, params)
            }
        }
        return ToolResult.Error("Unknown tool: $toolName")
    }

    fun releaseAll() {
        _modules.value.values.forEach { it.release() }
        _modules.value = emptyMap()
    }

    companion object {
        private const val TAG = "ModuleRegistry"
    }
}
