package com.hermie.llamacpp

import android.content.Context
import com.hermie.llamacpp.internal.InferenceEngineImpl

/**
 * Main entry point for the llama.cpp Android library.
 */
object LlamaCpp {
    fun getInferenceEngine(context: Context): InferenceEngine =
        InferenceEngineImpl.getInstance(context)
}
