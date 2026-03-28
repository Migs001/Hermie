package com.hermie.assistant.llm

import kotlinx.coroutines.flow.Flow

/**
 * Interface for LLM inference backends.
 * Implementations: MockLlmEngine (dev), LlamaEngine (production with llama.cpp)
 */
interface LlmEngine {

    /** Whether a model is currently loaded and ready */
    val isLoaded: Boolean

    /** Load model from the given file path */
    suspend fun loadModel(modelPath: String)

    /** Unload the current model and free resources */
    suspend fun unloadModel()

    /**
     * Generate a response given conversation messages.
     * Returns a Flow of token strings for streaming UI updates.
     */
    fun generate(
        messages: List<Message>,
        maxTokens: Int = 512,
        temperature: Float = 0.7f
    ): Flow<String>

    /** Stop an in-progress generation */
    fun stopGeneration()

    data class Message(
        val role: String,  // "system", "user", "assistant"
        val content: String
    )
}
