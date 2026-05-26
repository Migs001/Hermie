package com.hermie.assistant.llm

import android.content.Context
import android.util.Log
import com.hermie.assistant.modules.memory.MemoryConfig
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TFLite embedding engine using all-MiniLM-L6-v2 (quantized).
 *
 * Produces 384-dimensional sentence embeddings for memory retrieval.
 * Uses a simple WordPiece tokenizer loaded from vocab.txt.
 *
 * Model files expected in models/mind/:
 * - model.tflite (quantized MiniLM-L6-v2)
 * - vocab.txt (WordPiece vocabulary)
 */
class EmbeddingEngine(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddingEngine"
    }

    private var interpreter: Interpreter? = null
    private var vocab: Map<String, Int> = emptyMap()
    private var isReady = false
    private var inputCount = 3  // detected at load time

    val isLoaded: Boolean get() = isReady

    /**
     * Load the TFLite model and vocabulary.
     * Call from a background thread.
     */
    fun load(modelPath: String, vocabPath: String) {
        try {
            Log.d(TAG, "Loading embedding model: $modelPath")
            val modelFile = File(modelPath)
            val vocabFile = File(vocabPath)

            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: $modelPath")
                return
            }
            if (!vocabFile.exists()) {
                Log.e(TAG, "Vocab file not found: $vocabPath")
                return
            }

            // Load vocabulary
            vocab = vocabFile.readLines()
                .mapIndexed { index, token -> token to index }
                .toMap()
            Log.d(TAG, "Loaded vocab: ${vocab.size} tokens")

            // Load TFLite model
            val options = Interpreter.Options().apply {
                numThreads = 2
            }
            val interp = Interpreter(modelFile, options)
            interpreter = interp
            inputCount = interp.inputTensorCount
            isReady = true
            Log.d(TAG, "Embedding engine ready (${MemoryConfig.EMBEDDING_DIM}-dim, $inputCount inputs)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load embedding engine", e)
            isReady = false
        }
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        isReady = false
    }

    /**
     * Embed a text string into a float vector.
     * Returns null if the engine isn't loaded.
     */
    fun embed(text: String): FloatArray? {
        val interp = interpreter ?: return null
        if (!isReady) return null

        return try {
            val tokens = tokenize(text)

            // Prepare input tensors: input_ids, attention_mask
            val inputIds = IntArray(MemoryConfig.EMBEDDING_MAX_TOKENS)
            val attentionMask = IntArray(MemoryConfig.EMBEDDING_MAX_TOKENS)

            for (i in tokens.indices) {
                inputIds[i] = tokens[i]
                attentionMask[i] = 1
            }

            // Run inference — output shape is [1, 384]
            val output = Array(1) { FloatArray(MemoryConfig.EMBEDDING_DIM) }
            val outputMap = mapOf(0 to output)

            // Quantized MiniLM may have 2 inputs (no token_type_ids) or 3
            val inputs = if (inputCount >= 3) {
                val tokenTypeIds = IntArray(MemoryConfig.EMBEDDING_MAX_TOKENS) // all zeros
                arrayOf(arrayOf(inputIds), arrayOf(attentionMask), arrayOf(tokenTypeIds))
            } else {
                arrayOf(arrayOf(inputIds), arrayOf(attentionMask))
            }

            interp.runForMultipleInputsOutputs(inputs, outputMap)

            // L2-normalize the output
            normalize(output[0])
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed for: ${text.take(50)}", e)
            null
        }
    }

    // ── WordPiece tokenizer ──

    private val clsId get() = vocab["[CLS]"] ?: 101
    private val sepId get() = vocab["[SEP]"] ?: 102
    private val unkId get() = vocab["[UNK]"] ?: 100
    private val padId get() = vocab["[PAD]"] ?: 0

    /**
     * Simple WordPiece tokenization.
     * [CLS] token1 token2 ... [SEP] [PAD...]
     */
    private fun tokenize(text: String): List<Int> {
        val maxLen = MemoryConfig.EMBEDDING_MAX_TOKENS
        val tokens = mutableListOf(clsId)

        val words = text.lowercase().split(Regex("\\s+"))
        for (word in words) {
            if (tokens.size >= maxLen - 1) break // leave room for [SEP]
            val wordTokens = wordPieceTokenize(word)
            for (t in wordTokens) {
                if (tokens.size >= maxLen - 1) break
                tokens.add(t)
            }
        }
        tokens.add(sepId)

        // Pad to maxLen
        while (tokens.size < maxLen) tokens.add(padId)
        return tokens
    }

    private fun wordPieceTokenize(word: String): List<Int> {
        val result = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = false
            while (start < end) {
                val substr = if (start == 0) word.substring(start, end)
                else "##${word.substring(start, end)}"
                val id = vocab[substr]
                if (id != null) {
                    result.add(id)
                    start = end
                    found = true
                    break
                }
                end--
            }
            if (!found) {
                result.add(unkId)
                start++
            }
        }
        return result
    }

    private fun normalize(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }
}
