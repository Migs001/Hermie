package com.hermie.assistant.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Disk persistence for chat conversations.
 *
 * Each conversation is stored as a JSON file under
 * `filesDir/conversations/{id}.json`. Writes are atomic (temp-file + rename).
 * Old conversations beyond [MAX_ACTIVE_CONVERSATIONS] are moved to
 * `conversations/archive/{id}.json` rather than deleted.
 */
class ConversationStore(private val context: Context) {

    companion object {
        private const val TAG = "ConversationStore"

        /** Maximum active (non-archived) conversations to keep in the drawer. */
        const val MAX_ACTIVE_CONVERSATIONS = 20

        private const val DIR_NAME = "conversations"
        private const val ARCHIVE_DIR_NAME = "archive"
    }

    private val conversationsDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private val archiveDir = File(conversationsDir, ARCHIVE_DIR_NAME).apply { mkdirs() }

    /**
     * Load all active conversations from disk, sorted by [Conversation.updatedAt] descending.
     * Returns an empty list on first run (no conversations directory yet).
     */
    suspend fun loadAll(): List<Conversation> = withContext(Dispatchers.IO) {
        val dir = conversationsDir
        dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    fromJson(JSONObject(file.readText()))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read conversation file ${file.name}", e)
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    /**
     * Persist a conversation to disk via an atomic temp-file + rename.
     * Safe to call from any coroutine context — always dispatches to IO.
     */
    suspend fun save(conv: Conversation) = withContext(Dispatchers.IO) {
        val dir = conversationsDir
        val target = File(dir, "${conv.id}.json")
        val tmp = File(dir, "${conv.id}.tmp")
        try {
            tmp.writeText(toJson(conv).toString())
            if (!tmp.renameTo(target)) {
                // renameTo can fail cross-filesystem; fall back to copy + delete
                target.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save conversation ${conv.id}", e)
            tmp.delete()
        }
    }

    /**
     * Delete a conversation file from disk.
     */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(conversationsDir, "$id.json").delete()
    }

    /**
     * Move a conversation to the archive subdirectory.
     * Archived conversations are not shown in the drawer but remain on disk.
     */
    suspend fun archive(id: String) = withContext(Dispatchers.IO) {
        val src = File(conversationsDir, "$id.json")
        if (src.exists()) {
            val dst = File(archiveDir, "$id.json")
            if (!src.renameTo(dst)) {
                dst.writeText(src.readText())
                src.delete()
            }
            Log.d(TAG, "Archived conversation $id")
        }
    }

    // ── JSON serialization ──────────────────────────────────────

    private fun toJson(conv: Conversation): JSONObject {
        val messagesArray = JSONArray()
        conv.messages.forEach { msg ->
            val obj = JSONObject()
            obj.put("id", msg.id)
            obj.put("role", msg.role)
            obj.put("content", msg.content)
            obj.put("timestamp", msg.timestamp)
            msg.emotion?.let { obj.put("emotion", it) }
            msg.mindDebug?.let { obj.put("mindDebug", it) }
            msg.thinkingContent?.let { obj.put("thinkingContent", it) }
            msg.imageUri?.let { obj.put("imageUri", it) }
            messagesArray.put(obj)
        }
        return JSONObject().apply {
            put("id", conv.id)
            put("title", conv.title)
            put("messages", messagesArray)
            put("createdAt", conv.createdAt)
            put("updatedAt", conv.updatedAt)
        }
    }

    private fun fromJson(obj: JSONObject): Conversation {
        val messagesArray = obj.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until messagesArray.length()).map { i ->
            val m = messagesArray.getJSONObject(i)
            ChatMessage(
                id = m.optString("id").ifEmpty { UUID.randomUUID().toString() },
                role = m.getString("role"),
                content = m.getString("content"),
                timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                emotion = m.optString("emotion").ifEmpty { null },
                mindDebug = m.optString("mindDebug").ifEmpty { null },
                thinkingContent = m.optString("thinkingContent").ifEmpty { null },
                imageUri = m.optString("imageUri").ifEmpty { null }
            )
        }
        return Conversation(
            id = obj.getString("id"),
            title = obj.optString("title", "New Chat"),
            messages = messages,
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}
