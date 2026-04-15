package com.hermie.assistant.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central settings store for Hermie.
 * Replaces old BmoSettings — all preferences in one place.
 */
class HermieSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hermie_settings", Context.MODE_PRIVATE)

    // ── Onboarding ──────────────────────────────────────────

    private val _isOnboardingComplete = MutableStateFlow(
        prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
    )
    val isOnboardingComplete: StateFlow<Boolean> = _isOnboardingComplete.asStateFlow()

    fun completeOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
        _isOnboardingComplete.value = true
    }

    // ── User profile ────────────────────────────────────────

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_USER_NAME, value).apply() }

    /** "male", "female", or "other" */
    var userGender: String
        get() = prefs.getString(KEY_USER_GENDER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_USER_GENDER, value).apply() }

    /** Date of birth as "YYYY-MM-DD" */
    var userDateOfBirth: String
        get() = prefs.getString(KEY_USER_DOB, "") ?: ""
        set(value) { prefs.edit().putString(KEY_USER_DOB, value).apply() }

    // ── Model management ────────────────────────────────────

    private val _modelPath = MutableStateFlow(prefs.getString(KEY_MODEL_PATH, "") ?: "")
    val modelPath: StateFlow<String> = _modelPath.asStateFlow()

    fun setModelPath(path: String) {
        prefs.edit().putString(KEY_MODEL_PATH, path).apply()
        _modelPath.value = path
    }

    fun getActiveModelId(typeSubDir: String): String? =
        prefs.getString("active_model_$typeSubDir", null)

    fun setActiveModelId(typeSubDir: String, modelId: String) {
        prefs.edit().putString("active_model_$typeSubDir", modelId).apply()
    }

    /** Selected model size tier during onboarding: "small", "medium", "large" */
    var selectedModelTier: String
        get() = prefs.getString(KEY_MODEL_TIER, "small") ?: "small"
        set(value) { prefs.edit().putString(KEY_MODEL_TIER, value).apply() }

    // ── HuggingFace token ───────────────────────────────────

    private val _hfToken = MutableStateFlow(prefs.getString(KEY_HF_TOKEN, "") ?: "")
    val hfToken: StateFlow<String> = _hfToken.asStateFlow()

    fun setHfToken(token: String) {
        prefs.edit().putString(KEY_HF_TOKEN, token).apply()
        _hfToken.value = token
    }

    // ── Voice settings ──────────────────────────────────────

    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_VOICE_ENABLED, value).apply() }

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply() }

    var deskCaddyMode: Boolean
        get() = prefs.getBoolean(KEY_DESK_CADDY_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_DESK_CADDY_MODE, value).apply() }

    // ── Screen time triggers ────────────────────────────────

    fun getScreenTimeTriggers(): Map<String, Int> {
        val raw = prefs.getString(KEY_SCREEN_TIME_TRIGGERS, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) parts[0] to parts[1].toIntOrNull()?.let { it } else null
        }.toMap().filterValues { it != null }.mapValues { (_, v) -> v!! }
    }

    fun setScreenTimeTrigger(packageName: String, minutesLimit: Int) {
        val current = getScreenTimeTriggers().toMutableMap()
        current[packageName] = minutesLimit
        val raw = current.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs.edit().putString(KEY_SCREEN_TIME_TRIGGERS, raw).apply()
    }

    fun removeScreenTimeTrigger(packageName: String) {
        val current = getScreenTimeTriggers().toMutableMap()
        current.remove(packageName)
        val raw = current.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs.edit().putString(KEY_SCREEN_TIME_TRIGGERS, raw).apply()
        // Also remove the reason
        removeScreenTimeReason(packageName)
    }

    // ── Screen time personal reasons ──────────────────────────

    fun getScreenTimeReasons(): Map<String, String> {
        val raw = prefs.getString(KEY_SCREEN_TIME_REASONS, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split(";;").mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx > 0) entry.substring(0, idx) to entry.substring(idx + 1) else null
        }.toMap()
    }

    fun getScreenTimeReason(packageName: String): String? {
        return getScreenTimeReasons()[packageName]
    }

    fun setScreenTimeReason(packageName: String, reason: String) {
        val current = getScreenTimeReasons().toMutableMap()
        current[packageName] = reason
        val raw = current.entries.joinToString(";;") { "${it.key}=${it.value}" }
        prefs.edit().putString(KEY_SCREEN_TIME_REASONS, raw).apply()
    }

    fun removeScreenTimeReason(packageName: String) {
        val current = getScreenTimeReasons().toMutableMap()
        current.remove(packageName)
        val raw = current.entries.joinToString(";;") { "${it.key}=${it.value}" }
        prefs.edit().putString(KEY_SCREEN_TIME_REASONS, raw).apply()
    }

    // ── Screen time user justifications (from notification replies) ──

    fun getLastJustification(packageName: String): String? {
        return prefs.getString("st_justify_$packageName", null)
    }

    fun setLastJustification(packageName: String, text: String) {
        prefs.edit().putString("st_justify_$packageName", text).apply()
    }

    fun clearLastJustification(packageName: String) {
        prefs.edit().remove("st_justify_$packageName").apply()
    }

    // ── Overlay settings ────────────────────────────────────

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply() }

    // ── Personality joke message ─────────────────────────────

    var personalityJokeMessage: String
        get() = prefs.getString(KEY_PERSONALITY_JOKE, DEFAULT_PERSONALITY_JOKE) ?: DEFAULT_PERSONALITY_JOKE
        set(value) { prefs.edit().putString(KEY_PERSONALITY_JOKE, value).apply() }

    // ── Wardrobe ────────────────────────────────────────────

    /** "celsius" or "fahrenheit" */
    var wardrobeTemperatureUnit: String
        get() = prefs.getString(KEY_WARDROBE_TEMP_UNIT, "celsius") ?: "celsius"
        set(value) { prefs.edit().putString(KEY_WARDROBE_TEMP_UNIT, value).apply() }

    /**
     * Occasions as serialized "name:formality;name:formality;..." string.
     * Returns list of (name, formality) pairs.
     */
    fun getWardrobeOccasions(): List<Pair<String, Int>> {
        val raw = prefs.getString(KEY_WARDROBE_OCCASIONS, DEFAULT_OCCASIONS) ?: DEFAULT_OCCASIONS
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                parts[0].trim() to (parts[1].trim().toIntOrNull() ?: 3)
            } else null
        }
    }

    fun setWardrobeOccasions(occasions: List<Pair<String, Int>>) {
        val serialized = occasions.joinToString(";") { "${it.first}:${it.second}" }
        prefs.edit().putString(KEY_WARDROBE_OCCASIONS, serialized).apply()
    }

    companion object {
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_GENDER = "user_gender"
        private const val KEY_USER_DOB = "user_dob"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_MODEL_TIER = "model_tier"
        private const val KEY_HF_TOKEN = "hf_token"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_DESK_CADDY_MODE = "desk_caddy_mode"
        private const val KEY_SCREEN_TIME_TRIGGERS = "screen_time_triggers"
        private const val KEY_SCREEN_TIME_REASONS = "screen_time_reasons"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_PERSONALITY_JOKE = "personality_joke"
        private const val KEY_WARDROBE_TEMP_UNIT = "wardrobe_temp_unit"
        private const val KEY_WARDROBE_OCCASIONS = "wardrobe_occasions"
        private const val DEFAULT_PERSONALITY_JOKE = "Wouldn't you love to change people's personality this easy?"
        private const val DEFAULT_OCCASIONS = "Work:4;Dinner:4;Casual:2;Party:3"
    }
}
