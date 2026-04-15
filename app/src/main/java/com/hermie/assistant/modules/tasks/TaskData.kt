package com.hermie.assistant.modules.tasks

import java.util.UUID

/**
 * Represents a high-level task that gets broken into subtasks.
 * Each subtask runs through an iterative think/commit execution loop.
 */
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val subtasks: List<SubTask> = emptyList(),
    val status: TaskStatus = TaskStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,

    // ── Scheduling ─────────────────────────────────────────────
    /** Epoch-ms when this task should auto-fire. null = run now / manual. */
    val scheduledFor: Long? = null,

    // ── Plan review ────────────────────────────────────────────
    /** If true, execution pauses after planning for user approval (AWAITING_REVIEW status). */
    val requirePlanReview: Boolean = false,

    // ── Artifact ──────────────────────────────────────────────
    /** Structured output produced on task completion. */
    val artifact: TaskArtifact? = null,
    /** Whether the home-screen artifact chip has been dismissed. */
    val artifactDismissed: Boolean = false,

    // ── Pause/Resume ───────────────────────────────────────────
    /** Index of the subtask in [subtasks] that was executing when the task was paused. */
    val pausedAtSubtaskIndex: Int? = null,
    /** Iteration count within the paused subtask at the time of pause. */
    val pausedAtIteration: Int? = null
)

data class SubTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val status: TaskStatus = TaskStatus.PENDING,
    /** Final summary result for this subtask */
    val result: String? = null,
    /** Full iteration history — each think+commit cycle */
    val iterations: List<Iteration> = emptyList(),
    /** Parent subtask ID if this was spawned from another subtask */
    val parentSubtaskId: String? = null,
    val order: Int = 0,
    /** Max think/commit cycles before marking as failed */
    val maxIterations: Int = MAX_ITERATIONS
) {
    companion object {
        const val MAX_ITERATIONS = 8
    }
}

/**
 * A single think/commit cycle within the subtask execution loop.
 *
 * Pass 1 (think): LLM reasons about next action → output stored in [llmResponse].
 * Pass 2 (commit): LLM emits exactly one tool call / done / subtask tag.
 * The commit output is appended to [llmResponse] for full history.
 */
data class Iteration(
    val index: Int,
    val llmResponse: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    /** Whether LLM signaled it's done (<done> tag) in this iteration */
    val isDone: Boolean = false,
    /** Any sub-subtasks spawned during this iteration */
    val spawnedSubtasks: List<String> = emptyList()
)

/**
 * A single tool call made during an iteration, with its result.
 */
data class ToolCall(
    val toolName: String,
    val params: Map<String, String>,
    val result: String,
    val isSuccess: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

enum class TaskStatus {
    PENDING,
    PLANNING,           // LLM is breaking down the task into subtasks
    AWAITING_REVIEW,    // Plan ready; waiting for user to approve before execution
    SCHEDULED,          // Will fire at scheduledFor timestamp
    QUEUED,             // Alarm fired but Brain was busy; will run when free
    IN_PROGRESS,
    PAUSED,             // User paused mid-execution; resumable via Resume button
    COMPLETED,
    FAILED
}
