package com.hermie.assistant.modules.study

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lightweight Wikipedia API client — no API key needed.
 * Uses the REST API for search and MediaWiki API for article text extraction.
 */
object WikipediaApi {

    private const val TAG = "WikipediaApi"
    private const val SEARCH_URL = "https://en.wikipedia.org/w/api.php"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000

    /**
     * Search Wikipedia for articles matching the query.
     * Returns a list of (title, snippet) pairs.
     */
    fun search(query: String, limit: Int = 5): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL(
                "$SEARCH_URL?action=query&list=search&srsearch=$encoded" +
                "&srlimit=$limit&format=json&utf8=1"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "HermieAssistant/1.0 (Android)")
            }
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val searchArray = json.getJSONObject("query").getJSONArray("search")
                    for (i in 0 until searchArray.length()) {
                        val item = searchArray.getJSONObject(i)
                        val title = item.getString("title")
                        val snippet = item.getString("snippet")
                            .replace(Regex("<[^>]*>"), "") // Strip HTML tags
                        results.add(title to snippet)
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$query'", e)
        }
        return results
    }

    /**
     * Fetch the plain-text extract of a Wikipedia article by title.
     * Returns the full article text (up to ~100KB), or null on failure.
     */
    fun getArticleText(title: String): String? {
        try {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val url = URL(
                "$SEARCH_URL?action=query&titles=$encoded" +
                "&prop=extracts&explaintext=1&format=json&utf8=1"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "HermieAssistant/1.0 (Android)")
            }
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val pages = json.getJSONObject("query").getJSONObject("pages")
                    val key = pages.keys().next()
                    if (key == "-1") return null
                    return pages.getJSONObject(key).optString("extract", null)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch article '$title'", e)
        }
        return null
    }
}
