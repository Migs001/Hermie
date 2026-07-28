package com.hermie.llamacpp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(pathToModel: String, useTurboCache: Boolean, contextSize: Int)

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Load a mmproj (CLIP vision encoder) model for multimodal/vision support.
     * Must be called after loadModel(). Returns true on success.
     */
    suspend fun loadMmproj(mmprojPath: String): Boolean

    /**
     * Whether the loaded model supports vision (image) input.
     */
    val hasVision: Boolean

    /**
     * Send a user prompt with an attached image.
     * The image must be raw RGB bytes (width * height * 3).
     * Returns a Flow of generated tokens, same as sendUserPrompt.
     */
    fun sendUserPromptWithImage(
        message: String,
        imageRgb: ByteArray,
        width: Int,
        height: Int,
        predictLength: Int = DEFAULT_PREDICT_LENGTH
    ): Flow<String>

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Switch the active model slot (0 = main brain, 1 = small mind).
     * All subsequent operations (load, prompt, generate) target this slot.
     * This is thread-safe and serialized with other engine operations.
     */
    suspend fun setActiveSlot(slot: Int)

    /**
     * Reset the active slot's KV cache, chat history, and positions without
     * unloading the model. Used for stateless classifier-style slots that
     * don't maintain conversation history between calls.
     */
    suspend fun resetSlotContext()

    /**
     * Prefill the active slot's KV cache with conversation history.
     * Call after resetSlotContext + setSystemPrompt to restore a conversation's
     * context. Messages are formatted with the chat template and decoded.
     *
     * @param roles     Array of roles ("user", "assistant")
     * @param contents  Array of message contents
     * @return 0 on success, non-zero on error
     */
    suspend fun prefillHistory(roles: Array<String>, contents: Array<String>): Int

    /**
     * Cancel any ongoing token generation.
     * The next iteration of the generate loop will stop.
     * Unlike cleanUp(), this does NOT unload the model.
     */
    fun cancelGeneration()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    // ── Multi-slot / shared context / LoRA ──

    /**
     * Create a new llama_context on the target slot, sharing the source slot's model.
     * The target slot gets its own sampler, batch, and chat templates.
     * Returns true on success.
     */
    suspend fun createSharedContext(sourceSlot: Int, targetSlot: Int, contextSize: Int, useTurboCache: Boolean = false): Boolean

    /**
     * Destroy a slot's context (and model if it owns it).
     */
    suspend fun destroySlotContext(slot: Int)

    /**
     * Load a LoRA adapter from a GGUF file. Returns an opaque handle (0 on failure).
     */
    suspend fun loadLoraAdapter(adapterPath: String): Long

    /**
     * Apply a loaded LoRA adapter to the active slot's context.
     * Returns true on success.
     */
    suspend fun applyLoraAdapter(adapterHandle: Long, scale: Float = 1.0f): Boolean

    /**
     * Remove a LoRA adapter from the active slot's context (does not free the handle).
     */
    suspend fun removeLoraAdapter(adapterHandle: Long)

    /**
     * Free a LoRA adapter handle. Must be removed from all contexts first.
     */
    suspend fun freeLoraAdapter(adapterHandle: Long)

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val InferenceEngine.State.isUninterruptible
    get() = this is InferenceEngine.State.Initializing ||
        this is InferenceEngine.State.LoadingModel ||
        this is InferenceEngine.State.UnloadingModel ||
        this is InferenceEngine.State.Benchmarking ||
        this is InferenceEngine.State.ProcessingSystemPrompt ||
        this is InferenceEngine.State.ProcessingUserPrompt

val InferenceEngine.State.isModelLoaded: Boolean
    get() = this is InferenceEngine.State.ModelReady ||
        this is InferenceEngine.State.Benchmarking ||
        this is InferenceEngine.State.ProcessingSystemPrompt ||
        this is InferenceEngine.State.ProcessingUserPrompt ||
        this is InferenceEngine.State.Generating

class UnsupportedArchitectureException : Exception()
