package com.hermie.assistant.modules.tools

import android.content.Context
import android.util.Log
import com.hermie.assistant.modules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * arXiv paper search via the public Atom feed API — free, no key required.
 * Returns paper metadata (title, authors, summary, PDF URL, published date).
 */
class ArxivModule : ToolModule {

    override val id = "arxiv"
    override val displayName = "arXiv"
    override val description = "Search arXiv for academic papers"
    override val iconName = "science"
    override val isActive get() = _isActive
    override val availableInChatMode = false

    private var _isActive = false

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "arxiv.search",
            description = "Search arXiv for academic papers. Returns titles, summaries, authors, and PDF links.",
            parameters = mapOf(
                "query" to ToolParam("str", "Search query (keywords, title words, author names)"),
                "max_results" to ToolParam("int", "Maximum results 1-25 (default 10)", required = false),
                "sort" to ToolParam("str", "Sort by: relevance or submittedDate (default relevance)", required = false)
            )
        )
    )

    override suspend fun initialize(context: Context) { _isActive = true }
    override suspend fun start() { _isActive = true }
    override suspend fun stop() { _isActive = false }
    override fun release() { _isActive = false }

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult =
        when (name) {
            "arxiv.search" -> search(params)
            else -> ToolResult.Error("Unknown tool: $name")
        }

    private suspend fun search(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val query = params["query"] ?: return@withContext ToolResult.Error("query required")
            val maxResults = (params["max_results"]?.toIntOrNull() ?: 10).coerceIn(1, 25)
            val sort = if ((params["sort"] ?: "").lowercase().contains("date")) "submittedDate" else "relevance"
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "http://export.arxiv.org/api/query" +
                    "?search_query=all:$encoded&max_results=$maxResults&sortBy=$sort&sortOrder=descending"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "Hermie/1.0")
                val xml = conn.inputStream.bufferedReader().readText()
                val entries = parseAtomFeed(xml)
                if (entries.isEmpty()) {
                    return@withContext ToolResult.Success("No arXiv papers found for: $query")
                }
                val sb = StringBuilder("arXiv papers for \"$query\" (${entries.size} results):\n\n")
                entries.forEachIndexed { i, e ->
                    sb.append("${i + 1}. ${e.title}\n")
                    val authors = e.authors.take(3).joinToString(", ") +
                        if (e.authors.size > 3) " et al." else ""
                    sb.append("   Authors: $authors\n")
                    sb.append("   Published: ${e.published.take(10)}\n")
                    if (e.pdfUrl.isNotBlank()) sb.append("   PDF: ${e.pdfUrl}\n")
                    sb.append("   Summary: ${e.summary.replace("\n", " ").take(200)}…\n\n")
                }
                ToolResult.Success(sb.toString().trimEnd())
            } catch (e: Exception) {
                Log.w(TAG, "arXiv search failed", e)
                ToolResult.Error("arXiv search failed: ${e.message}")
            }
        }

    private data class Entry(
        val title: String,
        val authors: List<String>,
        val summary: String,
        val published: String,
        val pdfUrl: String
    )

    private fun parseAtomFeed(xml: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(xml.reader())

            var inEntry = false; var inAuthor = false
            var title = ""; var summary = ""; var published = ""
            var authors = mutableListOf<String>(); var pdfUrl = ""
            var tag = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name ?: ""
                        when {
                            tag == "entry" -> {
                                inEntry = true
                                title = ""; summary = ""; published = ""
                                authors = mutableListOf(); pdfUrl = ""
                            }
                            tag == "author" && inEntry -> inAuthor = true
                            tag == "link" && inEntry -> {
                                val titleAttr = parser.getAttributeValue(null, "title") ?: ""
                                val href = parser.getAttributeValue(null, "href") ?: ""
                                if (titleAttr.equals("pdf", ignoreCase = true) || href.contains("/pdf/")) {
                                    pdfUrl = href
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (inEntry) when {
                            tag == "title" && !inAuthor -> title = text
                            tag == "summary" -> summary += text
                            tag == "published" -> published = text
                            tag == "name" && inAuthor -> authors.add(text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "entry" -> {
                                if (inEntry && title.isNotBlank()) {
                                    entries.add(Entry(title.trim(), authors.toList(), summary.trim(), published, pdfUrl))
                                }
                                inEntry = false
                            }
                            "author" -> inAuthor = false
                        }
                        tag = ""
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse arXiv Atom feed", e)
        }
        return entries
    }

    companion object { private const val TAG = "ArxivModule" }
}
