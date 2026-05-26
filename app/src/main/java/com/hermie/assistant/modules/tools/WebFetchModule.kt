package com.hermie.assistant.modules.tools

import android.content.Context
import android.util.Log
import com.hermie.assistant.modules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetch and extract readable text from a URL.
 * Tasks mode only — result can be up to [MAX_CHARS] characters, which is
 * better suited for task-mode synthesis than inline chat.
 *
 * Note: the existing web.fetch in WebSearchModule is limited to 2000 chars
 * and does a simpler extraction. This module replaces that tool name with a
 * richer extractor that preserves article/main content.
 */
class WebFetchModule : ToolModule {

    override val id = "webfetch"
    override val displayName = "Web Fetch"
    override val description = "Fetch and extract readable text from a URL (up to $MAX_CHARS chars)"
    override val iconName = "language"
    override val isActive get() = _isActive
    override val availableInChatMode = false

    private var _isActive = false

    companion object {
        private const val TAG = "WebFetchModule"
        const val MAX_CHARS = 4000
    }

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "web.fetch",
            description = "Fetch a URL and extract its readable text content (up to $MAX_CHARS chars). Use for reading articles, documentation, and web pages.",
            parameters = mapOf(
                "url" to ToolParam("str", "The URL to fetch")
            )
        )
    )

    override suspend fun initialize(context: Context) { _isActive = true }
    override suspend fun start() { _isActive = true }
    override suspend fun stop() { _isActive = false }
    override fun release() { _isActive = false }

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult =
        when (name) {
            "web.fetch" -> fetchPage(params)
            else -> ToolResult.Error("Unknown tool: $name")
        }

    private suspend fun fetchPage(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            var url = params["url"] ?: return@withContext ToolResult.Error("url required")
            if (!url.startsWith("http")) url = "https://$url"
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Hermie/1.0)")
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                val code = conn.responseCode
                if (code !in 200..299) return@withContext ToolResult.Error("HTTP $code from $url")
                val html = conn.inputStream.bufferedReader().readText()
                val text = extractReadableText(html)
                if (text.isBlank()) {
                    ToolResult.Error("Could not extract readable text from $url")
                } else {
                    ToolResult.Success(if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "\n[truncated]" else text)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Web fetch failed for $url", e)
                ToolResult.Error("Failed to fetch $url: ${e.message}")
            }
        }

    /**
     * Minimal readability extraction:
     * 1. Strip script/style/nav/header/footer/aside
     * 2. Prefer <article> or <main> content when present
     * 3. Strip remaining HTML tags
     * 4. Decode HTML entities and collapse whitespace
     */
    private fun extractReadableText(html: String): String {
        var text = html
        // Remove noisy structural tags
        listOf("script", "style", "nav", "header", "footer", "aside", "noscript", "iframe").forEach { tag ->
            text = text.replace(Regex("<$tag[^>]*>[\\s\\S]*?</$tag>", RegexOption.IGNORE_CASE), " ")
        }
        // Prefer article/main content if available
        Regex("<(?:article|main)[^>]*>([\\s\\S]*?)</(?:article|main)>", RegexOption.IGNORE_CASE)
            .find(text)?.let { text = it.groupValues[1] }
        // Strip all remaining tags
        text = text.replace(Regex("<[^>]+>"), " ")
        // Decode entities
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("&#\\d+;"), "")
        // Collapse whitespace
        text = text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n")
        return text.trim()
    }
}
