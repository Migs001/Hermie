package com.hermie.assistant.modules.tasks

import java.util.UUID

/**
 * Represents a high-level task that gets broken into subtasks.
 * Each subtask can be assigned to a sub-agent (LLM call) for execution.
 */
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val subtasks: List<SubTask> = emptyList(),
    val status: TaskStatus = TaskStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

data class SubTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val status: TaskStatus = TaskStatus.PENDING,
    /** The agent's response/result for this subtask */
    val result: String? = null,
    /** Instructions for the sub-agent handling this subtask */
    val agentPrompt: String? = null,
    val order: Int = 0
)

enum class TaskStatus {
    PENDING,
    PLANNING,      // LLM is breaking down the task
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
