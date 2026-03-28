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

    /** "boy", "girl", or "other" */
    var userGender: String
        get() = prefs.getString(KEY_USER_GENDER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_USER_GENDER, value).apply() }

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

    // ── Overlay settings ────────────────────────────────────

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply() }

    // ── Personality joke message ─────────────────────────────

    var personalityJokeMessage: String
        get() = prefs.getString(KEY_PERSONALITY_JOKE, DEFAULT_PERSONALITY_JOKE) ?: DEFAULT_PERSONALITY_JOKE
        set(value) { prefs.edit().putString(KEY_PERSONALITY_JOKE, value).apply() }

    companion object {
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_GENDER = "user_gender"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_MODEL_TIER = "model_tier"
        private const val KEY_HF_TOKEN = "hf_token"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_SCREEN_TIME_TRIGGERS = "screen_time_triggers"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_PERSONALITY_JOKE = "personality_joke"
        private const val DEFAULT_PERSONALITY_JOKE = "Wouldn't you love to change people's personality this easy?"
    }
}
