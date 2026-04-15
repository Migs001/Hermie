package com.hermie.assistant.modules.tasks

import android.content.Context
import android.util.Log
import com.hermie.assistant.llm.LlmEngine
import com.hermie.assistant.llm.LlamaNativeEngine
import com.hermie.assistant.modules.ModuleRegistry
import com.hermie.assistant.modules.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Manages task lifecycle: creation → (planning) → think/commit execution → completion.
 *
 * **System-prompt contract**:
 * TaskManager does NOT swap the LLM system prompt itself. The ViewModel (HermieViewModel)
 * is responsible for setting the correct system prompt BEFORE calling any planning or
 * execution methods, and for restoring the chat prompt AFTER the task finishes.
 *
 *   Planning phase:  ViewModel sets tasks_planner.txt → calls planCurrentTask()
 *   Execution phase: ViewModel sets tasks_system.txt  → calls executeAllSubtasks()
 *   Restoration:     ViewModel sets chat system prompt in its finally block
 *
 * This mirrors the pattern used by startSleepMode / startStudyWikipedia.
 *
 * **Think/commit split**:
 * Pass 1 (Think, temp 0.3): model reasons about next action, ends with ACTION: or DONE:
 * Pass 2 (Commit, temp 0.1): model emits exactly one <tool>, <done>, or <subtask> tag.
 * No inline [INSTRUCTIONS] wrappers — the system prompt sets the executor persona.
 *
 * **Pause/resume**:
 * When the ViewModel cancels the task coroutine, executeSubtaskIteratively detects
 * !isActive and records pausedAtSubtaskIndex / pausedAtIteration. executeAllSubtasks
 * detects the PAUSED result and updates the task status before breaking the loop.
 */
class TaskManager(
    private val engine: LlmEngine,
    private val context: Context,
    private val moduleRegistry: ModuleRegistry,
    /** Called after every significant task/subtask state change for disk persistence. */
    private val onTaskMutated: suspend (Task) -> Unit = {}
) {

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _currentTask = MutableStateFlow<Task?>(null)
    val currentTask: StateFlow<Task?> = _currentTask.asStateFlow()

    /** Live status text shown in the UI banner during execution. */
    private val _executionStatus = MutableStateFlow<String?>(null)
    val executionStatus: StateFlow<String?> = _executionStatus.asStateFlow()

    // ── Pause state (read by ViewModel's finally block) ─────
    @Volatile var pausedAtSubtaskIndex: Int? = null
    @Volatile var pausedAtIteration: Int? = null

    // ── Regex patterns ───────────────────────────────────────
    private val toolPattern    = Regex("<tool>(.*?)</tool>", RegexOption.DOT_MATCHES_ALL)
    private val funcPattern    = Regex("""(\w+\.\w+)\((.*)\)""", RegexOption.DOT_MATCHES_ALL)
    private val paramPattern   = Regex("""(\w+)="([^"]*?)"""")
    private val donePattern    = Regex(
        """<done(?:\s+type="([^"]*)")?>([\\s\\S]*?)</done>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val subtaskPattern  = Regex("<subtask>(.*?)</subtask>", RegexOption.DOT_MATCHES_ALL)
    // Case-sensitive think-pass terminal signals (at line start).
    // The model is instructed to use these exact strings, so IGNORE_CASE is intentionally absent.
    private val thinkActionPattern  = Regex("""(?:^|\n)ACTION:\s*(.+)""")
    private val thinkDonePattern    = Regex("""(?:^|\n)DONE:\s*(.+)""")
    private val thinkGiveUpPattern  = Regex("""(?:^|\n)GIVE_UP:\s*(.+)""")

    // ── Public API ───────────────────────────────────────────

    /** Seed the task list from disk on ViewModel init. */
    fun setTasks(tasks: List<Task>) {
        _tasks.value = tasks
    }

    /**
     * Create a new Task record. Does NOT plan — planning happens inside ViewModel's
     * runTask() after the Brain is switched to tasks_planner.txt.
     */
    suspend fun createTask(
        title: String,
        description: String,
        requirePlanReview: Boolean = false
    ): Task {
        val task = Task(
            title = title,
            description = description,
            status = TaskStatus.PENDING,
            requirePlanReview = requirePlanReview
        )
        _tasks.value = _tasks.value + task
        _currentTask.value = task
        onTaskMutated(task)
        return task
    }

    /**
     * Plan the current task using the LLM.
     *
     * **Pre-condition**: the ViewModel MUST have already set the system prompt to
     * tasks_planner.txt and reset the KV cache before calling this method.
     *
     * Generates 2-7 numbered subtasks from the task title/description.
     * Sets task status to AWAITING_REVIEW if requirePlanReview, else PENDING.
     * Sets task status to FAILED if the model produces no parseable steps.
     */
    suspend fun planCurrentTask() {
        val task = _currentTask.value ?: return
        if (!engine.isLoaded) {
            updateTask(task.copy(status = TaskStatus.FAILED))
            onTaskMutated(_currentTask.value!!)
            return
        }

        updateTask(task.copy(status = TaskStatus.PLANNING))
        _executionStatus.value = "Planning subtasks..."

        // Extract scheduling phrase from title+description BEFORE sending to planner,
        // so the planner sees the clean goal without a time reference that would cause
        // it to add a spurious "set alarm" step.
        val fullText = buildString {
            append(task.title)
            if (task.description.isNotBlank() && task.description != task.title) {
                append(" "); append(task.description)
            }
        }
        val (cleanedGoal, extractedSchedule) = extractSchedule(fullText)

        // Compact tool list for the planner — names + short descriptions only.
        // Full param schemas are injected per-iteration during execution.
        val toolList = moduleRegistry.getToolDefinitionsForMode(ModuleRegistry.BrainMode.TASKS)
            .joinToString("\n") { "- ${it.name}: ${it.description.take(60)}" }

        val userMsg = buildString {
            appendLine("Goal: $cleanedGoal")
            appendLine()
            appendLine("Available tools:")
            appendLine(toolList)
            appendLine()
            append("Output the numbered step list only.")
        }

        val response = StringBuilder()
        try {
            engine.generate(
                listOf(LlmEngine.Message("user", userMsg)),
                maxTokens = 400,
                temperature = 0.3f
            ).collect { response.append(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Planning failed", e)
        }

        _executionStatus.value = null

        val subtasks = parseSubtasks(response.toString())
        val finalTask = _currentTask.value ?: return  // task may have been updated while generating

        // Scheduling extracted from the goal text takes priority over requirePlanReview
        // for status assignment — the task is SCHEDULED until the alarm fires.
        val scheduledFor = extractedSchedule ?: finalTask.scheduledFor
        val nextStatus = when {
            subtasks.isEmpty()           -> TaskStatus.FAILED
            finalTask.requirePlanReview  -> TaskStatus.AWAITING_REVIEW
            scheduledFor != null         -> TaskStatus.SCHEDULED
            else                         -> TaskStatus.PENDING
        }
        val updated = finalTask.copy(
            subtasks = subtasks,
            status = nextStatus,
            scheduledFor = scheduledFor
        )
        updateTask(updated)
        onTaskMutated(updated)

        Log.d(TAG, "Planned ${subtasks.size} subtasks for '${task.title}' → $nextStatus" +
            if (scheduledFor != null) " (scheduled: $scheduledFor)" else "")
    }

    /**
     * Execute all PENDING subtasks sequentially.
     *
     * **Pre-condition**: ViewModel MUST have already set the system prompt to
     * tasks_system.txt and reset the KV cache before calling this method.
     *
     * Breaks when:
     * - No more PENDING subtasks (completed or failed).
     * - Coroutine is cancelled (pause) — task is marked PAUSED.
     */
    suspend fun executeAllSubtasks() {
        val task = _currentTask.value ?: return
        updateTask(task.copy(status = TaskStatus.IN_PROGRESS))
        pausedAtSubtaskIndex = null
        pausedAtIteration = null

        while (currentCoroutineContext().isActive) {
            val result = executeNextSubtask() ?: break

            if (result.status == TaskStatus.PAUSED) {
                // Coroutine was cancelled — record PAUSED on the parent task too.
                val current = _currentTask.value
                if (current != null) {
                    val paused = current.copy(
                        status = TaskStatus.PAUSED,
                        pausedAtSubtaskIndex = pausedAtSubtaskIndex,
                        pausedAtIteration = pausedAtIteration
                    )
                    updateTask(paused)
                    onTaskMutated(paused)
                }
                break
            }

            if (result.status == TaskStatus.FAILED) {
                Log.w(TAG, "Subtask failed: ${result.title} — continuing with remaining")
            }
        }

        _executionStatus.value = null
    }

    /**
     * Execute the next PENDING subtask with the think/commit loop.
     * [startAtIteration] lets resume start mid-subtask.
     */
    suspend fun executeNextSubtask(startAtIteration: Int = 0): SubTask? {
        val task = _currentTask.value ?: return null
        val nextIdx = task.subtasks.indexOfFirst { it.status == TaskStatus.PENDING }
        if (nextIdx < 0) return null

        val next = task.subtasks[nextIdx]
        val inProgress = next.copy(status = TaskStatus.IN_PROGRESS)
        updateSubtask(task.id, inProgress)

        val executed = executeSubtaskIteratively(
            task = _currentTask.value ?: return null,
            subtask = inProgress,
            subtaskIndex = nextIdx,
            startAtIteration = startAtIteration
        )
        updateSubtask(task.id, executed)

        // Persist after subtask completion
        _currentTask.value?.let { onTaskMutated(it) }

        // Check if task as a whole is now done
        if (executed.status != TaskStatus.PAUSED) {
            val current = _currentTask.value!!
            val allSettled = current.subtasks.all {
                it.status == TaskStatus.COMPLETED || it.status == TaskStatus.FAILED
            }
            if (allSettled) {
                val anyFailed = current.subtasks.any { it.status == TaskStatus.FAILED }
                val artifact = extractArtifact(executed.result)
                val finalTask = current.copy(
                    status = if (anyFailed) TaskStatus.FAILED else TaskStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                    artifact = artifact
                )
                updateTask(finalTask)
                onTaskMutated(finalTask)
            }
        }

        return executed
    }

    /**
     * Resume a PAUSED task from [subtaskIndex] / [iterationStart].
     * ViewModel sets the executor system prompt before calling this.
     */
    suspend fun resumeTask(taskId: String, subtaskIndex: Int, iterationStart: Int) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        val resumed = task.copy(
            status = TaskStatus.IN_PROGRESS,
            pausedAtSubtaskIndex = null,
            pausedAtIteration = null
        )
        updateTask(resumed)
        selectTask(taskId)

        // Reset the paused subtask back to PENDING so executeNextSubtask picks it up
        val subtask = resumed.subtasks.getOrNull(subtaskIndex) ?: return
        updateSubtask(taskId, subtask.copy(status = TaskStatus.PENDING))

        pausedAtSubtaskIndex = null
        pausedAtIteration = null

        // Execute paused subtask from saved iteration, then remaining subtasks
        val first = executeNextSubtask(startAtIteration = iterationStart) ?: return
        if (first.status == TaskStatus.PAUSED) {
            val current = _currentTask.value
            if (current != null) {
                val paused = current.copy(
                    status = TaskStatus.PAUSED,
                    pausedAtSubtaskIndex = pausedAtSubtaskIndex,
                    pausedAtIteration = pausedAtIteration
                )
                updateTask(paused)
                onTaskMutated(paused)
            }
            return
        }

        // Continue with any remaining subtasks
        while (currentCoroutineContext().isActive) {
            val result = executeNextSubtask() ?: break
            if (result.status == TaskStatus.PAUSED) {
                val current = _currentTask.value
                if (current != null) {
                    val paused = current.copy(
                        status = TaskStatus.PAUSED,
                        pausedAtSubtaskIndex = pausedAtSubtaskIndex,
                        pausedAtIteration = pausedAtIteration
                    )
                    updateTask(paused)
                    onTaskMutated(paused)
                }
                break
            }
            if (result.status == TaskStatus.FAILED) {
                Log.w(TAG, "Subtask failed on resume: ${result.title}")
            }
        }
        _executionStatus.value = null
    }

    // ── Think/Commit Execution Loop ──────────────────────────

    private suspend fun executeSubtaskIteratively(
        task: Task,
        subtask: SubTask,
        subtaskIndex: Int,
        startAtIteration: Int = 0
    ): SubTask = withContext(Dispatchers.IO) {
        if (!engine.isLoaded) {
            return@withContext subtask.copy(status = TaskStatus.FAILED, result = "LLM not loaded")
        }

        // Cast once — used for context-pressure checks in the loop.
        val nativeEngine = engine as? LlamaNativeEngine

        var current = subtask
        val iterations = subtask.iterations.toMutableList()
        val previousResults = task.subtasks
            .filter { it.status == TaskStatus.COMPLETED && it.result != null }
            .joinToString("\n") { "- ${it.title}: ${it.result}" }

        for (i in startAtIteration until SubTask.MAX_ITERATIONS) {
            if (!isActive) {
                // Coroutine cancelled — record pause point for resume
                pausedAtSubtaskIndex = subtaskIndex
                pausedAtIteration = i
                return@withContext current.copy(
                    status = TaskStatus.PAUSED,
                    iterations = iterations.toList()
                )
            }

            _executionStatus.value =
                "Subtask ${subtaskIndex + 1}/${task.subtasks.size}: ${subtask.title} (step ${i + 1})"
            Log.d(TAG, "Think/Commit iteration ${i + 1} for: ${subtask.title}")

            // ── Context pressure check ─────────────────────────────────────────
            // If context is >75% full, reset it and prepend a compact progress note
            // to the next think prompt. This prevents the native auto-shift crash.
            var contextPressureNote: String? = null
            if ((nativeEngine?.approximateContextUsedPct() ?: 0f) > 0.75f) {
                Log.w(TAG, "Context >75% at iteration ${i + 1} — resetting and re-priming")
                contextPressureNote = buildCompactContextSummary(iterations.takeLast(2))
                try { nativeEngine!!.resetContext() } catch (e: Exception) {
                    Log.w(TAG, "Mid-task context reset failed", e)
                }
            }

            // ── Pass 1: Think ──────────────────────────────────────────────────
            // The model reasons about the current state and ends with exactly one of:
            //   ACTION: <what to call next>
            //   DONE:   <summary of what was accomplished>
            //   GIVE_UP:<reason this cannot be completed>
            val thinkPrompt = buildThinkPrompt(
                task, subtask, previousResults, iterations, i, contextPressureNote
            )
            val thinkOutput = StringBuilder()
            try {
                engine.generate(
                    listOf(LlmEngine.Message("user", thinkPrompt)),
                    maxTokens = 250,
                    temperature = 0.3f
                ).collect { thinkOutput.append(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Think pass failed at iteration ${i + 1}", e)
                iterations.add(Iteration(i, "THINK ERROR: ${e.message}", isDone = true))
                break
            }

            val think = thinkOutput.toString().trim()
            Log.d(TAG, "Think[${i + 1}]: ${think.take(200)}")

            // Parse terminal signals — case-sensitive, must appear at line start.
            val doneSignal   = thinkDonePattern.find(think)
            val giveUpSignal = thinkGiveUpPattern.find(think)
            val actionMatch  = thinkActionPattern.find(think)

            when {
                doneSignal != null -> {
                    // DONE: skip commit, complete the subtask immediately.
                    val summary = doneSignal.groupValues[1].trim().ifEmpty { "Done" }
                    iterations.add(Iteration(i, think, isDone = true))
                    return@withContext current.copy(
                        status = TaskStatus.COMPLETED,
                        result = summary,
                        iterations = iterations.toList()
                    )
                }
                giveUpSignal != null -> {
                    // GIVE_UP: model determined it cannot complete this subtask.
                    val reason = giveUpSignal.groupValues[1].trim()
                    Log.w(TAG, "Subtask gave up at iteration ${i + 1}: $reason")
                    iterations.add(Iteration(i, think, isDone = true))
                    return@withContext current.copy(
                        status = TaskStatus.FAILED,
                        result = "GIVE_UP: $reason",
                        iterations = iterations.toList()
                    )
                }
                actionMatch == null -> {
                    // Malformed think — no terminal signal found. Skip this iteration
                    // rather than crashing or blindly proceeding to commit.
                    Log.w(TAG, "Think iter ${i + 1}: no ACTION/DONE/GIVE_UP signal — skipping")
                    iterations.add(Iteration(i, "MALFORMED THINK (no signal):\n$think"))
                    current = current.copy(iterations = iterations.toList())
                    updateSubtask(task.id, current)
                    continue
                }
                else -> { /* ACTION: fall through to commit pass */ }
            }

            val actionText = actionMatch!!.groupValues[1].trim()

            // ── Pass 2: Commit ─────────────────────────────────────────────────
            // Model is given its own reasoning + the decided action and must emit
            // exactly one <tool>, <done>, or <subtask> tag — nothing else.
            // NOTE: KV cache is NOT reset between think and commit so the model
            // still sees its reasoning in context.
            val commitPrompt = buildCommitPrompt(task, subtask, think, actionText)
            val commitOutput = StringBuilder()
            try {
                engine.generate(
                    listOf(LlmEngine.Message("user", commitPrompt)),
                    maxTokens = 120,
                    temperature = 0.1f
                ).collect { commitOutput.append(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Commit pass failed at iteration ${i + 1}", e)
                iterations.add(Iteration(i, "$think\n\nCOMMIT ERROR: ${e.message}"))
                continue
            }

            val commit = commitOutput.toString().trim()
            Log.d(TAG, "Commit[${i + 1}]: ${commit.take(150)}")

            // Parse tool call (first match only — strict single-action commit)
            val toolCalls = mutableListOf<ToolCall>()
            val toolMatch = toolPattern.find(commit)
            if (toolMatch != null) {
                val callStr = toolMatch.groupValues[1]
                val funcMatch = funcPattern.find(callStr)
                if (funcMatch != null) {
                    val toolName = funcMatch.groupValues[1]
                    val params = mutableMapOf<String, String>()
                    paramPattern.findAll(funcMatch.groupValues[2]).forEach { pm ->
                        params[pm.groupValues[1]] = pm.groupValues[2]
                    }
                    _executionStatus.value = "Calling $toolName..."
                    Log.d(TAG, "Executing tool: $toolName($params)")
                    val result = moduleRegistry.executeTool(toolName, params)
                    val isSuccess = result is ToolResult.Success
                    val resultMsg = when (result) {
                        is ToolResult.Success -> result.message
                        is ToolResult.Error   -> "ERROR: ${result.message}"
                    }
                    toolCalls.add(ToolCall(toolName, params, resultMsg, isSuccess))
                    Log.d(TAG, "Tool result: ${resultMsg.take(100)}")
                }
            }

            // Parse sub-subtask spawns
            val spawnedIds = mutableListOf<String>()
            subtaskPattern.findAll(commit).forEach { match ->
                val desc = match.groupValues[1].trim()
                if (desc.isNotBlank()) {
                    val newSub = spawnSubtask(task, subtask, desc)
                    spawnedIds.add(newSub.id)
                }
            }

            // Check for <done> in commit
            val doneMatch = donePattern.find(commit)
            val isDone = doneMatch != null

            iterations.add(Iteration(
                index = i,
                llmResponse = "$think\n\n$commit",
                toolCalls = toolCalls,
                isDone = isDone,
                spawnedSubtasks = spawnedIds
            ))

            current = current.copy(iterations = iterations.toList())
            updateSubtask(task.id, current)

            if (isDone) {
                val artifactType = doneMatch!!.groupValues[1].trim()
                val doneContent = doneMatch.groupValues[2].trim()
                val summary = if (artifactType.isNotBlank()) {
                    "[artifact:$artifactType] $doneContent"
                } else {
                    doneContent.ifEmpty { "Done" }
                }
                return@withContext current.copy(
                    status = TaskStatus.COMPLETED,
                    result = summary,
                    iterations = iterations.toList()
                )
            }
        }

        // Max iterations reached without a DONE signal
        return@withContext current.copy(
            status = TaskStatus.FAILED,
            result = "Max iterations (${SubTask.MAX_ITERATIONS}) reached. " +
                "Last: ${iterations.lastOrNull()?.toolCalls?.firstOrNull()?.let {
                    "${it.toolName}: ${it.result.take(60)}"
                } ?: "no tool call"}",
            iterations = iterations.toList()
        )
    }

    // ── Prompt Building ──────────────────────────────────────
    //
    // NO [INSTRUCTIONS] wrappers. The system prompt (tasks_system.txt) already
    // defines the executor persona and output formats. These user messages only
    // provide per-turn context (task, subtask, history, tools).

    private fun buildThinkPrompt(
        task: Task,
        subtask: SubTask,
        previousResults: String,
        iterations: List<Iteration>,
        iterationIndex: Int,
        contextPressureNote: String? = null
    ): String {
        return buildString {
            // If context was just reset mid-subtask, prepend a compact progress note
            // so the model knows what has already been done.
            if (contextPressureNote != null) {
                appendLine("CONTEXT TRIMMED — resuming subtask.")
                appendLine("SUBTASK PROGRESS SO FAR:")
                appendLine(contextPressureNote)
                appendLine()
            }

            appendLine("TASK: ${task.title}")
            appendLine("SUBTASK (${task.subtasks.indexOfFirst { it.id == subtask.id } + 1}/${task.subtasks.size}): ${subtask.title}")
            if (subtask.description.isNotBlank() && subtask.description != subtask.title) {
                appendLine("Detail: ${subtask.description}")
            }

            if (previousResults.isNotBlank()) {
                appendLine()
                appendLine("COMPLETED STEPS:")
                appendLine(previousResults)
            }

            val history = buildIterationHistory(iterations)
            if (history.isNotBlank()) {
                appendLine()
                appendLine("RECENT HISTORY:")
                appendLine(history)
            }

            appendLine()
            appendLine("AVAILABLE TOOLS:")
            if (iterationIndex == 0) {
                // First iteration: include full schema (name, params, description).
                // This lands in the KV cache and stays in context for subsequent iterations.
                appendLine(buildToolsDescription())
            } else {
                // Subsequent iterations: names only — full schema is already in the KV cache
                // from iteration 0. Listing it again would waste ~4 000 chars per iteration.
                val toolNames = moduleRegistry
                    .getToolDefinitionsForMode(ModuleRegistry.BrainMode.TASKS)
                    .joinToString(", ") { it.name }
                appendLine(toolNames)
                appendLine("(Full schemas provided in iteration 1. Use the same call format.)")
            }

            appendLine()
            appendLine("Think about what to do next. One short paragraph of reasoning —")
            appendLine("reference any recent tool results. Do NOT emit a tool call.")
            appendLine("End your reasoning with EXACTLY ONE of these lines:")
            appendLine("  ACTION: <one sentence describing the next tool call or artifact>")
            appendLine("  DONE: <one-sentence summary of what was accomplished>")
            append("  GIVE_UP: <one sentence explaining why this cannot be completed>")
        }
    }

    private fun buildCommitPrompt(
        task: Task,
        subtask: SubTask,
        thinkOutput: String,
        actionText: String
    ): String = buildString {
        appendLine("TASK: ${task.title}")
        appendLine("SUBTASK: ${subtask.title}")
        appendLine()
        appendLine("YOUR REASONING:")
        appendLine(thinkOutput.trim())
        appendLine()
        appendLine("You decided: $actionText")
        appendLine()
        appendLine("Emit EXACTLY ONE of the following. Nothing else. No prose. No thinking.")
        appendLine("  <tool>module.func(param=\"value\")</tool>")
        appendLine("  <done type=\"TYPE\">{...json artifact...}</done>")
        append("  <subtask>description</subtask>")
    }

    private fun buildIterationHistory(iterations: List<Iteration>): String {
        if (iterations.isEmpty()) return ""
        return buildString {
            // Cap at last 2 tool-call pairs — older entries stay in the KV cache.
            iterations.takeLast(2).forEach { iter ->
                iter.toolCalls.forEach { tc ->
                    val truncated = truncateResult(tc.result)
                    appendLine("Called ${tc.toolName}(${tc.params.entries.take(2).joinToString { "${it.key}=\"${it.value}\"" }})")
                    appendLine("Result: $truncated")
                }
                if (iter.toolCalls.isEmpty() && !iter.isDone) appendLine("No tools called.")
            }
        }.trimEnd()
    }

    /** Cap a tool result to 200 chars (first 100 + last 100) to keep prompts lean. */
    private fun truncateResult(s: String): String {
        if (s.length <= 200) return s
        return s.take(100) + "…" + s.takeLast(100)
    }

    /** Compact summary of recent iterations for context-pressure re-priming. */
    private fun buildCompactContextSummary(iterations: List<Iteration>): String {
        if (iterations.isEmpty()) return "(no prior steps)"
        return buildString {
            iterations.forEach { iter ->
                iter.toolCalls.forEach { tc ->
                    appendLine("• ${tc.toolName}: ${truncateResult(tc.result)}")
                }
                if (iter.isDone) appendLine("• (completed)")
                if (iter.toolCalls.isEmpty() && !iter.isDone) appendLine("• (no tool call)")
            }
        }.trimEnd()
    }

    private fun buildToolsDescription(): String {
        val defs = moduleRegistry.getToolDefinitionsForMode(ModuleRegistry.BrainMode.TASKS)
        return if (defs.isEmpty()) "(no tools available)" else
            defs.joinToString("\n") { tool ->
                val params = tool.parameters.entries.joinToString(", ") { (k, v) ->
                    "$k: ${v.type}${if (v.required) "" else "?"}"
                }
                "${tool.name}($params) — ${tool.description}"
            }
    }

    // ── Artifact extraction ──────────────────────────────────

    private fun extractArtifact(result: String?): TaskArtifact? {
        if (result == null) return null
        val match = Regex("""\[artifact:([^\]]+)\]\s*(.*)""", RegexOption.DOT_MATCHES_ALL)
            .find(result) ?: return null
        val type = match.groupValues[1].trim()
        val json = match.groupValues[2].trim()
        return runCatching { TaskArtifact.parse(type, json) }
            .onFailure { Log.w(TAG, "Artifact parse failed for type=$type", it) }
            .getOrNull()
    }

    // ── Sub-subtask spawning ─────────────────────────────────

    private fun spawnSubtask(task: Task, parentSubtask: SubTask, description: String): SubTask {
        val newSubtask = SubTask(
            title = description.take(80),
            description = description,
            parentSubtaskId = parentSubtask.id,
            order = parentSubtask.order + 1
        )
        val current = _currentTask.value ?: return newSubtask
        val subtasks = current.subtasks.toMutableList()
        val parentIndex = subtasks.indexOfFirst { it.id == parentSubtask.id }
        if (parentIndex >= 0) subtasks.add(parentIndex + 1, newSubtask)
        else subtasks.add(newSubtask)
        updateTask(current.copy(subtasks = subtasks))
        return newSubtask
    }

    // ── Schedule extraction ──────────────────────────────────

    private data class ScheduleExtraction(
        val cleanedText: String,
        val scheduledFor: Long?
    )

    /**
     * Scan [text] (task title + description) for scheduling phrases such as
     * "at 8:30 am", "tomorrow at 9", "in 2 hours", "when I wake up", etc.
     *
     * If a phrase is found, strips it from the text and returns the computed
     * epoch-millisecond fire time alongside the cleaned string. The cleaned
     * string is handed to the planner so it doesn't generate a spurious alarm step.
     *
     * Default for "when I wake up" (no explicit time) → tomorrow 07:30.
     */
    private fun extractSchedule(text: String): ScheduleExtraction {
        val now = System.currentTimeMillis()

        fun adjustHour(h: Int, ampm: String): Int = when {
            ampm == "pm" && h < 12 -> h + 12
            ampm == "am" && h == 12 -> 0
            else -> h
        }
        fun String.stripped(m: MatchResult): String =
            replace(m.value, "").replace(Regex("\\s+"), " ").trim()
                .trimStart(',').trimStart(';').trim()

        // 1. "when I wake up at HH:MM (am|pm)?" → next day at that time
        Regex("""when I wake up at (\d{1,2}):(\d{2})\s*(am|pm)?""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val h = adjustHour(m.groupValues[1].toInt(), m.groupValues[3].lowercase())
                val min = m.groupValues[2].toInt()
                val cal = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }
                return ScheduleExtraction(text.stripped(m), cal.timeInMillis)
            }

        // 2. "when I wake up" (no explicit time) → tomorrow 07:30
        Regex("""when I wake up""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val cal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 7); set(java.util.Calendar.MINUTE, 30)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            return ScheduleExtraction(text.stripped(m), cal.timeInMillis)
        }

        // 3. "tomorrow at HH:MM (am|pm)?"
        Regex("""tomorrow\s+at\s+(\d{1,2}):(\d{2})\s*(am|pm)?""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val h = adjustHour(m.groupValues[1].toInt(), m.groupValues[3].lowercase())
                val min = m.groupValues[2].toInt()
                val cal = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }
                return ScheduleExtraction(text.stripped(m), cal.timeInMillis)
            }

        // 4. "tonight at HH:MM (am|pm)?" — today; if past, skip to "at" pattern below
        Regex("""tonight\s+at\s+(\d{1,2}):(\d{2})\s*(am|pm)?""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val h = adjustHour(m.groupValues[1].toInt(), m.groupValues[3].lowercase())
                val min = m.groupValues[2].toInt()
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                return ScheduleExtraction(text.stripped(m), cal.timeInMillis)
            }

        // 5. "next (weekday) at HH:MM (am|pm)?"
        Regex("""next\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+at\s+(\d{1,2}):(\d{2})\s*(am|pm)?""",
            RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val dayTarget = when (m.groupValues[1].lowercase()) {
                "sunday"    -> java.util.Calendar.SUNDAY
                "monday"    -> java.util.Calendar.MONDAY
                "tuesday"   -> java.util.Calendar.TUESDAY
                "wednesday" -> java.util.Calendar.WEDNESDAY
                "thursday"  -> java.util.Calendar.THURSDAY
                "friday"    -> java.util.Calendar.FRIDAY
                "saturday"  -> java.util.Calendar.SATURDAY
                else        -> java.util.Calendar.MONDAY
            }
            val h = adjustHour(m.groupValues[2].toInt(), m.groupValues[4].lowercase())
            val min = m.groupValues[3].toInt()
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                add(java.util.Calendar.DAY_OF_YEAR, 1)  // start searching from tomorrow
                while (get(java.util.Calendar.DAY_OF_WEEK) != dayTarget) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }
            return ScheduleExtraction(text.stripped(m), cal.timeInMillis)
        }

        // 6. "in N (minutes|hours)"
        Regex("""in\s+(\d+)\s+(minutes?|hours?)""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val n = m.groupValues[1].toLong()
            val ms = if (m.groupValues[2].lowercase().startsWith("hour")) n * 3_600_000L
                     else n * 60_000L
            return ScheduleExtraction(text.stripped(m), now + ms)
        }

        // 7. "at HH:MM (am|pm)?" — today if in future, else tomorrow
        Regex("""\bat\s+(\d{1,2}):(\d{2})\s*(am|pm)?\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val h = adjustHour(m.groupValues[1].toInt(), m.groupValues[3].lowercase())
                val min = m.groupValues[2].toInt()
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                return ScheduleExtraction(text.stripped(m), cal.timeInMillis)
            }

        return ScheduleExtraction(text, null)
    }

    // ── Subtask parsing ──────────────────────────────────────

    private fun parseSubtasks(text: String): List<SubTask> =
        text.lines()
            .map { it.trim() }
            .filter { it.matches(Regex("""^\d+[.)\-]\s+.+""")) }
            .mapIndexed { index, line ->
                val title = line.replace(Regex("""^\d+[.)\-]\s+"""), "").trim()
                SubTask(title = title, description = title, order = index)
            }

    // ── Task CRUD ─────────────────────────────────────────────

    fun updateTask(task: Task) {
        _tasks.value = _tasks.value.map { if (it.id == task.id) task else it }
        if (_currentTask.value?.id == task.id) _currentTask.value = task
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
        if (_currentTask.value?.id == taskId) _currentTask.value = null
    }

    fun selectTask(taskId: String) {
        _currentTask.value = _tasks.value.find { it.id == taskId }
    }

    fun deselectTask() {
        _currentTask.value = null
    }

    fun addTask(task: Task) {
        _tasks.value = _tasks.value + task
    }

    fun getTask(taskId: String): Task? = _tasks.value.find { it.id == taskId }

    fun removeTask(taskId: String) = deleteTask(taskId)

    val activeTasks: List<Task>
        get() = _tasks.value.filter {
            it.status != TaskStatus.COMPLETED && it.status != TaskStatus.FAILED
        }

    companion object {
        private const val TAG = "TaskManager"
    }
}
