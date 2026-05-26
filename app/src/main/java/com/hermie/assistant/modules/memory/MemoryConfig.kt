package com.hermie.assistant.modules.memory

/**
 * Centralized configuration for the memory module.
 * All tunable parameters in one place for easy tweaking during development.
 *
 * To adjust behavior, change values here — no need to hunt through multiple files.
 *
 * v5 changes:
 * - Split consolidation batch sizes: personal (20) vs study (8)
 * - Removed importance-based constants (decay, floor, deactivation thresholds)
 * - New deactivation signal: access_count=0 AND stale for DEACTIVATE_STALE_DAYS
 */
object MemoryConfig {

    // ── Embedding ──────────────────────────────────────────────
    /** Dimension of MiniLM-L6-v2 embeddings */
    const val EMBEDDING_DIM = 384

    /** Max input tokens for the tokenizer (MiniLM max is 256 wordpiece tokens) */
    const val EMBEDDING_MAX_TOKENS = 128

    // ── Retrieval ──────────────────────────────────────────────
    /** Top N embedding matches to return from vector search (nodes + buffer combined) */
    const val RETRIEVAL_TOP_K_EMBEDDING = 3

    /** Max total nodes returned to the LLM after dedup + sort */
    const val RETRIEVAL_MAX_NODES = 9

    /** Keyword search fallback limit (used when embeddings aren't ready) */
    const val RETRIEVAL_KEYWORD_LIMIT = 5

    /** Minimum cosine similarity to count as a match (raised from 0.3 to reduce noise) */
    const val RETRIEVAL_MIN_SIMILARITY = 0.55f

    // ── Retrieval Gate ─────────────────────────────────────────
    /** Minimum embedding similarity for a "probe hit" in the retrieval gate */
    const val GATE_PROBE_THRESHOLD = 0.5f

    /** Number of top probe results to check */
    const val GATE_PROBE_TOP_K = 3

    /** Linguistic keywords that force retrieval regardless of embedding score */
    val GATE_LINGUISTIC_TRIGGERS = setOf(
        "remember", "recall", "forgot", "forget", "told you", "mentioned",
        "last time", "before", "earlier", "previously", "my name", "my favorite",
        "i like", "i love", "i hate", "i prefer", "do you know", "what did i",
        "you said", "we talked", "we discussed", "remind me"
    )

    // ── DFS Retrieval ──────────────────────────────────────────
    /** Weight for node similarity vs edge alignment in DFS (0=pure edge, 1=pure node) */
    const val DFS_ALPHA = 0.2f

    /** Minimum score for DFS to expand through an edge (raised from 0.35) */
    const val DFS_THRESHOLD = 0.5f

    /** Maximum DFS depth from each seed node */
    const val DFS_MAX_DEPTH = 4

    // ── Edge Reinforcement ─────────────────────────────────────
    /** Min response-to-node similarity to count as "relevant" for edge enhancement */
    const val REINFORCE_RELEVANCE_THRESHOLD = 0.4f

    /** Dead zone: similarities between (RELEVANCE_THRESHOLD - DEAD_ZONE) and RELEVANCE_THRESHOLD are skipped */
    const val REINFORCE_DEAD_ZONE = 0.1f

    // ── Buffer ─────────────────────────────────────────────────
    /** How many recent buffer entries to include in short-term context */
    const val RECENT_BUFFER_LIMIT = 5

    /** Batch size for background embedding of new buffer entries */
    const val EMBEDDING_BATCH_SIZE = 10

    // ── Consolidation ──────────────────────────────────────────
    /** Batch size for personal fact consolidation — 1 entry at a time for quality */
    const val CONSOLIDATION_BATCH_SIZE_PERSONAL = 1

    /** Batch size for study material consolidation — 1 entry at a time for quality */
    const val CONSOLIDATION_BATCH_SIZE_STUDY = 1

    /** Max tokens for consolidation LLM response */
    const val CONSOLIDATION_MAX_TOKENS = 1024

    // ── Drip Atomizer ──────────────────────────────────────────
    /** How many raw messages to feed the SLM per drip batch */
    const val DRIP_BATCH_SIZE = 5

    /** Max tokens for SLM drip atomizer output */
    const val DRIP_MAX_TOKENS = 256

    /** Minimum interval between drip runs (ms) — prevents thrashing */
    const val DRIP_MIN_INTERVAL_MS = 30_000L  // 30 seconds

    /**
     * Drip will not run within this window after the user's last message.
     * Prevents atomization during an active conversation.
     */
    const val DRIP_IDLE_WINDOW_MS = 2 * 60 * 1000L  // 2 minutes

    // ── Pruning ────────────────────────────────────────────────
    /** Edge decay amount per pruning cycle */
    const val PRUNE_EDGE_DECAY = 2

    /** Minimum edge strength floor */
    const val PRUNE_EDGE_FLOOR = 5

    /** Days since last access with zero accesses before a node is deactivated */
    const val DEACTIVATE_STALE_DAYS = 60

    // ── Capacity ───────────────────────────────────────────────
    /** Max active nodes in DB — encourages more aggressive pruning when exceeded */
    const val MAX_ACTIVE_NODES = 500

    /** When node count exceeds this, log a capacity warning */
    const val PRUNE_PRESSURE_THRESHOLD = 400

    // ── Deduplication ─────────────────────────────────────────
    /** Jaccard/cosine similarity threshold above which a fact is considered duplicate */
    const val DEDUP_SIMILARITY_THRESHOLD = 0.75f

    // ── SLM Classification (kept for drip atomizer) ────────────
    /** Max tokens for SLM classifier output (short JSON ~30-50 tokens) */
    const val SLM_MAX_TOKENS = 64

    // ── Source Types ───────────────────────────────────────────
    /** Node was extracted from personal conversation */
    const val SOURCE_PERSONAL = "personal"

    /** Node was extracted from study material */
    const val SOURCE_STUDY = "study"

    /** Node is a high-importance anchor from study material */
    const val SOURCE_STUDY_ANCHOR = "study_anchor"

    // ── Exploratory Linking (Phase 4 of sleep mode) ────────────
    /** Minimum cosine similarity for exploratory candidate pairs */
    const val EXPLORATORY_SIM_MIN = 0.30f

    /**
     * Maximum cosine similarity for exploratory candidate pairs.
     * Above this, nodes are already semantically close — no novel link needed.
     */
    const val EXPLORATORY_SIM_MAX = 0.55f

    /** Strength assigned to edges created by exploratory linking */
    const val EXPLORATORY_EDGE_STRENGTH = 40

    /** Reset Brain KV cache every N evaluations during exploratory linking */
    const val EXPLORATORY_RESET_EVERY_N = 20

    /** Max tokens for the exploratory link verdict (short JSON) */
    const val EXPLORATORY_MAX_TOKENS = 64
}
