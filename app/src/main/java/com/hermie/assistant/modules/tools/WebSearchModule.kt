package com.hermie.assistant.modules.tools

import android.content.Context
import android.util.Log
import com.hermie.assistant.modules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tool module for web searching and fetching page content.
 *
 * Uses DuckDuckGo instant answer API (no key needed) and basic HTML scraping
 * for page content extraction.
 */
class WebSearchModule : HermieModule, ToolModule {

    override val id = "websearch"
    override val displayName = "Web Search"
    override val description = "Search the web for information"
    override val iconName = "search"
    override var isActive: Boolean = false
        private set
    // web.search is tasks-mode only — returns multi-line text better suited
    // for task synthesis. Use WeatherModule or memory.recall for quick chat answers.
    override val availableInChatMode = false

    private var context: Context? = null

    override suspend fun initialize(context: Context) {
        this.context = context
        isActive = true
    }

    override suspend fun start() { isActive = true }
    override suspend fun stop() { isActive = false }
    override fun release() { context = null }

    // web.fetch removed from this module — now provided by WebFetchModule
    // with a richer extractor and configurable char limit.
    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "web.search",
            description = "Search the web for information. Returns a summary from DuckDuckGo.",
            parameters = mapOf(
                "query" to ToolParam("str", "Search query", required = true)
            )
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        return when (name) {
            "web.search" -> search(params)
            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    private suspend fun search(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val query = params["query"] ?: return@withContext ToolResult.Error("Missing query parameter")

        try {
            // DuckDuckGo Instant Answer API — free, no key needed
            val encoded = URLEncoder.encode(query, "UTF-8")
            val apiUrl = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"

            val response = httpGet(apiUrl, timeoutMs = 10_000)
            if (response == null) {
                return@withContext ToolResult.Error("Web search failed — no response")
            }

            val json = JSONObject(response)
            val results = mutableListOf<String>()

            // Abstract (main answer)
            val abstractText = json.optString("AbstractText", "")
            val abstractSource = json.optString("AbstractSource", "")
            if (abstractText.isNotBlank()) {
                results.add("$abstractText (via $abstractSource)")
            }

            // Answer (instant answer)
            val answer = json.optString("Answer", "")
            if (answer.isNotBlank()) {
                results.add("Answer: $answer")
            }

            // Related topics (top 5)
            val relatedTopics = json.optJSONArray("RelatedTopics")
            if (relatedTopics != null) {
                for (i in 0 until minOf(relatedTopics.length(), 5)) {
                    val topic = relatedTopics.optJSONObject(i) ?: continue
                    val text = topic.optString("Text", "")
                    val firstUrl = topic.optString("FirstURL", "")
                    if (text.isNotBlank()) {
                        results.add("- ${text.take(200)}${if (firstUrl.isNotBlank()) " ($firstUrl)" else ""}")
                    }
                }
            }

            // Definition
            val definition = json.optString("Definition", "")
            if (definition.isNotBlank()) {
                results.add("Definition: $definition")
            }

            if (results.isEmpty()) {
                // Fallback: try HTML search results page scrape
                val fallback = scrapeSearchResults(query)
                if (fallback.isNotBlank()) {
                    return@withContext ToolResult.Success("Search results for '$query':\n$fallback")
                }
                return@withContext ToolResult.Success("No direct results found for '$query'. Try rephrasing or use web.fetch with a specific URL.")
            }

            ToolResult.Success("Search results for '$query':\n${results.joinToString("\n")}")
        } catch (e: Exception) {
            Log.e(TAG, "Web search failed", e)
            ToolResult.Error("Web search failed: ${e.message}")
        }
    }

    private suspend fun fetch(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        var url = params["url"] ?: return@withContext ToolResult.Error("Missing url parameter")
        if (!url.startsWith("http")) url = "https://$url"

        try {
            val html = httpGet(url, timeoutMs = 15_000)
                ?: return@withContext ToolResult.Error("Failed to fetch page")

            val text = extractReadableText(html)
            if (text.isBlank()) {
                return@withContext ToolResult.Error("No readable text found on page")
            }

            // Limit to ~2000 chars to fit in LLM context
            val truncated = if (text.length > 2000) text.take(2000) + "... [truncated]" else text
            ToolResult.Success("Content from $url:\n$truncated")
        } catch (e: Exception) {
            Log.e(TAG, "Web fetch failed", e)
            ToolResult.Error("Failed to fetch page: ${e.message}")
        }
    }

    /**
     * Fallback: scrape DuckDuckGo HTML results (lite version).
     */
    private fun scrapeSearchResults(query: String): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://lite.duckduckgo.com/lite/?q=$encoded"
            val html = httpGet(url, timeoutMs = 10_000) ?: return ""

            // Extract result snippets from lite page
            val results = mutableListOf<String>()
            val snippetRegex = Regex("""<a[^>]*class="result-link"[^>]*>([^<]+)</a>""")
            val descRegex = Regex("""<td[^>]*class="result-snippet"[^>]*>([^<]+)</td>""")

            val links = snippetRegex.findAll(html).map { it.groupValues[1].trim() }.toList()
            val descs = descRegex.findAll(html).map { it.groupValues[1].trim() }.toList()

            for (i in 0 until minOf(links.size, 5)) {
                val desc = descs.getOrNull(i)?.take(150) ?: ""
                results.add("- ${links[i]}${if (desc.isNotBlank()) ": $desc" else ""}")
            }

            results.joinToString("\n")
        } catch (_: Exception) { "" }
    }

    private fun httpGet(urlStr: String, timeoutMs: Int = 10_000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "Hermie/1.0 (Android Assistant)")
            conn.setRequestProperty("Accept", "text/html,application/json")

            if (conn.responseCode != 200) return null

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.appendLine(line)
                // Safety limit: don't read more than 500KB
                if (sb.length > 500_000) break
            }
            reader.close()
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "HTTP GET failed: $urlStr", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Extract readable text from HTML by stripping tags, scripts, styles.
     */
    private fun extractReadableText(html: String): String {
        var text = html
        // Remove script/style blocks
        text = text.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        // Remove HTML tags
        text = text.replace(Regex("<[^>]+>"), " ")
        // Decode common entities
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&nbsp;", " ").replace("&#39;", "'")
        // Collapse whitespace
        text = text.replace(Regex("\\s+"), " ").trim()
        return text
    }

    companion object {
        private const val TAG = "WebSearchModule"
    }
}
