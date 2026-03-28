package com.hermie.assistant.modules.tasks

import android.util.Log
import com.hermie.assistant.llm.LlmEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages task lifecycle: creation → planning → subtask execution → completion.
 * Uses the LLM to break tasks into subtasks and execute them step by step.
 */
class TaskManager(private val engine: LlmEngine) {

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _currentTask = MutableStateFlow<Task?>(null)
    val currentTask: StateFlow<Task?> = _currentTask.asStateFlow()

    /**
     * Create a new task from a user description.
     * The LLM will plan and break it into subtasks.
     */
    suspend fun createTask(title: String, description: String): Task {
        val task = Task(title = title, description = description, status = TaskStatus.PLANNING)
        _tasks.value = _tasks.value + task
        _currentTask.value = task

        // Ask LLM to break down the task
        val subtasks = planTask(task)
        val planned = task.copy(
            subtasks = subtasks,
            status = if (subtasks.isNotEmpty()) TaskStatus.PENDING else TaskStatus.FAILED
        )

        updateTask(planned)
        return planned
    }

    /**
     * Execute the next pending subtask in the current task.
     */
    suspend fun executeNextSubtask(): SubTask? {
        val task = _currentTask.value ?: return null
        val next = task.subtasks.firstOrNull { it.status == TaskStatus.PENDING } ?: return null

        // Mark as in progress
        val updated = next.copy(status = TaskStatus.IN_PROGRESS)
        updateSubtask(task.id, updated)

        // Execute via LLM
        val result = executeSubtask(task, updated)
        val completed = updated.copy(
            status = if (result != null) TaskStatus.COMPLETED else TaskStatus.FAILED,
            result = result
        )
        updateSubtask(task.id, completed)

        // Check if all subtasks are done
        val currentTask = _currentTask.value!!
        if (currentTask.subtasks.all { it.status == TaskStatus.COMPLETED }) {
            updateTask(currentTask.copy(
                status = TaskStatus.COMPLETED,
                completedAt = System.currentTimeMillis()
            ))
        }

        return completed
    }

    /**
     * Execute all remaining subtasks sequentially.
     */
    suspend fun executeAllSubtasks() {
        val task = _currentTask.value ?: return
        updateTask(task.copy(status = TaskStatus.IN_PROGRESS))

        while (true) {
            val result = executeNextSubtask() ?: break
            if (result.status == TaskStatus.FAILED) {
                _currentTask.value?.let {
                    updateTask(it.copy(status = TaskStatus.FAILED))
                }
                break
            }
        }
    }

    private suspend fun planTask(task: Task): List<SubTask> {
        if (!engine.isLoaded) return emptyList()

        val planPrompt = """Break down this task into 3-7 simple, sequential steps.
Each step should be a single clear action.
Format: numbered list, one step per line.

Task: ${task.title}
Details: ${task.description}

Steps:"""

        val messages = listOf(
            LlmEngine.Message("system", "You are a task planner. Break tasks into clear, actionable steps. Output ONLY the numbered steps, nothing else."),
            LlmEngine.Message("user", planPrompt)
        )

        val response = StringBuilder()
        engine.generate(messages, maxTokens = 300, temperature = 0.3f).collect { token ->
            response.append(token)
        }

        return parseSubtasks(response.toString())
    }

    private suspend fun executeSubtask(task: Task, subtask: SubTask): String? {
        if (!engine.isLoaded) return null

        val previousResults = task.subtasks
            .filter { it.status == TaskStatus.COMPLETED && it.result != null }
            .joinToString("\n") { "- ${it.title}: ${it.result}" }

        val prompt = buildString {
            append("You are working on: ${task.title}\n")
            if (previousResults.isNotBlank()) {
                append("Previous steps completed:\n$previousResults\n\n")
            }
            append("Current step: ${subtask.title}\n")
            append("${subtask.description}\n\n")
            append("Complete this step. Be concise and provide the result.")
        }

        val messages = listOf(
            LlmEngine.Message("system", "You are an efficient task executor. Complete the current step and provide a clear, concise result."),
            LlmEngine.Message("user", prompt)
        )

        val response = StringBuilder()
        try {
            engine.generate(messages, maxTokens = 400, temperature = 0.5f).collect { token ->
                response.append(token)
            }
            return response.toString().trim().ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute subtask ${subtask.id}", e)
            return null
        }
    }

    private fun parseSubtasks(text: String): List<SubTask> {
        return text.lines()
            .map { it.trim() }
            .filter { it.matches(Regex("^\\d+[.)\\-]\\s+.+")) }
            .mapIndexed { index, line ->
                val title = line.replace(Regex("^\\d+[.)\\-]\\s+"), "").trim()
                SubTask(
                    title = title,
                    description = title,
                    order = index
                )
            }
    }

    private fun updateTask(task: Task) {
        _tasks.value = _tasks.value.map { if (it.id == task.id) task else it }
        if (_currentTask.value?.id == task.id) {
            _currentTask.value = task
        }
    }

    private fun updateSubtask(taskId: String, subtask: SubTask) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        val updated = task.copy(
            subtasks = task.subtasks.map { if (it.id == subtask.id) subtask else it }
        )
        updateTask(updated)
    }

    fun deleteTask(taskId: String) {
        _tasks.value = _tasks.value.filter { it.id != taskId }
        if (_currentTask.value?.id == taskId) {
            _currentTask.value = null
        }
    }

    fun selectTask(taskId: String) {
        _currentTask.value = _tasks.value.find { it.id == taskId }
    }

    val activeTasks: List<Task>
        get() = _tasks.value.filter { it.status != TaskStatus.COMPLETED && it.status != TaskStatus.FAILED }

    companion object {
        private const val TAG = "TaskManager"
    }
}
