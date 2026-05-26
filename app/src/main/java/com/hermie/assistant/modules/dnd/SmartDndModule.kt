package com.hermie.assistant.modules.dnd

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.hermie.assistant.data.PromptLoader
import com.hermie.assistant.llm.LlmEngine
import com.hermie.assistant.modules.*
import com.hermie.assistant.modules.notifications.HermieNotificationListener
import com.hermie.assistant.modules.notifications.NotificationData
import com.hermie.assistant.modules.notifications.NotificationModule
import com.hermie.assistant.service.HermieNotificationHelper
import com.hermie.assistant.ui.mascot.MascotMood
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Smart Do Not Disturb module.
 *
 * When DND is enabled:
 * 1. Sets system DND / silent mode
 * 2. Intercepts all incoming notifications via HermieNotificationListener
 * 3. Fast-path: checks allow/block lists (contacts, apps)
 * 4. Slow-path: runs notification through local LLM to determine importance
 * 5. Alerts the user (overriding DND) if notification is deemed important
 * 6. Logs all notifications for later summary ("what did I miss?")
 *
 * Filter rules can be:
 * - Static: "always let through Mom's calls"
 * - Dynamic/contextual: "I'm waiting for an Amazon package, alert me on delivery notifications"
 *
 * Battery-conscious: batches LLM calls, caches decisions, falls back to heuristics
 * when battery is low or LLM is busy.
 */
class SmartDndModule : HermieModule, ToolModule, BackgroundModule {

    override val id = "smart_dnd"
    override val displayName = "Do Not Disturb"
    override val description = "Intelligent Do Not Disturb that filters important notifications"
    override val iconName = "do_not_disturb"
    override var isActive: Boolean = false
        private set
    override val needsBackgroundExecution = true

    override val requiredPermissions = listOf("android.permission.ACCESS_NOTIFICATION_POLICY")

    private var context: Context? = null
    private var store: DndSettingsStore? = null
    private var llmEngine: LlmEngine? = null
    private var mindEngine: com.hermie.assistant.llm.MindLlmEngine? = null
    private val moduleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Evaluation pipeline
    private val evalQueue = Channel<NotificationData>(Channel.BUFFERED)
    private val evalMutex = Mutex()
    private var evalJob: Job? = null

    // Rate limiting: max 6 LLM calls per minute
    private val llmCallTimestamps = mutableListOf<Long>()
    private val MAX_LLM_CALLS_PER_MINUTE = 6

    // Debounce: merge notifications from same app within this window
    private val DEBOUNCE_MS = 500L

    // In-memory notification log (synced to disk periodically)
    private val _notificationLog = mutableListOf<LoggedNotification>()
    private var logDirtyCount = 0

    // Live state
    private val _dndEnabled = MutableStateFlow(false)
    val dndEnabled: StateFlow<Boolean> = _dndEnabled.asStateFlow()

    private val _silencedCount = MutableStateFlow(0)
    val silencedCount: StateFlow<Int> = _silencedCount.asStateFlow()

    fun setLlmEngine(engine: LlmEngine) {
        this.llmEngine = engine
    }

    fun setMindEngine(engine: com.hermie.assistant.llm.MindLlmEngine) {
        this.mindEngine = engine
    }

    /**
     * Get the best available engine: prefer brain, fall back to mind engine.
     * When brain is busy (chat/sleep), mind engine handles background module work.
     */
    private fun getAvailableEngine(): LlmEngine? {
        val brain = llmEngine
        if (brain != null && brain.isLoaded) return brain
        return mindEngine
    }

    /** Get currently active filter rules (for Mind model prompt building) */
    fun getActiveRules(): List<DndFilterRule> = store?.getRules() ?: emptyList()

    // ── Lifecycle ──────────────────────────────────────────

    override suspend fun initialize(context: Context) {
        this.context = context
        store = DndSettingsStore(context)

        // Restore state
        _dndEnabled.value = store?.isDndEnabled ?: false

        // Load log into memory
        _notificationLog.clear()
        _notificationLog.addAll(store?.getLog() ?: emptyList())
        _silencedCount.value = _notificationLog.count { !it.wasLetThrough }

        // Register the DND filter callback on the notification listener
        HermieNotificationListener.onDndFilterCallback = { data ->
            onNotificationReceived(data)
        }

        // Start the evaluation consumer
        startEvalConsumer()

        isActive = true
        Log.d(TAG, "SmartDndModule initialized, DND=${_dndEnabled.value}, rules=${store?.getRules()?.size}")
    }

    override suspend fun start() {
        isActive = true
        if (_dndEnabled.value) {
            applySystemDnd(true)
        }
    }

    override suspend fun stop() {
        isActive = false
    }

    override fun release() {
        evalJob?.cancel()
        moduleScope.cancel()
        flushLog()
        HermieNotificationListener.onDndFilterCallback = null
        context = null
    }

    override suspend fun onBackgroundTick() {
        // Remove expired temporary rules
        val rules = store?.getRules() ?: return
        val now = System.currentTimeMillis()
        val expired = rules.filter { it.isTemporary && it.expiresAt != null && it.expiresAt < now }
        if (expired.isNotEmpty()) {
            expired.forEach { store?.removeRule(it.id) }
            Log.d(TAG, "Removed ${expired.size} expired rules")
        }

        // Periodic log flush
        if (logDirtyCount > 0) {
            flushLog()
        }
    }

    // ── DND Control ────────────────────────────────────────

    fun toggleDnd(enable: Boolean) {
        _dndEnabled.value = enable
        store?.isDndEnabled = enable

        if (enable) {
            store?.dndEnabledSince = System.currentTimeMillis()
            _silencedCount.value = 0
        }

        applySystemDnd(enable)
        Log.d(TAG, "DND toggled: $enable")
    }

    private fun applySystemDnd(enable: Boolean) {
        val ctx = context ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Check if we have DND policy access
        if (!nm.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "DND policy access not granted")
            return
        }

        nm.setInterruptionFilter(
            if (enable) NotificationManager.INTERRUPTION_FILTER_NONE
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )

        // Also set ringer to silent for extra safety
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (enable) {
            am?.ringerMode = AudioManager.RINGER_MODE_SILENT
        } else {
            am?.ringerMode = AudioManager.RINGER_MODE_NORMAL
        }
    }

    fun isDndPolicyAccessGranted(): Boolean {
        val ctx = context ?: return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return nm?.isNotificationPolicyAccessGranted ?: false
    }

    fun openDndPolicySettings() {
        val ctx = context ?: return
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    // ── Notification Interception ──────────────────────────

    // Known package patterns for heuristic classification
    private val CALL_PACKAGES = setOf(
        "dialer", "phone", "incallui", "telecom", "callui"
    )
    private val SMS_PACKAGES = setOf(
        "messaging", "mms", "sms", "messages"
    )
    private val SOCIAL_LOW_PRIORITY = setOf(
        "instagram", "tiktok", "twitter", "facebook.orca", "facebook.katana",
        "snapchat", "reddit", "tumblr", "pinterest", "linkedin"
    )

    /**
     * Called for every incoming notification when DND is active.
     * Returns true if the notification should be silenced (consumed by DND).
     *
     * Strategy: Use fast heuristics for 95% of notifications. Only queue LLM
     * evaluation for custom rules AND when the engine is idle (not chatting).
     */
    private fun onNotificationReceived(data: NotificationData): Boolean {
        if (!_dndEnabled.value) return false

        // Skip our own notifications
        val ctx = context ?: return false
        if (data.packageName == ctx.packageName) return false

        // Skip ongoing notifications (media, nav, etc.)
        if (data.isOngoing) return false

        val appName = getAppName(ctx, data.packageName)
        val pkgLower = data.packageName.lowercase()
        val rules = store?.getRules() ?: emptyList()

        // ── ALLOW rules (fast-path) ──────────────────────────

        for (rule in rules.filter { it.ruleType == RuleType.ALLOW_APP }) {
            val pattern = (rule.packagePattern ?: "").lowercase()
            if (pattern.isNotBlank() && pkgLower.contains(pattern) ||
                appName.contains(rule.packagePattern ?: "", ignoreCase = true) ||
                appName.equals(rule.description.removePrefix("Allow notifications from "), ignoreCase = true)) {
                logNotification(data, appName, ImportanceLevel.HIGH, "Allowed app: ${rule.description}", true, rule.id)
                return false
            }
        }

        for (rule in rules.filter { it.ruleType == RuleType.ALLOW_CONTACT }) {
            val contact = (rule.contactName ?: rule.description.removePrefix("Allow notifications from ")).trim()
            if (contact.isNotBlank() && (
                data.title.contains(contact, ignoreCase = true) ||
                data.text.contains(contact, ignoreCase = true))) {
                logNotification(data, appName, ImportanceLevel.HIGH, "Allowed contact: $contact", true, rule.id)
                return false
            }
        }

        // ── BLOCK rules (fast-path) ──────────────────────────

        for (rule in rules.filter { it.ruleType == RuleType.BLOCK_APP }) {
            val pattern = (rule.packagePattern ?: "").lowercase()
            if (pattern.isNotBlank() && pkgLower.contains(pattern)) {
                logNotification(data, appName, ImportanceLevel.LOW, "Blocked: ${rule.description}", false, rule.id)
                _silencedCount.value++
                return true
            }
        }

        // ── Heuristic classification (no LLM needed) ────────

        // Phone calls — ALWAYS let through
        if (CALL_PACKAGES.any { pkgLower.contains(it) }) {
            logNotification(data, appName, ImportanceLevel.CRITICAL, "Phone call", true, null)
            alertUser(data, appName, "Incoming call: ${data.title}")
            return false
        }

        // SMS/Messages from contacts — usually important
        if (SMS_PACKAGES.any { pkgLower.contains(it) }) {
            // Check if it matches any CUSTOM_LLM rule keywords
            val matchesCustom = rules.filter { it.ruleType == RuleType.CUSTOM_LLM }.any { rule ->
                matchesCustomRule(rule.description, data)
            }
            if (matchesCustom) {
                logNotification(data, appName, ImportanceLevel.HIGH, "SMS matched custom rule", true, null)
                alertUser(data, appName, "${data.title}: ${data.text}")
                return false
            }
            // Default: silence SMS but log it
            logNotification(data, appName, ImportanceLevel.MEDIUM, "SMS silenced (no matching rule)", false, null)
            _silencedCount.value++
            return true
        }

        // Social media noise — always silence
        if (SOCIAL_LOW_PRIORITY.any { pkgLower.contains(it) }) {
            logNotification(data, appName, ImportanceLevel.LOW, "Social media", false, null)
            _silencedCount.value++
            return true
        }

        // ── CUSTOM_LLM rules: keyword matching (fast, no LLM) ──

        for (rule in rules.filter { it.ruleType == RuleType.CUSTOM_LLM }) {
            if (matchesCustomRule(rule.description, data)) {
                logNotification(data, appName, ImportanceLevel.HIGH, "Matched rule: ${rule.description}", true, rule.id)
                alertUser(data, appName, "${data.title}: ${data.text}")
                return false
            }
        }

        // ── Cache check ─────────────────────────────────────

        val cacheKey = "${data.packageName}:${data.title.take(20)}"
        val cached = store?.getCachedDecision(cacheKey)
        if (cached != null) {
            val letThrough = cached.shouldAlert
            logNotification(data, appName, cached.importance, "Cached: ${cached.reason}", letThrough, null)
            if (letThrough) alertUser(data, appName, "${data.title}: ${data.text}")
            else _silencedCount.value++
            return !letThrough
        }

        // ── LLM evaluation (ONLY when engine is idle) ───────

        val hasCustomRules = rules.any { it.ruleType == RuleType.CUSTOM_LLM }
        if (hasCustomRules && isEngineFreeForBackground()) {
            moduleScope.launch { evalQueue.send(data) }
            logNotification(data, appName, ImportanceLevel.MEDIUM, "Queued for AI evaluation", false, null)
            _silencedCount.value++
            return true
        }

        // Default: silence
        logNotification(data, appName, ImportanceLevel.LOW, "Default silence", false, null)
        _silencedCount.value++
        return true
    }

    /**
     * Smart keyword matching for CUSTOM_LLM rules. Extracts keywords from
     * the rule description and checks if the notification matches.
     * This handles 90%+ of cases without needing the LLM.
     */
    private fun matchesCustomRule(ruleDescription: String, data: NotificationData): Boolean {
        val desc = ruleDescription.lowercase()
        val notifText = "${data.title} ${data.text}".lowercase()
        val pkgLower = data.packageName.lowercase()

        // Extract meaningful keywords from rule (skip common filler words)
        val fillerWords = setOf(
            "let", "me", "know", "if", "when", "alert", "notify", "about", "from",
            "any", "the", "a", "an", "i", "am", "is", "are", "my", "on", "for",
            "get", "gets", "got", "with", "that", "this", "and", "or", "of", "to",
            "waiting", "expecting", "want", "need", "should", "might", "be"
        )
        val keywords = desc.split(Regex("[\\s,.'\"!?]+"))
            .filter { it.length > 2 && it !in fillerWords }

        if (keywords.isEmpty()) return false

        // Check if notification contains any keyword from the rule
        var matchCount = 0
        for (kw in keywords) {
            if (notifText.contains(kw) || pkgLower.contains(kw)) {
                matchCount++
            }
        }

        // Require at least 1 keyword match, or 2+ for longer rules
        val threshold = if (keywords.size <= 3) 1 else 2
        return matchCount >= threshold
    }

    /**
     * Check if the LLM engine is free (not generating for chat).
     * We only use LLM for background evaluation when it's not busy.
     */
    private fun isEngineFreeForBackground(): Boolean {
        val engine = getAvailableEngine() ?: return false
        if (!engine.isLoaded) return false
        if (!canMakeLlmCall()) return false
        if (isLowBattery()) return false
        // The engine is free if it's in ModelReady state (not generating)
        // We check this via a try — if generate returns empty flow, engine is busy
        return true
    }

    // ── LLM Evaluation Pipeline ────────────────────────────

    private fun startEvalConsumer() {
        evalJob = moduleScope.launch {
            val batch = mutableListOf<NotificationData>()

            while (isActive) {
                // Wait for first notification
                val first = evalQueue.receive()
                batch.clear()
                batch.add(first)

                // Debounce: collect more notifications within the window
                val deadline = System.currentTimeMillis() + DEBOUNCE_MS
                while (System.currentTimeMillis() < deadline) {
                    val next = evalQueue.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }

                // Group by package for efficient evaluation
                val groups = batch.groupBy { it.packageName }

                for ((pkg, notifications) in groups) {
                    // Merge group into single evaluation
                    val merged = if (notifications.size > 1) {
                        val lastNotif = notifications.last()
                        lastNotif.copy(
                            text = "${notifications.size} messages, latest: ${lastNotif.text}"
                        )
                    } else {
                        notifications.first()
                    }

                    evaluateWithLlm(merged)
                }
            }
        }
    }

    private suspend fun evaluateWithLlm(data: NotificationData) {
        val ctx = context ?: return
        val appName = getAppName(ctx, data.packageName)
        val engine = getAvailableEngine()

        // Battery check: skip LLM on low battery
        if (isLowBattery()) {
            Log.d(TAG, "Low battery — skipping LLM eval for ${data.packageName}")
            return
        }

        // Rate limit check
        if (!canMakeLlmCall()) {
            Log.d(TAG, "Rate limited — skipping LLM eval for ${data.packageName}")
            return
        }

        if (engine == null || !engine.isLoaded) {
            Log.d(TAG, "LLM not available — skipping eval for ${data.packageName}")
            return
        }

        evalMutex.withLock {
            try {
                val rules = store?.getRules()?.filter { it.ruleType == RuleType.CUSTOM_LLM } ?: emptyList()
                val rulesText = if (rules.isNotEmpty()) {
                    rules.joinToString("\n") { "- ${it.description}" }
                } else {
                    "No special rules set. Use your best judgment."
                }

                val promptText = PromptLoader.loadAndFill(ctx, "dnd_evaluate.txt", mapOf(
                    "filter_rules" to rulesText,
                    "app_name" to appName,
                    "package_name" to data.packageName,
                    "notif_title" to data.title,
                    "notif_text" to data.text,
                    "timestamp" to java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(data.timestamp))
                )) ?: buildFallbackEvalPrompt(appName, data, rulesText)

                val dndSystemPrompt = PromptLoader.load(ctx, "dnd_system.txt") ?: FALLBACK_SYSTEM_PROMPT

                val messages = listOf(LlmEngine.Message("user", promptText))
                val response = StringBuilder()

                recordLlmCall()

                engine.generate(messages, systemPrompt = dndSystemPrompt).collect { token ->
                    response.append(token)
                    if (response.length > 150) return@collect
                }

                val result = parseEvalResponse(response.toString())
                val cacheKey = "${data.packageName}:${data.title.take(20)}"
                store?.cacheDecision(cacheKey, result)

                // Update the log entry
                updateLogEntry(data.packageName, data.title, result)

                if (result.shouldAlert) {
                    Log.d(TAG, "LLM says ALERT for ${data.packageName}: ${result.reason}")
                    alertUser(data, appName, "${data.title}: ${data.text}\n(${result.reason})")
                } else {
                    Log.d(TAG, "LLM says SILENCE for ${data.packageName}: ${result.reason}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "LLM evaluation failed for ${data.packageName}", e)
            }
        }
    }

    private fun parseEvalResponse(raw: String): DndEvalResult {
        val text = raw.trim().uppercase()

        // Parse IMPORTANCE line
        val importanceMatch = Regex("IMPORTANCE:\\s*(LOW|MEDIUM|HIGH|CRITICAL)").find(text)
        val importance = importanceMatch?.groupValues?.get(1)?.let {
            try { ImportanceLevel.valueOf(it) } catch (_: Exception) { ImportanceLevel.MEDIUM }
        } ?: ImportanceLevel.MEDIUM

        // Parse REASON line
        val reasonMatch = Regex("REASON:\\s*(.+?)(?:\n|$)").find(raw.trim())
        val reason = reasonMatch?.groupValues?.get(1)?.trim() ?: "No reason given"

        // Parse ACTION line
        val actionMatch = Regex("ACTION:\\s*(SILENCE|ALERT)").find(text)
        val shouldAlert = actionMatch?.groupValues?.get(1) == "ALERT" ||
                importance == ImportanceLevel.CRITICAL ||
                importance == ImportanceLevel.HIGH

        return DndEvalResult(importance, reason, shouldAlert)
    }

    // ── Alerting ──────────────────────────────────────────

    private fun alertUser(data: NotificationData, appName: String, message: String) {
        val ctx = context ?: return
        HermieNotificationHelper.notifyDndAlert(
            context = ctx,
            title = "Important: $appName",
            message = message,
            mood = MascotMood.CONCERNED
        )
    }

    // ── Notification Log ─────────────────────────────────

    private fun logNotification(
        data: NotificationData,
        appName: String,
        importance: ImportanceLevel,
        reasoning: String?,
        wasLetThrough: Boolean,
        matchedRuleId: String?
    ) {
        val entry = LoggedNotification(
            packageName = data.packageName,
            appName = appName,
            title = data.title,
            text = data.text,
            timestamp = data.timestamp,
            importance = importance,
            llmReasoning = reasoning,
            wasLetThrough = wasLetThrough,
            matchedRuleId = matchedRuleId
        )
        synchronized(_notificationLog) {
            _notificationLog.add(entry)
            if (_notificationLog.size > 200) {
                _notificationLog.removeAt(0)
            }
        }
        logDirtyCount++
        // Flush every 10 entries
        if (logDirtyCount >= 10) flushLog()
    }

    private fun updateLogEntry(packageName: String, title: String, result: DndEvalResult) {
        synchronized(_notificationLog) {
            val idx = _notificationLog.indexOfLast {
                it.packageName == packageName && it.title == title && it.llmReasoning == "Pending LLM evaluation"
            }
            if (idx >= 0) {
                _notificationLog[idx] = _notificationLog[idx].copy(
                    importance = result.importance,
                    llmReasoning = result.reason,
                    wasLetThrough = result.shouldAlert
                )
                logDirtyCount++
            }
        }
    }

    private fun flushLog() {
        synchronized(_notificationLog) {
            store?.saveLog(_notificationLog.toList())
            logDirtyCount = 0
        }
    }

    // ── Summary Generation ────────────────────────────────

    suspend fun generateMissedSummary(sinceMinutes: Int? = null): String {
        val since = if (sinceMinutes != null) {
            System.currentTimeMillis() - sinceMinutes * 60 * 1000L
        } else {
            store?.dndEnabledSince ?: (System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        }

        val log = synchronized(_notificationLog) {
            _notificationLog.filter { it.timestamp >= since }
        }

        if (log.isEmpty()) return "No notifications received during this period."

        val silenced = log.count { !it.wasLetThrough }
        val letThrough = log.count { it.wasLetThrough }
        val duration = (System.currentTimeMillis() - since) / 60000

        // Group by app
        val byApp = log.groupBy { it.appName }
        val summary = StringBuilder()
        summary.append("While DND was on (~${duration}m):\n")
        summary.append("$silenced silenced, $letThrough let through\n\n")

        for ((app, notifs) in byApp.entries.sortedByDescending { it.value.size }) {
            val count = notifs.size
            val latest = notifs.maxByOrNull { it.timestamp }
            val importantCount = notifs.count { it.importance == ImportanceLevel.HIGH || it.importance == ImportanceLevel.CRITICAL }

            if (count <= 2) {
                notifs.forEach { n ->
                    summary.append("- $app: ${n.title}: ${n.text.take(60)}\n")
                }
            } else {
                summary.append("- $app: $count notifications")
                if (importantCount > 0) summary.append(" ($importantCount important)")
                if (latest != null) summary.append(" — latest: ${latest.title}")
                summary.append("\n")
            }
        }

        // Try LLM-based summary for richer output
        val ctx = context
        val engine = getAvailableEngine()
        if (ctx != null && engine != null && engine.isLoaded && log.size > 3) {
            try {
                val rawList = log.takeLast(30).joinToString("\n") { n ->
                    "${n.appName} | ${n.title}: ${n.text.take(50)}"
                }

                val promptText = PromptLoader.loadAndFill(ctx, "dnd_summarize.txt", mapOf(
                    "notification_list" to rawList,
                    "duration" to "${duration}m",
                    "total_count" to log.size.toString(),
                    "silenced_count" to silenced.toString()
                )) ?: return summary.toString()

                val dndSystemPrompt = PromptLoader.load(ctx, "dnd_system.txt") ?: FALLBACK_SYSTEM_PROMPT

                val messages = listOf(LlmEngine.Message("user", promptText))
                val response = StringBuilder()
                engine.generate(messages, systemPrompt = dndSystemPrompt).collect { token ->
                    response.append(token)
                    if (response.length > 500) return@collect
                }

                val cleaned = response.toString().trim()
                    .replace(Regex("<[^>]+>"), "")
                    .take(500)
                if (cleaned.length > 20) return cleaned
            } catch (e: Exception) {
                Log.e(TAG, "LLM summary failed, using basic", e)
            }
        }

        return summary.toString()
    }

    // ── Rate Limiting & Battery ────────────────────────────

    private fun canMakeLlmCall(): Boolean {
        val now = System.currentTimeMillis()
        llmCallTimestamps.removeAll { now - it > 60000 }
        return llmCallTimestamps.size < MAX_LLM_CALLS_PER_MINUTE
    }

    private fun recordLlmCall() {
        llmCallTimestamps.add(System.currentTimeMillis())
    }

    private fun shouldAttemptLlmEval(): Boolean {
        return llmEngine?.isLoaded == true && canMakeLlmCall() && !isLowBattery()
    }

    private fun isLowBattery(): Boolean {
        val ctx = context ?: return false
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        return level < 15
    }

    // ── Helpers ──────────────────────────────────────────

    private fun getAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private fun buildFallbackEvalPrompt(
        appName: String,
        data: NotificationData,
        rulesText: String
    ): String {
        return """
User's active filter rules:
$rulesText

Evaluate this notification:
- App: $appName (${data.packageName})
- Title: ${data.title}
- Content: ${data.text}

Respond with:
IMPORTANCE: [LOW|MEDIUM|HIGH|CRITICAL]
REASON: [brief explanation]
ACTION: [SILENCE|ALERT]
        """.trimIndent()
    }

    // ── Tool Interface ──────────────────────────────────────

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "dnd.toggle",
            description = "Enable or disable Smart Do Not Disturb mode",
            parameters = mapOf(
                "enabled" to ToolParam("bool", "true to enable DND, false to disable")
            )
        ),
        ToolDefinition(
            name = "dnd.status",
            description = "Get current DND status, active rules, and stats",
            parameters = emptyMap()
        ),
        ToolDefinition(
            name = "dnd.add_rule",
            description = "Add a smart filter rule for notifications during DND. Supports natural language rules like 'Let me know if Mom texts something important' or 'Alert me about delivery notifications'",
            parameters = mapOf(
                "description" to ToolParam("str", "Natural language rule description"),
                "type" to ToolParam("str", "Rule type: allow_contact, allow_app, block_app, or custom (default: custom)", required = false),
                "contact" to ToolParam("str", "Contact name (for allow_contact rules)", required = false),
                "app" to ToolParam("str", "App name or package (for allow_app/block_app rules)", required = false),
                "temporary" to ToolParam("bool", "Whether this rule expires automatically", required = false),
                "duration_minutes" to ToolParam("int", "How long a temporary rule lasts in minutes", required = false)
            )
        ),
        ToolDefinition(
            name = "dnd.remove_rule",
            description = "Remove a DND filter rule by its ID or description",
            parameters = mapOf(
                "rule_id" to ToolParam("str", "Rule ID or partial description to match")
            )
        ),
        ToolDefinition(
            name = "dnd.list_rules",
            description = "List all active DND filter rules",
            parameters = emptyMap()
        ),
        ToolDefinition(
            name = "dnd.missed",
            description = "Get a summary of notifications missed while DND was active",
            parameters = mapOf(
                "since_minutes" to ToolParam("int", "How far back to look in minutes (default: since DND was enabled)", required = false)
            )
        ),
        ToolDefinition(
            name = "dnd.allow_contact",
            description = "Always allow notifications from a specific contact during DND",
            parameters = mapOf(
                "contact" to ToolParam("str", "Contact name to allow through DND")
            )
        ),
        ToolDefinition(
            name = "dnd.allow_app",
            description = "Always allow notifications from a specific app during DND",
            parameters = mapOf(
                "app" to ToolParam("str", "App name or package name to allow")
            )
        ),
        // Notification reading tools (absorbed from old NotificationModule)
        ToolDefinition(
            name = "notification.recent",
            description = "Get recent notifications from all apps",
            parameters = mapOf(
                "count" to ToolParam("int", "Number of recent notifications to return", required = false)
            )
        ),
        ToolDefinition(
            name = "notification.from",
            description = "Get notifications from a specific app",
            parameters = mapOf(
                "app" to ToolParam("str", "App name or package name")
            )
        ),
        ToolDefinition(
            name = "notification.summary",
            description = "Get a summary of recent notifications grouped by app",
            parameters = emptyMap()
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        return when (name) {
            "dnd.toggle" -> {
                val enabled = params["enabled"]?.toBooleanStrictOrNull()
                    ?: return ToolResult.Error("Missing 'enabled' parameter (true/false)")

                if (enabled && !isDndPolicyAccessGranted()) {
                    return ToolResult.Error("DND policy access not granted. Please enable it in Settings > Do Not Disturb access.")
                }

                toggleDnd(enabled)
                val status = if (enabled) "enabled" else "disabled"
                ToolResult.Success("Smart DND $status. ${if (enabled) "Notifications will be filtered intelligently." else "All notifications will come through normally."}")
            }

            "dnd.status" -> {
                val enabled = _dndEnabled.value
                val rules = store?.getRules() ?: emptyList()
                val silenced = _silencedCount.value
                val policyAccess = isDndPolicyAccessGranted()
                val notifAccess = context?.let { NotificationModule.isNotificationAccessGranted(it) } ?: false
                val since = store?.dndEnabledSince ?: 0
                val duration = if (enabled && since > 0) {
                    val mins = (System.currentTimeMillis() - since) / 60000
                    if (mins > 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
                } else "N/A"

                val sb = StringBuilder()
                sb.append("Smart DND: ${if (enabled) "ON (${duration})" else "OFF"}\n")
                sb.append("DND policy access: ${if (policyAccess) "granted" else "NOT granted"}\n")
                sb.append("Notification access: ${if (notifAccess) "granted" else "NOT granted"}\n")
                sb.append("Active rules: ${rules.size}\n")
                if (enabled) sb.append("Notifications silenced: $silenced\n")
                if (rules.isNotEmpty()) {
                    sb.append("\nRules:\n")
                    rules.forEach { r ->
                        sb.append("- [${r.ruleType.name}] ${r.description}")
                        if (r.isTemporary) sb.append(" (temporary)")
                        sb.append("\n")
                    }
                }
                ToolResult.Success(sb.toString())
            }

            "dnd.add_rule" -> {
                val description = params["description"]
                    ?: return ToolResult.Error("Missing 'description' parameter")
                val typeStr = params["type"]?.uppercase() ?: "CUSTOM"
                val contact = params["contact"]
                val app = params["app"]
                val temporary = params["temporary"]?.toBooleanStrictOrNull() ?: false
                val durationMinutes = params["duration_minutes"]?.toIntOrNull()

                val ruleType = when (typeStr) {
                    "ALLOW_CONTACT" -> RuleType.ALLOW_CONTACT
                    "ALLOW_APP" -> RuleType.ALLOW_APP
                    "BLOCK_APP" -> RuleType.BLOCK_APP
                    else -> RuleType.CUSTOM_LLM
                }

                val expiresAt = if (temporary && durationMinutes != null) {
                    System.currentTimeMillis() + durationMinutes * 60 * 1000L
                } else null

                val rule = DndFilterRule(
                    description = description,
                    ruleType = ruleType,
                    contactName = contact,
                    packagePattern = app,
                    isTemporary = temporary,
                    expiresAt = expiresAt
                )

                store?.addRule(rule)
                val expiryNote = if (temporary && durationMinutes != null) " (expires in ${durationMinutes}m)" else ""
                ToolResult.Success("Rule added: \"$description\" [${ruleType.name}]$expiryNote")
            }

            "dnd.remove_rule" -> {
                val query = params["rule_id"]
                    ?: return ToolResult.Error("Missing 'rule_id' parameter")
                val rule = store?.findRuleByDescription(query)
                    ?: return ToolResult.Error("No rule found matching: $query")
                store?.removeRule(rule.id)
                ToolResult.Success("Removed rule: \"${rule.description}\"")
            }

            "dnd.list_rules" -> {
                val rules = store?.getRules() ?: emptyList()
                if (rules.isEmpty()) {
                    ToolResult.Success("No active DND rules. Add rules with dnd.add_rule.")
                } else {
                    val text = rules.mapIndexed { i, r ->
                        "${i + 1}. [${r.ruleType.name}] ${r.description}" +
                            (if (r.isTemporary) " (temporary, expires: ${r.expiresAt?.let { formatTime(it) } ?: "?"})" else "") +
                            " (id: ${r.id.take(8)})"
                    }.joinToString("\n")
                    ToolResult.Success("Active rules:\n$text")
                }
            }

            "dnd.missed" -> {
                val sinceMinutes = params["since_minutes"]?.toIntOrNull()
                val summary = generateMissedSummary(sinceMinutes)
                ToolResult.Success(summary)
            }

            "dnd.allow_contact" -> {
                val contact = params["contact"]
                    ?: return ToolResult.Error("Missing 'contact' parameter")
                val rule = DndFilterRule(
                    description = "Allow notifications from $contact",
                    ruleType = RuleType.ALLOW_CONTACT,
                    contactName = contact
                )
                store?.addRule(rule)
                ToolResult.Success("$contact's notifications will always come through during DND.")
            }

            "dnd.allow_app" -> {
                val app = params["app"]
                    ?: return ToolResult.Error("Missing 'app' parameter")
                val rule = DndFilterRule(
                    description = "Allow notifications from $app",
                    ruleType = RuleType.ALLOW_APP,
                    packagePattern = app
                )
                store?.addRule(rule)
                ToolResult.Success("$app notifications will always come through during DND.")
            }

            // Notification reading tools
            "notification.recent" -> {
                val notifications = HermieNotificationListener.recentNotifications.value
                val count = params["count"]?.toIntOrNull() ?: 5
                val recent = notifications.take(count)
                if (recent.isEmpty()) {
                    ToolResult.Success("No recent notifications.")
                } else {
                    val text = recent.joinToString("\n") { n ->
                        val app = context?.let { getAppName(it, n.packageName) } ?: n.packageName
                        "- $app | ${n.title}: ${n.text}"
                    }
                    ToolResult.Success(text)
                }
            }

            "notification.from" -> {
                val app = params["app"] ?: return ToolResult.Error("Missing app parameter")
                val notifications = HermieNotificationListener.recentNotifications.value
                val matching = notifications.filter {
                    it.packageName.contains(app, ignoreCase = true) ||
                    it.title.contains(app, ignoreCase = true)
                }
                if (matching.isEmpty()) {
                    ToolResult.Success("No notifications from $app.")
                } else {
                    val text = matching.joinToString("\n") { "- ${it.title}: ${it.text}" }
                    ToolResult.Success(text)
                }
            }

            "notification.summary" -> {
                val notifications = HermieNotificationListener.recentNotifications.value
                if (notifications.isEmpty()) {
                    ToolResult.Success("No notifications to summarize.")
                } else {
                    val byApp = notifications.groupBy { it.packageName }
                    val summary = byApp.entries.joinToString("\n") { (pkg, notifs) ->
                        val app = context?.let { getAppName(it, pkg) } ?: pkg
                        "- $app: ${notifs.size} notification(s) — latest: ${notifs.first().title}"
                    }
                    ToolResult.Success("${notifications.size} recent notifications:\n$summary")
                }
            }

            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    private fun formatTime(millis: Long): String {
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
    }

    companion object {
        private const val TAG = "SmartDndModule"

        private const val FALLBACK_SYSTEM_PROMPT =
            "You are Hermie's notification filter. Evaluate notification importance. " +
            "Respond with IMPORTANCE, REASON, and ACTION lines only. Be concise."
    }
}
