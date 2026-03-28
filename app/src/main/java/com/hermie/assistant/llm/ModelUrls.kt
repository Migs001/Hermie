package com.hermie.assistant.llm

/**
 * Central config for all model download URLs.
 * Change these when pointing to private finetuned repos on HuggingFace.
 *
 * URL format for private repos:
 *   https://huggingface.co/{user}/{repo}/resolve/main/{file}
 *   + Authorization: Bearer hf_YOUR_TOKEN header
 *
 * NOTE: Qwen 3 (pure transformer) — NOT Qwen 3.5 (hybrid SSM/Mamba).
 * Qwen 3.5's Mamba layers are too slow for phone CPU inference.
 */
object ModelUrls {

    // ── Brain (LLM) — Base models (Qwen 3, pure transformer) ─────
    const val BRAIN_QWEN3_2B =
        "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
    const val BRAIN_QWEN3_4B =
        "https://huggingface.co/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"
    const val BRAIN_QWEN3_8B =
        "https://huggingface.co/unsloth/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf"

    // ── Brain (LLM) — Qwen 2.5 base models ──────────────────────
    const val BRAIN_QWEN25_05B =
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val BRAIN_QWEN25_1B =
        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
    const val BRAIN_QWEN_15B =
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val BRAIN_QWEN_3B =
        "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"
    const val BRAIN_NEMOTRON_4B =
        "https://huggingface.co/bartowski/nvidia_Llama-3.1-Nemotron-Nano-4B-v1.1-GGUF/resolve/main/nvidia_Llama-3.1-Nemotron-Nano-4B-v1.1-Q4_K_M.gguf"

    // ── Brain (LLM) — Finetuned models ────────────────────────────
    const val BRAIN_FINETUNED_QWEN_15B =
        "https://huggingface.co/MigsN9/bmo-qwen2.5-1.5b/resolve/main/bmo-qwen2.5-1.5b-q4_k_m.gguf"
    const val BRAIN_FINETUNED_QWEN_3B =
        "https://huggingface.co/MigsN9/bmo-qwen2.5-3b/resolve/main/bmo-qwen2.5-3b-q4_k_m.gguf"

    // ── Ears (STT) ──────────────────────────────────────────────
    const val EARS_WHISPER_TINY_ENCODER =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-encoder.int8.onnx"
    const val EARS_WHISPER_TINY_DECODER =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-decoder.int8.onnx"
    const val EARS_WHISPER_TINY_TOKENS =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-tokens.txt"

    // ── Voice (TTS) ─────────────────────────────────────────────
    const val VOICE_PIPER_EN_MEDIUM =
        "https://huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium/resolve/main/en_US-lessac-medium.onnx"
    const val VOICE_PIPER_EN_MEDIUM_JSON =
        "https://huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium/resolve/main/en_US-lessac-medium.onnx.json"
    const val VOICE_PIPER_TOKENS =
        "https://huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium/resolve/main/tokens.txt"
    const val VOICE_PIPER_ESPEAK_DATA =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2"

    // ── Mind (Embeddings) ─────────────────────────────────────
    const val MIND_MINILM_TFLITE =
        "https://huggingface.co/Nihal2000/all-MiniLM-L6-v2-quant.tflite/resolve/main/all-MiniLM-L6-v2-quant.tflite"
    const val MIND_MINILM_VOCAB =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt"
}
