/**
 * JNI bridge between Kotlin and llama.cpp.
 *
 * Exposes a minimal surface: load, generate (streaming via callback), stop, release.
 * All heavy lifting (sampling, KV cache) is handled by llama.cpp internals.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <thread>

#include "llama.h"
#include "ggml.h"
#include <sys/stat.h>

#define TAG "llama-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Route llama.cpp internal logs to Android logcat ──────────

static void llama_android_log(enum ggml_log_level level, const char *text, void * /*user_data*/) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOGE("%s", text); break;
        case GGML_LOG_LEVEL_WARN:  LOGW("%s", text); break;
        default:                   LOGI("%s", text); break;
    }
}

// ── One-time backend init ────────────────────────────────────

static bool g_backend_initialized = false;

static void ensure_backend() {
    if (!g_backend_initialized) {
        // Redirect llama.cpp logs to logcat so we can see errors
        llama_log_set(llama_android_log, nullptr);
        llama_backend_init();
        g_backend_initialized = true;
        LOGI("llama backend initialized");
    }
}

// ── Global state (one model loaded at a time) ────────────────

static llama_model          *g_model   = nullptr;
static llama_context        *g_ctx     = nullptr;
static llama_sampler        *g_sampler = nullptr;
static std::atomic<bool>     g_abort{false};
static std::mutex            g_mutex;

// ── Helpers ──────────────────────────────────────────────────

static std::string jstring_to_string(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

static void release_resources() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model   = nullptr; }
}

// ── JNI exports ──────────────────────────────────────────────

extern "C" {

/**
 * Load a GGUF model from the given file path.
 * Returns true on success.
 */
JNIEXPORT jboolean JNICALL
Java_com_hermie_assistant_llm_LlamaNativeEngine_nativeLoad(
        JNIEnv *env, jobject /* this */,
        jstring jModelPath, jint contextSize) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_backend();
    release_resources();

    std::string path = jstring_to_string(env, jModelPath);
    LOGI("Loading model: %s (ctx=%d)", path.c_str(), contextSize);

    // Check file exists and size
    struct stat st;
    if (stat(path.c_str(), &st) != 0) {
        LOGE("File does not exist: %s", path.c_str());
        return JNI_FALSE;
    }
    LOGI("File size: %lld bytes (%.1f MB)", (long long)st.st_size, st.st_size / 1048576.0);
    if (st.st_size < 1000000) {
        LOGE("File too small (%lld bytes), likely corrupted or incomplete download", (long long)st.st_size);
        return JNI_FALSE;
    }

    // Model params
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;  // CPU only on Android

    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (!g_model) {
        LOGE("Failed to load model from %s", path.c_str());
        return JNI_FALSE;
    }

    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx   = contextSize;
    ctx_params.n_batch = 512;

    // Use performance cores for inference (big.LITTLE: use ~half the cores)
    int hw_threads = (int)std::thread::hardware_concurrency();
    int n_threads  = std::max(1, hw_threads > 4 ? hw_threads / 2 : hw_threads);
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;
    LOGI("Using %d threads (hardware: %d)", n_threads, hw_threads);

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    // Sampler chain: temp → top-k → top-p → greedy pick
    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(chain_params);
    // Temperature and top-p/top-k are set per-generate call; start with defaults
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

/**
 * Generate tokens from a prompt string, calling onToken(String) for each token.
 * Blocks until generation finishes (EOS, max tokens, or abort).
 */
JNIEXPORT void JNICALL
Java_com_hermie_assistant_llm_LlamaNativeEngine_nativeGenerate(
        JNIEnv *env, jobject /* this */,
        jstring jPrompt, jint maxTokens, jfloat temperature,
        jobject callback) {
    if (!g_model || !g_ctx || !g_sampler) {
        LOGE("generate called but model not loaded");
        return;
    }

    g_abort.store(false);

    std::string prompt = jstring_to_string(env, jPrompt);

    // Tokenize prompt
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    int n_prompt_max = prompt.size() + 128;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                                  tokens.data(), n_prompt_max,
                                  /* add_special */ true, /* parse_special */ true);
    if (n_tokens < 0) {
        LOGE("Tokenization failed (needed %d tokens)", -n_tokens);
        return;
    }
    tokens.resize(n_tokens);

    // Get callback methods early
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "()V");

    // Clear memory for fresh generation
    llama_memory_clear(llama_get_memory(g_ctx), true);

    // Prompt eval — process in chunks of n_batch to avoid overflow
    const int n_batch = 512;
    LOGI("Prompt: %d tokens, processing in batches of %d", n_tokens, n_batch);

    struct timespec ts_start, ts_end;
    clock_gettime(CLOCK_MONOTONIC, &ts_start);

    for (int i = 0; i < n_tokens; i += n_batch) {
        if (g_abort.load()) {
            LOGI("Aborted during prompt eval");
            env->CallVoidMethod(callback, onComplete);
            return;
        }
        int n_chunk = std::min(n_batch, n_tokens - i);
        LOGI("  eval batch %d..%d / %d", i, i + n_chunk, n_tokens);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n_chunk);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Prompt eval failed at token %d/%d", i, n_tokens);
            env->CallVoidMethod(callback, onComplete);
            return;
        }
    }

    clock_gettime(CLOCK_MONOTONIC, &ts_end);
    double prompt_ms = (ts_end.tv_sec - ts_start.tv_sec) * 1000.0 +
                       (ts_end.tv_nsec - ts_start.tv_nsec) / 1e6;
    LOGI("Prompt eval done: %.0f ms (%.1f tok/s)", prompt_ms, n_tokens * 1000.0 / prompt_ms);

    const llama_vocab *vocab2 = llama_model_get_vocab(g_model);
    const llama_token eos = llama_vocab_eos(vocab2);
    const llama_token eot = llama_vocab_eot(vocab2);

    // Generate tokens one at a time
    clock_gettime(CLOCK_MONOTONIC, &ts_start);
    int generated = 0;

    for (int i = 0; i < maxTokens; i++) {
        if (g_abort.load()) {
            LOGI("Generation aborted after %d tokens", generated);
            break;
        }

        llama_token new_token = llama_sampler_sample(g_sampler, g_ctx, -1);

        // Check for end-of-sequence
        if (llama_vocab_is_eog(vocab, new_token) ||
            new_token == eos || new_token == eot) {
            LOGI("EOS after %d tokens", generated);
            break;
        }

        generated++;

        // Detokenize
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string piece(buf, n);
            jstring jPiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, jPiece);
            env->DeleteLocalRef(jPiece);
        }

        // Log speed every 10 tokens
        if (generated % 10 == 0) {
            clock_gettime(CLOCK_MONOTONIC, &ts_end);
            double gen_ms = (ts_end.tv_sec - ts_start.tv_sec) * 1000.0 +
                            (ts_end.tv_nsec - ts_start.tv_nsec) / 1e6;
            LOGI("Generated %d tokens (%.1f tok/s)", generated, generated * 1000.0 / gen_ms);
        }

        // Prepare next decode (single token)
        llama_batch next = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, next) != 0) {
            LOGE("Decode failed at token %d", i);
            break;
        }
    }

    clock_gettime(CLOCK_MONOTONIC, &ts_end);
    double gen_ms = (ts_end.tv_sec - ts_start.tv_sec) * 1000.0 +
                    (ts_end.tv_nsec - ts_start.tv_nsec) / 1e6;
    LOGI("Generation complete: %d tokens in %.0f ms (%.1f tok/s)", generated, gen_ms,
         generated > 0 ? generated * 1000.0 / gen_ms : 0);

    // Signal completion
    env->CallVoidMethod(callback, onComplete);
}

/**
 * Signal the generation loop to stop.
 */
JNIEXPORT void JNICALL
Java_com_hermie_assistant_llm_LlamaNativeEngine_nativeStop(
        JNIEnv * /* env */, jobject /* this */) {
    g_abort.store(true);
}

/**
 * Unload model and free all native resources.
 */
JNIEXPORT void JNICALL
Java_com_hermie_assistant_llm_LlamaNativeEngine_nativeRelease(
        JNIEnv * /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_resources();
    LOGI("Resources released");
}

} // extern "C"
