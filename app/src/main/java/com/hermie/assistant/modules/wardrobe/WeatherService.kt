package com.hermie.assistant.modules.wardrobe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Weather data from Open-Meteo (free API, no key required).
 */
data class WeatherData(
    val temperature: Double,
    val feelsLike: Double,
    val precipitation: Double,
    val cloudCover: Int,
    val windSpeed: Double,
    val tempMin: Double? = null,
    val tempMax: Double? = null
) {
    fun formatForPrompt(useFahrenheit: Boolean): String {
        val tempStr = if (useFahrenheit) {
            val f = temperature * 9.0 / 5.0 + 32
            val fFeel = feelsLike * 9.0 / 5.0 + 32
            "${f.toInt()}F (feels like ${fFeel.toInt()}F)"
        } else {
            "${temperature.toInt()}C (feels like ${feelsLike.toInt()}C)"
        }

        val sky = when {
            cloudCover < 20 -> "clear sky"
            cloudCover < 50 -> "partly cloudy"
            cloudCover < 80 -> "mostly cloudy"
            else -> "overcast"
        }

        val rain = when {
            precipitation <= 0.0 -> "no rain"
            precipitation < 2.0 -> "light rain"
            precipitation < 7.0 -> "moderate rain"
            else -> "heavy rain"
        }

        val wind = when {
            windSpeed < 5.0 -> "calm"
            windSpeed < 15.0 -> "light breeze"
            windSpeed < 30.0 -> "moderate wind"
            else -> "strong wind"
        }

        return "Currently $tempStr, $sky, $rain, $wind"
    }

    /**
     * Simple temperature-based filtering hints for outfit generation.
     */
    fun getWeatherConstraints(): WeatherConstraints {
        val isCold = temperature < 10
        val isCool = temperature in 10.0..18.0
        val isWarm = temperature in 18.0..25.0
        val isHot = temperature > 25
        val isRainy = precipitation > 0.5

        return WeatherConstraints(
            needsCoat = isCold || (isCool && isRainy),
            needsJumper = isCold || isCool,
            avoidCoats = isHot,
            avoidJumpers = isHot,
            needsWaterproof = isRainy,
            isCold = isCold,
            isHot = isHot
        )
    }
}

data class WeatherConstraints(
    val needsCoat: Boolean = false,
    val needsJumper: Boolean = false,
    val avoidCoats: Boolean = false,
    val avoidJumpers: Boolean = false,
    val needsWaterproof: Boolean = false,
    val isCold: Boolean = false,
    val isHot: Boolean = false
)

class WeatherService(private val context: Context) {

    companion object {
        private const val TAG = "WeatherService"
        private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
    }

    /**
     * Get current weather. Returns null if location unavailable or API fails.
     */
    suspend fun getCurrentWeather(): WeatherData? = withContext(Dispatchers.IO) {
        try {
            val (lat, lon) = getLocation() ?: run {
                Log.d(TAG, "No location available")
                return@withContext null
            }

            val url = URL("$BASE_URL?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,precipitation,cloud_cover,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1")

            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Weather API returned ${connection.responseCode}")
                return@withContext null
            }

            val body = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val current = json.getJSONObject("current")

            // Parse daily min/max if available
            val daily = json.optJSONObject("daily")
            val tMin = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)
            val tMax = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)

            WeatherData(
                temperature = current.getDouble("temperature_2m"),
                feelsLike = current.getDouble("apparent_temperature"),
                precipitation = current.getDouble("precipitation"),
                cloudCover = current.getInt("cloud_cover"),
                windSpeed = current.getDouble("wind_speed_10m"),
                tempMin = if (tMin != null && !tMin.isNaN()) tMin else null,
                tempMax = if (tMax != null && !tMax.isNaN()) tMax else null
            ).also {
                Log.d(TAG, "Weather: ${it.formatForPrompt(false)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get weather", e)
            null
        }
    }

    private fun getLocation(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "No location permission")
            return null
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Try network provider first (faster), then GPS
        val location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)

        return if (location != null) {
            Log.d(TAG, "Location: ${location.latitude}, ${location.longitude}")
            Pair(location.latitude, location.longitude)
        } else {
            Log.d(TAG, "No last known location")
            null
        }
    }
}
