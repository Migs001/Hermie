package com.hermie.assistant.llm

/**
 * Central config for all model download URLs.
 * Change these when pointing to private finetuned repos on HuggingFace.
 *
 * URL format for private repos:
 *   https://huggingface.co/{user}/{repo}/resolve/main/{file}
 *   + Authorization: Bearer hf_YOUR_TOKEN header
 *
 * NOTE: Qwen 3 = pure transformer. Qwen 3.5 = hybrid (Gated Delta Networks + MoE).
 * Qwen 3.5 may be slower on phone CPU but offers better reasoning/tool-calling.
 */
object ModelUrls {

    // ── Brain (LLM) — Qwen 3.5 (hybrid arch, unified vision-language) ──
    const val BRAIN_QWEN35_08B =
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
    const val BRAIN_QWEN35_2B =
        "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf"
    const val BRAIN_QWEN35_4B =
        "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf"
    const val BRAIN_QWEN35_8B =
        "https://huggingface.co/unsloth/Qwen3.5-8B-GGUF/resolve/main/Qwen3.5-8B-Q4_K_M.gguf"

    // ── Brain (Finetuned — private HF repos, require token) ──
    const val BRAIN_FINETUNED_QWEN_15B =
        "https://huggingface.co/MigsN9/Hermie-Qwen2.5-1.5B/resolve/main/qwen2.5-1.5b-finetuned.gguf"
    const val BRAIN_FINETUNED_QWEN_3B =
        "https://huggingface.co/MigsN9/Hermie-Qwen2.5-3B/resolve/main/bmo-qwen2.5-3b-q4_k_m.gguf"

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

    // ── SLM (Small Language Model — drip atomizer for memory extraction) ──
    const val SLM_SMOLLM2_360M =
        "https://huggingface.co/MigsN9/SmolLM2-360M-Instruct-Mem-Cat/resolve/main/smollm2-360m-mem-cat-q8_0.gguf"
    const val SLM_QWEN3_06B =
        "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"

    // ── Vision (VLM — dedicated image-to-text) ────────────────
    const val VISION_QWEN3VL_2B =
        "https://huggingface.co/unsloth/Qwen3-VL-2B-Instruct-GGUF/resolve/main/Qwen3-VL-2B-Instruct-Q4_K_M.gguf"
    const val VISION_QWEN3VL_2B_MMPROJ =
        "https://huggingface.co/unsloth/Qwen3-VL-2B-Instruct-GGUF/resolve/main/mmproj-F16.gguf"
    const val VISION_QWEN3VL_4B =
        "https://huggingface.co/unsloth/Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen3-VL-4B-Instruct-Q4_K_M.gguf"
    const val VISION_QWEN3VL_4B_MMPROJ =
        "https://huggingface.co/unsloth/Qwen3-VL-4B-Instruct-GGUF/resolve/main/mmproj-F16.gguf"

    // ── Mind (Embeddings) ─────────────────────────────────────
    const val MIND_MINILM_TFLITE =
        "https://huggingface.co/Nihal2000/all-MiniLM-L6-v2-quant.tflite/resolve/main/all-MiniLM-L6-v2-quant.tflite"
    const val MIND_MINILM_VOCAB =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt"
}
