package com.hermie.assistant.modules.screentime

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ConversationTurn(
    val timestamp: Long,
    val role: String,         // "hermie" | "user" | "event"
    val content: String,
    val escalationLevel: Int? = null
)

/**
 * Persists the per-app screen time conversation thread for today.
 * Backed by a dedicated SharedPreferences file so it never collides with HermieSettings.
 *
 * Thread keys are scoped to (packageName, dateYYYYMMDD).
 * On a new day, [rolloverIfNeeded] archives the first user turn as "yesterday's excuse"
 * and clears the thread for the new day.
 *
 * Thread is capped at [MAX_TURNS] to keep prompts bounded. When over cap, the oldest
 * "event" turns are dropped first, then the oldest turns regardless of role.
 */
class ScreenTimeConversationStore(context: Context) {

    companion object {
        private const val TAG = "ConvStore"
        private const val PREFS_NAME = "screen_time_conversations"
        private const val MAX_TURNS = 40
        private val DATE_FMT = SimpleDateFormat("yyyyMMdd", Locale.US)

        /**
         * Schema version — bump to wipe all persisted threads on next launch.
         * v2: wipe threads that may contain Qwen3 <think> blocks (persisted
         * before cleanLlmResponse stripped them).
         */
        private const val SCHEMA_VERSION = 2
        private const val KEY_SCHEMA_VERSION = "schema_version"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        val stored = prefs.getInt(KEY_SCHEMA_VERSION, 0)
        if (stored < SCHEMA_VERSION) {
            Log.d(TAG, "Schema migration: $stored -> $SCHEMA_VERSION — wiping persisted threads")
            prefs.edit().clear().putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION).apply()
        }
    }

    private fun todayStr(): String = DATE_FMT.format(Date())

    /**
     * If the stored date for this package is not today, archive the first user turn
     * as yesterday's excuse and clear the thread. Also resets dismissal counts
     * (handled by caller via the returned flag).
     *
     * Safe to call multiple times per day — is a no-op once the date matches.
     */
    fun rolloverIfNeeded(packageName: String) {
        val storedDate = prefs.getString("thread_date_$packageName", null)
        val today = todayStr()
        if (storedDate == today) return  // Already on today's thread

        if (storedDate != null) {
            // Archive first user turn as yesterday's excuse (overwriting any older one)
            val thread = loadThread(packageName)
            val firstUserTurn = thread.firstOrNull { it.role == "user" }
            if (firstUserTurn != null) {
                val excuse = JSONObject().apply {
                    put("date", storedDate)
                    put("text", firstUserTurn.content)
                }
                prefs.edit().putString("yesterday_excuse_$packageName", excuse.toString()).apply()
                Log.d(TAG, "Archived yesterday's excuse for $packageName: ${firstUserTurn.content.take(60)}")
            }
        }

        // Clear thread for the new day
        prefs.edit()
            .remove("thread_$packageName")
            .putString("thread_date_$packageName", today)
            .apply()
    }

    fun getTodayThread(packageName: String): List<ConversationTurn> {
        val storedDate = prefs.getString("thread_date_$packageName", null)
        if (storedDate != todayStr()) return emptyList()
        return loadThread(packageName)
    }

    fun appendTurn(packageName: String, turn: ConversationTurn) {
        val current = loadThread(packageName).toMutableList()
        current.add(turn)

        val capped = if (current.size > MAX_TURNS) capThread(current) else current
        saveThread(packageName, capped)
    }

    /**
     * Returns the text of the first user turn from yesterday, or null if:
     * - No excuse was recorded yesterday
     * - The stored excuse is older than 1 day
     */
    fun getYesterdayFirstExcuse(packageName: String): String? {
        val json = prefs.getString("yesterday_excuse_$packageName", null) ?: return null
        return try {
            val obj = JSONObject(json)
            val storedDate = obj.getString("date")
            val yesterday = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -1)
            }.let { DATE_FMT.format(it.time) }
            if (storedDate == yesterday) obj.getString("text") else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse yesterday's excuse for $packageName", e)
            null
        }
    }

    private fun loadThread(packageName: String): List<ConversationTurn> {
        val json = prefs.getString("thread_$packageName", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ConversationTurn(
                    timestamp = obj.getLong("timestamp"),
                    role = obj.getString("role"),
                    content = obj.getString("content"),
                    escalationLevel = if (obj.has("escalationLevel") && !obj.isNull("escalationLevel"))
                        obj.getInt("escalationLevel") else null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse thread for $packageName", e)
            emptyList()
        }
    }

    private fun saveThread(packageName: String, turns: List<ConversationTurn>) {
        val arr = JSONArray()
        for (t in turns) {
            arr.put(JSONObject().apply {
                put("timestamp", t.timestamp)
                put("role", t.role)
                put("content", t.content)
                if (t.escalationLevel != null) put("escalationLevel", t.escalationLevel)
            })
        }
        prefs.edit()
            .putString("thread_$packageName", arr.toString())
            .putString("thread_date_$packageName", todayStr())
            .apply()
    }

    /**
     * Cap thread to [MAX_TURNS] by dropping oldest event turns first, then oldest turns.
     */
    private fun capThread(turns: MutableList<ConversationTurn>): List<ConversationTurn> {
        val result = turns.toMutableList()
        while (result.size > MAX_TURNS) {
            val oldestEventIdx = result.indexOfFirst { it.role == "event" }
            if (oldestEventIdx >= 0) {
                result.removeAt(oldestEventIdx)
            } else {
                result.removeAt(0)
            }
        }
        return result
    }
}
