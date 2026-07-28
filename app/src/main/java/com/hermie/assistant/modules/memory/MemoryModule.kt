package com.hermie.assistant.modules.memory

import android.content.Context
import android.util.Log
import com.hermie.assistant.llm.EmbeddingEngine
import com.hermie.assistant.llm.LlamaNativeEngine
import com.hermie.assistant.llm.LlmEngine
import com.hermie.assistant.llm.MindLlmEngine
import com.hermie.assistant.modules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Memory Module — gives the assistant persistent memory across sessions.
 *
 * Architecture (v4):
 * - Realtime path: linguistic gate + embedding probes → retrieval decision → context packaging
 * - Background drip: SLM (Qwen2.5-0.5B) atomizes raw user messages → facts in buffer
 * - SQLite graph DB: nodes (facts + source tags) + edges (learnable embeddings)
 * - MiniLM-L6-v2 (TFLite): 384-dim sentence embeddings for semantic retrieval
 * - Post-response: embed Brain response → enhance/penalize traversed edges (REMINDRAG)
 * - Consolidation: main LLM processes buffer entries into graph nodes + edges (sleep mode)
 * - All tunable parameters in [MemoryConfig]
 *
 * v4 changes:
 * - SLM removed from realtime chat path (no more per-message classification)
 * - 3-stage retrieval gate: linguistic triggers → embedding probes → retrieve decision
 * - Raw message buffer captures every user message verbatim
 * - Drip atomizer: SLM processes raw messages in background batches
 * - Source tagging on nodes (personal, study, study_anchor)
 * - Reinforcement dead zone to prevent borderline edge thrashing
 */
class MemoryModule : HermieModule, ToolModule, BackgroundModule {

    companion object {
        private const val TAG = "MemoryModule"

        // ── Drip Atomizer prompt (replaces realtime SLM classifier) ──
        // The SLM processes batches of raw user messages in the background,
        // extracting durable personal facts. No more per-message classification.
        private const val DRIP_SYSTEM_PROMPT_FALLBACK =
            "Extract durable personal facts from user messages.\n" +
                    "Output a JSON array of objects: [{\"fact\":str,\"emotion\":str},...]\n" +
                    "fact: compressed personal fact worth remembering long-term.\n" +
                    "emotion: user's emotional state (happy/sad/excited/concerned/neutral).\n" +
                    "If a message has no fact worth storing, omit it from the array.\n" +
                    "Output ONLY the JSON array. No markdown."

        var DRIP_SYSTEM_PROMPT: String = DRIP_SYSTEM_PROMPT_FALLBACK
            private set

        // Keep legacy SLM prompt for backward compat (used by DnD filter mode)
        private const val SLM_SYSTEM_PROMPT_FALLBACK =
            "\n{\"fact\":str|null,\"emotion\":str}\n" +
                    "fact: durable personal fact, compressed. null if nothing to store."

        var SLM_SYSTEM_PROMPT: String = SLM_SYSTEM_PROMPT_FALLBACK
            private set

        fun loadPrompts(context: android.content.Context) {
            val loaded = com.hermie.assistant.data.PromptLoader.load(context, "mind_system.txt")
            if (loaded != null) {
                SLM_SYSTEM_PROMPT = loaded
                Log.d(TAG, "Loaded mind system prompt from assets (${loaded.length} chars)")
            } else {
                Log.w(TAG, "Failed to load mind_system.txt, using fallback")
            }
            val dripLoaded = com.hermie.assistant.data.PromptLoader.load(context, "drip_system.txt")
            if (dripLoaded != null) {
                DRIP_SYSTEM_PROMPT = dripLoaded
                Log.d(TAG, "Loaded drip system prompt from assets (${dripLoaded.length} chars)")
            } else {
                Log.d(TAG, "No drip_system.txt found, using built-in prompt")
            }
            com.hermie.assistant.data.PromptLoader.load(context, "consolidation_personal.txt")?.let {
                CONSOLIDATION_PROMPT_PERSONAL = it
                Log.d(TAG, "Loaded consolidation_personal prompt (${it.length} chars)")
            } ?: Log.w(TAG, "consolidation_personal.txt not found")
            com.hermie.assistant.data.PromptLoader.load(context, "consolidation_study.txt")?.let {
                CONSOLIDATION_PROMPT_STUDY = it
                Log.d(TAG, "Loaded consolidation_study prompt (${it.length} chars)")
            } ?: Log.w(TAG, "consolidation_study.txt not found")
            com.hermie.assistant.data.PromptLoader.load(context, "exploratory_link.txt")?.let {
                ExploratoryLinker.LINK_PROMPT = it
                Log.d(TAG, "Loaded exploratory_link prompt (${it.length} chars)")
            } ?: Log.w(TAG, "exploratory_link.txt not found")
        }

        var CONSOLIDATION_PROMPT_PERSONAL: String = ""
            private set
        var CONSOLIDATION_PROMPT_STUDY: String = ""
            private set
    }

    override val id = "memory"
    override val displayName = "Memory"
    override val description = "Persistent memory across sessions using a graph database"
    override val iconName = "psychology"

    private var _isActive = false
    override val isActive get() = _isActive

    override val needsBackgroundExecution = true

    private var db: MemoryDatabase? = null
    private var mindEngine: MindLlmEngine? = null
    private var mainEngine: LlmEngine? = null
    private var nativeEngine: LlamaNativeEngine? = null
    var embeddingEngine: EmbeddingEngine? = null
        private set
    private var sessionId = "session_${System.currentTimeMillis() / 1000}"

    @Volatile
    var isBrainBusy = false

    /**
     * Set by ViewModel when generation, voice mode, Desk Caddy, or model loading is active.
     * Drip will not run while this is true.
     */
    @Volatile
    var isDripSuppressed: Boolean = false

    /**
     * Epoch-ms of the last user message sent via sendMessage().
     * Drip will not run within DRIP_IDLE_WINDOW_MS of this timestamp.
     * Set synchronously before any coroutine is launched.
     */
    @Volatile
    var lastUserMessageAt: Long = 0L

    private val _lastClassification = MutableStateFlow<MemoryClassification?>(null)
    val lastClassification: StateFlow<MemoryClassification?> = _lastClassification.asStateFlow()

    private val _lastRetrievedNodes = MutableStateFlow<List<MemoryNode>>(emptyList())
    val lastRetrievedNodes: StateFlow<List<MemoryNode>> = _lastRetrievedNodes.asStateFlow()

    // REMOVED: lastUsedNodeIds (was for feedback)
    // NEW: state for post-response edge reinforcement
    private var lastQueryEmbedding: FloatArray? = null
    private var lastDFSResult: DFSResult? = null

    fun setEngines(mind: MindLlmEngine, main: LlmEngine, native: LlamaNativeEngine? = null) {
        this.mindEngine = mind
        this.mainEngine = main
        this.nativeEngine = native ?: (main as? LlamaNativeEngine)
    }

    fun setEmbeddingEngine(engine: EmbeddingEngine) {
        this.embeddingEngine = engine
    }

    override suspend fun initialize(context: Context) {
        try {
            val database = MemoryDatabase(context)
            val count = database.getNodeCount()
            db = database
            _isActive = true
            Log.d(TAG, "Memory module initialized ($count active nodes)")
            // One-time cleanup: remove rows that look like LLM responses rather than user input
            val (rawDel, bufDel) = database.cleanupCorruptedRows()
            if (rawDel > 0 || bufDel > 0) {
                Log.w(TAG, "Startup cleanup: deleted $rawDel corrupted raw messages, $bufDel corrupted buffer rows")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Memory DB failed, deleting and recreating", e)
            context.deleteDatabase("hermie_memory.db")
            try {
                val database = MemoryDatabase(context)
                database.getNodeCount()
                db = database
                _isActive = true
                Log.d(TAG, "Memory module initialized (fresh database)")
            } catch (e2: Exception) {
                Log.e(TAG, "Memory DB recreation also failed", e2)
                _isActive = false
            }
        }
    }

    override suspend fun start() { _isActive = true }
    override suspend fun stop() { _isActive = false }

    override fun release() {
        db?.close()
        db = null
        _isActive = false
    }

    // ── Realtime Pipeline (v4 — no SLM in chat path) ───────────

    /**
     * Capture a raw user message for later drip atomization.
     * Called immediately when the user sends a message — very fast, no LLM involved.
     */
    fun rawMessageCapture(message: String) {
        val database = db ?: return
        database.rawMessageInsert(message, sessionId)
        Log.d(TAG, "Raw message captured (${message.length} chars)")
    }

    /**
     * Process a user utterance through the retrieval pipeline.
     * Called BEFORE the main LLM sees the message.
     *
     * v4: SLM classification removed from realtime path. Instead uses a
     * 3-stage gate: linguistic triggers → embedding probes → retrieve decision.
     *
     * Returns a [MemoryContext] with everything the main LLM needs.
     * After the Brain responds, call [reinforceFromResponse] with the output.
     */
    suspend fun processUtterance(utterance: String): MemoryContext = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext MemoryContext.EMPTY

        // Stage 1: Linguistic gate — check for memory-related keywords
        val linguisticHit = checkLinguisticGate(utterance)

        // Stage 2: Embedding probe — quick similarity check against top nodes
        val probeHit = checkEmbeddingProbe(utterance)

        // Stage 3: Decision — retrieve if either gate fires
        val shouldRetrieve = linguisticHit || probeHit

        Log.d(TAG, "Retrieval gate: linguistic=$linguisticHit, probe=$probeHit → retrieve=$shouldRetrieve")

        val retrieval = if (shouldRetrieve) {
            retrieveRelevantNodes(utterance)
        } else {
            RetrievalResult.EMPTY
        }
        _lastRetrievedNodes.value = retrieval.allNodes
        _lastClassification.value = null  // No SLM classification in realtime

        // Recent buffer entries for short-term context (always included)
        val recentBuffer = database.bufferRecent(sessionId, limit = MemoryConfig.RECENT_BUFFER_LIMIT)

        MemoryContext(
            classification = null,
            retrieval = retrieval,
            recentBuffer = recentBuffer,
            gateSignals = GateSignals(linguisticHit, probeHit, shouldRetrieve),
            emotion = "neutral"
        )
    }

    /**
     * Stage 1: Check if the utterance contains linguistic triggers that
     * suggest the user is referencing memory (e.g., "remember", "last time").
     */
    private fun checkLinguisticGate(utterance: String): Boolean {
        val lower = utterance.lowercase()
        return MemoryConfig.GATE_LINGUISTIC_TRIGGERS.any { trigger -> lower.contains(trigger) }
    }

    /**
     * Stage 2: Quick embedding probe — embed the query and check if any
     * node exceeds the probe threshold. Cheaper than full retrieval because
     * we only need to know IF there's a match, not WHAT matched.
     */
    private fun checkEmbeddingProbe(utterance: String): Boolean {
        val database = db ?: return false
        val ee = embeddingEngine ?: return false
        if (!ee.isLoaded) return false
        val queryEmb = ee.embed(utterance) ?: return false

        val topNodes = database.retrieveByEmbedding(
            queryEmb,
            limit = MemoryConfig.GATE_PROBE_TOP_K,
            minSimilarity = MemoryConfig.GATE_PROBE_THRESHOLD
        )
        return topNodes.isNotEmpty()
    }

    /**
     * NEW: Reinforce edge embeddings based on what the Brain actually used.
     *
     * Called AFTER the Brain generates its response. Embeds the response,
     * computes similarity to each DFS-retrieved node, and:
     * - Enhances edges that led to relevant nodes (Brain used them)
     * - Penalizes edges that led to irrelevant nodes (Brain ignored them)
     *
     * This is the implicit feedback signal that replaces thumbs up/down.
     * Cost: 1 embedding call + N cosine similarities + N edge updates.
     */
    suspend fun reinforceFromResponse(responseText: String) = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext
        val queryEmb = lastQueryEmbedding ?: return@withContext
        val dfsResult = lastDFSResult ?: return@withContext
        if (dfsResult.nodeToEdgeId.isEmpty()) return@withContext

        val ee = embeddingEngine ?: return@withContext
        if (!ee.isLoaded) return@withContext

        val responseEmb = ee.embed(responseText) ?: return@withContext

        var enhanced = 0
        var penalized = 0

        for ((nodeId, edgeId) in dfsResult.nodeToEdgeId) {
            val nodeEmb = database.getNodeEmbedding(nodeId) ?: continue
            val similarity = cosineSim(responseEmb, nodeEmb)

            if (similarity >= MemoryConfig.REINFORCE_RELEVANCE_THRESHOLD) {
                database.edgeEnhance(edgeId, queryEmb)
                enhanced++
            } else if (similarity < MemoryConfig.REINFORCE_RELEVANCE_THRESHOLD - MemoryConfig.REINFORCE_DEAD_ZONE) {
                // Only penalize if clearly below threshold (dead zone prevents thrashing)
                database.edgePenalize(edgeId, queryEmb)
                penalized++
            }
            // else: in dead zone — skip (similarity is borderline)
        }

        if (enhanced > 0 || penalized > 0) {
            Log.d(TAG, "Edge reinforcement: $enhanced enhanced, $penalized penalized")
        }

        // Clear for next query
        lastQueryEmbedding = null
        lastDFSResult = null
    }

    // ── Drip Atomization (Background SLM Processing) ─────────────

    private var lastDripTime = 0L

    /**
     * Drip atomizer: processes raw user messages in background batches.
     * The SLM extracts facts from raw messages and writes them to the buffer.
     * Called from onBackgroundTick or explicitly during sleep mode.
     *
     * Guard conditions — drip is skipped when ANY of these are true:
     * - isBrainBusy: sleep/study/consolidation taking the brain
     * - isDripSuppressed: ViewModel signals generation/voice/deskCaddy/loading is active
     * - within DRIP_IDLE_WINDOW_MS of the last user message (active conversation)
     * - rate-limited (within DRIP_MIN_INTERVAL_MS of last drip)
     */
    suspend fun dripAtomize() = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext
        val engine = mindEngine ?: return@withContext
        if (!engine.isLoaded) return@withContext
        if (isBrainBusy) return@withContext
        if (isDripSuppressed) return@withContext

        val now = System.currentTimeMillis()

        // Don't run within 2 minutes of last user message (active chat window)
        if (lastUserMessageAt > 0L && (now - lastUserMessageAt) < MemoryConfig.DRIP_IDLE_WINDOW_MS) return@withContext

        // Rate limit
        if (now - lastDripTime < MemoryConfig.DRIP_MIN_INTERVAL_MS) return@withContext

        val rawMessages = database.rawMessagesUnatomized(limit = MemoryConfig.DRIP_BATCH_SIZE)
        if (rawMessages.isEmpty()) return@withContext

        Log.d(TAG, "Drip atomizer: processing ${rawMessages.size} raw messages")
        lastDripTime = now

        try {
            val batchText = rawMessages.joinToString("\n") { "- ${it.message}" }
            val messages = listOf(LlmEngine.Message("user", batchText))
            val response = StringBuilder()
            engine.generate(messages, maxTokens = MemoryConfig.DRIP_MAX_TOKENS, systemPrompt = DRIP_SYSTEM_PROMPT).collect { token ->
                response.append(token)
            }
            val raw = response.toString().trim()
            Log.d(TAG, "Drip atomizer SLM output: $raw")

            val facts = parseDripOutput(raw)
            for (fact in facts) {
                val isDuplicate = isDuplicateFact(database, fact.fact!!)
                if (!isDuplicate) {
                    val tags = extractKeywordTags(fact.fact)
                    database.bufferWrite(
                        rawInput = "[drip] ${fact.fact}",
                        extracted = fact.fact,
                        tags = tags,
                        sessionId = sessionId
                    )
                    Log.d(TAG, "Drip extracted fact: \"${fact.fact}\"")
                    // Immediately embed if possible
                    val ee = embeddingEngine
                    if (ee != null && ee.isLoaded) {
                        val latestEntries = database.bufferWithoutEmbedding(1)
                        for ((id, text) in latestEntries) {
                            val emb = ee.embed(text)
                            if (emb != null) database.bufferSetEmbedding(id, emb)
                        }
                    }
                }
            }

            database.rawMessagesMarkAtomized(rawMessages.map { it.id })
            Log.d(TAG, "Drip atomizer: ${facts.size} facts extracted from ${rawMessages.size} messages")
        } catch (e: Exception) {
            Log.e(TAG, "Drip atomization failed", e)
        }
    }

    /**
     * Parse the drip atomizer's JSON array output into MemoryClassification objects.
     * Expected format: [{"fact":"...","emotion":"..."},...]
     */
    private fun parseDripOutput(raw: String): List<MemoryClassification> {
        return try {
            val jsonStr = extractJsonArray(raw) ?: return emptyList()
            val array = org.json.JSONArray(jsonStr)
            val results = mutableListOf<MemoryClassification>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val fact = obj.optString("fact", "").takeIf { it != "null" && it.isNotBlank() }
                if (fact != null) {
                    results.add(MemoryClassification(
                        fact = fact,
                        emotion = obj.optString("emotion", "neutral")
                    ))
                }
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse drip output: ${raw.take(200)}", e)
            // Fallback: try parsing as single object
            val single = parseSingleClassification(raw)
            if (single != null) listOf(single) else emptyList()
        }
    }

    private fun parseSingleClassification(raw: String): MemoryClassification? {
        return try {
            val jsonStr = extractJson(raw) ?: return null
            val json = JSONObject(jsonStr)
            MemoryClassification(
                fact = json.optString("fact", "").takeIf { it != "null" && it.isNotBlank() },
                emotion = json.optString("emotion", "neutral")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    /**
     * CHANGED: Retrieve relevant memory nodes for a query.
     *
     * Strategy:
     * 1. Embedding search on nodes + buffer → seed nodes
     * 2. DFS expansion from seeds using edge embeddings (REMINDRAG-style)
     * 3. Keyword/tag fallback when embeddings aren't ready
     *
     * Stores queryEmbedding and DFS result for post-response reinforcement.
     */
    fun retrieveByEmbedding(query: String): List<MemoryNode> {
        val database = db ?: return emptyList()
        val engine = embeddingEngine ?: return emptyList()
        if (!engine.isLoaded) return emptyList()
        val emb = engine.embed(query) ?: return emptyList()
        return database.retrieveByEmbedding(
            emb, limit = MemoryConfig.RETRIEVAL_TOP_K_EMBEDDING,
            minSimilarity = MemoryConfig.RETRIEVAL_MIN_SIMILARITY
        )
    }

    private fun retrieveRelevantNodes(query: String): RetrievalResult {
        val database = db ?: return RetrievalResult.EMPTY
        val seen = mutableSetOf<Int>()
        val anchors = mutableListOf<MemoryNode>()
        val bufferHits = mutableListOf<BufferEntry>()

        val ee = this.embeddingEngine
        val queryEmbedding = if (ee != null && ee.isLoaded) {
            ee.embed(query)
        } else {
            Log.d(TAG, "Retrieval: embedding engine not available, using keyword fallback")
            null
        }

        if (queryEmbedding != null) {
            // Store for post-response reinforcement
            lastQueryEmbedding = queryEmbedding

            // --- Seed nodes from embedding search ---
            val embeddingNodes = database.retrieveByEmbedding(
                queryEmbedding,
                limit = MemoryConfig.RETRIEVAL_TOP_K_EMBEDDING,
                minSimilarity = MemoryConfig.RETRIEVAL_MIN_SIMILARITY
            )
            for (node in embeddingNodes) {
                if (seen.add(node.id)) anchors.add(node)
            }

            // --- Buffer hits ---
            val embeddingBuffer = database.retrieveBufferByEmbedding(
                queryEmbedding,
                limit = MemoryConfig.RETRIEVAL_TOP_K_EMBEDDING,
                minSimilarity = MemoryConfig.RETRIEVAL_MIN_SIMILARITY
            )
            bufferHits.addAll(embeddingBuffer)

            // --- DFS expansion from seeds using edge embeddings ---
            val seedIds = anchors.map { it.id }
            val remainingBudget = MemoryConfig.RETRIEVAL_MAX_NODES - anchors.size
            val dfsResult = if (seedIds.isNotEmpty() && remainingBudget > 0) {
                database.retrieveByDFS(
                    queryEmbedding, seedIds,
                    alpha = MemoryConfig.DFS_ALPHA,
                    threshold = MemoryConfig.DFS_THRESHOLD,
                    maxNodes = remainingBudget,
                    maxDepth = MemoryConfig.DFS_MAX_DEPTH
                )
            } else {
                DFSResult(emptyList(), emptyMap())
            }
            lastDFSResult = dfsResult

            val dfsNodes = dfsResult.nodes.filter { seen.add(it.id) }

            // Touch all accessed nodes (keeps last_accessed fresh for decay)
            for (node in anchors) database.nodeTouch(node.id)
            for (node in dfsNodes) database.nodeTouch(node.id)

            val allNodes = (anchors + dfsNodes)
                .sortedByDescending { it.lastAccessed }
                .take(MemoryConfig.RETRIEVAL_MAX_NODES)

            Log.d(TAG, "Retrieval: ${anchors.size} seeds, ${dfsNodes.size} DFS expanded, " +
                    "${bufferHits.size} buffer hits, ${allNodes.size} total")

            return RetrievalResult(
                anchors = anchors,
                dfsNodes = dfsNodes,
                dfsEdgeMap = dfsResult.nodeToEdgeId,
                bufferHits = bufferHits,
                allNodes = allNodes
            )
        } else {
            // Fallback: keyword search (no DFS without embeddings)
            lastQueryEmbedding = null
            lastDFSResult = null

            val keywordNodes = database.retrieveByKeywords(
                query, limit = MemoryConfig.RETRIEVAL_KEYWORD_LIMIT
            )
            for (node in keywordNodes) {
                if (seen.add(node.id)) anchors.add(node)
            }

            val queryTags = extractKeywordTags(query)
            if (queryTags.isNotEmpty()) {
                val tagNodes = database.retrieveByTags(queryTags, limit = MemoryConfig.RETRIEVAL_KEYWORD_LIMIT)
                for (node in tagNodes) {
                    if (seen.add(node.id)) anchors.add(node)
                }
            }

            val allNodes = anchors
                .sortedByDescending { it.lastAccessed }
                .take(MemoryConfig.RETRIEVAL_MAX_NODES)

            Log.d(TAG, "Retrieval (keyword fallback): ${allNodes.size} nodes")

            return RetrievalResult(
                anchors = allNodes,
                dfsNodes = emptyList(),
                dfsEdgeMap = emptyMap(),
                bufferHits = emptyList(),
                allNodes = allNodes
            )
        }
    }

    private fun isDuplicateFact(database: MemoryDatabase, fact: String): Boolean {
        val normalized = fact.trim().lowercase()

        // Check against recent buffer entries only (not graph nodes).
        // We don't check nodes because facts may evolve over time and the
        // user should be able to mention similar topics in new contexts.
        val recentBuffer = database.bufferRecentAll(limit = 20)
        for (entry in recentBuffer) {
            val entryNorm = entry.extracted.trim().lowercase()
            if (entryNorm == normalized) {
                Log.d(TAG, "Dedup: exact match in buffer — \"${fact.take(50)}\"")
                return true
            }
            val words1 = normalized.split(Regex("\\s+")).toSet()
            val words2 = entryNorm.split(Regex("\\s+")).toSet()
            val intersection = words1.intersect(words2).size.toFloat()
            val union = words1.union(words2).size.toFloat()
            val jaccard = if (union > 0) intersection / union else 0f
            if (jaccard >= MemoryConfig.DEDUP_SIMILARITY_THRESHOLD) {
                Log.d(TAG, "Dedup: buffer Jaccard ${jaccard} >= ${MemoryConfig.DEDUP_SIMILARITY_THRESHOLD} — \"${fact.take(50)}\" vs \"${entry.extracted.take(50)}\"")
                return true
            }
        }

        return false
    }

    private fun extractKeywordTags(text: String): List<String> {
        val stopWords = setOf(
            "i", "me", "my", "the", "a", "an", "is", "am", "are", "was", "were",
            "be", "been", "being", "have", "has", "had", "do", "does", "did",
            "will", "would", "could", "should", "can", "may", "might",
            "to", "of", "in", "for", "on", "with", "at", "by", "from",
            "and", "or", "but", "not", "no", "so", "if", "then", "than",
            "this", "that", "it", "its", "what", "which", "who", "when",
            "where", "how", "all", "each", "every", "some", "any",
            "just", "really", "very", "also", "about", "like", "dont", "im"
        )
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
            .take(8)
    }

    // ── Vector Math (for response similarity scoring) ──────────

    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var nA = 0f; var nB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; nA += a[i] * a[i]; nB += b[i] * b[i] }
        val d = sqrt(nA) * sqrt(nB)
        return if (d > 0f) dot / d else 0f
    }

    // ── Consolidation Pipeline (Background) ────────────────────

    override suspend fun onBackgroundTick() {
        // Embedding uses a separate TFLite engine — always safe to run.
        embedPending()
        // Drip atomizer uses SLM on slot 3 — safe if brain isn't generating.
        // Consolidation and pruning are DEFERRED TO SLEEP MODE ONLY because
        // they hijack the brain's system prompt and reset its KV cache.
        dripAtomize()
    }

    suspend fun embedPending() = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext
        val engine = embeddingEngine ?: return@withContext
        if (!engine.isLoaded) return@withContext

        val pendingBuffer = database.bufferWithoutEmbedding(MemoryConfig.EMBEDDING_BATCH_SIZE)
        for ((id, text) in pendingBuffer) {
            val emb = engine.embed(text)
            if (emb != null) database.bufferSetEmbedding(id, emb)
        }

        val pendingNodes = database.nodesWithoutEmbedding(MemoryConfig.EMBEDDING_BATCH_SIZE)
        for ((id, text) in pendingNodes) {
            val emb = engine.embed(text)
            if (emb != null) database.nodeSetEmbedding(id, emb)
        }

        if (pendingBuffer.isNotEmpty() || pendingNodes.isNotEmpty()) {
            Log.d(TAG, "Embedded ${pendingBuffer.size} buffer + ${pendingNodes.size} nodes")
        }
    }

    // runConsolidation removed — use runIterativeConsolidation instead

    suspend fun runIterativeConsolidation(
        onProgress: suspend (String) -> Unit = {},
        onBetweenBatches: suspend () -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val database = db ?: run { onProgress("Error: memory database not available"); return@withContext 0 }
        val engine = mainEngine ?: run { onProgress("Error: LLM engine not available"); return@withContext 0 }
        val native = nativeEngine

        var totalOps = 0

        // ── Debug snapshot: surface data-integrity issues before processing ──
        val totalBuf = database.getBufferCount()
        val unprocessed = database.getUnprocessedCount()
        val sourceStats = database.bufferSourceStats()
        onProgress("Buffer snapshot: total=$totalBuf unprocessed=$unprocessed by-source=$sourceStats")
        if ((sourceStats["study_orphan"] ?: 0) > 0) {
            Log.w(TAG, "Consolidation: ${sourceStats["study_orphan"]} study orphan rows detected (study_anchor_id=null)")
        }
        database.bufferRecentAll(3).forEach { e ->
            onProgress("  Sample: source=${e.source} anchor=${e.studyAnchorId} processed=${e.processed} \"${e.extracted.take(60)}\"")
        }

        onProgress("Embedding pending entries...")
        embedPending()

        try {
            // ── Phase A: Personal entries — one at a time ──────────
            var personalPass = 1
            while (isActive) {
                val entries = database.bufferUnprocessedPersonal(limit = MemoryConfig.CONSOLIDATION_BATCH_SIZE_PERSONAL)
                if (entries.isEmpty()) break

                val entry = entries.first()
                onProgress("Personal pass $personalPass: \"${entry.extracted.take(60)}\"")

                // Reset KV cache for each entry — clean slate prevents context bleed
                if (native != null) {
                    native.setSystemPrompt(CONSOLIDATION_PROMPT_PERSONAL)
                    native.resetContext()
                }

                val allTags = entry.tags.split(",").filter { it.isNotBlank() }.distinct()
                val existing = if (allTags.isNotEmpty()) database.retrieveByTags(allTags, limit = 15) else emptyList()
                val existingJson = existing.joinToString(",\n") { n ->
                    """{"id":${n.id},"fact":"${n.fact.replace("\"", "\\\"")}","category":"${n.category}"}"""
                }
                val safeFact = entry.extracted.replace("\"", "\\\"")
                val safeTags = entry.tags.replace("\"", "\\\"")
                val userMsg = """{"fact":"$safeFact","tags":"$safeTags","existing":[$existingJson]}"""

                try {
                    val ops = consolidateBatch(engine, CONSOLIDATION_PROMPT_PERSONAL, userMsg, onProgress)
                    if (ops.isNotEmpty()) {
                        logOps(ops, onProgress)
                        executeOps(database, ops)
                        totalOps += ops.size
                    } else {
                        onProgress("  No ops generated — marking done")
                    }
                    database.bufferMarkDone(entries.map { it.id })
                    onProgress("  Personal pass $personalPass: ${ops.size} ops")
                } catch (e: Exception) {
                    Log.e(TAG, "Personal pass $personalPass failed", e)
                    onProgress("  Error: ${e.message}")
                    database.bufferMarkDone(entries.map { it.id })
                }

                onBetweenBatches()
                personalPass++
            }

            // ── Phase B: Study entries — grouped by anchor, one at a time ──
            val anchorIds = database.bufferUnprocessedStudyAnchorIds()
            for (anchorId in anchorIds) {
                if (!isActive) break
                val anchorFact = database.nodeFindById(anchorId)?.fact ?: "Unknown Topic"
                onProgress("Study topic: \"$anchorFact\" (anchor #$anchorId)")

                var studyPass = 1
                while (isActive) {
                    val entries = database.bufferUnprocessedByAnchor(anchorId, limit = MemoryConfig.CONSOLIDATION_BATCH_SIZE_STUDY)
                    if (entries.isEmpty()) break

                    val entry = entries.first()
                    onProgress("  Study pass $studyPass: \"${entry.extracted.take(60)}\"")

                    // Reset KV cache for each entry
                    if (native != null) {
                        native.setSystemPrompt(CONSOLIDATION_PROMPT_STUDY)
                        native.resetContext()
                    }

                    val allTags = entry.tags.split(",").filter { it.isNotBlank() }.distinct()
                    val existing = if (allTags.isNotEmpty()) database.retrieveByTags(allTags, limit = 15) else emptyList()
                    val existingJson = existing.joinToString(",\n") { n ->
                        """{"id":${n.id},"fact":"${n.fact.replace("\"", "\\\"")}","category":"${n.category}"}"""
                    }
                    val safeAnchor = anchorFact.replace("\"", "\\\"")
                    val safeFact = entry.extracted.replace("\"", "\\\"")
                    val userMsg = """{"anchor":"$safeAnchor","anchor_id":$anchorId,"fact":"$safeFact","existing":[$existingJson]}"""

                    try {
                        val ops = consolidateBatch(engine, CONSOLIDATION_PROMPT_STUDY, userMsg, onProgress)
                        if (ops.isNotEmpty()) {
                            logOps(ops, onProgress)
                            executeOps(database, ops)
                            totalOps += ops.size
                        } else {
                            onProgress("  No ops generated — marking done")
                        }
                        database.bufferMarkDone(entries.map { it.id })
                        onProgress("  Study pass $studyPass: ${ops.size} ops")
                    } catch (e: Exception) {
                        Log.e(TAG, "Study pass $studyPass (anchor=$anchorId) failed", e)
                        onProgress("  Error: ${e.message}")
                        database.bufferMarkDone(entries.map { it.id })
                    }

                    onBetweenBatches()
                    studyPass++
                }
            }

            // ── Phase C: Study orphans — source=study but anchor_id=null ──
            // These rows were written without a valid anchor (DB failure, race condition).
            // Process them as personal entries so they are never silently lost.
            val orphanCount = sourceStats["study_orphan"] ?: 0
            if (orphanCount > 0) {
                onProgress("Phase C: $orphanCount study orphans — processing as personal entries")
                Log.w(TAG, "Processing $orphanCount study orphan rows as personal entries")
            }
            var orphanPass = 1
            while (isActive) {
                val entries = database.bufferUnprocessedStudyOrphans(limit = 1)
                if (entries.isEmpty()) break

                val entry = entries.first()
                onProgress("  Orphan pass $orphanPass: \"${entry.extracted.take(60)}\"")

                if (native != null) {
                    native.setSystemPrompt(CONSOLIDATION_PROMPT_PERSONAL)
                    native.resetContext()
                }

                val allTags = entry.tags.split(",").filter { it.isNotBlank() }.distinct()
                val existing = if (allTags.isNotEmpty()) database.retrieveByTags(allTags, limit = 15) else emptyList()
                val existingJson = existing.joinToString(",\n") { n ->
                    """{"id":${n.id},"fact":"${n.fact.replace("\"", "\\\"")}","category":"${n.category}"}"""
                }
                val safeFact = entry.extracted.replace("\"", "\\\"")
                val safeTags = entry.tags.replace("\"", "\\\"")
                val userMsg = """{"fact":"$safeFact","tags":"$safeTags","existing":[$existingJson]}"""

                try {
                    val ops = consolidateBatch(engine, CONSOLIDATION_PROMPT_PERSONAL, userMsg, onProgress)
                    if (ops.isNotEmpty()) {
                        logOps(ops, onProgress)
                        executeOps(database, ops)
                        totalOps += ops.size
                    } else {
                        onProgress("  No ops — marking done")
                    }
                    database.bufferMarkDone(entries.map { it.id })
                    onProgress("  Orphan pass $orphanPass: ${ops.size} ops")
                } catch (e: Exception) {
                    Log.e(TAG, "Orphan pass $orphanPass failed", e)
                    onProgress("  Error: ${e.message}")
                    database.bufferMarkDone(entries.map { it.id })
                }

                onBetweenBatches()
                orphanPass++
            }
        } finally {
            // Always reset context so KV cache is clean for next user chat
            native?.resetContext()
        }

        onProgress("Embedding new nodes...")
        embedPending()

        val cleared = database.bufferDeleteProcessed()
        if (cleared > 0) onProgress("Cleared $cleared processed buffer entries")

        totalOps
    }

    private suspend fun consolidateBatch(
        engine: LlmEngine,
        systemPrompt: String,
        userMsg: String,
        onProgress: suspend (String) -> Unit
    ): List<Map<String, Any>> {
        for (attempt in 1..3) {
            if (attempt > 1) onProgress("  Retry attempt $attempt...")
            val messages = listOf(
                LlmEngine.Message("system", systemPrompt),
                LlmEngine.Message("user", userMsg)
            )
            val response = StringBuilder()
            engine.generate(messages, maxTokens = MemoryConfig.CONSOLIDATION_MAX_TOKENS).collect { token ->
                response.append(token)
            }
            val raw = response.toString()
            Log.d(TAG, "Consolidation response (${raw.length} chars): ${raw.take(300)}")
            val ops = parseConsolidationOps(raw)
            if (ops.isNotEmpty()) return ops
            if (attempt < 3) onProgress("  No valid JSON — retrying...")
        }
        Log.w(TAG, "No consolidation ops after 3 attempts")
        return emptyList()
    }

    private suspend fun logOps(ops: List<Map<String, Any>>, onProgress: suspend (String) -> Unit) {
        for (op in ops) {
            when (op["op"]) {
                "create_node" -> onProgress("  + \"${(op["fact"] as? String)?.take(50)}\" [${op["category"]}]")
                "merge_node" -> onProgress("  ~ Merge into #${op["existing_id"]}: \"${(op["updated_fact"] as? String)?.take(50)}\"")
                "create_edge" -> {
                    val str = (op["strength"] as? Number)?.toInt() ?: 50
                    onProgress("  -> \"${(op["source_fact"] as? String)?.take(25)}\" --[$str]--> \"${(op["target_fact"] as? String)?.take(25)}\"")
                }
            }
        }
    }

    fun runPruningWithProgress(onProgress: (String) -> Unit) {
        val database = db ?: return

        onProgress("Decaying stale edges...")
        database.decayEdges(MemoryConfig.PRUNE_EDGE_DECAY, MemoryConfig.PRUNE_EDGE_FLOOR)

        val nodeCount = database.getNodeCount()
        if (nodeCount > MemoryConfig.PRUNE_PRESSURE_THRESHOLD) {
            onProgress("Capacity note: $nodeCount nodes (threshold: ${MemoryConfig.PRUNE_PRESSURE_THRESHOLD})")
        }

        onProgress("Deactivating unaccessed stale nodes (>${MemoryConfig.DEACTIVATE_STALE_DAYS} days)...")
        database.deactivateStale(MemoryConfig.DEACTIVATE_STALE_DAYS)

        val finalCount = database.getNodeCount()
        onProgress("Pruning complete: $finalCount active nodes remain")
    }

    /**
     * Phase 4 of sleep-mode: Exploratory Graph Linking.
     *
     * Delegates to [ExploratoryLinker] — see that class for full documentation.
     * Requires the embedding engine to be available for edge embedding seeding.
     *
     * @return Number of new edges created (0 if preconditions not met).
     */
    suspend fun runExploratoryLinking(
        nativeEngine: LlamaNativeEngine,
        onProgress: suspend (String) -> Unit = {},
        onThermalCheck: suspend () -> Unit = {}
    ): Int {
        val database = db ?: run {
            onProgress("ERROR: memory database not available for exploratory linking")
            return 0
        }
        val engine = mainEngine ?: run {
            onProgress("ERROR: LLM engine not available for exploratory linking")
            return 0
        }
        return ExploratoryLinker(database, embeddingEngine).run(
            engine = engine,
            nativeEngine = nativeEngine,
            onProgress = onProgress,
            onThermalCheck = onThermalCheck
        )
    }

    private fun parseConsolidationOps(raw: String): List<Map<String, Any>> {
        return try {
            val jsonStr = extractJsonArray(raw) ?: return emptyList()
            val array = org.json.JSONArray(jsonStr)
            val ops = mutableListOf<Map<String, Any>>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val map = mutableMapOf<String, Any>()
                for (key in obj.keys()) {
                    map[key] = obj.get(key)
                }
                ops.add(map)
            }
            ops
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse consolidation ops: ${raw.take(200)}", e)
            emptyList()
        }
    }

    private fun extractJsonArray(text: String): String? {
        val cleaned = text
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        val start = cleaned.indexOf('[')
        if (start == -1) return null
        var depth = 0
        for (i in start until cleaned.length) {
            when (cleaned[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return cleaned.substring(start, i + 1) }
            }
        }
        return null
    }

    // CHANGED: create_edge no longer passes relation_type
    private fun executeOps(database: MemoryDatabase, ops: List<Map<String, Any>>) {
        val factToId = mutableMapOf<String, Long>()

        for (op in ops) {
            try {
                when (op["op"]) {
                    "create_node" -> {
                        val fact = op["fact"] as? String ?: continue
                        val existing = database.nodeFindByFact(fact)
                        if (existing != null) {
                            factToId[fact] = existing.id.toLong()
                            database.nodeTouch(existing.id)
                            continue
                        }
                        val nid = database.nodeCreate(
                            fact = fact,
                            category = op["category"] as? String ?: "knowledge.general",
                            isPhysical = op["is_physical"] as? Boolean ?: false,
                            isConcept = op["is_concept"] as? Boolean ?: true
                        )
                        if (nid <= 0) {
                            Log.w(TAG, "nodeCreate returned invalid id=$nid for fact=\"${fact.take(50)}\" — skipping tags")
                            continue
                        }
                        val tags = when (val t = op["tags"]) {
                            is org.json.JSONArray -> (0 until t.length()).map { t.getString(it) }
                            is List<*> -> t.filterIsInstance<String>()
                            else -> emptyList()
                        }
                        if (tags.isNotEmpty()) database.tagsSet(nid, tags)
                        val ee = embeddingEngine
                        if (ee != null && ee.isLoaded) {
                            val emb = ee.embed(fact)
                            if (emb != null) database.nodeSetEmbedding(nid.toInt(), emb)
                        }
                        factToId[fact] = nid
                    }
                    "merge_node" -> {
                        val existingId = (op["existing_id"] as? Number)?.toInt() ?: continue
                        val updatedFact = op["updated_fact"] as? String
                        database.nodeUpdate(
                            nodeId = existingId,
                            fact = updatedFact
                        )
                        if (updatedFact != null) {
                            val newTags = extractKeywordTags(updatedFact)
                            if (newTags.isNotEmpty()) database.tagsSet(existingId.toLong(), newTags)
                            val ee = embeddingEngine
                            if (ee != null && ee.isLoaded) {
                                val emb = ee.embed(updatedFact)
                                if (emb != null) database.nodeSetEmbedding(existingId, emb)
                            }
                            factToId[updatedFact] = existingId.toLong()
                        }
                    }
                    "create_edge" -> {
                        val srcFact = op["source_fact"] as? String
                        val tgtFact = op["target_fact"] as? String
                        val src = factToId[srcFact]
                            ?: srcFact?.let { database.nodeFindByFact(it)?.id?.toLong() }
                        val tgt = factToId[tgtFact]
                            ?: tgtFact?.let { database.nodeFindByFact(it)?.id?.toLong() }
                        if (src != null && tgt != null) {
                            database.edgeCreate(
                                sourceId = src,
                                targetId = tgt,
                                strength = (op["strength"] as? Number)?.toInt() ?: 50
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "executeOps: failed on ${op["op"]} for \"${(op["fact"] as? String)?.take(50)}\"", e)
                // Continue with remaining operations — don't let one failure kill the batch
            }
        }
    }

    fun runPruning() {
        val database = db ?: return
        database.decayEdges(MemoryConfig.PRUNE_EDGE_DECAY, MemoryConfig.PRUNE_EDGE_FLOOR)
        database.deactivateStale(MemoryConfig.DEACTIVATE_STALE_DAYS)
    }

    // ── Tool Interface ─────────────────────────────────────────

    override val toolDefinitions = listOf(
        ToolDefinition(
            name = "memory.recall",
            description = "Search the user's memory graph for relevant facts. Use when the user asks about themselves or references past conversations.",
            parameters = mapOf(
                "query" to ToolParam("str", "What to search for in memory")
            )
        ),
        ToolDefinition(
            name = "memory.store",
            description = "Manually store a fact in the user's memory. Use when the user explicitly asks you to remember something.",
            parameters = mapOf(
                "fact" to ToolParam("str", "The compressed fact to store"),
                "category" to ToolParam("str", "Category: user.pref, user.health, user.people, user.schedule, user.work, knowledge.general", required = false)
            )
        ),
        ToolDefinition(
            name = "memory.count",
            description = "Get the number of facts stored in memory",
            parameters = emptyMap()
        )
    )

    override suspend fun executeTool(name: String, params: Map<String, String>): ToolResult {
        val database = db ?: return ToolResult.Error("Memory not initialized")

        return when (name) {
            "memory.recall" -> {
                val query = params["query"] ?: return ToolResult.Error("Missing query")
                val retrieval = retrieveRelevantNodes(query)
                val nodes = retrieval.allNodes
                if (nodes.isEmpty()) {
                    ToolResult.Success("No memories found matching '$query'")
                } else {
                    val formatted = nodes.joinToString("\n") { n ->
                        "- [${n.category}] ${n.fact}"
                    }
                    ToolResult.Success("Found ${nodes.size} memories:\n$formatted")
                }
            }
            "memory.store" -> {
                val fact = params["fact"] ?: return ToolResult.Error("Missing fact")
                val category = params["category"] ?: "knowledge.general"
                database.bufferWrite(
                    rawInput = "[manual store] $fact",
                    extracted = fact,
                    tags = extractKeywordTags(fact),
                    sessionId = sessionId
                )
                ToolResult.Success("Stored: $fact")
            }
            "memory.count" -> {
                val count = database.getNodeCount()
                ToolResult.Success("Memory contains $count active facts")
            }
            else -> ToolResult.Error("Unknown tool: $name")
        }
    }

    // ── Visualizer / External Queries ──────────────────────────

    fun getAllNodes(limit: Int = 200): List<MemoryNode> = db?.getAllNodes(limit) ?: emptyList()
    fun getAllBuffer(limit: Int = 200): List<BufferEntry> = db?.getAllBuffer(limit) ?: emptyList()
    fun getNodeCount(): Int = db?.getNodeCount() ?: 0
    fun getBufferCount(): Int = db?.getBufferCount() ?: 0
    fun getUnprocessedCount(): Int = db?.getUnprocessedCount() ?: 0
    fun resetOrphanedBuffer(): Int = db?.bufferResetOrphaned() ?: 0

    // REMOVED: recordFeedback — replaced by reinforceFromResponse

    fun newSession() {
        sessionId = "session_${System.currentTimeMillis() / 1000}"
    }

    /**
     * Create a study anchor node for a document. Returns the node id.
     * If an anchor with the same title already exists, returns its id.
     * Called once per document before writing any study buffer entries.
     */
    fun createStudyAnchor(title: String): Long {
        val database = db ?: return -1L
        val existing = database.nodeFindByFact(title)
        if (existing != null) return existing.id.toLong()
        val tags = extractKeywordTags(title)
        val anchorId = database.nodeCreate(
            fact = title,
            category = "knowledge.general",
            source = MemoryConfig.SOURCE_STUDY_ANCHOR
        )
        if (anchorId > 0) {
            if (tags.isNotEmpty()) database.tagsSet(anchorId, tags)
            val ee = embeddingEngine
            if (ee != null && ee.isLoaded) {
                ee.embed(title)?.let { database.nodeSetEmbedding(anchorId.toInt(), it) }
            }
        }
        Log.d(TAG, "Study anchor created: \"$title\" → id=$anchorId")
        return anchorId
    }

    /**
     * Store a fact extracted by the Study module directly into the buffer.
     * Source tagged as SOURCE_STUDY with a reference to the anchor node.
     *
     * Requires a valid studyAnchorId > 0. If the anchor id is invalid, the write is
     * skipped and an error is logged — writing with a null anchor would orphan the row
     * from Phase B consolidation and silently lose the fact.
     */
    fun storeStudyFact(fact: String, sourceTitle: String, studyAnchorId: Long) {
        if (studyAnchorId <= 0) {
            Log.e(TAG, "storeStudyFact: invalid studyAnchorId=$studyAnchorId for \"${fact.take(60)}\" — skipping write to prevent orphan row")
            return
        }
        val database = db ?: return
        database.bufferWrite(
            rawInput = "[Study: $sourceTitle] $fact",
            extracted = fact,
            tags = extractKeywordTags(fact) + listOf("study"),
            sessionId = sessionId,
            source = MemoryConfig.SOURCE_STUDY,
            studyAnchorId = studyAnchorId.toInt()
        )
        Log.d(TAG, "Study fact stored (anchor=$studyAnchorId): ${fact.take(80)}")
    }

    fun getRawMessageCount(): Int = db?.rawMessagesUnatomizedCount() ?: 0
}

// ── Context passed to the main LLM ────────────────────────────

// CHANGED: simplified — replaces anchorToConnected/anchorEdges with DFS results
data class RetrievalResult(
    /** Direct matches from embedding search (seed nodes) */
    val anchors: List<MemoryNode>,
    /** Nodes found via DFS graph expansion from seeds */
    val dfsNodes: List<MemoryNode>,
    /** Maps each DFS node ID to the edge ID traversed to reach it */
    val dfsEdgeMap: Map<Int, Int>,
    /** Buffer entries matched by embedding */
    val bufferHits: List<BufferEntry>,
    /** All nodes flattened and sorted (for LLM context) */
    val allNodes: List<MemoryNode>
) {
    companion object {
        val EMPTY = RetrievalResult(
            anchors = emptyList(),
            dfsNodes = emptyList(),
            dfsEdgeMap = emptyMap(),
            bufferHits = emptyList(),
            allNodes = emptyList()
        )
    }
}

/** Signals from the 3-stage retrieval gate */
data class GateSignals(
    val linguisticHit: Boolean,
    val probeHit: Boolean,
    val shouldRetrieve: Boolean
) {
    companion object {
        val EMPTY = GateSignals(false, false, false)
    }
}

data class MemoryContext(
    val classification: MemoryClassification?,
    val retrieval: RetrievalResult,
    val recentBuffer: List<BufferEntry>,
    val gateSignals: GateSignals = GateSignals.EMPTY,
    val emotion: String
) {
    val retrievedNodes: List<MemoryNode> get() = retrieval.allNodes

    companion object {
        val EMPTY = MemoryContext(null, RetrievalResult.EMPTY, emptyList(), GateSignals.EMPTY, "neutral")
    }

    fun formatForLlm(): String? {
        val parts = mutableListOf<String>()

        if (retrieval.allNodes.isNotEmpty()) {
            val nodeStrs = retrieval.allNodes.joinToString("\n") { n ->
                "- [${n.category}] ${n.fact}"
            }
            parts.add("REMEMBERED FACTS:\n$nodeStrs")
        }

        if (retrieval.bufferHits.isNotEmpty()) {
            val hitStrs = retrieval.bufferHits.joinToString("\n") { b -> "- ${b.extracted}" }
            parts.add("RECALLED MEMORIES:\n$hitStrs")
        }

        if (recentBuffer.isNotEmpty()) {
            val hitIds = retrieval.bufferHits.map { it.id }.toSet()
            val filtered = recentBuffer.filter { it.id !in hitIds }
            if (filtered.isNotEmpty()) {
                val bufStrs = filtered.joinToString("\n") { b -> "- ${b.extracted}" }
                parts.add("THIS SESSION:\n$bufStrs")
            }
        }

        if (parts.isEmpty()) return null
        return "[MEMORY CONTEXT]\n${parts.joinToString("\n\n")}"
    }
}