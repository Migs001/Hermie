package com.hermie.assistant.modules.dnd

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence layer for Smart DND state, rules, and notification log.
 * Uses SharedPreferences with JSON serialization.
 */
class DndSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hermie_dnd", Context.MODE_PRIVATE)

    // ── DND State ──────────────────────────────────────────

    var isDndEnabled: Boolean
        get() = prefs.getBoolean(KEY_DND_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_DND_ENABLED, value).apply() }

    var dndEnabledSince: Long
        get() = prefs.getLong(KEY_DND_ENABLED_SINCE, 0)
        set(value) { prefs.edit().putLong(KEY_DND_ENABLED_SINCE, value).apply() }

    // ── Filter Rules ───────────────────────────────────────

    fun getRules(): List<DndFilterRule> {
        val json = prefs.getString(KEY_FILTER_RULES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i -> ruleFromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse rules", e)
            emptyList()
        }
    }

    fun saveRules(rules: List<DndFilterRule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(ruleToJson(it)) }
        prefs.edit().putString(KEY_FILTER_RULES, arr.toString()).apply()
    }

    fun addRule(rule: DndFilterRule) {
        val rules = getRules().toMutableList()
        rules.add(rule)
        saveRules(rules)
    }

    fun removeRule(ruleId: String) {
        val rules = getRules().filter { it.id != ruleId }
        saveRules(rules)
    }

    fun findRuleByDescription(query: String): DndFilterRule? {
        return getRules().find {
            it.description.contains(query, ignoreCase = true) || it.id == query
        }
    }

    // ── Notification Log ───────────────────────────────────

    fun getLog(): List<LoggedNotification> {
        val json = prefs.getString(KEY_NOTIFICATION_LOG, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i -> logFromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse notification log", e)
            emptyList()
        }
    }

    fun saveLog(log: List<LoggedNotification>) {
        // Cap at MAX_LOG_SIZE, keep most recent
        val capped = if (log.size > MAX_LOG_SIZE) log.takeLast(MAX_LOG_SIZE) else log
        val arr = JSONArray()
        capped.forEach { arr.put(logToJson(it)) }
        prefs.edit().putString(KEY_NOTIFICATION_LOG, arr.toString()).apply()
    }

    fun appendToLog(entry: LoggedNotification) {
        val log = getLog().toMutableList()
        log.add(entry)
        saveLog(log)
    }

    fun getLogSince(sinceTimestamp: Long): List<LoggedNotification> {
        return getLog().filter { it.timestamp >= sinceTimestamp }
    }

    fun clearLog() {
        prefs.edit().remove(KEY_NOTIFICATION_LOG).apply()
    }

    // ── LLM Decision Cache ─────────────────────────────────

    private val decisionCache = mutableMapOf<String, Pair<DndEvalResult, Long>>()

    fun getCachedDecision(cacheKey: String): DndEvalResult? {
        val entry = decisionCache[cacheKey] ?: return null
        val age = System.currentTimeMillis() - entry.second
        return if (age < CACHE_TTL_MS) entry.first else {
            decisionCache.remove(cacheKey)
            null
        }
    }

    fun cacheDecision(cacheKey: String, result: DndEvalResult) {
        decisionCache[cacheKey] = result to System.currentTimeMillis()
        // Evict old entries
        if (decisionCache.size > 100) {
            val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
            decisionCache.entries.removeAll { it.value.second < cutoff }
        }
    }

    // ── JSON serialization ────────────────────────────────

    private fun ruleToJson(rule: DndFilterRule): JSONObject = JSONObject().apply {
        put("id", rule.id)
        put("description", rule.description)
        put("ruleType", rule.ruleType.name)
        put("contactName", rule.contactName ?: JSONObject.NULL)
        put("packagePattern", rule.packagePattern ?: JSONObject.NULL)
        put("isTemporary", rule.isTemporary)
        put("expiresAt", rule.expiresAt ?: JSONObject.NULL)
        put("createdAt", rule.createdAt)
        put("priority", rule.priority)
    }

    private fun ruleFromJson(json: JSONObject): DndFilterRule = DndFilterRule(
        id = json.getString("id"),
        description = json.getString("description"),
        ruleType = RuleType.valueOf(json.getString("ruleType")),
        contactName = json.optString("contactName").takeIf { it != "null" && it.isNotBlank() },
        packagePattern = json.optString("packagePattern").takeIf { it != "null" && it.isNotBlank() },
        isTemporary = json.optBoolean("isTemporary", false),
        expiresAt = json.optLong("expiresAt", 0).takeIf { it > 0 },
        createdAt = json.optLong("createdAt", 0),
        priority = json.optInt("priority", 0)
    )

    private fun logToJson(entry: LoggedNotification): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("packageName", entry.packageName)
        put("appName", entry.appName)
        put("title", entry.title)
        put("text", entry.text)
        put("timestamp", entry.timestamp)
        put("importance", entry.importance.name)
        put("llmReasoning", entry.llmReasoning ?: JSONObject.NULL)
        put("wasLetThrough", entry.wasLetThrough)
        put("matchedRuleId", entry.matchedRuleId ?: JSONObject.NULL)
    }

    private fun logFromJson(json: JSONObject): LoggedNotification = LoggedNotification(
        id = json.getString("id"),
        packageName = json.getString("packageName"),
        appName = json.getString("appName"),
        title = json.getString("title"),
        text = json.getString("text"),
        timestamp = json.getLong("timestamp"),
        importance = try { ImportanceLevel.valueOf(json.getString("importance")) } catch (_: Exception) { ImportanceLevel.MEDIUM },
        llmReasoning = json.optString("llmReasoning").takeIf { it != "null" && it.isNotBlank() },
        wasLetThrough = json.optBoolean("wasLetThrough", false),
        matchedRuleId = json.optString("matchedRuleId").takeIf { it != "null" && it.isNotBlank() }
    )

    companion object {
        private const val TAG = "DndSettingsStore"
        private const val KEY_DND_ENABLED = "dnd_enabled"
        private const val KEY_DND_ENABLED_SINCE = "dnd_enabled_since"
        private const val KEY_FILTER_RULES = "dnd_filter_rules"
        private const val KEY_NOTIFICATION_LOG = "dnd_notification_log"
        private const val MAX_LOG_SIZE = 200
        private const val CACHE_TTL_MS = 5 * 60 * 1000L  // 5 minutes
    }
}
