package com.hermie.assistant.modules.tasks

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Disk persistence for tasks.
 *
 * Each task is stored as a JSON file under `filesDir/tasks/{id}.json`.
 * Writes are atomic (temp-file + rename). Non-scheduled completed tasks
 * beyond [MAX_ACTIVE_TASKS] are moved to `tasks/archive/{id}.json`.
 *
 * Mirrors the ConversationStore pattern.
 */
class TaskStore(private val context: Context) {

    companion object {
        private const val TAG = "TaskStore"
        const val MAX_ACTIVE_TASKS = 30
        private const val DIR_NAME = "tasks"
        private const val ARCHIVE_DIR_NAME = "archive"
    }

    private val tasksDir: File
        get() = File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    private val archiveDir: File
        get() = File(tasksDir, ARCHIVE_DIR_NAME).also { it.mkdirs() }

    /** Load all active tasks from disk, sorted by createdAt descending. */
    suspend fun loadAll(): List<Task> = withContext(Dispatchers.IO) {
        tasksDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { file ->
                try { fromJson(JSONObject(file.readText())) }
                catch (e: Exception) {
                    Log.w(TAG, "Failed to read task file ${file.name}", e)
                    null
                }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /** Persist a task atomically via temp-file + rename. */
    suspend fun save(task: Task) = withContext(Dispatchers.IO) {
        val dir = tasksDir
        val target = File(dir, "${task.id}.json")
        val tmp = File(dir, "${task.id}.tmp")
        try {
            tmp.writeText(toJson(task).toString())
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save task ${task.id}", e)
            tmp.delete()
        }
    }

    /** Delete a task file from disk. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(tasksDir, "$id.json").delete()
    }

    /** Move a task to the archive subdirectory. */
    suspend fun archive(id: String) = withContext(Dispatchers.IO) {
        val src = File(tasksDir, "$id.json")
        if (src.exists()) {
            val dst = File(archiveDir, "$id.json")
            if (!src.renameTo(dst)) {
                dst.writeText(src.readText())
                src.delete()
            }
            Log.d(TAG, "Archived task $id")
        }
    }

    // ── JSON serialization ─────────────────────────────────────

    private fun toJson(task: Task): JSONObject {
        val subtasksArray = JSONArray()
        task.subtasks.forEach { sub ->
            val subObj = JSONObject()
            subObj.put("id", sub.id)
            subObj.put("title", sub.title)
            subObj.put("description", sub.description)
            subObj.put("status", sub.status.name)
            sub.result?.let { subObj.put("result", it) }
            subObj.put("order", sub.order)
            subObj.put("maxIterations", sub.maxIterations)
            sub.parentSubtaskId?.let { subObj.put("parentSubtaskId", it) }

            val itersArray = JSONArray()
            sub.iterations.forEach { iter ->
                val iterObj = JSONObject()
                iterObj.put("index", iter.index)
                iterObj.put("llmResponse", iter.llmResponse)
                iterObj.put("timestamp", iter.timestamp)
                iterObj.put("isDone", iter.isDone)
                val toolsArray = JSONArray()
                iter.toolCalls.forEach { tc ->
                    val tcObj = JSONObject()
                    tcObj.put("toolName", tc.toolName)
                    val paramsObj = JSONObject()
                    tc.params.forEach { (k, v) -> paramsObj.put(k, v) }
                    tcObj.put("params", paramsObj)
                    tcObj.put("result", tc.result)
                    tcObj.put("isSuccess", tc.isSuccess)
                    tcObj.put("timestamp", tc.timestamp)
                    toolsArray.put(tcObj)
                }
                iterObj.put("toolCalls", toolsArray)
                val spawnedArray = JSONArray()
                iter.spawnedSubtasks.forEach { spawnedArray.put(it) }
                iterObj.put("spawnedSubtasks", spawnedArray)
                itersArray.put(iterObj)
            }
            subObj.put("iterations", itersArray)
            subtasksArray.put(subObj)
        }

        return JSONObject().apply {
            put("id", task.id)
            put("title", task.title)
            put("description", task.description)
            put("status", task.status.name)
            put("createdAt", task.createdAt)
            task.completedAt?.let { put("completedAt", it) }
            task.scheduledFor?.let { put("scheduledFor", it) }
            put("requirePlanReview", task.requirePlanReview)
            put("artifactDismissed", task.artifactDismissed)
            task.pausedAtSubtaskIndex?.let { put("pausedAtSubtaskIndex", it) }
            task.pausedAtIteration?.let { put("pausedAtIteration", it) }
            task.artifact?.let { put("artifact", TaskArtifact.toJson(it)) }
            put("subtasks", subtasksArray)
        }
    }

    private fun fromJson(obj: JSONObject): Task {
        val subtasksArray = obj.optJSONArray("subtasks") ?: JSONArray()
        val subtasks = (0 until subtasksArray.length()).map { i ->
            val sub = subtasksArray.getJSONObject(i)
            val itersArray = sub.optJSONArray("iterations") ?: JSONArray()
            val iterations = (0 until itersArray.length()).map { j ->
                val iter = itersArray.getJSONObject(j)
                val toolsArray = iter.optJSONArray("toolCalls") ?: JSONArray()
                val toolCalls = (0 until toolsArray.length()).map { k ->
                    val tc = toolsArray.getJSONObject(k)
                    val paramsObj = tc.optJSONObject("params") ?: JSONObject()
                    val params = mutableMapOf<String, String>()
                    paramsObj.keys().forEach { key -> params[key] = paramsObj.optString(key) }
                    ToolCall(
                        toolName = tc.getString("toolName"),
                        params = params,
                        result = tc.optString("result"),
                        isSuccess = tc.optBoolean("isSuccess", true),
                        timestamp = tc.optLong("timestamp", System.currentTimeMillis())
                    )
                }
                val spawnedArray = iter.optJSONArray("spawnedSubtasks") ?: JSONArray()
                val spawned = (0 until spawnedArray.length()).map { spawnedArray.getString(it) }
                Iteration(
                    index = iter.getInt("index"),
                    llmResponse = iter.optString("llmResponse"),
                    toolCalls = toolCalls,
                    timestamp = iter.optLong("timestamp", System.currentTimeMillis()),
                    isDone = iter.optBoolean("isDone", false),
                    spawnedSubtasks = spawned
                )
            }
            SubTask(
                id = sub.getString("id"),
                title = sub.getString("title"),
                description = sub.optString("description"),
                status = runCatching { TaskStatus.valueOf(sub.getString("status")) }
                    .getOrDefault(TaskStatus.PENDING),
                result = sub.optString("result").ifEmpty { null },
                iterations = iterations,
                parentSubtaskId = sub.optString("parentSubtaskId").ifEmpty { null },
                order = sub.optInt("order", 0),
                maxIterations = sub.optInt("maxIterations", SubTask.MAX_ITERATIONS)
            )
        }

        val artifact = obj.optJSONObject("artifact")?.let { TaskArtifact.fromJson(it) }

        return Task(
            id = obj.getString("id"),
            title = obj.getString("title"),
            description = obj.optString("description"),
            subtasks = subtasks,
            status = runCatching { TaskStatus.valueOf(obj.getString("status")) }
                .getOrDefault(TaskStatus.PENDING),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            completedAt = if (obj.has("completedAt")) obj.getLong("completedAt") else null,
            scheduledFor = if (obj.has("scheduledFor")) obj.getLong("scheduledFor") else null,
            requirePlanReview = obj.optBoolean("requirePlanReview", false),
            artifact = artifact,
            artifactDismissed = obj.optBoolean("artifactDismissed", false),
            pausedAtSubtaskIndex = if (obj.has("pausedAtSubtaskIndex")) obj.getInt("pausedAtSubtaskIndex") else null,
            pausedAtIteration = if (obj.has("pausedAtIteration")) obj.getInt("pausedAtIteration") else null
        )
    }
}
