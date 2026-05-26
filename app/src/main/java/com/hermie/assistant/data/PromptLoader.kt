package com.hermie.assistant.data

import android.content.Context
import android.util.Log

/**
 * Loads prompt templates from assets/prompts/ directory.
 * Templates can contain {placeholder} tokens that get replaced with actual values.
 */
object PromptLoader {

    private const val TAG = "PromptLoader"
    private const val PROMPTS_DIR = "prompts"

    /** Cache loaded prompts in memory */
    private val cache = mutableMapOf<String, String>()

    /**
     * Load a prompt file from assets/prompts/.
     * Returns the raw template string, or null if not found.
     */
    fun load(context: Context, fileName: String): String? {
        cache[fileName]?.let { return it }

        return try {
            val content = context.assets.open("$PROMPTS_DIR/$fileName")
                .bufferedReader().readText()
            cache[fileName] = content
            content
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load prompt: $fileName", e)
            null
        }
    }

    /**
     * Load a prompt and fill in placeholders.
     * Placeholders use {key} format, e.g. {app_name}, {minutes_used}.
     * Empty/null values result in the placeholder being removed.
     */
    fun loadAndFill(context: Context, fileName: String, vars: Map<String, String?>): String? {
        val template = load(context, fileName) ?: return null
        return fill(template, vars)
    }

    /**
     * Fill placeholders in a template string.
     */
    fun fill(template: String, vars: Map<String, String?>): String {
        var result = template
        for ((key, value) in vars) {
            result = result.replace("{$key}", value ?: "")
        }
        // Clean up empty lines from removed placeholders
        return result.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Clear the cache (e.g., if prompts are updated at runtime) */
    fun clearCache() {
        cache.clear()
    }
}
