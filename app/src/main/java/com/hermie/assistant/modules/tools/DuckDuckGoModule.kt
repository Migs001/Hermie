package com.hermie.assistant.modules.tools

import android.content.Context
import android.util.Log
import com.hermie.assistant.modules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * DuckDuckGo Instant Answer API — free, no key required.
 * Returns definitions, abstracts, and related topics.
 * Tasks mode only.
 */
class DuckDuckGoModule : ToolModule {

    override val id = "duckduckgo"
    override val displayName = "DDG Instant"
    override val description = "Quick fact lookup via DuckDuckGo Instant Answers"
    override val iconName = "search"
    override val isActive get() = _isActive
    override val availableInChatMode = false

    private var _isActive = false
    private val cache = mutableMapOf<String, Pair<Long, String>>()
    private val CACHE_TTL_MS = 5 * 60 * 1000L

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "search.quick",
            description = "Quick fact lookup using DuckDuckGo Instant Answers — returns definitions and abstracts. For full web results, use web.search.",
            parameters = mapOf(
                "query" to ToolParam("str", "Search query")
            )
        )
    )

    override suspend fun initialize(context: Context) { _isActive = true }
    override suspend fun start() { _isActive = true }
    override suspend fun stop() { _isActive = false }
    override fun release() { _isActive = false; cache.clear() }

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult =
        when (name) {
            "search.quick" -> quickSearch(params)
            else -> ToolResult.Error("Unknown tool: $name")
        }

    private suspend fun quickSearch(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val query = params["query"] ?: return@withContext ToolResult.Error("query required")
            val cacheKey = query.lowercase().trim()
            cache[cacheKey]?.let { (ts, r) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext ToolResult.Success(r)
            }
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val conn = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_redirect=1&no_html=1")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "Hermie/1.0")
                val json = JSONObject(conn.inputStream.bufferedReader().readText())

                val sb = StringBuilder()
                json.optString("Answer").ifNotBlank { sb.append("Answer: $it\n") }
                json.optString("Abstract").ifNotBlank { ab ->
                    sb.append(ab)
                    json.optString("AbstractURL").ifNotBlank { sb.append(" ($it)") }
                    sb.append("\n")
                }
                json.optString("Definition").ifNotBlank { sb.append("Definition: $it\n") }

                // Top related topics if no main result yet
                if (sb.isBlank()) {
                    val related = json.optJSONArray("RelatedTopics")
                    if (related != null) {
                        for (i in 0 until minOf(3, related.length())) {
                            val t = related.optJSONObject(i) ?: continue
                            val text = t.optString("Text")
                            if (text.isEmpty()) continue
                            sb.append("- $text\n")
                        }
                    }
                }

                val result = if (sb.isNotBlank()) {
                    sb.toString().trimEnd()
                } else {
                    """{"result": null, "suggestion": "Use web.search for deeper results"}"""
                }
                cache[cacheKey] = System.currentTimeMillis() to result
                ToolResult.Success(result)
            } catch (e: Exception) {
                Log.w(TAG, "DDG instant answer failed", e)
                ToolResult.Error("Search failed: ${e.message}")
            }
        }

    private fun String.ifNotBlank(block: (String) -> Unit) { if (isNotBlank()) block(this) }

    companion object { private const val TAG = "DuckDuckGoModule" }
}
