package com.hermie.assistant.llm

import android.content.Context
import android.util.Log
import com.hermie.llamacpp.InferenceEngine
import com.hermie.llamacpp.LlamaCpp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small Language Model engine — stateless classifier running on slot 1.
 *
 * Used as a "mind" for memory classification via the fine-tuned SmolLM2-360M-Instruct-Mem-Cat.
 * Each prediction is independent: context is fully reset, system prompt re-processed,
 * user message classified, output collected, then slot returns to brain.
 *
 * Output format: {"fact":str|null,"retrieve":bool,"tool":bool,"emotion":str}
 *
 * Key design decisions for minimal memory footprint:
 * - Context size: 512 tokens (system prompt + 1 user message + short JSON output)
 * - KV cache cleared before EVERY prediction (no history, no accumulation)
 * - System prompt re-processed each call (tiny at ~50 tokens, takes <5ms)
 * - No chat history tracking (each call is independent)
 */
class MindLlmEngine(context: Context) : LlmEngine {

    companion object {
        private const val TAG = "MindEngine"
        private const val SLOT = 1
    }

    private val engine: InferenceEngine = LlamaCpp.getInferenceEngine(context)
    private val loadMutex = Mutex()

    /**
     * Held for the duration of each generate() flow collection.
     * Acquire (then immediately release) from outside to wait for in-flight generation.
     * Used by HermieBackgroundService for graceful shutdown.
     */
    val generationMutex = Mutex()

    @Volatile
    private var currentModelPath: String? = null

    @Volatile
    private var slmReady = false

    override val isLoaded: Boolean get() = slmReady

    override suspend fun loadModel(modelPath: String, useTurboCache: Boolean, contextSize: Int) {
        val file = java.io.File(modelPath)
        if (!file.exists()) {
            throw RuntimeException("SLM model file not found: $modelPath")
        }

        if (slmReady && currentModelPath == modelPath) {
            Log.d(TAG, "SLM already loaded at $modelPath — skipping")
            return
        }

        loadMutex.withLock {
            // Re-check after acquiring lock — another coroutine may have loaded while we waited
            if (slmReady && currentModelPath == modelPath) {
                Log.d(TAG, "SLM already loaded (post-lock check) — skipping")
                return
            }

            // Acquire shared slot mutex to prevent brain from interleaving
            LlamaNativeEngine.slotMutex.withLock {
                try {
                    Log.d(TAG, "Loading SLM on slot $SLOT (ctx=$contextSize): $modelPath")
                    engine.setActiveSlot(SLOT)

                    // If slot already has a model loaded (e.g. from a concurrent load),
                    // reset it back to Initialized so loadModel() can proceed cleanly
                    val slotState = engine.state.value
                    if (slotState is InferenceEngine.State.ModelReady) {
                        Log.d(TAG, "Slot $SLOT already in ModelReady — cleaning up first")
                        engine.cleanUp()
                    }

                    currentModelPath = modelPath
                    engine.loadModel(modelPath, false, contextSize)
                    Log.d(TAG, "SLM loaded on slot $SLOT")
                    slmReady = true
                    engine.setActiveSlot(0)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load SLM", e)
                    currentModelPath = null
                    slmReady = false
                    try { engine.setActiveSlot(0) } catch (_: Exception) {}
                    throw RuntimeException("Failed to load SLM: ${e.message}", e)
                }
            }
        }
    }

    override suspend fun unloadModel() {
        loadMutex.withLock {
            LlamaNativeEngine.slotMutex.withLock {
                try {
                    engine.setActiveSlot(SLOT)
                    engine.cleanUp()
                    engine.setActiveSlot(0)
                } catch (e: Exception) {
                    Log.w(TAG, "Error during SLM unload", e)
                    try { engine.setActiveSlot(0) } catch (_: Exception) {}
                }
                currentModelPath = null
                slmReady = false
                Log.d(TAG, "SLM unloaded")
            }
        }
    }

    /**
     * Run a single stateless prediction. Each call:
     * 1. Switches to SLM slot
     * 2. Clears KV cache + all state (fresh start every time)
     * 3. Processes system prompt (if set — e.g., <MEM> classifier prefix)
     * 4. Processes user input and generates output
     * 5. Switches back to brain slot
     *
     * No history is retained between calls.
     *
     * The slot mutex is held for the entire operation to prevent any concurrent
     * brain operations from switching slots mid-generation. This is safe because
     * the brain's generate() does NOT acquire the slot mutex — it runs entirely
     * on slot 0 without switching. The mutex only guards against concurrent
     * loadModel/resetContext/unload calls which are rare.
     */
    override fun generate(
        messages: List<LlmEngine.Message>,
        maxTokens: Int,
        temperature: Float,
        systemPrompt: String?
    ): Flow<String> {
        if (!slmReady) {
            Log.w(TAG, "Generate called but SLM not loaded")
            return emptyFlow()
        }

        val lastUserMsg = messages.lastOrNull { it.role == "user" }?.content ?: return emptyFlow()

        return flow {
            generationMutex.withLock {
            LlamaNativeEngine.slotMutex.withLock {
                engine.setActiveSlot(SLOT)
                try {
                    // Check slot state — if Error, clean up first
                    val slotState = engine.state.value
                    if (slotState is InferenceEngine.State.Error) {
                        Log.w(TAG, "Slot $SLOT in Error state — resetting")
                        try { engine.cleanUp() } catch (_: Exception) {}
                        if (currentModelPath != null) {
                            engine.loadModel(currentModelPath!!, false, 256)
                        } else {
                            Log.e(TAG, "Cannot recover slot $SLOT — no model path")
                            return@withLock
                        }
                    }

                    // Reset all context state — stateless classifier, no history
                    engine.resetSlotContext()

                    // Process system prompt if provided for this call
                    if (!systemPrompt.isNullOrBlank()) {
                        engine.setSystemPrompt(systemPrompt)
                    }

                    val startTime = System.currentTimeMillis()
                    engine.sendUserPrompt(lastUserMsg, maxTokens).collect { token ->
                        emit(token)
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "SLM prediction completed in ${elapsed}ms")
                } finally {
                    engine.setActiveSlot(0)
                }
            }
            } // generationMutex
        }
    }

    override fun stopGeneration() {
        Log.d(TAG, "Stop SLM generation requested")
    }
}
