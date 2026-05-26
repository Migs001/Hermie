package com.hermie.assistant.modules.dnd

import java.util.UUID

/**
 * Data models for the Smart DND system.
 */

data class DndFilterRule(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val ruleType: RuleType,
    val contactName: String? = null,
    val packagePattern: String? = null,
    val isTemporary: Boolean = false,
    val expiresAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val priority: Int = 0
)

enum class RuleType {
    ALLOW_CONTACT,   // Always let through from this contact
    ALLOW_APP,       // Always let through from this app
    BLOCK_APP,       // Always block from this app
    CUSTOM_LLM       // Evaluate via LLM with the description as context
}

data class LoggedNotification(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val importance: ImportanceLevel = ImportanceLevel.MEDIUM,
    val llmReasoning: String? = null,
    val wasLetThrough: Boolean = false,
    val matchedRuleId: String? = null
)

enum class ImportanceLevel {
    LOW,       // Spam, marketing, social media noise
    MEDIUM,    // Normal messages, regular activity
    HIGH,      // Important messages, calls from known contacts
    CRITICAL   // Emergency-level: override DND no matter what
}

/**
 * Result of the LLM evaluating a notification's importance.
 */
data class DndEvalResult(
    val importance: ImportanceLevel,
    val reason: String,
    val shouldAlert: Boolean
)
