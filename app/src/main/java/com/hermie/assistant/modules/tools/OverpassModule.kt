package com.hermie.assistant.modules.tools

import android.content.Context
import android.util.Log
import com.hermie.assistant.modules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/**
 * Point-of-interest search via Overpass API (OpenStreetMap data, free).
 * Tasks mode only — results are too verbose for inline chat.
 */
class OverpassModule : ToolModule {

    override val id = "overpass"
    override val displayName = "Places"
    override val description = "Search for points of interest using OpenStreetMap (Overpass API)"
    override val iconName = "place"
    override val isActive get() = _isActive
    override val availableInChatMode = false

    private var _isActive = false
    private val cache = mutableMapOf<String, Pair<Long, String>>()
    private val CACHE_TTL_MS = 5 * 60 * 1000L

    private val categoryToTag = mapOf(
        "restaurant" to ("amenity" to "restaurant"),
        "cafe" to ("amenity" to "cafe"),
        "bar" to ("amenity" to "bar"),
        "supermarket" to ("shop" to "supermarket"),
        "pharmacy" to ("amenity" to "pharmacy"),
        "hospital" to ("amenity" to "hospital"),
        "park" to ("leisure" to "park"),
        "fuel" to ("amenity" to "fuel"),
        "hotel" to ("tourism" to "hotel"),
        "bank" to ("amenity" to "bank"),
        "atm" to ("amenity" to "atm"),
        "toilet" to ("amenity" to "toilets")
    )

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "poi.search",
            description = "Search for points of interest near a location. Valid categories: ${categoryToTag.keys.joinToString()}",
            parameters = mapOf(
                "lat" to ToolParam("float", "Center latitude"),
                "lon" to ToolParam("float", "Center longitude"),
                "radius_m" to ToolParam("int", "Search radius in meters (max 5000)"),
                "category" to ToolParam("str", "Category of place")
            )
        ),
        ToolDefinition(
            name = "poi.search_cuisine",
            description = "Search for restaurants filtered by cuisine type (e.g. italian, japanese, mexican, thai)",
            parameters = mapOf(
                "lat" to ToolParam("float", "Center latitude"),
                "lon" to ToolParam("float", "Center longitude"),
                "radius_m" to ToolParam("int", "Search radius in meters (max 5000)"),
                "cuisine" to ToolParam("str", "Cuisine type")
            )
        )
    )

    override suspend fun initialize(context: Context) { _isActive = true }
    override suspend fun start() { _isActive = true }
    override suspend fun stop() { _isActive = false }
    override fun release() { _isActive = false; cache.clear() }

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult =
        when (name) {
            "poi.search" -> searchCategory(params)
            "poi.search_cuisine" -> searchCuisine(params)
            else -> ToolResult.Error("Unknown tool: $name")
        }

    private suspend fun searchCategory(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val lat = params["lat"] ?: return@withContext ToolResult.Error("lat required")
            val lon = params["lon"] ?: return@withContext ToolResult.Error("lon required")
            val radius = (params["radius_m"]?.toIntOrNull() ?: 1000).coerceAtMost(5000)
            val category = (params["category"] ?: return@withContext ToolResult.Error("category required")).lowercase()
            val (tagKey, tagValue) = categoryToTag[category]
                ?: return@withContext ToolResult.Error("Unknown category: $category. Valid: ${categoryToTag.keys.joinToString()}")
            val cacheKey = "$lat,$lon,$radius,$category"
            cache[cacheKey]?.let { (ts, r) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext ToolResult.Success(r)
            }
            val query = "[out:json][timeout:10];\nnode[$tagKey=$tagValue](around:$radius,$lat,$lon);\nout body 20;"
            runQuery(query, lat.toDouble(), lon.toDouble(), cacheKey)
        }

    private suspend fun searchCuisine(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val lat = params["lat"] ?: return@withContext ToolResult.Error("lat required")
            val lon = params["lon"] ?: return@withContext ToolResult.Error("lon required")
            val radius = (params["radius_m"]?.toIntOrNull() ?: 1000).coerceAtMost(5000)
            val cuisine = params["cuisine"] ?: return@withContext ToolResult.Error("cuisine required")
            val cacheKey = "$lat,$lon,$radius,cuisine=$cuisine"
            cache[cacheKey]?.let { (ts, r) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext ToolResult.Success(r)
            }
            val query = "[out:json][timeout:10];\nnode[amenity=restaurant][cuisine~\"$cuisine\",i](around:$radius,$lat,$lon);\nout body 20;"
            runQuery(query, lat.toDouble(), lon.toDouble(), cacheKey)
        }

    private fun runQuery(query: String, centerLat: Double, centerLon: Double, cacheKey: String): ToolResult {
        return try {
            val conn = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000; conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "Hermie/1.0")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            OutputStreamWriter(conn.outputStream).use {
                it.write("data=${URLEncoder.encode(query, "UTF-8")}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val elements = json.optJSONArray("elements")
                ?: return ToolResult.Success("No results found")

            data class Place(val name: String, val address: String, val dist: Double)
            val places = mutableListOf<Place>()
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val tags = el.optJSONObject("tags") ?: continue
                val name = tags.optString("name")
                if (name.isEmpty()) continue
                val dist = haversine(centerLat, centerLon, el.optDouble("lat"), el.optDouble("lon"))
                places.add(Place(name, buildAddress(tags), dist))
            }
            places.sortBy { it.dist }
            if (places.isEmpty()) return ToolResult.Success("No named places found in that area")

            val sb = StringBuilder("Found ${places.size} places:\n")
            places.take(20).forEachIndexed { i, p ->
                sb.append("${i + 1}. ${p.name} (${p.dist.toInt()}m)")
                if (p.address.isNotBlank()) sb.append(" — ${p.address}")
                sb.append("\n")
            }
            val result = sb.toString().trimEnd()
            cache[cacheKey] = System.currentTimeMillis() to result
            ToolResult.Success(result)
        } catch (e: Exception) {
            Log.w(TAG, "Overpass query failed", e)
            ToolResult.Error("POI search failed: ${e.message}")
        }
    }

    private fun buildAddress(tags: JSONObject): String = listOf(
        tags.optString("addr:housenumber"),
        tags.optString("addr:street"),
        tags.optString("addr:city")
    ).filter { it.isNotEmpty() }.joinToString(", ")

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * asin(sqrt(a))
    }

    companion object { private const val TAG = "OverpassModule" }
}
