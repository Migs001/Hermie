package com.hermie.assistant.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Mock LLM that returns canned responses for UI development.
 * Used for UI development before real model integration.
 */
class MockLlmEngine : LlmEngine {

    override var isLoaded: Boolean = true
        private set

    override suspend fun loadModel(modelPath: String, useTurboCache: Boolean, contextSize: Int) {
        delay(500) // Simulate load time
        isLoaded = true
    }

    override suspend fun unloadModel() {
        isLoaded = false
    }

    private val responses = listOf(
        "Hermie is the greatest! I can help you with that!",
        "Oh boy, that sounds like fun! Let Hermie think about it...",
        "Hermie knows the answer! It is because Hermie is very smart.",
        "Who wants to play video games? Oh wait, you asked a question. Hermie will answer!",
        "Football taught Hermie about this! The answer is yes, probably, maybe!",
        "Hermie is not sure, but Hermie will try very hard for you!",
        "Beep boop! Hermie has computed the response. Here it is!",
        "Hermie loves helping friends! Let me tell you what Hermie thinks.",
        "If you are cold, Hermie can be your friend and also your flashlight!",
        "Hermie is living in the moment. And in this moment, Hermie has an answer!"
    )

    override fun generate(
        messages: List<LlmEngine.Message>,
        maxTokens: Int,
        temperature: Float,
        systemPrompt: String?
    ): Flow<String> = flow {
        // Simulate thinking delay
        delay(800)

        val response = responses.random()
        // Stream token by token
        val words = response.split(" ")
        for (word in words) {
            emit(if (word == words.first()) word else " $word")
            delay((40L..100L).random()) // Simulate token generation speed
        }
    }

    override fun stopGeneration() {
        // No-op for mock
    }
}
