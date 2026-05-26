package com.hermie.assistant.modules.screentime

import android.content.Context
import android.util.Log
import com.hermie.assistant.data.HermieSettings
import com.hermie.assistant.data.PromptLoader
import com.hermie.assistant.llm.LlmEngine
import com.hermie.assistant.modules.*
import com.hermie.assistant.modules.accessibility.HermieAccessibilityService
import com.hermie.assistant.service.HermieNotificationHelper
import com.hermie.assistant.ui.mascot.MascotMood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Module that monitors screen time per app and fires triggers.
 * Uses UsageStatsManager (requires user grant in Settings).
 *
 * When a trigger fires:
 * 1. Builds full conversation transcript for today from ScreenTimeConversationStore
 * 2. Generates a dynamic LLM message (mind engine only — stays resident in background)
 * 3. Sends a notification with inline reply + bubble
 * 4. Escalates every 5 minutes if the app keeps being used
 *
 * Also monitors app close/reopen events:
 * - Congratulates the user when they close a triggered app (LLM-generated)
 * - Shows disappointment if they reopen it (LLM-generated)
 *
 * Bubble dismissal: re-fires once angrier at the same level, then gives up with a
 * cheeky message if dismissed again. Dismissal counts are in-memory and reset daily.
 *
 * At escalation level 2: sends user home via HermieAccessibilityService (3s delay,
 * only if the app is still in the foreground).
 */
class ScreenTimeModule : HermieModule, ToolModule, BackgroundModule {

    override val id = "screentime"
    override val displayName = "Screen Time"
    override val description = "Monitor app usage and set screen time limits"
    override val iconName = "timer"
    override var isActive: Boolean = false
        private set
    override val needsBackgroundExecution = true

    override val requiredPermissions = listOf("android.permission.PACKAGE_USAGE_STATS")

    private var tracker: ScreenTimeTracker? = null
    private var context: Context? = null
    private var settings: HermieSettings? = null
    private var mindEngine: com.hermie.assistant.llm.MindLlmEngine? = null
    private var conversationStore: ScreenTimeConversationStore? = null

    private val moduleScope = CoroutineScope(Dispatchers.IO)

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    /**
     * Per-(packageName, escalationLevel) dismissal count — resets when rolloverIfNeeded
     * detects a new day for that package. Session-scoped: not persisted.
     */
    private val dismissalCounts = mutableMapOf<Pair<String, Int>, Int>()
    private val dismissalCountsDate = mutableMapOf<String, String>()

    fun setMindEngine(engine: com.hermie.assistant.llm.MindLlmEngine) {
        this.mindEngine = engine
    }

    override suspend fun initialize(context: Context) {
        this.context = context
        this.settings = HermieSettings(context)
        this.conversationStore = ScreenTimeConversationStore(context)
        tracker = ScreenTimeTracker(context)

        tracker?.onTriggerFired = onTriggerFired@{ pkg, minutesUsed, limit, escalationLevel ->
            val ctx = this.context ?: return@onTriggerFired
            val appName = getAppName(ctx, pkg)
            val reason = settings?.getScreenTimeReason(pkg)

            moduleScope.launch {
                val history = tracker?.getAppUsageHistory(pkg)

                val message = generateConvincingMessage(
                    pkg = pkg,
                    appName = appName,
                    minutesUsed = minutesUsed,
                    limitMinutes = limit,
                    personalReason = reason,
                    escalationLevel = escalationLevel,
                    history = history
                )

                val mood = when (escalationLevel) {
                    0 -> MascotMood.CONCERNED
                    1 -> MascotMood.ANNOYED
                    else -> MascotMood.ANNOYED
                }
                val title = when (escalationLevel) {
                    0 -> "Hey, $appName limit reached"
                    1 -> "Still on $appName?"
                    else -> "$appName — seriously."
                }

                try {
                    HermieNotificationHelper.notifyScreenTime(
                        context = ctx,
                        packageName = pkg,
                        title = title,
                        message = message,
                        mood = mood,
                        escalationLevel = escalationLevel
                    )
                    Log.d(TAG, "Screen time notification sent for $pkg (level $escalationLevel)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send notification for $pkg", e)
                }

                // Level 2: send user home if the app is still in foreground
                if (escalationLevel == 2) {
                    val currentFg = tracker?.getCurrentForegroundApp()
                    if (currentFg == pkg) {
                        delay(3_000)
                        val sent = HermieAccessibilityService.goHome()
                        Log.d(TAG, "Level-2 home action for $pkg: sent=$sent")
                    }
                }
            }
        }

        tracker?.onMonitoredAppClosed = onAppClosed@{ pkg ->
            val ctx = this.context ?: return@onAppClosed
            val appName = getAppName(ctx, pkg)
            val reason = settings?.getScreenTimeReason(pkg)
            Log.d(TAG, "User closed monitored app: $appName")

            moduleScope.launch {
                val store = conversationStore ?: return@launch
                store.rolloverIfNeeded(pkg)
                resetDismissalCountsIfNewDay(pkg)
                store.appendTurn(pkg, ConversationTurn(System.currentTimeMillis(), "event", "closed app"))

                val message = generateCloseMessage(pkg, appName, reason)
                store.appendTurn(pkg, ConversationTurn(System.currentTimeMillis(), "hermie", message))

                try {
                    HermieNotificationHelper.notify(
                        context = ctx,
                        title = "Nice one!",
                        message = message,
                        mood = MascotMood.HAPPY,
                        type = HermieNotificationHelper.NotificationType.SCREEN_TIME,
                        notificationId = HermieNotificationHelper.screenTimeNotificationId(pkg)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send close congratulation", e)
                }
            }
        }

        tracker?.onMonitoredAppReopened = onAppReopened@{ pkg, minutesUsed, limitMinutes ->
            val ctx = this.context ?: return@onAppReopened
            val appName = getAppName(ctx, pkg)
            val reason = settings?.getScreenTimeReason(pkg)
            Log.d(TAG, "User reopened monitored app: $appName")

            moduleScope.launch {
                val store = conversationStore ?: return@launch
                store.rolloverIfNeeded(pkg)
                resetDismissalCountsIfNewDay(pkg)
                store.appendTurn(
                    pkg,
                    ConversationTurn(System.currentTimeMillis(), "event", "reopened app at ${minutesUsed}m total today")
                )

                val message = generateReopenMessage(pkg, appName, minutesUsed, limitMinutes, reason)
                store.appendTurn(pkg, ConversationTurn(System.currentTimeMillis(), "hermie", message))

                try {
                    HermieNotificationHelper.notifyScreenTime(
                        context = ctx,
                        packageName = pkg,
                        title = "Back on $appName?",
                        message = message,
                        mood = MascotMood.ANNOYED,
                        escalationLevel = 0
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send reopen notification", e)
                }
            }
        }

        isActive = tracker?.hasPermission() ?: false

        if (isActive) {
            val triggerMap = settings?.getScreenTimeTriggers() ?: emptyMap()
            tracker?.setTriggers(triggerMap)
            Log.d(TAG, "Loaded ${triggerMap.size} saved screen time triggers")
        }
    }

    override suspend fun start() {
        if (tracker?.hasPermission() != true) return
        isActive = true
        val triggerMap = settings?.getScreenTimeTriggers() ?: emptyMap()
        tracker?.setTriggers(triggerMap)
    }

    override suspend fun stop() {
        tracker?.stopTracking()
        isActive = false
    }

    override fun release() {
        tracker?.stopTracking()
        tracker = null
        context = null
    }

    override suspend fun onBackgroundTick() {
        tracker?.queryTodayUsage()
    }

    fun reloadTriggers() {
        val triggerMap = settings?.getScreenTimeTriggers() ?: emptyMap()
        tracker?.setTriggers(triggerMap)
        Log.d(TAG, "Reloaded ${triggerMap.size} triggers")
    }

    fun hasPermission(): Boolean = tracker?.hasPermission() ?: false

    fun getTracker(): ScreenTimeTracker? = tracker

    // ── Bubble dismissal handling ────────────────────────────

    /**
     * Called by BubbleDismissReceiver when the user swipes away a screen time bubble.
     *
     * First dismissal at a given level: wait 15s, re-fire at same level with angrier mood,
     * and generate a short acknowledgment message.
     * Second dismissal at the same level: post a one-shot giveup notification and stop
     * bubbling for this level (next level starts fresh).
     */
    fun onBubbleDismissed(pkg: String, escalationLevel: Int) {
        val ctx = context ?: return
        val appName = getAppName(ctx, pkg)

        moduleScope.launch {
            val store = conversationStore ?: return@launch
            store.rolloverIfNeeded(pkg)
            resetDismissalCountsIfNewDay(pkg)

            store.appendTurn(
                pkg,
                ConversationTurn(System.currentTimeMillis(), "event", "dismissed bubble", escalationLevel)
            )

            val key = pkg to escalationLevel
            val count = (dismissalCounts[key] ?: 0) + 1
            dismissalCounts[key] = count

            when (count) {
                1 -> {
                    // First dismissal: re-fire after 15s with angrier mood
                    delay(15_000)
                    val used = tracker?.appUsageToday?.value?.get(pkg) ?: 0
                    val limit = settings?.getScreenTimeTriggers()?.get(pkg) ?: 0
                    val reason = settings?.getScreenTimeReason(pkg)

                    val angryMood = when (escalationLevel) {
                        0 -> MascotMood.ANNOYED
                        else -> MascotMood.ANNOYED
                    }
                    val message = generateRedismissMessage(pkg, appName, used, limit, escalationLevel, reason)
                    store.appendTurn(pkg, ConversationTurn(System.currentTimeMillis(), "hermie", message, escalationLevel))

                    val title = when (escalationLevel) {
                        0 -> "Hey, $appName…"
                        1 -> "Still on $appName."
                        else -> "$appName."
                    }

                    try {
                        HermieNotificationHelper.notifyScreenTime(
                            context = ctx,
                            packageName = pkg,
                            title = title,
                            message = message,
                            mood = angryMood,
                            escalationLevel = escalationLevel
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to re-fire bubble for $pkg", e)
                    }
                }
                else -> {
                    // Second dismissal: giveup notification, stop bubbling for this level
                    val message = generateGiveupMessage(pkg, appName)
                    store.appendTurn(pkg, ConversationTurn(System.currentTimeMillis(), "hermie", message, escalationLevel))

                    try {
                        HermieNotificationHelper.notifyScreenTimeGiveup(
                            context = ctx,
                            packageName = pkg,
                            message = message
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send giveup notification for $pkg", e)
                    }
                }
            }
        }
    }

    private fun resetDismissalCountsIfNewDay(pkg: String) {
        val today = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        if (dismissalCountsDate[pkg] != today) {
            dismissalCountsDate[pkg] = today
            dismissalCounts.keys.filter { it.first == pkg }.forEach { dismissalCounts.remove(it) }
        }
    }

    // ── Prompt loading ────────────────────────────────────────

    private fun loadSystemPrompt(): String {
        val ctx = context ?: return FALLBACK_SYSTEM_PROMPT
        return PromptLoader.load(ctx, "screentime_system.txt") ?: FALLBACK_SYSTEM_PROMPT
    }

    // ── LLM message generation ────────────────────────────────

    private suspend fun generateWithLlm(promptText: String, maxLength: Int = 250, fallback: String): String {
        val engine = mindEngine
        if (engine == null || !engine.isLoaded) {
            Log.w(TAG, "Mind engine not available (engine=${engine != null}, loaded=${engine?.isLoaded})")
            return fallback
        }

        return try {
            val messages = listOf(LlmEngine.Message("user", promptText))

            val response = StringBuilder()
            engine.generate(messages, maxTokens = maxLength, systemPrompt = loadSystemPrompt()).collect { token ->
                response.append(token)
                if (response.length > maxLength * 4) return@collect  // ~4 chars/token safety
            }

            val result = cleanLlmResponse(response.toString())
            if (result.isNotBlank()) {
                result
            } else {
                Log.w(TAG, "LLM response empty after cleaning (raw=${response.length} chars, likely all thinking/tags) — using fallback")
                fallback
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM generation failed, using fallback", e)
            fallback
        }
    }

    private suspend fun generateConvincingMessage(
        pkg: String,
        appName: String,
        minutesUsed: Long,
        limitMinutes: Int,
        personalReason: String?,
        escalationLevel: Int,
        history: ScreenTimeTracker.UsageHistory?
    ): String {
        val store = conversationStore
        store?.rolloverIfNeeded(pkg)
        resetDismissalCountsIfNewDay(pkg)

        val thread = store?.getTodayThread(pkg) ?: emptyList()
        val conversationSection = buildConversationSection(thread, appName)

        val historySection = buildHistorySection(appName, history)
        val reasonSection = if (personalReason != null) {
            "The user's personal reason for limiting $appName: \"$personalReason\""
        } else ""
        val escalationSection = when (escalationLevel) {
            0 -> "This is the FIRST warning. Be caring and encouraging — nudge them gently."
            1 -> "This is the SECOND warning — they ignored your first message and are STILL using $appName. Be more direct and show disappointment."
            else -> "This is the THIRD and FINAL warning — they've ignored you TWICE now. Be very firm, blunt, and make it hit hard."
        }

        // 30% chance to include yesterday's excuse in level-0 openers
        val yesterdayExcuseSection = if (escalationLevel == 0 && thread.isEmpty() && Random.nextFloat() < 0.30f) {
            val excuse = store?.getYesterdayFirstExcuse(pkg)
            if (excuse != null) "Yesterday they told you: \"$excuse\" — weave this in naturally only if it fits. Don't force it." else ""
        } else ""

        val ctx = context
        val promptText = if (ctx != null) {
            PromptLoader.loadAndFill(ctx, "screentime_trigger.txt", mapOf(
                "app_name" to appName,
                "minutes_used" to minutesUsed.toString(),
                "limit_minutes" to limitMinutes.toString(),
                "history_section" to historySection,
                "reason_section" to reasonSection,
                "conversation_section" to conversationSection,
                "yesterday_excuse_section" to yesterdayExcuseSection,
                "escalation_section" to escalationSection
            ))
        } else null

        val fallback = generateFallbackMessage(appName, minutesUsed, limitMinutes, personalReason, escalationLevel, history)
        val usedPrompt = promptText ?: buildFallbackTriggerPrompt(appName, minutesUsed, limitMinutes, personalReason, escalationLevel, history)

        val message = generateWithLlm(usedPrompt, 250, fallback)

        store?.appendTurn(pkg, ConversationTurn(System.currentTimeMillis(), "hermie", message, escalationLevel))
        return message
    }

    private suspend fun generateReopenMessage(
        pkg: String,
        appName: String,
        minutesUsed: Long,
        limitMinutes: Int,
        personalReason: String?
    ): String {
        val store = conversationStore
        val thread = store?.getTodayThread(pkg) ?: emptyList()
        val conversationSection = buildConversationSection(thread, appName)
        val reasonSection = if (personalReason != null) "Their reason for limiting: \"$personalReason\". " else ""

        val ctx = context
        val promptText = if (ctx != null) {
            PromptLoader.loadAndFill(ctx, "screentime_reopen.txt", mapOf(
                "app_name" to appName,
                "minutes_used" to minutesUsed.toString(),
                "limit_minutes" to limitMinutes.toString(),
                "reason_section" to reasonSection,
                "conversation_section" to conversationSection
            ))
        } else null

        val fallback = if (personalReason != null) {
            "You just opened $appName again. Remember why you limited it: $personalReason. Close it."
        } else {
            "Really? Back on $appName already? You set that limit for a reason."
        }
        val usedPrompt = promptText ?: ("The user just REOPENED $appName after you told them to stop. " +
            "They've used it ${minutesUsed}m today (limit: ${limitMinutes}m). $reasonSection" +
            "Express brief disappointment and firmly remind them why they should close it. 1-2 sentences:")

        return generateWithLlm(usedPrompt, 200, fallback)
    }

    private suspend fun generateCloseMessage(
        pkg: String,
        appName: String,
        personalReason: String?
    ): String {
        val store = conversationStore
        val thread = store?.getTodayThread(pkg) ?: emptyList()
        val conversationSection = buildConversationSection(thread, appName)
        val reasonSection = if (personalReason != null) "They limited it because: \"$personalReason\". Reference this." else ""

        val ctx = context
        val promptText = if (ctx != null) {
            PromptLoader.loadAndFill(ctx, "screentime_close.txt", mapOf(
                "app_name" to appName,
                "reason_section" to reasonSection,
                "conversation_section" to conversationSection
            ))
        } else null

        val usedPrompt = promptText ?: ("The user just CLOSED $appName after exceeding their screen time limit. " +
            "They did the right thing! $reasonSection" +
            "Generate a short, warm congratulations (1-2 sentences):")

        return generateWithLlm(usedPrompt, 200, "You closed $appName. Keep it up!")
    }

    private suspend fun generateRedismissMessage(
        pkg: String,
        appName: String,
        minutesUsed: Long,
        limitMinutes: Int,
        escalationLevel: Int,
        personalReason: String?
    ): String {
        val store = conversationStore
        val thread = store?.getTodayThread(pkg) ?: emptyList()
        val conversationSection = buildConversationSection(thread, appName)
        val escalationSection = when (escalationLevel) {
            0 -> "First warning, slightly sharper now."
            1 -> "Second warning, noticeably sharper."
            else -> "Final warning, very direct."
        }

        val ctx = context
        val promptText = if (ctx != null) {
            PromptLoader.loadAndFill(ctx, "screentime_redismiss.txt", mapOf(
                "app_name" to appName,
                "minutes_used" to minutesUsed.toString(),
                "limit_minutes" to limitMinutes.toString(),
                "conversation_section" to conversationSection,
                "escalation_section" to escalationSection
            ))
        } else null

        return generateWithLlm(
            promptText ?: "The user swiped away your $appName notification. Come back with a slightly sharper version. 1-2 sentences:",
            200,
            "Still on $appName? You dismissed me but here I am."
        )
    }

    private suspend fun generateGiveupMessage(pkg: String, appName: String): String {
        val store = conversationStore
        val thread = store?.getTodayThread(pkg) ?: emptyList()
        val conversationSection = buildConversationSection(thread, appName)

        val ctx = context
        val promptText = if (ctx != null) {
            PromptLoader.loadAndFill(ctx, "screentime_giveup.txt", mapOf(
                "app_name" to appName,
                "conversation_section" to conversationSection
            ))
        } else null

        return generateWithLlm(
            promptText ?: "The user dismissed your $appName warnings twice. Give up gracefully — cheeky, not mean. One sentence:",
            150,
            "Fine. Not your mom. Enjoy $appName."
        )
    }

    /**
     * Generate an LLM reply to the user's inline justification.
     * Called from ScreenTimeReplyReceiver after appending the user turn.
     */
    suspend fun generateReplyResponse(packageName: String, replyText: String): String {
        val ctx = context ?: return "Noted. Hermie's watching..."
        val appName = getAppName(ctx, packageName)
        val reason = settings?.getScreenTimeReason(packageName)
        val usage = tracker?.appUsageToday?.value?.get(packageName) ?: 0
        val limit = settings?.getScreenTimeTriggers()?.get(packageName) ?: 0

        val store = conversationStore
        store?.rolloverIfNeeded(packageName)
        resetDismissalCountsIfNewDay(packageName)

        val thread = store?.getTodayThread(packageName) ?: emptyList()
        val conversationSection = buildConversationSection(thread, appName)
        val reasonSection = if (reason != null) "Their reason for limiting: \"$reason\". " else ""

        val promptText = PromptLoader.loadAndFill(ctx, "screentime_reply.txt", mapOf(
            "app_name" to appName,
            "reply_text" to replyText,
            "minutes_used" to usage.toString(),
            "limit_minutes" to limit.toString(),
            "reason_section" to reasonSection,
            "conversation_section" to conversationSection
        )) ?: ("The user replied to your screen time warning about $appName with: \"$replyText\". " +
            "Acknowledge what they said but be witty. Let them know you'll check back. 1-2 sentences:")

        val message = generateWithLlm(promptText, 200, "Sure, \"$replyText\"... Hermie will check back on you.")
        store?.appendTurn(packageName, ConversationTurn(System.currentTimeMillis(), "hermie", message))
        return message
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun buildConversationSection(thread: List<ConversationTurn>, appName: String): String {
        if (thread.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("[CONVERSATION TODAY ON ${appName.uppercase()}]")
        for (turn in thread) {
            val time = timeFmt.format(Date(turn.timestamp))
            val prefix = when (turn.role) {
                "hermie" -> "Hermie ($time)"
                "user" -> "User ($time)"
                "event" -> "Event ($time)"
                else -> turn.role
            }
            sb.appendLine("$prefix: ${turn.content}")
        }
        sb.append("[/CONVERSATION]")
        return sb.toString()
    }

    private fun buildHistorySection(appName: String, history: ScreenTimeTracker.UsageHistory?): String {
        if (history == null) return ""
        val parts = mutableListOf<String>()
        if (history.lastWeekMinutes > 0) parts.add("${history.lastWeekFormatted()} this week")
        if (history.lastMonthMinutes > 0) parts.add("${history.lastMonthFormatted()} this month")
        if (history.lastYearMinutes > 0) parts.add("${history.lastYearFormatted()} this year")
        return if (parts.isNotEmpty()) "Total time on $appName: ${parts.joinToString(", ")}." else ""
    }

    private fun buildFallbackTriggerPrompt(
        appName: String,
        minutesUsed: Long,
        limitMinutes: Int,
        personalReason: String?,
        escalationLevel: Int,
        history: ScreenTimeTracker.UsageHistory?
    ): String {
        val sb = StringBuilder()
        sb.append("The user has been on $appName for $minutesUsed minutes today (limit: $limitMinutes minutes).")
        val historySection = buildHistorySection(appName, history)
        if (historySection.isNotBlank()) sb.append("\n\n$historySection")
        if (personalReason != null) sb.append("\n\nThe user's personal reason for limiting $appName: \"$personalReason\"")
        when (escalationLevel) {
            0 -> sb.append("\n\nThis is the first warning. Be caring and encouraging.")
            1 -> sb.append("\n\nThis is the SECOND warning — they ignored you. Be more direct.")
            else -> sb.append("\n\nThis is the THIRD and FINAL warning. Be very firm and blunt.")
        }
        sb.append("\n\nWrite a short convincing notification message (1-2 sentences):")
        return sb.toString()
    }

    private fun generateFallbackMessage(
        appName: String,
        minutesUsed: Long,
        limitMinutes: Int,
        personalReason: String?,
        escalationLevel: Int,
        history: ScreenTimeTracker.UsageHistory?
    ): String {
        val overBy = minutesUsed - limitMinutes
        val historyNote = if (history != null && history.lastMonthMinutes > 60) {
            " You've spent ${history.lastMonthFormatted()} on it this month alone."
        } else ""

        return when (escalationLevel) {
            0 -> if (personalReason != null) {
                "You've been on $appName for ${minutesUsed}m.$historyNote You said: $personalReason"
            } else {
                "You've hit your ${limitMinutes}m limit on $appName.$historyNote Time for a break."
            }
            1 -> if (personalReason != null) {
                "You're still on $appName — ${minutesUsed}m now.$historyNote Remember: $personalReason."
            } else {
                "Still on $appName? That's ${minutesUsed}m now, ${overBy}m over limit.$historyNote"
            }
            else -> if (personalReason != null) {
                "Last warning. ${minutesUsed}m on $appName.$historyNote You said: $personalReason. Put it down."
            } else {
                "Final reminder. ${minutesUsed}m on $appName, ${overBy}m over.$historyNote Enough."
            }
        }
    }

    private fun cleanLlmResponse(raw: String): String {
        return raw.trim()
            // Strip Qwen3 thinking blocks FIRST — multi-line, case-insensitive.
            // Must run before the generic <[^>]+> stripper because that would
            // remove the <think> tags but leave the thinking content behind.
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            // Also strip any unclosed <think> block (e.g. generation cut off inside thinking)
            .replace(Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<emotion>\\w+</emotion>\\s*"), "")
            .replace(Regex("<[^>]+>"), "")
            .removePrefix("\"").removeSuffix("\"")
            .trim()
            .take(250)
    }

    // ── Tool interface ──────────────────────────────────────

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "screentime.today",
            description = "Get today's screen time usage by app",
            parameters = emptyMap()
        ),
        ToolDefinition(
            name = "screentime.app",
            description = "Get screen time for a specific app",
            parameters = mapOf("app" to ToolParam("str", "App name or package name"))
        ),
        ToolDefinition(
            name = "screentime.limit",
            description = "Set a screen time limit for an app (triggers a warning)",
            parameters = mapOf(
                "app" to ToolParam("str", "App package name"),
                "minutes" to ToolParam("int", "Time limit in minutes")
            )
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        if (!isActive) {
            return ToolResult.Error("Screen time access not granted. Please enable Usage Access in Settings.")
        }

        return when (name) {
            "screentime.today" -> {
                val usage = tracker?.queryTodayUsage() ?: return ToolResult.Error("Tracker not available")
                if (usage.isEmpty()) {
                    ToolResult.Success("No usage data available for today.")
                } else {
                    val sorted = usage.entries.sortedByDescending { it.value }.take(10)
                    val text = sorted.joinToString("\n") { (pkg, minutes) ->
                        val appName = context?.let { getAppName(it, pkg) } ?: pkg
                        "• $appName: ${minutes}m"
                    }
                    ToolResult.Success("Today's screen time (top 10):\n$text")
                }
            }
            "screentime.app" -> {
                val app = params["app"] ?: return ToolResult.Error("Missing app parameter")
                val usage = tracker?.queryTodayUsage() ?: return ToolResult.Error("Tracker not available")
                val matching = usage.entries.filter { it.key.contains(app, ignoreCase = true) }
                if (matching.isEmpty()) {
                    ToolResult.Success("No usage found for $app today.")
                } else {
                    val text = matching.joinToString("\n") { (pkg, minutes) -> "• $pkg: ${minutes}m" }
                    ToolResult.Success(text)
                }
            }
            "screentime.limit" -> {
                val app = params["app"] ?: return ToolResult.Error("Missing app parameter")
                val minutes = params["minutes"]?.toIntOrNull()
                    ?: return ToolResult.Error("Invalid minutes parameter")
                settings?.setScreenTimeTrigger(app, minutes)
                reloadTriggers()
                ToolResult.Success("Set ${minutes}m limit for $app. Hermie will warn when exceeded.")
            }
            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    private fun getAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    companion object {
        private const val TAG = "ScreenTimeModule"
        private const val FALLBACK_SYSTEM_PROMPT =
            "You are Hermie, a caring but firm personal assistant. " +
            "Generate a SHORT (1-2 sentences max) convincing message to get the user to stop using an app. " +
            "Be personal, empathetic but direct. No emojis. No quotes around your message."
    }
}
