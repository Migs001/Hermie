#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "ggml.h"
#include "mtmd.h"
#include "mtmd-helper.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: dual-slot system for running 2 models simultaneously.
 * Slot 0 = main brain LLM, Slot 1 = small mind LLM.
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 8192;
constexpr int   TURBO_CONTEXT_SIZE      = 16384;

constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

constexpr int   NUM_SLOTS               = 2;

/**
 * Per-model slot holding all state for one loaded model.
 */
struct ModelSlot {
    llama_model                      * model          = nullptr;
    llama_context                    * context        = nullptr;
    llama_batch                        batch          = {};
    common_chat_templates_ptr          chat_templates = nullptr;
    common_sampler                   * sampler        = nullptr;

    // Multimodal (vision) support
    mtmd_context                     * mtmd_ctx       = nullptr;

    // Long-term state
    std::vector<common_chat_msg>       chat_msgs;
    llama_pos                          system_prompt_position = 0;
    llama_pos                          current_position       = 0;

    // Short-term state
    llama_pos                          stop_generation_position = 0;
    std::string                        cached_token_chars;
    std::ostringstream                 assistant_ss;

    bool is_loaded() const { return model != nullptr && context != nullptr; }
    bool has_vision() const { return mtmd_ctx != nullptr; }
};

static ModelSlot g_slots[NUM_SLOTS];
static int       g_active_slot = 0;

// Convenience aliases — always point to the active slot
static inline ModelSlot &S() { return g_slots[g_active_slot]; }

extern "C"
JNIEXPORT void JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    llama_log_set(aichat_android_log_callback, nullptr);

    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    llama_backend_init();
    LOGi("Backend initiated; Log handler set. Dual-slot engine ready (%d slots).", NUM_SLOTS);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_nativeSetActiveSlot(JNIEnv * /*env*/, jobject /*unused*/, jint slot) {
    if (slot < 0 || slot >= NUM_SLOTS) {
        LOGe("setActiveSlot: invalid slot %d (max %d)", (int)slot, NUM_SLOTS - 1);
        return;
    }
    g_active_slot = (int)slot;
    LOGi("Active slot set to %d", g_active_slot);
}

/**
 * Reset the active slot's context (KV cache, chat history, positions)
 * without unloading the model. Used for stateless classifier-style slots
 * that don't need to maintain conversation history between calls.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_nativeResetSlotContext(JNIEnv * /*env*/, jobject /*unused*/) {
    auto &s = S();
    if (!s.context) return;

    // Clear KV cache
    llama_memory_clear(llama_get_memory(s.context), false);

    // Reset all positions and chat history
    s.chat_msgs.clear();
    s.system_prompt_position = 0;
    s.current_position = 0;
    s.stop_generation_position = 0;
    s.cached_token_chars.clear();
    s.assistant_ss.str("");

    // Reset sampler state
    if (s.sampler) {
        common_sampler_reset(s.sampler);
    }

    LOGi("resetSlotContext: slot %d context cleared", g_active_slot);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: [slot %d] Loading model from: \n%s\n", __func__, g_active_slot, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    S().model = model;
    return 0;
}

static llama_context *init_context(
        llama_model *model,
        const int n_ctx = DEFAULT_CONTEXT_SIZE,
        const ggml_type cache_type_k = GGML_TYPE_F16,
        const ggml_type cache_type_v = GGML_TYPE_F16
) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx           = n_ctx;
    ctx_params.n_batch         = BATCH_SIZE;
    ctx_params.n_ubatch        = BATCH_SIZE;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    ctx_params.type_k = cache_type_k;
    ctx_params.type_v = cache_type_v;

    LOGi("%s: KV cache type_k=%d, type_v=%d, n_ctx=%d", __func__,
         (int)cache_type_k, (int)cache_type_v, n_ctx);

    auto *context = llama_init_from_model(model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(llama_model *model, float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_prepare(
        JNIEnv * /*env*/,
        jobject /*unused*/,
        jboolean use_turbo_cache,
        jint context_size
) {
    llama_context *context;
    const int n_ctx = (int)context_size;

    if (use_turbo_cache) {
        context = init_context(S().model, n_ctx,
                               GGML_TYPE_Q8_0, GGML_TYPE_Q8_0);
    } else {
        context = init_context(S().model, n_ctx,
                               GGML_TYPE_F16, GGML_TYPE_F16);
    }

    if (!context) { return 1; }
    S().context = context;
    S().batch = llama_batch_init(BATCH_SIZE, 0, 1);
    S().chat_templates = common_chat_templates_init(S().model, "");
    S().sampler = new_sampler(S().model, DEFAULT_SAMPLER_TEMP);
    LOGi("prepare: slot %d ready", g_active_slot);
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(S().model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    // Use a temporary batch for benchmarking
    auto bench_batch = llama_batch_init(BATCH_SIZE, 0, 1);

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(bench_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(bench_batch, 0, i, {0}, false);
        }

        bench_batch.logits[bench_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, bench_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(bench_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(bench_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, bench_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_batch_free(bench_batch);
    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(S().model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(S().model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(S().model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states (per-slot):
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static void reset_long_term_states(const bool clear_kv_cache = true) {
    auto &s = S();
    s.chat_msgs.clear();
    s.system_prompt_position = 0;
    s.current_position = 0;

    if (clear_kv_cache && s.context)
        llama_memory_clear(llama_get_memory(s.context), false);
}

/**
 * Context shifting by discarding the older half of the tokens appended after system prompt.
 */
static void shift_context() {
    auto &s = S();
    const int n_discard = (s.current_position - s.system_prompt_position) / 2;
    LOGi("%s: [slot %d] Discarding %d tokens", __func__, g_active_slot, n_discard);
    llama_memory_seq_rm(llama_get_memory(s.context), 0, s.system_prompt_position, s.system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(s.context), 0, s.system_prompt_position + n_discard, s.current_position, -n_discard);
    s.current_position -= n_discard;
    LOGi("%s: [slot %d] Context shifting done! Current position: %d", __func__, g_active_slot, s.current_position);
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    auto &s = S();
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            s.chat_templates.get(), s.chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);
    s.chat_msgs.push_back(new_msg);
    LOGi("%s: [slot %d] Formatted and added %s message: \n%s\n", __func__, g_active_slot, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Reset short-term states for the active slot.
 */
static void reset_short_term_states() {
    auto &s = S();
    s.stop_generation_position = 0;
    s.cached_token_chars.clear();
    s.assistant_ss.str("");
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    auto &s = S();
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        if (start_pos + i + cur_batch_size >= (int)llama_n_ctx(s.context) - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            shift_context();
        }

        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode() failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    auto &s = S();
    reset_long_term_states();
    reset_short_term_states();

    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: [slot %d] System prompt received: \n%s", __func__, g_active_slot, system_prompt);
    std::string formatted_system_prompt(system_prompt);

    const bool has_chat_template = common_chat_templates_was_explicit(s.chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    const auto system_tokens = common_tokenize(s.context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(s.context, id).c_str(), id);
    }

    const int max_batch_size = (int)llama_n_ctx(s.context) - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    if (decode_tokens_in_batches(s.context, s.batch, system_tokens, s.current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    s.system_prompt_position = s.current_position = (int) system_tokens.size();
    return 0;
}

/**
 * Prefill the KV cache with conversation history (role/content pairs).
 * Call AFTER processSystemPrompt. Each message is formatted using the chat
 * template and decoded into the KV cache without triggering generation.
 * This allows restoring a conversation's context when switching chats.
 *
 * @param roles     String[] of roles ("user", "assistant")
 * @param contents  String[] of message contents
 * @return 0 on success, non-zero on error
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_nativePrefillHistory(
        JNIEnv *env, jobject /*unused*/,
        jobjectArray roles, jobjectArray contents
) {
    auto &s = S();
    if (!s.context) {
        LOGe("prefillHistory: no context loaded");
        return 1;
    }

    const int count = env->GetArrayLength(roles);
    if (count != env->GetArrayLength(contents)) {
        LOGe("prefillHistory: roles/contents length mismatch");
        return 2;
    }
    if (count == 0) return 0;

    LOGi("prefillHistory: [slot %d] Replaying %d messages into KV cache", g_active_slot, count);

    const bool has_chat_template = common_chat_templates_was_explicit(s.chat_templates.get());
    if (!has_chat_template) {
        LOGw("prefillHistory: no chat template — skipping");
        return 3;
    }

    for (int i = 0; i < count; i++) {
        auto jrole = (jstring)env->GetObjectArrayElement(roles, i);
        auto jcontent = (jstring)env->GetObjectArrayElement(contents, i);

        const char *role = env->GetStringUTFChars(jrole, nullptr);
        const char *content = env->GetStringUTFChars(jcontent, nullptr);

        // Format using chat template and add to chat history
        std::string formatted = chat_add_and_format(std::string(role), std::string(content));
        auto tokens = common_tokenize(s.context, formatted, true, true);

        // Check context space
        const int max_ctx = (int)llama_n_ctx(s.context) - OVERFLOW_HEADROOM;
        if (s.current_position + (int)tokens.size() > max_ctx) {
            LOGw("prefillHistory: context full after %d/%d messages (pos %d + %d > %d)",
                 i, count, s.current_position, (int)tokens.size(), max_ctx);
            env->ReleaseStringUTFChars(jrole, role);
            env->ReleaseStringUTFChars(jcontent, content);
            break;  // Stop — we've filled as much as fits
        }

        if (decode_tokens_in_batches(s.context, s.batch, tokens, s.current_position, true)) {
            LOGe("prefillHistory: decode failed at message %d", i);
            env->ReleaseStringUTFChars(jrole, role);
            env->ReleaseStringUTFChars(jcontent, content);
            return 4;
        }
        s.current_position += (int)tokens.size();

        env->ReleaseStringUTFChars(jrole, role);
        env->ReleaseStringUTFChars(jcontent, content);
    }

    LOGi("prefillHistory: [slot %d] Done. KV cache position: %d", g_active_slot, s.current_position);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    auto &s = S();
    reset_short_term_states();

    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: [slot %d] User prompt received: \n%s", __func__, g_active_slot, user_prompt);
    std::string formatted_user_prompt(user_prompt);

    const bool has_chat_template = common_chat_templates_was_explicit(s.chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    auto user_tokens = common_tokenize(s.context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(s.context, id).c_str(), id);
    }

    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = (int)llama_n_ctx(s.context) - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    if (decode_tokens_in_batches(s.context, s.batch, user_tokens, s.current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    s.current_position += user_prompt_size;

    // ── Prefill empty think block to suppress Qwen 3.5 reasoning ──
    // Only on slot 0 (brain LLM). Slot 1 is a fine-tuned SmolLM2 classifier
    // that doesn't use <think> tags — injecting them corrupts its output.
    if (g_active_slot == 0) {
        const std::string think_prefill = "<think>\n</think>\n";
        auto think_tokens = common_tokenize(s.context, think_prefill, false, false);
        if (!think_tokens.empty()) {
            LOGi("%s: [slot 0] Injecting think prefill (%d tokens)", __func__, (int) think_tokens.size());
            if (decode_tokens_in_batches(s.context, s.batch, think_tokens, s.current_position, true)) {
                LOGw("%s: Think prefill decode failed — continuing without it", __func__);
            } else {
                s.current_position += (int) think_tokens.size();
            }
        }
    }

    s.stop_generation_position = s.current_position + user_prompt_size + n_predict;
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    auto &s = S();

    if (s.current_position >= (int)llama_n_ctx(s.context) - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }

    if (s.current_position >= s.stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, s.stop_generation_position);
        return nullptr;
    }

    const auto new_token_id = common_sampler_sample(s.sampler, s.context, -1);
    common_sampler_accept(s.sampler, new_token_id, true);

    common_batch_clear(s.batch);
    common_batch_add(s.batch, new_token_id, s.current_position, {0}, true);
    if (llama_decode(s.context, s.batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    s.current_position++;

    if (llama_vocab_is_eog(llama_model_get_vocab(s.model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        chat_add_and_format(ROLE_ASSISTANT, s.assistant_ss.str());
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(s.context, new_token_id);
    s.cached_token_chars += new_token_chars;

    jstring result = nullptr;
    if (is_valid_utf8(s.cached_token_chars.c_str())) {
        result = env->NewStringUTF(s.cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, s.cached_token_chars.c_str(), new_token_chars.c_str());

        s.assistant_ss << s.cached_token_chars;
        s.cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}


// ── Multimodal (Vision) Support ──────────────────────────────────

/**
 * Load a mmproj (CLIP vision encoder) model for the active slot.
 * Must be called after the main model is loaded (prepare() completed).
 * Returns 0 on success, 1 on failure.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_nativeLoadMmproj(
        JNIEnv *env, jobject /*unused*/, jstring jmmproj_path
) {
    auto &s = S();
    if (!s.model) {
        LOGe("loadMmproj: no model loaded in slot %d", g_active_slot);
        return 1;
    }

    // Free any existing mtmd context
    if (s.mtmd_ctx) {
        mtmd_free(s.mtmd_ctx);
        s.mtmd_ctx = nullptr;
    }

    const auto *mmproj_path = env->GetStringUTFChars(jmmproj_path, nullptr);
    LOGi("loadMmproj: loading %s for slot %d", mmproj_path, g_active_slot);

    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;  // CPU-only on Android for CLIP
    params.n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                (int) sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));
    params.warmup = false;   // Skip warmup to save memory

    s.mtmd_ctx = mtmd_init_from_file(mmproj_path, s.model, params);
    env->ReleaseStringUTFChars(jmmproj_path, mmproj_path);

    if (!s.mtmd_ctx) {
        LOGe("loadMmproj: failed to init mtmd context");
        return 1;
    }

    bool has_vision = mtmd_support_vision(s.mtmd_ctx);
    LOGi("loadMmproj: success (vision=%d)", has_vision);
    return 0;
}

/**
 * Check if the active slot has a vision model loaded.
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_hasVision(
        JNIEnv * /*env*/, jobject /*unused*/
) {
    return S().has_vision() && mtmd_support_vision(S().mtmd_ctx);
}

/**
 * Process a user prompt that includes an image.
 * The image is passed as raw RGB bytes (width * height * 3).
 * The prompt text should NOT contain the media marker — it's inserted automatically.
 *
 * Flow:
 * 1. Format the user prompt with the chat template + <__media__> marker
 * 2. Create mtmd_bitmap from the RGB data
 * 3. Tokenize text + image via mtmd_tokenize
 * 4. Evaluate all chunks via mtmd_helper_eval_chunks
 * 5. Set up for token generation (same as processUserPrompt)
 *
 * Returns 0 on success, non-zero on error.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_processUserPromptWithImage(
        JNIEnv *env, jobject /*unused*/,
        jstring juser_prompt,
        jbyteArray jimage_rgb,
        jint width,
        jint height,
        jint n_predict
) {
    auto &s = S();
    if (!s.mtmd_ctx) {
        LOGe("processUserPromptWithImage: no mtmd context loaded");
        return 1;
    }

    reset_short_term_states();

    // Get user prompt text
    const auto *user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("processUserPromptWithImage: [slot %d] prompt: %s, image: %dx%d",
         g_active_slot, user_prompt, (int)width, (int)height);

    // Build the prompt text with media marker
    // Insert <__media__> before the user text so the model sees the image first
    const char *marker = mtmd_default_marker();
    std::string prompt_with_image = std::string(marker) + "\n" + std::string(user_prompt);
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Format with chat template
    const bool has_chat_template = common_chat_templates_was_explicit(s.chat_templates.get());
    std::string formatted_prompt;
    if (has_chat_template) {
        formatted_prompt = chat_add_and_format(ROLE_USER, prompt_with_image);
    } else {
        formatted_prompt = prompt_with_image;
    }

    // Get image RGB data from Java
    jsize rgb_len = env->GetArrayLength(jimage_rgb);
    jbyte *rgb_data = env->GetByteArrayElements(jimage_rgb, nullptr);

    // Create bitmap (RGB format: nx * ny * 3 bytes)
    mtmd_bitmap *bitmap = mtmd_bitmap_init(
        (uint32_t)width, (uint32_t)height, (const unsigned char *)rgb_data
    );
    env->ReleaseByteArrayElements(jimage_rgb, rgb_data, JNI_ABORT);

    if (!bitmap) {
        LOGe("processUserPromptWithImage: failed to create bitmap");
        return 2;
    }

    // Tokenize text + image
    mtmd_input_text input_text;
    input_text.text = formatted_prompt.c_str();
    input_text.add_special = has_chat_template;
    input_text.parse_special = has_chat_template;

    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    const mtmd_bitmap *bitmaps[] = { bitmap };
    int32_t tok_res = mtmd_tokenize(s.mtmd_ctx, chunks, &input_text, bitmaps, 1);
    mtmd_bitmap_free(bitmap);

    if (tok_res != 0) {
        LOGe("processUserPromptWithImage: mtmd_tokenize failed: %d", tok_res);
        mtmd_input_chunks_free(chunks);
        return 3;
    }

    size_t n_chunks = mtmd_input_chunks_size(chunks);
    size_t total_tokens = mtmd_helper_get_n_tokens(chunks);
    LOGi("processUserPromptWithImage: %zu chunks, %zu total tokens", n_chunks, total_tokens);

    // Evaluate all chunks using the helper
    llama_pos new_n_past = 0;
    int32_t eval_res = mtmd_helper_eval_chunks(
        s.mtmd_ctx,
        s.context,
        chunks,
        s.current_position,
        /* seq_id */ 0,
        BATCH_SIZE,
        /* logits_last */ true,
        &new_n_past
    );

    mtmd_input_chunks_free(chunks);

    if (eval_res != 0) {
        LOGe("processUserPromptWithImage: mtmd_helper_eval_chunks failed: %d", eval_res);
        return 4;
    }

    s.current_position = new_n_past;

    // ── Prefill empty think block (same as processUserPrompt, slot 0 only) ──
    if (g_active_slot == 0) {
        const std::string think_prefill = "<think>\n</think>\n";
        auto think_tokens = common_tokenize(s.context, think_prefill, false, false);
        if (!think_tokens.empty()) {
            LOGi("processUserPromptWithImage: [slot 0] Injecting think prefill (%d tokens)", (int) think_tokens.size());
            if (decode_tokens_in_batches(s.context, s.batch, think_tokens, s.current_position, true)) {
                LOGw("processUserPromptWithImage: Think prefill decode failed — continuing without it");
            } else {
                s.current_position += (int) think_tokens.size();
            }
        }
    }

    s.stop_generation_position = s.current_position + n_predict;

    LOGi("processUserPromptWithImage: success, position=%d, stop=%d",
         s.current_position, s.stop_generation_position);
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    auto &s = S();
    reset_long_term_states();
    reset_short_term_states();

    if (s.mtmd_ctx) { mtmd_free(s.mtmd_ctx); s.mtmd_ctx = nullptr; }
    if (s.sampler) { common_sampler_free(s.sampler); s.sampler = nullptr; }
    s.chat_templates.reset();
    if (s.batch.token) { llama_batch_free(s.batch); s.batch = {}; }
    if (s.context) { llama_free(s.context); s.context = nullptr; }
    if (s.model) { llama_model_free(s.model); s.model = nullptr; }
    LOGi("unload: slot %d freed", g_active_slot);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_hermie_llamacpp_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    // Unload all slots before shutting down backend
    for (int i = 0; i < NUM_SLOTS; i++) {
        auto &s = g_slots[i];
        if (s.mtmd_ctx) { mtmd_free(s.mtmd_ctx); s.mtmd_ctx = nullptr; }
        if (s.sampler) { common_sampler_free(s.sampler); s.sampler = nullptr; }
        s.chat_templates.reset();
        if (s.batch.token) { llama_batch_free(s.batch); s.batch = {}; }
        if (s.context) { llama_free(s.context); s.context = nullptr; }
        if (s.model) { llama_model_free(s.model); s.model = nullptr; }
    }
    llama_backend_free();
}
