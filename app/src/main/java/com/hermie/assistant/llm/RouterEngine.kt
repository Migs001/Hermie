package com.hermie.assistant.llm

import android.util.Log
import com.hermie.llamacpp.InferenceEngine
import kotlinx.coroutines.sync.withLock

/**
 * Router engine — runs the QLoRA tool-routing model on slot 2.
 *
 * Shares the Brain's base model (loaded on slot 0). The LoRA adapter
 * is applied to slot 2's context to specialize it for tool routing.
 *
 * Context is reset every call (stateless classifier). The LoRA adapter
 * stays applied for the lifetime of the engine.
 *
 * Output: JSON string like {"tool":"calendar_add","args":{...}}
 * Caller parses this into a ToolCall.
 */
class RouterEngine(private val engine: InferenceEngine) {

    companion object {
        const val SLOT = 2
        const val CONTEXT_SIZE = 512
        private const val TAG = "RouterEngine"
        private const val MAX_TOKENS = 264
    }

    @Volatile
    private var ready = false

    private var adapterHandle: Long = 0L
    private var systemPrompt: String = ""

    /**
     * True once the shared context and LoRA adapter are initialized.
     */
    val isReady: Boolean get() = ready

    /**
     * Called once after Brain model is loaded on slot 0.
     * Creates a shared context on slot 2 and applies the LoRA adapter.
     *
     * @param adapterPath         Path to the LoRA adapter GGUF file
     * @param routerSystemPrompt  System prompt for the router classifier
     */
    suspend fun initialize(adapterPath: String, routerSystemPrompt: String) {
        LlamaNativeEngine.slotMutex.withLock {
            try {
                Log.d(TAG, "Initializing router on slot $SLOT")

                // Create shared context from Brain's model
                engine.setActiveSlot(SLOT)
                val ok = engine.createSharedContext(
                    sourceSlot = 0,
                    targetSlot = SLOT,
                    contextSize = CONTEXT_SIZE,
                    useTurboCache = false
                )
                if (!ok) {
                    Log.e(TAG, "Failed to create shared context on slot $SLOT")
                    engine.setActiveSlot(0)
                    return
                }

                // Load and apply LoRA adapter
                adapterHandle = engine.loadLoraAdapter(adapterPath)
                if (adapterHandle == 0L) {
                    Log.e(TAG, "Failed to load LoRA adapter from $adapterPath")
                    engine.setActiveSlot(0)
                    return
                }
                engine.applyLoraAdapter(adapterHandle)

                engine.setActiveSlot(0)  // restore brain slot
                systemPrompt = routerSystemPrompt
                ready = true
                Log.i(TAG, "Router initialized on slot $SLOT (adapter=$adapterHandle)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize router", e)
                try { engine.setActiveSlot(0) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Classify a user message. Returns raw JSON string from the model, or null on failure.
     *
     * Acquires slotMutex — blocks Brain generation during classification.
     * The operation is fast (~200ms) because it's a 512-token classifier.
     *
     * Each call:
     * 1. Switches to slot 2
     * 2. Clears KV cache (stateless)
     * 3. Sets system prompt
     * 4. Runs classification
     * 5. Switches back to slot 0
     */
    suspend fun classify(userMessage: String): String? {
        if (!ready) {
            Log.w(TAG, "Classify called but router not ready")
            return null
        }

        return LlamaNativeEngine.slotMutex.withLock {
            try {
                engine.setActiveSlot(SLOT)
                engine.resetSlotContext()

                // Set system prompt
                engine.setSystemPrompt(systemPrompt)

                val output = StringBuilder()
                val startTime = System.currentTimeMillis()

                engine.sendUserPrompt(userMessage, MAX_TOKENS).collect { token ->
                    output.append(token)
                }

                val elapsed = System.currentTimeMillis() - startTime
                val result = output.toString().trim()
                Log.d(TAG, "Classification complete in ${elapsed}ms: $result")

                result.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.e(TAG, "Classification failed", e)
                null
            } finally {
                engine.setActiveSlot(0)
            }
        }
    }

    /**
     * Tear down the router — remove LoRA adapter and destroy shared context.
     * Call during app shutdown or model unload.
     */
    suspend fun shutdown() {
        if (!ready) return

        LlamaNativeEngine.slotMutex.withLock {
            try {
                engine.setActiveSlot(SLOT)
                if (adapterHandle != 0L) {
                    engine.removeLoraAdapter(adapterHandle)
                    engine.freeLoraAdapter(adapterHandle)
                    adapterHandle = 0L
                }
                engine.setActiveSlot(0)
            } catch (e: Exception) {
                Log.w(TAG, "Error during router shutdown", e)
                try { engine.setActiveSlot(0) } catch (_: Exception) {}
            }
            ready = false
        }
    }
}
