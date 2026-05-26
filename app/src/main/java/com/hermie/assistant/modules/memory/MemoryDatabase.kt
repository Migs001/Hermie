package com.hermie.assistant.modules.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.abs

/**
 * SQLite graph database for persistent memory.
 *
 * Schema (v5):
 * - nodes: atomic facts with source tagging (personal, study, study_anchor); no importance column
 * - edges: relationships between nodes, with learnable embedding vectors
 * - tags: keyword index on nodes
 * - short_term_buffer: SLM-extracted facts before consolidation; source + study_anchor_id columns
 * - raw_user_messages: verbatim user messages for drip atomization
 *
 * v5 changes:
 * - Removed `importance` column from nodes (deactivation now time+access based)
 * - Added `source` and `study_anchor_id` columns to short_term_buffer
 * - New deactivation: access_count=0 AND last_accessed older than DEACTIVATE_STALE_DAYS
 */
class MemoryDatabase(context: Context) : SQLiteOpenHelper(
    context, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val TAG = "MemoryDB"
        private const val DB_NAME = "hermie_memory.db"
        private const val DB_VERSION = 6

        // Categories
        val CATEGORIES = mapOf(
            1 to "user.pref",
            2 to "user.health",
            3 to "user.people",
            4 to "user.schedule",
            5 to "user.work",
            6 to "knowledge.cooking",
            7 to "knowledge.science",
            8 to "knowledge.history",
            9 to "knowledge.general",
            10 to "context.location",
            11 to "context.device"
        )
        val CAT_BY_NAME = CATEGORIES.entries.associate { it.value to it.key }

        val PRUNE_EXEMPT = setOf("user.health", "user.people")

        // REMOVED: RELATION_TYPES map — no longer used for retrieval/display
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {

        // Kept for migration compatibility — not used by new code
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS relation_types (
                id       INTEGER PRIMARY KEY,
                name     TEXT NOT NULL,
                directed INTEGER NOT NULL DEFAULT 1
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS nodes (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                fact          TEXT NOT NULL,
                is_physical   INTEGER NOT NULL DEFAULT 0,
                is_concept    INTEGER NOT NULL DEFAULT 0,
                category      TEXT NOT NULL,
                source        TEXT NOT NULL DEFAULT '${MemoryConfig.SOURCE_PERSONAL}',
                created_at    INTEGER NOT NULL,
                last_accessed INTEGER NOT NULL,
                access_count  INTEGER NOT NULL DEFAULT 0,
                active        INTEGER NOT NULL DEFAULT 1,
                embedding     BLOB
            )
        """)

        // CHANGED: added embedding BLOB, relation_type kept with DEFAULT for compat
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS edges (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id     INTEGER NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
                target_id     INTEGER NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
                relation_type INTEGER NOT NULL DEFAULT 1,
                strength      INTEGER NOT NULL DEFAULT 50,
                created_at    INTEGER NOT NULL,
                embedding     BLOB,
                UNIQUE(source_id, target_id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tags (
                node_id INTEGER NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
                tag     TEXT NOT NULL,
                PRIMARY KEY (node_id, tag)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS short_term_buffer (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                raw_input        TEXT NOT NULL,
                extracted        TEXT NOT NULL,
                tags             TEXT NOT NULL,
                session_id       TEXT NOT NULL,
                created_at       INTEGER NOT NULL,
                processed        INTEGER NOT NULL DEFAULT 0,
                embedding        BLOB,
                source           TEXT NOT NULL DEFAULT '${MemoryConfig.SOURCE_PERSONAL}',
                study_anchor_id  INTEGER
            )
        """)

        // NEW v4: Raw user messages for drip atomization
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS raw_user_messages (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                message    TEXT NOT NULL,
                session_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                atomized   INTEGER NOT NULL DEFAULT 0
            )
        """)

        // NEW v6: Exploratory linking audit log — tracks all pair evaluations so we never
        // re-evaluate the same two nodes. id_a is always < id_b (normalized on write).
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS exploratory_attempts (
                id_a       INTEGER NOT NULL,
                id_b       INTEGER NOT NULL,
                accepted   INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (id_a, id_b)
            )
        """)

        // REMOVED: co_access table — replaced by edge embeddings
        // REMOVED: feedback table — replaced by implicit response-similarity signal

        // Indexes
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_category ON nodes(category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_active ON nodes(active)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_source ON nodes(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_source ON edges(source_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_target ON edges(target_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tags_tag ON tags(tag)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stb_processed ON short_term_buffer(processed)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stb_source ON short_term_buffer(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stb_anchor ON short_term_buffer(study_anchor_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_msgs_atomized ON raw_user_messages(atomized)")

        // Seed relation types (kept for migration compat)
        val relTypes = mapOf(1 to "relates_to", 2 to "property_of", 3 to "belongs_to", 4 to "comes_from", 5 to "transferable_concept")
        for ((id, name) in relTypes) {
            val directed = if (name in setOf("relates_to", "transferable_concept")) 0 else 1
            db.execSQL(
                "INSERT OR IGNORE INTO relation_types (id, name, directed) VALUES (?, ?, ?)",
                arrayOf(id, name, directed)
            )
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE nodes ADD COLUMN embedding BLOB")
            db.execSQL("ALTER TABLE short_term_buffer ADD COLUMN embedding BLOB")
            Log.d(TAG, "Migrated DB to version 2: added embedding columns")
        }
        // NEW: v2 → v3 migration
        if (oldVersion < 3) {
            // Add edge embeddings for REMINDRAG-style memory
            db.execSQL("ALTER TABLE edges ADD COLUMN embedding BLOB")
            // Drop unused tables (nothing references these via FK)
            db.execSQL("DROP TABLE IF EXISTS feedback")
            db.execSQL("DROP TABLE IF EXISTS co_access")
            // relation_types table kept — edges still has the FK column
            Log.d(TAG, "Migrated DB to version 3: edge embeddings, removed feedback/co_access")
        }
        // NEW: v3 → v4 migration
        if (oldVersion < 4) {
            // Add source tagging to nodes
            db.execSQL("ALTER TABLE nodes ADD COLUMN source TEXT NOT NULL DEFAULT '${MemoryConfig.SOURCE_PERSONAL}'")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_source ON nodes(source)")
            // Add raw user messages table for drip atomization
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS raw_user_messages (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    message    TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    atomized   INTEGER NOT NULL DEFAULT 0
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_msgs_atomized ON raw_user_messages(atomized)")
            Log.d(TAG, "Migrated DB to version 4: source column, raw_user_messages table")
        }
        // v4 → v5 migration
        if (oldVersion < 5) {
            // Recreate nodes table without importance column
            db.execSQL("""
                CREATE TABLE nodes_v5 (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    fact          TEXT NOT NULL,
                    is_physical   INTEGER NOT NULL DEFAULT 0,
                    is_concept    INTEGER NOT NULL DEFAULT 0,
                    category      TEXT NOT NULL,
                    source        TEXT NOT NULL DEFAULT '${MemoryConfig.SOURCE_PERSONAL}',
                    created_at    INTEGER NOT NULL,
                    last_accessed INTEGER NOT NULL,
                    access_count  INTEGER NOT NULL DEFAULT 0,
                    active        INTEGER NOT NULL DEFAULT 1,
                    embedding     BLOB
                )
            """)
            db.execSQL("""
                INSERT INTO nodes_v5 (id, fact, is_physical, is_concept, category, source,
                    created_at, last_accessed, access_count, active, embedding)
                SELECT id, fact, is_physical, is_concept, category,
                    COALESCE(source, '${MemoryConfig.SOURCE_PERSONAL}'),
                    created_at, last_accessed, access_count, active, embedding
                FROM nodes
            """)
            db.execSQL("DROP TABLE nodes")
            db.execSQL("ALTER TABLE nodes_v5 RENAME TO nodes")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_category ON nodes(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_active ON nodes(active)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_source ON nodes(source)")
            // Add source and study_anchor_id to short_term_buffer
            db.execSQL("ALTER TABLE short_term_buffer ADD COLUMN source TEXT NOT NULL DEFAULT '${MemoryConfig.SOURCE_PERSONAL}'")
            db.execSQL("ALTER TABLE short_term_buffer ADD COLUMN study_anchor_id INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_stb_source ON short_term_buffer(source)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_stb_anchor ON short_term_buffer(study_anchor_id)")
            Log.d(TAG, "Migrated DB to version 5: removed importance, added buffer source/anchor columns")
        }
        // v5 → v6 migration
        if (oldVersion < 6) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS exploratory_attempts (
                    id_a       INTEGER NOT NULL,
                    id_b       INTEGER NOT NULL,
                    accepted   INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (id_a, id_b)
                )
            """)
            Log.d(TAG, "Migrated DB to version 6: added exploratory_attempts table")
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
    }

    // ── Buffer Operations ──────────────────────────────────────

    fun bufferWrite(
        rawInput: String,
        extracted: String,
        tags: List<String>,
        sessionId: String,
        source: String = MemoryConfig.SOURCE_PERSONAL,
        studyAnchorId: Int? = null
    ) {
        val now = System.currentTimeMillis() / 1000
        writableDatabase.execSQL(
            "INSERT INTO short_term_buffer (raw_input, extracted, tags, session_id, created_at, source, study_anchor_id) VALUES (?,?,?,?,?,?,?)",
            arrayOf(rawInput, extracted, tags.joinToString(","), sessionId, now, source, studyAnchorId)
        )
    }

    fun bufferUnprocessed(excludeSessionId: String? = null, limit: Int = 50): List<BufferEntry> {
        val rows = mutableListOf<BufferEntry>()
        val query = if (excludeSessionId != null) {
            "SELECT * FROM short_term_buffer WHERE processed=0 AND session_id!=? ORDER BY created_at ASC LIMIT ?"
        } else {
            "SELECT * FROM short_term_buffer WHERE processed=0 ORDER BY created_at ASC LIMIT ?"
        }
        val args = if (excludeSessionId != null) {
            arrayOf(excludeSessionId, limit.toString())
        } else {
            arrayOf(limit.toString())
        }
        readableDatabase.rawQuery(query, args).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(cursorToBufferEntry(cursor))
            }
        }
        return rows
    }

    fun bufferMarkDone(ids: List<Int>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.execSQL("UPDATE short_term_buffer SET processed=1 WHERE id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun bufferDeleteProcessed(): Int {
        val count = writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM short_term_buffer WHERE processed=1", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (count > 0) {
            writableDatabase.execSQL("DELETE FROM short_term_buffer WHERE processed=1")
        }
        return count
    }

    fun bufferUnprocessedCount(): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM short_term_buffer WHERE processed=0", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun bufferResetOrphaned(): Int {
        val result = writableDatabase.rawQuery(
            """SELECT b.id FROM short_term_buffer b
               WHERE b.processed = 1
               AND NOT EXISTS (
                   SELECT 1 FROM nodes n WHERE n.active = 1
                   AND (n.fact = b.extracted OR n.fact LIKE '%' || b.extracted || '%')
               )""", null
        )
        val ids = mutableListOf<Int>()
        result.use { c ->
            while (c.moveToNext()) ids.add(c.getInt(0))
        }
        if (ids.isNotEmpty()) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (id in ids) {
                    db.execSQL("UPDATE short_term_buffer SET processed=0 WHERE id=?", arrayOf(id))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return ids.size
    }

    fun bufferRecent(sessionId: String, limit: Int = 10): List<BufferEntry> {
        val rows = mutableListOf<BufferEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM short_term_buffer WHERE session_id=? ORDER BY created_at DESC LIMIT ?",
            arrayOf(sessionId, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(cursorToBufferEntry(cursor))
            }
        }
        return rows
    }

    fun bufferRecentAll(limit: Int = 20): List<BufferEntry> {
        val rows = mutableListOf<BufferEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM short_term_buffer ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(cursorToBufferEntry(cursor))
            }
        }
        return rows
    }

    fun bufferUnprocessedPersonal(limit: Int = 50): List<BufferEntry> {
        val rows = mutableListOf<BufferEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM short_term_buffer WHERE processed=0 AND source=? ORDER BY created_at ASC LIMIT ?",
            arrayOf(MemoryConfig.SOURCE_PERSONAL, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(cursorToBufferEntry(cursor))
        }
        return rows
    }

    fun bufferUnprocessedStudyAnchorIds(): List<Int> {
        val ids = mutableListOf<Int>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT study_anchor_id FROM short_term_buffer WHERE processed=0 AND source=? AND study_anchor_id IS NOT NULL",
            arrayOf(MemoryConfig.SOURCE_STUDY)
        ).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getInt(0))
        }
        return ids
    }

    fun bufferUnprocessedByAnchor(anchorId: Int, limit: Int = 10): List<BufferEntry> {
        val rows = mutableListOf<BufferEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM short_term_buffer WHERE processed=0 AND source=? AND study_anchor_id=? ORDER BY created_at ASC LIMIT ?",
            arrayOf(MemoryConfig.SOURCE_STUDY, anchorId.toString(), limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(cursorToBufferEntry(cursor))
        }
        return rows
    }

    /**
     * Unprocessed study rows that have no anchor (source=study AND anchor_id IS NULL).
     * These are orphans written before the anchor was created, or after a failed anchor insert.
     * Phase C of consolidation processes them as personal entries so they don't get stuck.
     */
    fun bufferUnprocessedStudyOrphans(limit: Int = 10): List<BufferEntry> {
        val rows = mutableListOf<BufferEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM short_term_buffer WHERE processed=0 AND source=? AND study_anchor_id IS NULL ORDER BY created_at ASC LIMIT ?",
            arrayOf(MemoryConfig.SOURCE_STUDY, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(cursorToBufferEntry(cursor))
        }
        return rows
    }

    /**
     * Count unprocessed buffer rows grouped by source + orphan status.
     * Returns a map with keys: "personal", "study_with_anchor", "study_orphan".
     * Used at the start of consolidation to surface data-integrity issues early.
     */
    fun bufferSourceStats(): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            """SELECT source, (study_anchor_id IS NULL) AS is_orphan, COUNT(*)
               FROM short_term_buffer WHERE processed=0
               GROUP BY source, is_orphan""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val source = cursor.getString(0)
                val isOrphan = cursor.getInt(1) != 0
                val cnt = cursor.getInt(2)
                val key = when {
                    source == MemoryConfig.SOURCE_STUDY && isOrphan -> "study_orphan"
                    source == MemoryConfig.SOURCE_STUDY -> "study_with_anchor"
                    else -> source
                }
                stats[key] = (stats[key] ?: 0) + cnt
            }
        }
        return stats
    }

    // ── Raw User Messages (for Drip Atomization) ────────────────

    fun rawMessageInsert(message: String, sessionId: String) {
        val now = System.currentTimeMillis() / 1000
        writableDatabase.execSQL(
            "INSERT INTO raw_user_messages (message, session_id, created_at) VALUES (?,?,?)",
            arrayOf(message, sessionId, now)
        )
    }

    fun rawMessagesUnatomized(limit: Int = 10): List<RawUserMessage> {
        val rows = mutableListOf<RawUserMessage>()
        readableDatabase.rawQuery(
            "SELECT * FROM raw_user_messages WHERE atomized=0 ORDER BY created_at ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(RawUserMessage(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    message = cursor.getString(cursor.getColumnIndexOrThrow("message")),
                    sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    atomized = false
                ))
            }
        }
        return rows
    }

    fun rawMessagesMarkAtomized(ids: List<Int>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.execSQL("UPDATE raw_user_messages SET atomized=1 WHERE id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun rawMessagesUnatomizedCount(): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM raw_user_messages WHERE atomized=0", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun rawMessagesDeleteAtomized(): Int {
        val count = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM raw_user_messages WHERE atomized=1", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (count > 0) {
            writableDatabase.execSQL("DELETE FROM raw_user_messages WHERE atomized=1")
        }
        return count
    }

    // ── Node Operations ────────────────────────────────────────

    fun nodeCreate(
        fact: String, category: String,
        isPhysical: Boolean = false, isConcept: Boolean = false,
        source: String = MemoryConfig.SOURCE_PERSONAL
    ): Long {
        val now = System.currentTimeMillis() / 1000
        val db = writableDatabase
        db.execSQL(
            "INSERT INTO nodes (fact,is_physical,is_concept,category,source,created_at,last_accessed) VALUES (?,?,?,?,?,?,?)",
            arrayOf(fact, if (isPhysical) 1 else 0, if (isConcept) 1 else 0, category, source, now, now)
        )
        // IMPORTANT: must use writableDatabase for last_insert_rowid(), NOT readableDatabase.
        // In WAL mode these may be separate connections, and last_insert_rowid() is per-connection.
        var id = -1L
        db.rawQuery("SELECT last_insert_rowid()", null).use { c ->
            if (c.moveToFirst()) id = c.getLong(0)
        }
        return id
    }

    fun nodeUpdate(nodeId: Int, fact: String? = null) {
        val now = System.currentTimeMillis() / 1000
        val parts = mutableListOf<String>("last_accessed=?")
        val vals = mutableListOf<Any>(now)
        if (fact != null) { parts.add("fact=?"); vals.add(fact) }
        vals.add(nodeId)
        writableDatabase.execSQL(
            "UPDATE nodes SET ${parts.joinToString(",")} WHERE id=?",
            vals.toTypedArray()
        )
    }

    fun nodeFindById(id: Int): MemoryNode? {
        readableDatabase.rawQuery(
            "SELECT * FROM nodes WHERE id=? AND active=1", arrayOf(id.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursorToNode(cursor)
        }
        return null
    }

    fun nodeTouch(nodeId: Int) {
        val now = System.currentTimeMillis() / 1000
        writableDatabase.execSQL(
            "UPDATE nodes SET last_accessed=?, access_count=access_count+1 WHERE id=?",
            arrayOf(now, nodeId)
        )
    }

    fun nodeFindByFact(fact: String): MemoryNode? {
        readableDatabase.rawQuery(
            "SELECT * FROM nodes WHERE fact=? AND active=1", arrayOf(fact)
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursorToNode(cursor)
        }
        return null
    }

    // ── Edge Operations ────────────────────────────────────────

    // CHANGED: removed relation_type param, preserves embedding on conflict
    fun edgeCreate(sourceId: Long, targetId: Long, strength: Int = 50) {
        val now = System.currentTimeMillis() / 1000
        val exists = readableDatabase.rawQuery(
            "SELECT id FROM edges WHERE source_id=? AND target_id=?",
            arrayOf(sourceId.toString(), targetId.toString())
        ).use { c -> c.moveToFirst() }

        if (exists) {
            // Update strength but preserve edge embedding
            writableDatabase.execSQL(
                "UPDATE edges SET strength=?, created_at=? WHERE source_id=? AND target_id=?",
                arrayOf(strength, now, sourceId, targetId)
            )
        } else {
            writableDatabase.execSQL(
                "INSERT INTO edges (source_id, target_id, relation_type, strength, created_at) VALUES (?,?,1,?,?)",
                arrayOf(sourceId, targetId, strength, now)
            )
        }
    }

    // NEW: Store embedding vector for an edge
    fun edgeSetEmbedding(edgeId: Int, embedding: FloatArray) {
        writableDatabase.execSQL(
            "UPDATE edges SET embedding=? WHERE id=?",
            arrayOf(floatArrayToBlob(embedding), edgeId)
        )
    }

    // NEW: Load embedding vector for an edge (null if not yet set)
    fun getEdgeEmbedding(edgeId: Int): FloatArray? {
        readableDatabase.rawQuery(
            "SELECT embedding FROM edges WHERE id=?", arrayOf(edgeId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                val blob = c.getBlob(0) ?: return null
                return blobToFloatArray(blob)
            }
        }
        return null
    }

    // NEW: Load embedding vector for a node
    fun getNodeEmbedding(nodeId: Int): FloatArray? {
        readableDatabase.rawQuery(
            "SELECT embedding FROM nodes WHERE id=?", arrayOf(nodeId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                val blob = c.getBlob(0) ?: return null
                return blobToFloatArray(blob)
            }
        }
        return null
    }

    /**
     * NEW: Enhance an edge embedding in the direction of a query.
     *
     * From REMINDRAG Equation 1 — called when traversing this edge
     * led to a node that was relevant to the Brain's response.
     *
     * v̂ = v + δ(‖v‖) · q/‖q‖
     * where δ(x) = (2/π) · cos(π/2 · x)
     *
     * Fast Wakeup: new edges (small ‖v‖) get large updates.
     * Damped Update: experienced edges (large ‖v‖) resist change.
     */
    fun edgeEnhance(edgeId: Int, queryEmbedding: FloatArray) {
        val dim = queryEmbedding.size
        val v = getEdgeEmbedding(edgeId) ?: FloatArray(dim)
        val qNorm = vectorNorm(queryEmbedding)
        if (qNorm == 0f) return

        val delta = weightDelta(vectorNorm(v))
        val updated = FloatArray(dim)
        for (i in 0 until dim) {
            updated[i] = v[i] + delta * (queryEmbedding[i] / qNorm)
        }
        edgeSetEmbedding(edgeId, updated)
    }

    /**
     * NEW: Penalize an edge embedding by reducing its query-aligned component.
     *
     * From REMINDRAG Equation 1 — called when traversing this edge
     * led to a node that was NOT relevant to the Brain's response.
     *
     * v̂ = v − δ(|v·q̂|) · (v·q̂) · q̂
     * where q̂ = q/‖q‖
     *
     * This subtracts the projection of v onto q, scaled by δ.
     * Effectively "forgets" that this edge was useful for queries like q.
     */
    fun edgePenalize(edgeId: Int, queryEmbedding: FloatArray) {
        val dim = queryEmbedding.size
        val v = getEdgeEmbedding(edgeId) ?: FloatArray(dim)
        val qNorm = vectorNorm(queryEmbedding)
        if (qNorm == 0f) return

        // Scalar projection of v onto unit query direction
        var scalarProj = 0f
        for (i in 0 until dim) {
            scalarProj += v[i] * (queryEmbedding[i] / qNorm)
        }

        val delta = weightDelta(abs(scalarProj))
        val updated = FloatArray(dim)
        for (i in 0 until dim) {
            val qUnit = queryEmbedding[i] / qNorm
            updated[i] = v[i] - delta * scalarProj * qUnit
        }
        edgeSetEmbedding(edgeId, updated)
    }

    // ── Tag Operations ─────────────────────────────────────────

    fun tagsSet(nodeId: Long, tags: List<String>) {
        val db = writableDatabase
        for (tag in tags) {
            db.execSQL(
                "INSERT OR IGNORE INTO tags (node_id, tag) VALUES (?,?)",
                arrayOf(nodeId, tag)
            )
        }
    }

    // ── Retrieval ──────────────────────────────────────────────

    // CHANGED: removed logCoAccess call
    fun retrieveByTags(tags: List<String>, limit: Int = 20): List<MemoryNode> {
        if (tags.isEmpty()) return emptyList()
        val ph = tags.joinToString(",") { "?" }
        val args = tags.toTypedArray() + arrayOf(limit.toString())
        val nodes = mutableListOf<MemoryNode>()
        readableDatabase.rawQuery("""
            SELECT DISTINCT n.* FROM nodes n
            JOIN tags t ON t.node_id = n.id
            WHERE t.tag IN ($ph) AND n.active=1
            ORDER BY n.last_accessed DESC
            LIMIT ?
        """, args).use { cursor ->
            while (cursor.moveToNext()) {
                nodes.add(cursorToNode(cursor))
            }
        }
        for (node in nodes) nodeTouch(node.id)
        return nodes
    }

    fun retrieveByCategory(prefix: String, limit: Int = 20): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        readableDatabase.rawQuery("""
            SELECT * FROM nodes WHERE category LIKE ? AND active=1
            ORDER BY last_accessed DESC LIMIT ?
        """, arrayOf("$prefix%", limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                nodes.add(cursorToNode(cursor))
            }
        }
        return nodes
    }

    // CHANGED: simplified — removed relation_types JOIN
    fun retrieveEdgesForNode(nodeId: Int, limit: Int = 10): List<MemoryEdge> {
        val edges = mutableListOf<MemoryEdge>()
        readableDatabase.rawQuery("""
            SELECT e.id AS edge_id, e.source_id, e.target_id, e.strength,
                   n.id, n.fact, n.is_physical, n.is_concept, n.category,
                   n.source, n.created_at, n.last_accessed, n.access_count, n.active
            FROM edges e
            JOIN nodes n ON (
                CASE WHEN e.source_id = ? THEN n.id = e.target_id
                     ELSE n.id = e.source_id END
            )
            WHERE (e.source_id = ? OR e.target_id = ?) AND n.active = 1
            ORDER BY e.strength DESC LIMIT ?
        """, arrayOf(nodeId.toString(), nodeId.toString(), nodeId.toString(), limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val sourceIdx = cursor.getColumnIndex("source")
                val node = MemoryNode(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    fact = cursor.getString(cursor.getColumnIndexOrThrow("fact")),
                    isPhysical = cursor.getInt(cursor.getColumnIndexOrThrow("is_physical")) == 1,
                    isConcept = cursor.getInt(cursor.getColumnIndexOrThrow("is_concept")) == 1,
                    category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                    source = if (sourceIdx >= 0) cursor.getString(sourceIdx) ?: MemoryConfig.SOURCE_PERSONAL else MemoryConfig.SOURCE_PERSONAL,
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    lastAccessed = cursor.getLong(cursor.getColumnIndexOrThrow("last_accessed")),
                    accessCount = cursor.getInt(cursor.getColumnIndexOrThrow("access_count")),
                    active = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1
                )
                edges.add(MemoryEdge(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("edge_id")),
                    sourceId = cursor.getInt(cursor.getColumnIndexOrThrow("source_id")),
                    targetId = cursor.getInt(cursor.getColumnIndexOrThrow("target_id")),
                    strength = cursor.getInt(cursor.getColumnIndexOrThrow("strength")),
                    connectedNode = node
                ))
            }
        }
        return edges
    }

    fun retrieveConnected(nodeId: Int, limit: Int = 10): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        readableDatabase.rawQuery("""
            SELECT n.* FROM nodes n
            JOIN edges e ON (e.target_id=n.id AND e.source_id=?)
                         OR (e.source_id=n.id AND e.target_id=?)
            WHERE n.active=1
            ORDER BY e.strength DESC LIMIT ?
        """, arrayOf(nodeId.toString(), nodeId.toString(), limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                nodes.add(cursorToNode(cursor))
            }
        }
        return nodes
    }

    fun retrieveAll(limit: Int = 100): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        readableDatabase.rawQuery(
            "SELECT * FROM nodes WHERE active=1 ORDER BY last_accessed DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                nodes.add(cursorToNode(cursor))
            }
        }
        return nodes
    }

    // CHANGED: removed logCoAccess call
    fun retrieveByKeywords(query: String, limit: Int = 15): List<MemoryNode> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        if (words.isEmpty()) return emptyList()

        val whereClauses = words.joinToString(" OR ") { "LOWER(n.fact) LIKE ?" }
        val args = words.map { "%$it%" }.toTypedArray() + arrayOf(limit.toString())

        val nodes = mutableListOf<MemoryNode>()
        readableDatabase.rawQuery("""
            SELECT DISTINCT n.* FROM nodes n
            WHERE ($whereClauses) AND n.active=1
            ORDER BY n.last_accessed DESC
            LIMIT ?
        """, args).use { cursor ->
            while (cursor.moveToNext()) {
                nodes.add(cursorToNode(cursor))
            }
        }
        for (node in nodes) nodeTouch(node.id)
        return nodes
    }

    /**
     * Retrieve nodes by cosine similarity against a query embedding.
     * Brute-force scan — fine for <1000 nodes on device.
     */
    fun retrieveByEmbedding(
        queryEmbedding: FloatArray,
        limit: Int = 5,
        minSimilarity: Float = 0.3f
    ): List<MemoryNode> {
        val candidates = mutableListOf<Pair<MemoryNode, Float>>()
        readableDatabase.rawQuery(
            "SELECT * FROM nodes WHERE embedding IS NOT NULL AND active=1",
            null
        ).use { cursor ->
            val embIdx = cursor.getColumnIndexOrThrow("embedding")
            while (cursor.moveToNext()) {
                val blob = cursor.getBlob(embIdx) ?: continue
                val nodeEmbedding = blobToFloatArray(blob)
                val sim = cosineSimilarity(queryEmbedding, nodeEmbedding)
                if (sim >= minSimilarity) {
                    candidates.add(cursorToNode(cursor) to sim)
                }
            }
        }
        return candidates
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun retrieveBufferByEmbedding(
        queryEmbedding: FloatArray,
        limit: Int = 5,
        minSimilarity: Float = 0.3f
    ): List<BufferEntry> {
        val candidates = mutableListOf<Pair<BufferEntry, Float>>()
        readableDatabase.rawQuery(
            "SELECT * FROM short_term_buffer WHERE embedding IS NOT NULL",
            null
        ).use { cursor ->
            val embIdx = cursor.getColumnIndexOrThrow("embedding")
            while (cursor.moveToNext()) {
                val blob = cursor.getBlob(embIdx) ?: continue
                val entryEmbedding = blobToFloatArray(blob)
                val sim = cosineSimilarity(queryEmbedding, entryEmbedding)
                if (sim >= minSimilarity) {
                    candidates.add(cursorToBufferEntry(cursor) to sim)
                }
            }
        }
        return candidates
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * NEW: Threshold-based DFS expansion from seed nodes using edge embeddings.
     *
     * Implements REMINDRAG Equation 2:
     *   w = α · sim(node_from, node_to) + (1−α) · dot(query, edge_emb) / ‖query‖
     *
     * When edge embeddings are null (no experience), falls back to pure node
     * similarity — graceful degradation for fresh edges.
     *
     * Returns expanded nodes + mapping of nodeId → edgeId for enhance/penalize.
     */
    fun retrieveByDFS(
        queryEmbedding: FloatArray,
        seedNodeIds: List<Int>,
        alpha: Float = 0.2f,
        threshold: Float = 0.35f,
        maxNodes: Int = 9,
        maxDepth: Int = 4
    ): DFSResult {
        val visited = seedNodeIds.toMutableSet()
        val resultNodes = mutableListOf<MemoryNode>()
        val nodeToEdgeId = mutableMapOf<Int, Int>()

        for (seedId in seedNodeIds) {
            if (resultNodes.size >= maxNodes) break
            val seedEmb = getNodeEmbedding(seedId)
            dfsExpand(
                queryEmbedding, seedId, seedEmb,
                alpha, threshold, maxNodes, maxDepth, 0,
                visited, resultNodes, nodeToEdgeId
            )
        }

        return DFSResult(resultNodes, nodeToEdgeId)
    }

    // ── Pruning ────────────────────────────────────────────────

    fun decayEdges(amount: Int = 2, floor: Int = 5) {
        val exemptPh = PRUNE_EXEMPT.joinToString(",") { "'$it'" }
        writableDatabase.execSQL("""
            UPDATE edges SET strength = MAX(strength - $amount, $floor)
            WHERE source_id NOT IN (
                SELECT id FROM nodes WHERE category IN ($exemptPh)
            )
        """)
    }

    fun deactivateStale(days: Int = MemoryConfig.DEACTIVATE_STALE_DAYS) {
        val cutoff = System.currentTimeMillis() / 1000 - (days * 86400L)
        val exemptPh = PRUNE_EXEMPT.joinToString(",") { "'$it'" }
        writableDatabase.execSQL("""
            UPDATE nodes SET active=0
            WHERE access_count = 0 AND last_accessed < $cutoff AND active=1
            AND category NOT IN ($exemptPh)
        """)
    }

    // REMOVED: boostFromFeedback — replaced by edge enhance/penalize
    // REMOVED: strengthenCoAccessEdges — replaced by edge embeddings

    // ── Exploratory Linking ────────────────────────────────────

    /**
     * Returns true if this node pair has already been evaluated (in either order).
     * IDs are normalized so that id_a < id_b on lookup.
     */
    fun exploratoryAttemptExists(idA: Long, idB: Long): Boolean {
        val minId = minOf(idA, idB)
        val maxId = maxOf(idA, idB)
        return readableDatabase.rawQuery(
            "SELECT 1 FROM exploratory_attempts WHERE id_a=? AND id_b=?",
            arrayOf(minId.toString(), maxId.toString())
        ).use { c -> c.moveToFirst() }
    }

    /**
     * Record that a pair has been evaluated.
     * IDs are normalized so that id_a < id_b. Uses INSERT OR IGNORE to be idempotent.
     */
    fun exploratoryAttemptLog(idA: Long, idB: Long, accepted: Boolean) {
        val minId = minOf(idA, idB)
        val maxId = maxOf(idA, idB)
        val now = System.currentTimeMillis() / 1000
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO exploratory_attempts (id_a, id_b, accepted, created_at) VALUES (?,?,?,?)",
            arrayOf(minId, maxId, if (accepted) 1 else 0, now)
        )
    }

    /**
     * Find the edge ID between two nodes (checks both directions).
     * Returns null if no edge exists.
     */
    fun findEdgeId(sourceId: Long, targetId: Long): Int? {
        readableDatabase.rawQuery(
            "SELECT id FROM edges WHERE (source_id=? AND target_id=?) OR (source_id=? AND target_id=?)",
            arrayOf(sourceId.toString(), targetId.toString(), targetId.toString(), sourceId.toString())
        ).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return null
    }

    /**
     * Load all active nodes that have embeddings, paired with their embedding vectors.
     * Used by ExploratoryLinker to compute candidate pairs entirely in Kotlin.
     * Capped at 500 nodes to bound memory usage.
     */
    fun nodesWithEmbeddingsForLinking(limit: Int = 500): List<Pair<MemoryNode, FloatArray>> {
        val results = mutableListOf<Pair<MemoryNode, FloatArray>>()
        readableDatabase.rawQuery(
            "SELECT * FROM nodes WHERE embedding IS NOT NULL AND active=1 LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            val embIdx = cursor.getColumnIndexOrThrow("embedding")
            while (cursor.moveToNext()) {
                val blob = cursor.getBlob(embIdx) ?: continue
                results.add(cursorToNode(cursor) to blobToFloatArray(blob))
            }
        }
        return results
    }

    /**
     * Bulk-load all attempted pair keys as "minId-maxId" strings.
     * Used by ExploratoryLinker to avoid per-pair DB round-trips during candidate scan.
     */
    fun loadAllAttemptedPairKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT id_a, id_b FROM exploratory_attempts", null).use { c ->
            while (c.moveToNext()) {
                keys.add("${c.getLong(0)}-${c.getLong(1)}")
            }
        }
        return keys
    }

    /**
     * Bulk-load all existing edge pairs as normalized "minId-maxId" strings.
     * Used by ExploratoryLinker to skip pairs that are already connected.
     */
    fun loadAllEdgePairKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT source_id, target_id FROM edges", null).use { c ->
            while (c.moveToNext()) {
                val a = c.getLong(0)
                val b = c.getLong(1)
                keys.add("${minOf(a, b)}-${maxOf(a, b)}")
            }
        }
        return keys
    }

    // ── Embedding Operations ───────────────────────────────────

    fun nodeSetEmbedding(nodeId: Int, embedding: FloatArray) {
        writableDatabase.execSQL(
            "UPDATE nodes SET embedding=? WHERE id=?",
            arrayOf(floatArrayToBlob(embedding), nodeId)
        )
    }

    fun bufferSetEmbedding(entryId: Int, embedding: FloatArray) {
        writableDatabase.execSQL(
            "UPDATE short_term_buffer SET embedding=? WHERE id=?",
            arrayOf(floatArrayToBlob(embedding), entryId)
        )
    }

    fun nodesWithoutEmbedding(limit: Int = 50): List<Pair<Int, String>> {
        val results = mutableListOf<Pair<Int, String>>()
        readableDatabase.rawQuery(
            "SELECT id, fact FROM nodes WHERE embedding IS NULL AND active=1 LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(cursor.getInt(0) to cursor.getString(1))
            }
        }
        return results
    }

    fun bufferWithoutEmbedding(limit: Int = 50): List<Pair<Int, String>> {
        val results = mutableListOf<Pair<Int, String>>()
        readableDatabase.rawQuery(
            "SELECT id, extracted FROM short_term_buffer WHERE embedding IS NULL LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(cursor.getInt(0) to cursor.getString(1))
            }
        }
        return results
    }

    // ── Vector Math ────────────────────────────────────────────

    private fun floatArrayToBlob(arr: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in arr) buf.putFloat(v)
        return buf.array()
    }

    private fun blobToFloatArray(blob: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buf.getFloat() }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    // NEW: L2 norm of a vector
    private fun vectorNorm(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return sqrt(sum)
    }

    // NEW: dot product of two vectors (same dimension assumed)
    private fun vectorDot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /**
     * NEW: REMINDRAG weight function δ(x) = (2/π) · cos(π/2 · x)
     *
     * When x is small (near 0): δ ≈ 2/π ≈ 0.637 → large update (Fast Wakeup)
     * When x is large (near 1): δ ≈ 0 → small update (Damped Update)
     * When x > 1: δ < 0 → self-regulating (prevents unbounded growth)
     */
    private fun weightDelta(x: Float): Float {
        return (2f / Math.PI.toFloat()) * cos((Math.PI.toFloat() / 2f) * x)
    }

    // ── DFS Internals ──────────────────────────────────────────

    private data class DFSNeighbor(
        val edgeId: Int,
        val node: MemoryNode,
        val nodeEmbedding: FloatArray?,
        val edgeEmbedding: FloatArray?
    )

    /**
     * Get all neighbors of a node with their edge and node embeddings.
     * Returns edges in both directions (source→target and target→source).
     */
    private fun getEdgeNeighborsForDFS(nodeId: Int): List<DFSNeighbor> {
        val neighbors = mutableListOf<DFSNeighbor>()
        readableDatabase.rawQuery("""
            SELECT e.id AS edge_id, e.embedding AS edge_emb,
                   n.id AS node_id, n.fact, n.is_physical, n.is_concept, n.category, n.source,
                   n.created_at, n.last_accessed, n.access_count, n.active,
                   n.embedding AS node_emb
            FROM edges e
            JOIN nodes n ON (
                CASE WHEN e.source_id = ? THEN n.id = e.target_id
                     ELSE n.id = e.source_id END
            )
            WHERE (e.source_id = ? OR e.target_id = ?) AND n.active = 1
        """, arrayOf(nodeId.toString(), nodeId.toString(), nodeId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val edgeEmbBlob = cursor.getBlob(cursor.getColumnIndexOrThrow("edge_emb"))
                val nodeEmbBlob = cursor.getBlob(cursor.getColumnIndexOrThrow("node_emb"))
                neighbors.add(DFSNeighbor(
                    edgeId = cursor.getInt(cursor.getColumnIndexOrThrow("edge_id")),
                    node = MemoryNode(
                        id = cursor.getInt(cursor.getColumnIndexOrThrow("node_id")),
                        fact = cursor.getString(cursor.getColumnIndexOrThrow("fact")),
                        isPhysical = cursor.getInt(cursor.getColumnIndexOrThrow("is_physical")) == 1,
                        isConcept = cursor.getInt(cursor.getColumnIndexOrThrow("is_concept")) == 1,
                        category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                        source = cursor.getString(cursor.getColumnIndexOrThrow("source")) ?: MemoryConfig.SOURCE_PERSONAL,
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                        lastAccessed = cursor.getLong(cursor.getColumnIndexOrThrow("last_accessed")),
                        accessCount = cursor.getInt(cursor.getColumnIndexOrThrow("access_count")),
                        active = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1
                    ),
                    nodeEmbedding = nodeEmbBlob?.let { blobToFloatArray(it) },
                    edgeEmbedding = edgeEmbBlob?.let { blobToFloatArray(it) }
                ))
            }
        }
        return neighbors
    }

    /**
     * Recursive DFS expansion using REMINDRAG relevance scoring.
     *
     * For each unvisited neighbor:
     *   w = α · sim(currentNode, neighbor) + (1−α) · dot(query, edgeEmb) / ‖query‖
     *
     * If edge embedding is null (no experience), uses pure node similarity
     * as fallback (w = sim), so fresh edges still get traversed when nodes
     * are semantically related.
     */
    private fun dfsExpand(
        queryEmbedding: FloatArray,
        currentNodeId: Int,
        currentNodeEmbedding: FloatArray?,
        alpha: Float,
        threshold: Float,
        maxNodes: Int,
        maxDepth: Int,
        currentDepth: Int,
        visited: MutableSet<Int>,
        resultNodes: MutableList<MemoryNode>,
        nodeToEdgeId: MutableMap<Int, Int>
    ) {
        if (currentDepth >= maxDepth || resultNodes.size >= maxNodes) return

        val neighbors = getEdgeNeighborsForDFS(currentNodeId)
        val qNorm = vectorNorm(queryEmbedding)
        if (qNorm == 0f) return

        // Score all unvisited neighbors
        val scored = mutableListOf<Triple<DFSNeighbor, Float, Float?>>() // neighbor, score, nodeEmb used
        for (neighbor in neighbors) {
            if (neighbor.node.id in visited) continue

            // Node-to-node similarity (structural term)
            val nodeSim = if (currentNodeEmbedding != null && neighbor.nodeEmbedding != null) {
                cosineSimilarity(currentNodeEmbedding, neighbor.nodeEmbedding)
            } else {
                0f
            }

            // Edge alignment with query (memory term)
            val edgeAlignment = if (neighbor.edgeEmbedding != null) {
                vectorDot(queryEmbedding, neighbor.edgeEmbedding) / qNorm
            } else {
                null // No experience — use fallback
            }

            // Combined score: blend structural + memory, or pure structural if no memory
            val w = if (edgeAlignment != null) {
                alpha * nodeSim + (1f - alpha) * edgeAlignment
            } else {
                nodeSim // Graceful degradation for fresh edges
            }

            if (w > threshold) {
                scored.add(Triple(neighbor, w, null))
            }
        }

        // Expand highest-scoring neighbors first
        scored.sortByDescending { it.second }

        for ((neighbor, _, _) in scored) {
            if (resultNodes.size >= maxNodes) break
            if (!visited.add(neighbor.node.id)) continue

            resultNodes.add(neighbor.node)
            nodeToEdgeId[neighbor.node.id] = neighbor.edgeId

            // Recurse from this neighbor
            dfsExpand(
                queryEmbedding, neighbor.node.id, neighbor.nodeEmbedding,
                alpha, threshold, maxNodes, maxDepth, currentDepth + 1,
                visited, resultNodes, nodeToEdgeId
            )
        }
    }

    // ── Cursor Helpers ─────────────────────────────────────────

    private fun cursorToNode(cursor: android.database.Cursor): MemoryNode {
        val sourceIdx = cursor.getColumnIndex("source")
        return MemoryNode(
            id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
            fact = cursor.getString(cursor.getColumnIndexOrThrow("fact")),
            isPhysical = cursor.getInt(cursor.getColumnIndexOrThrow("is_physical")) == 1,
            isConcept = cursor.getInt(cursor.getColumnIndexOrThrow("is_concept")) == 1,
            category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
            source = if (sourceIdx >= 0) cursor.getString(sourceIdx) ?: MemoryConfig.SOURCE_PERSONAL else MemoryConfig.SOURCE_PERSONAL,
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            lastAccessed = cursor.getLong(cursor.getColumnIndexOrThrow("last_accessed")),
            accessCount = cursor.getInt(cursor.getColumnIndexOrThrow("access_count")),
            active = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1
        )
    }

    private fun cursorToBufferEntry(cursor: android.database.Cursor): BufferEntry {
        val sourceIdx = cursor.getColumnIndex("source")
        val anchorIdx = cursor.getColumnIndex("study_anchor_id")
        return BufferEntry(
            id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
            rawInput = cursor.getString(cursor.getColumnIndexOrThrow("raw_input")),
            extracted = cursor.getString(cursor.getColumnIndexOrThrow("extracted")),
            tags = cursor.getString(cursor.getColumnIndexOrThrow("tags")),
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            processed = cursor.getInt(cursor.getColumnIndexOrThrow("processed")) == 1,
            source = if (sourceIdx >= 0) cursor.getString(sourceIdx) ?: MemoryConfig.SOURCE_PERSONAL else MemoryConfig.SOURCE_PERSONAL,
            studyAnchorId = if (anchorIdx >= 0 && !cursor.isNull(anchorIdx)) cursor.getInt(anchorIdx) else null
        )
    }

    // ── Stats / Accessors ──────────────────────────────────────

    fun getNodeCount(): Int {
        var count = 0
        readableDatabase.rawQuery("SELECT COUNT(*) FROM nodes WHERE active=1", null).use { c ->
            if (c.moveToFirst()) count = c.getInt(0)
        }
        return count
    }

    fun getAllNodes(limit: Int = 200): List<MemoryNode> {
        val rows = mutableListOf<MemoryNode>()
        readableDatabase.rawQuery(
            "SELECT * FROM nodes WHERE active=1 ORDER BY last_accessed DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(cursorToNode(cursor))
        }
        return rows
    }

    fun getAllBuffer(limit: Int = 200): List<BufferEntry> {
        val rows = mutableListOf<BufferEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM short_term_buffer ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(cursorToBufferEntry(cursor))
            }
        }
        return rows
    }

    fun getBufferCount(): Int {
        var count = 0
        readableDatabase.rawQuery("SELECT COUNT(*) FROM short_term_buffer", null).use { c ->
            if (c.moveToFirst()) count = c.getInt(0)
        }
        return count
    }

    fun getUnprocessedCount(): Int {
        var count = 0
        readableDatabase.rawQuery("SELECT COUNT(*) FROM short_term_buffer WHERE processed=0", null).use { c ->
            if (c.moveToFirst()) count = c.getInt(0)
        }
        return count
    }

    // REMOVED: logCoAccess — replaced by edge embeddings
    // REMOVED: recordFeedback — replaced by implicit response-similarity signal

    /**
     * One-time startup cleanup: delete rows that look like they were produced by
     * the LLM (assistant responses) rather than real user messages.
     *
     * raw_user_messages: delete if message > 500 chars, contains <emotion>/<tool>
     *   tags (SLM artifacts), or contains JSON array/object brackets (drip output leakage).
     * short_term_buffer: delete if extracted contains markdown code fences,
     *   consolidation op JSON ("op":), or looks like structured LLM output.
     *
     * Returns Pair(rawDeleted, bufferDeleted) for logging.
     */
    fun cleanupCorruptedRows(): Pair<Int, Int> {
        val db = writableDatabase

        // Count + delete from raw_user_messages
        val rawCount = readableDatabase.rawQuery("""
            SELECT COUNT(*) FROM raw_user_messages
            WHERE atomized = 0 AND (
                length(message) > 500
                OR message LIKE '%<emotion>%'
                OR message LIKE '%<tool>%'
                OR message LIKE '%```%'
                OR (message LIKE '[%' AND message LIKE '%"fact"%')
            )
        """, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        if (rawCount > 0) {
            db.execSQL("""
                DELETE FROM raw_user_messages
                WHERE atomized = 0 AND (
                    length(message) > 500
                    OR message LIKE '%<emotion>%'
                    OR message LIKE '%<tool>%'
                    OR message LIKE '%```%'
                    OR (message LIKE '[%' AND message LIKE '%"fact"%')
                )
            """)
        }

        // Count + delete from short_term_buffer
        val bufCount = readableDatabase.rawQuery("""
            SELECT COUNT(*) FROM short_term_buffer
            WHERE processed = 0 AND (
                extracted LIKE '%```%'
                OR extracted LIKE '%"op":%'
                OR extracted LIKE '%{"fact":%'
                OR extracted LIKE '%<emotion>%'
                OR length(extracted) > 600
            )
        """, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        if (bufCount > 0) {
            db.execSQL("""
                DELETE FROM short_term_buffer
                WHERE processed = 0 AND (
                    extracted LIKE '%```%'
                    OR extracted LIKE '%"op":%'
                    OR extracted LIKE '%{"fact":%'
                    OR extracted LIKE '%<emotion>%'
                    OR length(extracted) > 600
                )
            """)
        }

        return rawCount to bufCount
    }
}

// ── Data Classes ───────────────────────────────────────────────

data class MemoryNode(
    val id: Int,
    val fact: String,
    val isPhysical: Boolean,
    val isConcept: Boolean,
    val category: String,
    val source: String = MemoryConfig.SOURCE_PERSONAL,
    val createdAt: Long,
    val lastAccessed: Long,
    val accessCount: Int,
    val active: Boolean
)

// CHANGED: added id, removed relationType/relationName
data class MemoryEdge(
    val id: Int,
    val sourceId: Int,
    val targetId: Int,
    val strength: Int,
    val connectedNode: MemoryNode? = null
)

data class BufferEntry(
    val id: Int,
    val rawInput: String,
    val extracted: String,
    val tags: String,
    val sessionId: String,
    val createdAt: Long,
    val processed: Boolean,
    val source: String = MemoryConfig.SOURCE_PERSONAL,
    val studyAnchorId: Int? = null
)

data class MemoryClassification(
    val fact: String?,
    val emotion: String
) {
    val store: Boolean get() = fact != null
}

data class RawUserMessage(
    val id: Int,
    val message: String,
    val sessionId: String,
    val createdAt: Long,
    val atomized: Boolean
)

/**
 * NEW: Result from DFS graph expansion.
 *
 * nodes: the expanded nodes found via DFS (does NOT include seed nodes)
 * nodeToEdgeId: maps each expanded node's ID to the edge ID that was
 *   traversed to reach it — used by MemoryModule to target enhance/penalize
 *   after evaluating response relevance.
 */
data class DFSResult(
    val nodes: List<MemoryNode>,
    val nodeToEdgeId: Map<Int, Int>
)