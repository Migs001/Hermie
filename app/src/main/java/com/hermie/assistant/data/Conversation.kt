package com.hermie.assistant.data

import java.util.UUID

/** A single chat message */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,       // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** Parsed emotion tag from assistant responses */
    val emotion: String? = null,
    /** SLM mind debug info (classification JSON, retrieved nodes summary) */
    val mindDebug: String? = null,
    /** Model's thinking/reasoning content (Qwen 3.5 <think> blocks) */
    val thinkingContent: String? = null,
    /** Attached image URI (content:// or file://) — for vision model input */
    val imageUri: String? = null
)

/** A conversation (chat thread) */
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
