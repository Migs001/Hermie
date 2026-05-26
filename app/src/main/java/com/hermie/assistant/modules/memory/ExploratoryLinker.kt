package com.hermie.assistant.modules.memory

import android.util.Log
import com.hermie.assistant.llm.EmbeddingEngine
import com.hermie.assistant.llm.LlmEngine
import com.hermie.assistant.llm.LlamaNativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Phase 4 of sleep-mode processing: Exploratory Graph Linking.
 *
 * Discovers non-obvious cross-category connections by evaluating pairs of nodes
 * whose embeddings fall in the "mid-similarity" band (cosine 0.30–0.55). Nodes
 * that are too similar are already obviously related; nodes that are too dissimilar
 * are likely unrelated. The mid-band contains the interesting candidates.
 *
 * For each candidate pair, the Brain LLM is asked for a yes/no verdict. On yes:
 *  - An edge is created with [MemoryConfig.EXPLORATORY_EDGE_STRENGTH].
 *  - The edge embedding is seeded from the LLM's reason text (via MiniLM-L6-v2),
 *    giving the REMINDRAG traversal engine an immediate retrieval signal.
 *
 * Every evaluated pair is recorded in `exploratory_attempts` so it is never
 * re-evaluated in a future sleep session.
 *
 * The loop runs until the calling coroutine is cancelled (user wakes Hermie)
 * or until all candidate pairs in the current snapshot have been evaluated.
 */
class ExploratoryLinker(
    private val db: MemoryDatabase,
    private val embeddingEngine: EmbeddingEngine?
) {

    companion object {
        private const val TAG = "ExploratoryLinker"

        /** Loaded from assets/prompts/exploratory_link.txt at app start. */
        var LINK_PROMPT: String = ""
    }

    /** Result of one Brain LLM evaluation. */
    private data class Verdict(val related: Boolean, val reason: String?)

    /**
     * Run the exploratory linking loop.
     *
     * @param engine       The LLM engine (Brain) used for verdict generation.
     * @param nativeEngine The native engine, used to set system prompt + reset KV cache.
     *                     May be null if the engine doesn't support it.
     * @param onProgress   Suspend callback for human-readable progress updates.
     * @param onThermalCheck Suspend callback invoked after each pair evaluation;
     *                      the caller can suspend here if the device is too hot.
     * @return Total number of new edges created.
     */
    suspend fun run(
        engine: LlmEngine,
        nativeEngine: LlamaNativeEngine?,
        onProgress: suspend (String) -> Unit,
        onThermalCheck: suspend () -> Unit
    ): Int = withContext(Dispatchers.IO) {

        if (LINK_PROMPT.isEmpty()) {
            onProgress("WARN: exploratory_link.txt not loaded — skipping Phase 4")
            return@withContext 0
        }

        // Load all active nodes that have embeddings
        val nodesWithEmb = db.nodesWithEmbeddingsForLinking()
        if (nodesWithEmb.size < 2) {
            onProgress("Not enough embedded nodes for exploratory linking (${nodesWithEmb.size} found)")
            return@withContext 0
        }

        onProgress("Scanning ${nodesWithEmb.size} embedded nodes for candidate pairs...")

        // Bulk-load pair exclusion sets to avoid per-pair DB queries
        val attemptedKeys = db.loadAllAttemptedPairKeys().toMutableSet()
        val edgeKeys = db.loadAllEdgePairKeys().toMutableSet()

        // Build the full candidate list in memory — O(N²/2) but bounded at 500 nodes = 125K pairs max
        val candidates = mutableListOf<Pair<Int, Int>>() // indices into nodesWithEmb
        for (i in nodesWithEmb.indices) {
            if (!isActive) break
            val (nodeA, embA) = nodesWithEmb[i]
            for (j in i + 1 until nodesWithEmb.size) {
                val (nodeB, embB) = nodesWithEmb[j]

                // Only cross-category pairs — same-category nodes are already well-connected
                if (nodeA.category == nodeB.category) continue

                // Skip pairs already evaluated or already connected
                val key = pairKey(nodeA.id.toLong(), nodeB.id.toLong())
                if (key in attemptedKeys || key in edgeKeys) continue

                // Filter to the mid-similarity band
                val sim = cosineSimilarity(embA, embB)
                if (sim in MemoryConfig.EXPLORATORY_SIM_MIN..MemoryConfig.EXPLORATORY_SIM_MAX) {
                    candidates.add(i to j)
                }
            }
        }

        if (candidates.isEmpty()) {
            onProgress("No candidate pairs found in similarity band [${MemoryConfig.EXPLORATORY_SIM_MIN}, ${MemoryConfig.EXPLORATORY_SIM_MAX}] — Phase 4 complete")
            return@withContext 0
        }

        // Shuffle so repeated sleep sessions cover different parts of the space
        candidates.shuffle()
        onProgress("${candidates.size} candidate pairs to evaluate")

        // Prepare Brain KV cache
        nativeEngine?.setSystemPrompt(LINK_PROMPT)
        nativeEngine?.resetContext()

        var totalLinks = 0
        var evalCount = 0

        for ((i, j) in candidates) {
            if (!isActive) break

            val (nodeA, _) = nodesWithEmb[i]
            val (nodeB, _) = nodesWithEmb[j]

            // Reset KV cache every N evaluations to prevent context bleed
            if (evalCount > 0 && evalCount % MemoryConfig.EXPLORATORY_RESET_EVERY_N == 0) {
                nativeEngine?.resetContext()
                onProgress("  (KV cache reset at evaluation $evalCount)")
            }

            // Re-check pair freshness against in-memory sets (fast, no DB hit)
            val key = pairKey(nodeA.id.toLong(), nodeB.id.toLong())
            if (key in attemptedKeys || key in edgeKeys) continue

            val previewA = nodeA.fact.take(45).let { if (nodeA.fact.length > 45) "$it…" else it }
            val previewB = nodeB.fact.take(45).let { if (nodeB.fact.length > 45) "$it…" else it }
            onProgress("Eval ${evalCount + 1}: [${nodeA.category}] \"$previewA\" ↔ [${nodeB.category}] \"$previewB\"")

            val verdict = askBrain(engine, nodeA.fact, nodeB.fact)

            // Record attempt regardless of verdict
            db.exploratoryAttemptLog(nodeA.id.toLong(), nodeB.id.toLong(), verdict.related)
            attemptedKeys.add(key)

            if (verdict.related) {
                db.edgeCreate(nodeA.id.toLong(), nodeB.id.toLong(), MemoryConfig.EXPLORATORY_EDGE_STRENGTH)
                edgeKeys.add(key)
                totalLinks++

                // Seed the edge embedding from the Brain's reason text
                val reason = verdict.reason
                if (reason != null && embeddingEngine != null) {
                    val reasonEmb = embeddingEngine.embed(reason)
                    if (reasonEmb != null) {
                        val edgeId = db.findEdgeId(nodeA.id.toLong(), nodeB.id.toLong())
                        if (edgeId != null) {
                            db.edgeSetEmbedding(edgeId, reasonEmb)
                        }
                    }
                }

                val reasonPreview = reason?.take(70) ?: "(no reason)"
                onProgress("  ✓ Linked (strength=${MemoryConfig.EXPLORATORY_EDGE_STRENGTH}): $reasonPreview")
            } else {
                onProgress("  ✗ No relation")
            }

            evalCount++
            onThermalCheck()
        }

        // Always leave the KV cache clean
        nativeEngine?.resetContext()

        onProgress("Phase 4 complete: $evalCount pairs evaluated, $totalLinks new links created")
        totalLinks
    }

    // ── Private Helpers ────────────────────────────────────────

    private suspend fun askBrain(engine: LlmEngine, factA: String, factB: String): Verdict {
        val safeA = factA.replace("\\", "\\\\").replace("\"", "\\\"")
        val safeB = factB.replace("\\", "\\\\").replace("\"", "\\\"")
        val userMsg = """{"fact_a":"$safeA","fact_b":"$safeB"}"""

        val messages = listOf(
            LlmEngine.Message("system", LINK_PROMPT),
            LlmEngine.Message("user", userMsg)
        )

        val response = StringBuilder()
        try {
            engine.generate(messages, maxTokens = MemoryConfig.EXPLORATORY_MAX_TOKENS).collect { token ->
                response.append(token)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Brain generation failed for exploratory pair", e)
            return Verdict(false, null)
        }

        return parseVerdict(response.toString())
    }

    private fun parseVerdict(raw: String): Verdict {
        return try {
            val cleaned = raw
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start == -1 || end == -1 || end <= start) {
                Log.w(TAG, "No JSON object found in verdict: ${raw.take(100)}")
                return Verdict(false, null)
            }
            val obj = JSONObject(cleaned.substring(start, end + 1))
            val related = obj.optBoolean("related", false)
            val reason = obj.optString("reason").ifEmpty { null }
            Verdict(related, reason)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse exploratory verdict: ${raw.take(100)}", e)
            Verdict(false, null)
        }
    }

    /** Normalized pair key: always "smallerId-largerId". */
    private fun pairKey(idA: Long, idB: Long): String {
        val minId = minOf(idA, idB)
        val maxId = maxOf(idA, idB)
        return "$minId-$maxId"
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }
}
