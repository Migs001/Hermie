package com.hermie.assistant.modules.tools

import android.content.Context
import android.util.Log
import com.hermie.assistant.modules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Weather module using Open-Meteo (free, no API key required).
 *
 * availableInChatMode = true — pairs perfectly with hands-free voice use.
 * Results are cached for 5 minutes to avoid hammering the endpoint.
 */
class WeatherModule : ToolModule {

    override val id = "weather"
    override val displayName = "Weather"
    override val description = "Current weather and multi-day forecasts via Open-Meteo"
    override val iconName = "wb_sunny"
    override val isActive get() = _isActive
    override val availableInChatMode = true

    private var _isActive = false

    /** Key = "lat,lon,type" → (fetchedAtMs, resultString) */
    private val cache = mutableMapOf<String, Pair<Long, String>>()
    private val CACHE_TTL_MS = 5 * 60 * 1000L

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "weather.now",
            description = "Get current weather conditions at a given location",
            parameters = mapOf(
                "lat" to ToolParam("float", "Latitude"),
                "lon" to ToolParam("float", "Longitude"),
                "city" to ToolParam("str", "City name for display (optional)", required = false)
            )
        ),
        ToolDefinition(
            name = "weather.forecast",
            description = "Get daily weather forecast for the next 1–7 days",
            parameters = mapOf(
                "lat" to ToolParam("float", "Latitude"),
                "lon" to ToolParam("float", "Longitude"),
                "days" to ToolParam("int", "Forecast days 1-7 (default 3)", required = false),
                "city" to ToolParam("str", "City name for display (optional)", required = false)
            )
        )
    )

    override suspend fun initialize(context: Context) { _isActive = true }
    override suspend fun start() { _isActive = true }
    override suspend fun stop() { _isActive = false }
    override fun release() { _isActive = false; cache.clear() }

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult =
        when (name) {
            "weather.now" -> getCurrentWeather(params)
            "weather.forecast" -> getForecast(params)
            else -> ToolResult.Error("Unknown tool: $name")
        }

    private suspend fun getCurrentWeather(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val lat = params["lat"] ?: return@withContext ToolResult.Error("lat required")
            val lon = params["lon"] ?: return@withContext ToolResult.Error("lon required")
            val city = params["city"] ?: "$lat,$lon"
            val cacheKey = "$lat,$lon,now"
            cache[cacheKey]?.let { (ts, r) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext ToolResult.Success(r)
            }
            try {
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon&current_weather=true&temperature_unit=celsius"
                val json = JSONObject(get(url))
                val cw = json.getJSONObject("current_weather")
                val temp = cw.getDouble("temperature")
                val wind = cw.getDouble("windspeed")
                val condition = codeToDesc(cw.getInt("weathercode"))
                val result = "Weather in $city: ${"%.1f".format(temp)}°C, $condition, wind ${"%.0f".format(wind)} km/h"
                cache[cacheKey] = System.currentTimeMillis() to result
                ToolResult.Success(result)
            } catch (e: Exception) {
                Log.w(TAG, "Current weather fetch failed", e)
                ToolResult.Error("Failed to fetch weather: ${e.message}")
            }
        }

    private suspend fun getForecast(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val lat = params["lat"] ?: return@withContext ToolResult.Error("lat required")
            val lon = params["lon"] ?: return@withContext ToolResult.Error("lon required")
            val days = (params["days"]?.toIntOrNull() ?: 3).coerceIn(1, 7)
            val city = params["city"] ?: "$lat,$lon"
            val cacheKey = "$lat,$lon,forecast$days"
            cache[cacheKey]?.let { (ts, r) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext ToolResult.Success(r)
            }
            try {
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&daily=temperature_2m_max,temperature_2m_min,weathercode,precipitation_probability_max" +
                    "&temperature_unit=celsius&forecast_days=$days&timezone=auto"
                val json = JSONObject(get(url))
                val daily = json.getJSONObject("daily")
                val dates = daily.getJSONArray("time")
                val maxT = daily.getJSONArray("temperature_2m_max")
                val minT = daily.getJSONArray("temperature_2m_min")
                val codes = daily.getJSONArray("weathercode")
                val rain = daily.optJSONArray("precipitation_probability_max")
                val sb = StringBuilder("Forecast for $city:\n")
                for (i in 0 until minOf(days, dates.length())) {
                    val cond = codeToDesc(codes.getInt(i))
                    val rainPct = rain?.optInt(i, 0) ?: 0
                    sb.append("${dates.getString(i)}: ${"%.0f".format(minT.getDouble(i))}–${"%.0f".format(maxT.getDouble(i))}°C, $cond, ${rainPct}% rain\n")
                }
                val result = sb.toString().trimEnd()
                cache[cacheKey] = System.currentTimeMillis() to result
                ToolResult.Success(result)
            } catch (e: Exception) {
                Log.w(TAG, "Forecast fetch failed", e)
                ToolResult.Error("Failed to fetch forecast: ${e.message}")
            }
        }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Hermie/1.0")
        return conn.inputStream.bufferedReader().readText()
    }

    private fun codeToDesc(code: Int) = when (code) {
        0 -> "clear sky"
        1, 2, 3 -> "partly cloudy"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        61, 63, 65 -> "rain"
        71, 73, 75 -> "snow"
        80, 81, 82 -> "rain showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "mixed conditions"
    }

    companion object { private const val TAG = "WeatherModule" }
}
