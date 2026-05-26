package com.hermie.assistant.llm

import android.content.Context
import android.util.Log
import com.hermie.llamacpp.InferenceEngine
import com.hermie.llamacpp.LlamaCpp
import com.hermie.llamacpp.isModelLoaded
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LLM engine backed by llama.cpp built from source (latest, supports Qwen 2.5 + Qwen 3).
 *
 * Uses the official llama.cpp Android library with:
 * - ARM NEON/i8mm/SVE hardware-optimized inference
 * - Automatic chat template formatting
 * - Context shifting for long conversations
 * - Streaming token generation via Kotlin Flow
 */
class LlamaNativeEngine(context: Context) : LlmEngine {

    companion object {
        private const val TAG = "LlamaEngine"

        /**
         * Prefix for thinking tokens emitted from generate().
         * The ViewModel uses this to separate thinking content from the actual response.
         */
        const val THINK_PREFIX = "\u0000THINK:"

        /**
         * Shared mutex that serializes slot-level operations across brain (slot 0)
         * and SLM (slot 1). Without this, MindLlmEngine's multi-step generate flow
         * (setActiveSlot→resetContext→setSystemPrompt→generate→setActiveSlot) can
         * interleave with LlamaNativeEngine's loadModel/generate/resetContext calls,
         * causing system prompts to land on the wrong slot.
         */
        val slotMutex = Mutex()
    }

    private val engine: InferenceEngine = LlamaCpp.getInferenceEngine(context)

    /**
     * Mutex to serialize all load/unload operations.
     * Prevents "Cannot load model in ModelReady!" race conditions
     * when multiple coroutines try to load concurrently.
     */
    private val loadMutex = Mutex()

    /** Track the path of the currently loaded (or loading) model to skip redundant loads */
    @Volatile
    private var currentModelPath: String? = null

    /** Context window size recorded at load time — used for usage estimation. */
    @Volatile private var loadedContextSize: Int = 8192

    /**
     * Crude running estimate of chars submitted to the engine since the last context reset.
     * chars / 4 ≈ token count. Resets in [resetContext] and [resetAndReplayHistory].
     * Used by [approximateContextUsedPct] to detect context pressure.
     */
    @Volatile private var charsSinceReset: Long = 0L

    /**
     * isLoaded is derived from the actual native engine state.
     * This handles the case where the Activity is recreated (new ViewModel) but
     * the native singleton survives (process didn't die) — avoids "Cannot load
     * model in ModelReady!" crashes.
     */
    override val isLoaded: Boolean
        get() = engine.state.value.isModelLoaded

    /**
     * True while loadModel() is executing. Drip atomizer and other slot-sensitive
     * operations check this to avoid interleaving with an in-progress model load.
     */
    @Volatile
    var isModelLoading: Boolean = false
        private set

    /**
     * Whether the engine already has a system prompt loaded and is fully ready.
     * Distinguishes ModelReady-after-system-prompt from ModelReady-before-system-prompt.
     */
    val isReady: Boolean
        get() = engine.state.value is InferenceEngine.State.ModelReady

    // System prompt to set after model load
    private var pendingSystemPrompt: String? = null

    override suspend fun loadModel(modelPath: String, useTurboCache: Boolean, contextSize: Int) {
        val file = java.io.File(modelPath)
        if (!file.exists()) {
            throw RuntimeException("Model file not found: $modelPath")
        }
        isModelLoading = true

        // Fast path: if this exact model is already loaded, skip
        if (isLoaded && currentModelPath == modelPath) {
            Log.d(TAG, "Model already loaded at $modelPath — skipping")
            return
        }

        // Serialize all load/unload operations
        loadMutex.withLock {
            // Re-check after acquiring lock (another coroutine may have loaded it)
            if (isLoaded && currentModelPath == modelPath) {
                Log.d(TAG, "Model already loaded (checked after lock) — skipping")
                return
            }

            Log.d(TAG, "Model file verified: ${file.length()} bytes at $modelPath")

            // If the native engine already has a model loaded, clean up first
            if (engine.state.value.isModelLoaded) {
                Log.d(TAG, "Native engine already has a model loaded — cleaning up first")
                try {
                    engine.cleanUp()
                } catch (e: Exception) {
                    Log.w(TAG, "Error during cleanup before reload", e)
                }
            }

            // If the engine is in error state, also clean up to get back to Initialized
            if (engine.state.value is InferenceEngine.State.Error) {
                Log.d(TAG, "Native engine in error state — resetting")
                try {
                    engine.cleanUp()
                } catch (e: Exception) {
                    Log.w(TAG, "Error resetting engine state", e)
                }
            }

            try {
                // Hold slotMutex to prevent SLM from switching slots between
                // loadModel and setSystemPrompt (which would cause the brain's
                // system prompt to land on slot 1)
                slotMutex.withLock {
                    Log.d(TAG, "Loading model: $modelPath (engine state: ${engine.state.value})")
                    currentModelPath = modelPath
                    loadedContextSize = contextSize
                    charsSinceReset = 0L
                    engine.loadModel(modelPath, useTurboCache, contextSize)
                    Log.d(TAG, "Model loaded successfully")

                    // Set system prompt immediately after load (required by the engine)
                    val prompt = pendingSystemPrompt
                    if (!prompt.isNullOrBlank()) {
                        Log.d(TAG, "Setting system prompt (${prompt.length} chars)")
                        engine.setSystemPrompt(prompt)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                currentModelPath = null
                throw RuntimeException("Failed to load model: ${e.message}", e)
            } finally {
                isModelLoading = false
            }
        }
    }

    /** Pre-set the system prompt before loading the model */
    fun setSystemPrompt(prompt: String) {
        pendingSystemPrompt = prompt
    }

    /**
     * Load a mmproj (CLIP vision encoder) model to enable image/vision input.
     * Must be called after the main model is loaded.
     */
    suspend fun loadMmproj(mmprojPath: String): Boolean {
        if (!isLoaded) {
            Log.w(TAG, "Cannot load mmproj — model not loaded")
            return false
        }
        return try {
            val success = engine.loadMmproj(mmprojPath)
            Log.d(TAG, "mmproj loaded: $success (hasVision=${engine.hasVision})")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load mmproj", e)
            false
        }
    }

    /**
     * Sync the loaded model path when the native engine already has a model loaded
     * (e.g., Activity recreated but process survived). Prevents unnecessary reloads.
     */
    fun syncLoadedPath(path: String) {
        if (isLoaded && path.isNotBlank()) {
            currentModelPath = path
            Log.d(TAG, "Synced loaded path: $path")
        }
    }

    override suspend fun unloadModel() {
        loadMutex.withLock {
            try {
                engine.cleanUp()
                currentModelPath = null
            } catch (e: Exception) {
                Log.w(TAG, "Error during unload", e)
            }
            Log.d(TAG, "Model unloaded")
        }
    }

    override val hasVision: Boolean
        get() = isLoaded && engine.hasVision

    override fun generate(
        messages: List<LlmEngine.Message>,
        maxTokens: Int,
        temperature: Float,
        systemPrompt: String?
    ): Flow<String> {
        if (!isLoaded) {
            Log.w(TAG, "Generate called but model not loaded (state=${engine.state.value})")
            return emptyFlow()
        }

        // Extract the last user message — the engine handles chat history internally
        val lastUserMsg = messages.lastOrNull { it.role == "user" } ?: return emptyFlow()

        // Accumulate char count for approximate context-usage tracking
        charsSinceReset += lastUserMsg.content.length.toLong()

        Log.d(TAG, "Generate called, user message length=${lastUserMsg.content.length}, maxTokens=$maxTokens")

        // Check if the last user message has an image and vision is available
        val hasImage = lastUserMsg.imageRgb != null && lastUserMsg.imageRgb.isNotEmpty()

        // The engine's sendUserPrompt handles tokenization, decoding, and streaming.
        // We separate <think> blocks from the actual response:
        // - Thinking tokens are emitted with THINK_PREFIX so the ViewModel can
        //   collect them into a separate thinkingContent field
        // - Regular tokens are emitted as-is
        // The C++ layer prefills an empty think block to discourage thinking,
        // but if the model still thinks, the user sees it in a dropdown rather
        // than staring at an apparently frozen screen.
        return flow {
            var insideThink = false

            val tokenFlow = if (hasImage && engine.hasVision) {
                Log.d(TAG, "Using vision path: ${lastUserMsg.imageWidth}x${lastUserMsg.imageHeight}")
                engine.sendUserPromptWithImage(
                    lastUserMsg.content,
                    lastUserMsg.imageRgb!!,
                    lastUserMsg.imageWidth,
                    lastUserMsg.imageHeight,
                    maxTokens
                )
            } else {
                if (hasImage && !engine.hasVision) {
                    Log.w(TAG, "Image attached but no vision model loaded — sending text only")
                }
                engine.sendUserPrompt(lastUserMsg.content, maxTokens)
            }

            tokenFlow.collect { token ->
                if (token.contains("<think>")) {
                    insideThink = true
                    return@collect
                }
                if (token.contains("</think>")) {
                    insideThink = false
                    return@collect
                }
                if (insideThink) {
                    // Emit thinking tokens with prefix so ViewModel can route them
                    emit(THINK_PREFIX + token)
                } else {
                    emit(token)
                }
            }
        }
    }

    override fun stopGeneration() {
        engine.cancelGeneration()
        Log.d(TAG, "Stop generation requested — native cancel flag set")
    }

    /**
     * Reset the brain slot's KV cache and chat history.
     * Call when switching conversations to prevent context bleedthrough.
     * The system prompt is re-processed so the model starts fresh.
     */
    suspend fun resetContext() {
        if (!isLoaded) return
        slotMutex.withLock {
            try {
                engine.resetSlotContext()
                val prompt = pendingSystemPrompt
                if (!prompt.isNullOrBlank()) {
                    engine.setSystemPrompt(prompt)
                }
                charsSinceReset = 0L
                Log.d(TAG, "Brain context reset (KV cache cleared, system prompt re-set)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reset brain context", e)
            }
        }
    }

    /**
     * Reset the brain context AND replay conversation history into the KV cache.
     * Use when switching conversations — resets the KV cache, re-sends the system
     * prompt, then prefills all previous messages so the model has full context.
     *
     * @param messages List of (role, content) pairs representing the conversation
     */
    suspend fun resetAndReplayHistory(messages: List<Pair<String, String>>) {
        if (!isLoaded) return
        slotMutex.withLock {
            try {
                // 1. Clear KV cache
                engine.resetSlotContext()

                // 2. Re-send system prompt
                val prompt = pendingSystemPrompt
                if (!prompt.isNullOrBlank()) {
                    engine.setSystemPrompt(prompt)
                }

                // 3. Prefill conversation history
                if (messages.isNotEmpty()) {
                    val roles = messages.map { it.first }.toTypedArray()
                    val contents = messages.map { it.second }.toTypedArray()
                    val result = engine.prefillHistory(roles, contents)
                    Log.d(TAG, "History replay: ${messages.size} messages, result=$result")
                }

                charsSinceReset = 0L
                Log.d(TAG, "Brain context reset + history replayed (${messages.size} messages)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reset and replay history", e)
            }
        }
    }

    /**
     * Returns a 0–1 estimate of how full the active context window is.
     *
     * Uses a char-based heuristic (chars / 4 ≈ tokens) reset on every
     * [resetContext] or [resetAndReplayHistory] call. Not exact, but good
     * enough to catch the "context about to overflow" case before the native
     * layer attempts an auto-shift (which crashes on hybrid KV-cache builds).
     */
    fun approximateContextUsedPct(): Float {
        if (loadedContextSize <= 0) return 0f
        val estimatedTokens = charsSinceReset / 4L
        return (estimatedTokens.toFloat() / loadedContextSize).coerceIn(0f, 1f)
    }
}
