package com.hermie.assistant.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.nehuatl.llamacpp.LlamaHelper
import org.nehuatl.llamacpp.LlamaHelper.LLMEvent
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LLM engine backed by kotlinllamacpp 0.2.0 (pre-built llama.cpp with ARM NEON/i8mm).
 *
 * Uses LlamaHelper with event-based streaming via MutableSharedFlow<LLMEvent>.
 * The pre-built native libs include hardware-optimized SIMD for fast inference.
 */
class LlamaNativeEngine(private val context: Context) : LlmEngine {

    companion object {
        private const val TAG = "LlamaEngine"
        private const val CONTEXT_SIZE = 2048
    }

    override var isLoaded: Boolean = false
        private set

    private var chatFormat: ChatFormat = ChatFormat.CHATML
    private enum class ChatFormat { CHATML, LLAMA3 }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val eventFlow = MutableSharedFlow<LLMEvent>(
        extraBufferCapacity = 512
    )
    private val contentResolver = context.contentResolver
    private var helper: LlamaHelper = LlamaHelper(contentResolver, scope, eventFlow)

    // ── LlmEngine implementation ────────────────────────────

    override suspend fun loadModel(modelPath: String) {
        // Verify model file exists and is readable
        val file = java.io.File(modelPath)
        if (!file.exists()) {
            throw RuntimeException("Model file not found: $modelPath")
        }
        if (!file.canRead()) {
            throw RuntimeException("Model file not readable: $modelPath")
        }
        Log.d(TAG, "Model file verified: ${file.length()} bytes at $modelPath")

        detectChatFormat(modelPath)

        // Release previous model if any
        if (isLoaded) {
            helper.release()
            helper = LlamaHelper(contentResolver, scope, eventFlow)
        }

        // Pass the raw file path — models are in app-private storage so no
        // ContentResolver / FileProvider needed. The content:// URI approach was
        // producing file descriptor numbers (e.g. "130") that crash native code.
        val absolutePath = file.absolutePath
        Log.d(TAG, "Loading model: $absolutePath (ctx=$CONTEXT_SIZE)")

        // v0.2.0 load() is not suspend — it takes a callback
        return suspendCancellableCoroutine { cont ->
            try {
                helper.load(absolutePath, CONTEXT_SIZE) { contextId ->
                    Log.d(TAG, "Model loaded successfully, context ID: $contextId")
                    isLoaded = true
                    if (cont.isActive) cont.resume(Unit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                if (cont.isActive) cont.resumeWithException(
                    RuntimeException("Failed to load model: ${e.message}", e)
                )
            }

            // Also listen for error events during load
            val errorJob = scope.launch {
                eventFlow.collect { event ->
                    if (event is LLMEvent.Error && cont.isActive) {
                        Log.e(TAG, "Load error event: ${event.message}")
                        cont.resumeWithException(RuntimeException("Load failed: ${event.message}"))
                    }
                }
            }

            cont.invokeOnCancellation {
                errorJob.cancel()
            }
        }
    }

    override suspend fun unloadModel() {
        helper.release()
        helper = LlamaHelper(contentResolver, scope, eventFlow)
        isLoaded = false
        Log.d(TAG, "Model unloaded")
    }

    override fun generate(
        messages: List<LlmEngine.Message>,
        maxTokens: Int,
        temperature: Float
    ): Flow<String> = callbackFlow {
        if (!isLoaded) {
            Log.w(TAG, "Generate called but model not loaded")
            close()
            return@callbackFlow
        }

        val prompt = formatPrompt(messages)
        Log.d(TAG, "Generate called, prompt length=${prompt.length}")

        var insideThink = false

        // Collect LLMEvents from the shared flow
        val collectJob = launch {
            try {
                eventFlow.collect { event ->
                    when (event) {
                        is LLMEvent.Ongoing -> {
                            val token = event.word

                            // Filter special tokens (ChatML + Llama3)
                            if (token.contains("<|im_end|>") ||
                                token.contains("<|im_start|>") ||
                                token.contains("<|endoftext|>") ||
                                token.contains("<|eot_id|>") ||
                                token.contains("<|end_of_text|>") ||
                                token.contains("<|start_header_id|>") ||
                                token.contains("<|end_header_id|>")
                            ) return@collect

                            // Filter Qwen3 thinking blocks
                            if (token.contains("<think>")) { insideThink = true; return@collect }
                            if (token.contains("</think>")) { insideThink = false; return@collect }
                            if (insideThink) return@collect

                            trySend(token)
                        }
                        is LLMEvent.Done -> {
                            Log.d(TAG, "Generation done: ${event.tokenCount} tokens in ${event.duration}ms")
                            close()
                        }
                        is LLMEvent.Error -> {
                            Log.e(TAG, "Generation error: ${event.message}")
                            close(RuntimeException(event.message))
                        }
                        is LLMEvent.Started -> {
                            Log.d(TAG, "Generation started")
                        }
                        is LLMEvent.Loaded -> {
                            // Ignore load events during generation
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Event collection error", e)
                close(e)
            }
        }

        // Start prediction
        helper.predict(prompt, true)

        awaitClose {
            helper.stopPrediction()
            collectJob.cancel()
        }
    }

    override fun stopGeneration() {
        helper.stopPrediction()
    }

    // ── Chat format ─────────────────────────────────────────

    private fun detectChatFormat(modelPath: String) {
        chatFormat = when {
            modelPath.lowercase().contains("nemotron") ||
            modelPath.lowercase().contains("llama-3") -> ChatFormat.LLAMA3
            else -> ChatFormat.CHATML
        }
    }

    private fun formatPrompt(messages: List<LlmEngine.Message>): String {
        return when (chatFormat) {
            ChatFormat.CHATML -> formatChatML(messages)
            ChatFormat.LLAMA3 -> formatLlama3(messages)
        }
    }

    private fun formatChatML(messages: List<LlmEngine.Message>): String {
        val sb = StringBuilder()
        for ((i, msg) in messages.withIndex()) {
            if (i == messages.lastIndex && msg.role == "assistant") {
                sb.append("<|im_start|>${msg.role}\n${msg.content}")
            } else {
                sb.append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
            }
        }
        if (messages.lastOrNull()?.role != "assistant") {
            sb.append("<|im_start|>assistant\n")
        }
        return sb.toString()
    }

    private fun formatLlama3(messages: List<LlmEngine.Message>): String {
        val sb = StringBuilder("<|begin_of_text|>")
        for ((i, msg) in messages.withIndex()) {
            if (i == messages.lastIndex && msg.role == "assistant") {
                sb.append("<|start_header_id|>${msg.role}<|end_header_id|>\n\n${msg.content}")
            } else {
                sb.append("<|start_header_id|>${msg.role}<|end_header_id|>\n\n${msg.content}<|eot_id|>")
            }
        }
        if (messages.lastOrNull()?.role != "assistant") {
            sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }
        return sb.toString()
    }
}
