#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <chrono>
#include <android/log.h>
#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "sara_llama", __VA_ARGS__)

static std::mutex g_mu;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static int g_n_past = 0;
static int g_n_threads = 4;
static int64_t g_last_prompt_ms = 0;
static int64_t g_last_gen_ms = 0;
static int g_last_prompt_tokens = 0;
static int g_last_gen_tokens = 0;
static int64_t g_last_ttft_ms = 0;
static int64_t g_load_ms = 0;

static void unload_locked() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }
    g_n_past = 0;
}

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeGenerateEx(
    JNIEnv * env, jobject,
    jstring prompt, jint max_tokens, jfloat temperature, jint top_k, jfloat top_p);

extern "C" JNIEXPORT jstring JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeLoad(JNIEnv * env, jobject, jstring path, jint n_ctx, jint threads) {
    std::lock_guard<std::mutex> lock(g_mu);
    auto t0 = std::chrono::steady_clock::now();
    unload_locked();
    llama_backend_init();
    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    std::string p = jstr(env, path);
    g_model = llama_load_model_from_file(p.c_str(), mparams);
    if (!g_model) return env->NewStringUTF("model file could not be loaded");
    auto cparams = llama_context_default_params();
    int ctx = n_ctx > 128 ? n_ctx : 512;
    if (ctx > 2048) ctx = 2048;
    cparams.n_ctx = (uint32_t) ctx;
    int th = threads > 0 ? threads : 4;
    if (th > 4) th = 4;
    g_n_threads = th;
    cparams.n_threads = th;
    cparams.n_threads_batch = th;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (!g_ctx) {
        unload_locked();
        return env->NewStringUTF("context could not be created");
    }
    g_load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    LOGI("model loaded ctx=%d threads=%d load_ms=%lld", ctx, th, (long long) g_load_ms);
    return env->NewStringUTF("ok");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeGenerate(JNIEnv * env, jobject, jstring prompt, jint max_tokens) {
    return Java_com_saraai_offline_llm_LlamaBridge_nativeGenerateEx(
        env, nullptr, prompt, max_tokens, 0.6f, 40, 0.9f);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeGenerateEx(
    JNIEnv * env, jobject,
    jstring prompt, jint max_tokens, jfloat temperature, jint top_k, jfloat top_p
) {
    std::lock_guard<std::mutex> lock(g_mu);
    g_last_prompt_ms = 0; g_last_gen_ms = 0; g_last_prompt_tokens = 0; g_last_gen_tokens = 0; g_last_ttft_ms = 0;
    if (!g_model || !g_ctx) return env->NewStringUTF("");
    std::string text = jstr(env, prompt);
    if (text.empty()) return env->NewStringUTF("");
    auto t_all = std::chrono::steady_clock::now();
    int n_max = (int) text.size() + 16;
    std::vector<llama_token> tokens(n_max);
    int n = llama_tokenize(g_model, text.c_str(), (int) text.size(), tokens.data(), n_max, true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(g_model, text.c_str(), (int) text.size(), tokens.data(), (int) tokens.size(), true, true);
    }
    if (n <= 0) return env->NewStringUTF("");
    tokens.resize(n);
    g_last_prompt_tokens = n;
    int n_ctx = (int) llama_n_ctx(g_ctx);
    int limit_prompt = n_ctx - 32;
    if (limit_prompt < 8) limit_prompt = 8;
    if (n > limit_prompt) {
        int skip = n - limit_prompt;
        tokens.erase(tokens.begin(), tokens.begin() + skip);
        n = (int) tokens.size();
        g_last_prompt_tokens = n;
    }
    llama_kv_cache_clear(g_ctx);
    g_n_past = 0;
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == n - 1) ? 1 : 0;
    }
    batch.n_tokens = n;
    auto t_prompt0 = std::chrono::steady_clock::now();
    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }
    g_last_prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t_prompt0).count();
    g_n_past = n;
    llama_batch_free(batch);
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    int tk = top_k > 0 ? top_k : 40;
    float tp = top_p > 0.05f && top_p <= 1.0f ? top_p : 0.9f;
    float temp = temperature >= 0.0f ? temperature : 0.6f;
    if (temp < 0.05f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(tk));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(tp, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));
    }
    std::string out;
    int limit = max_tokens > 0 ? max_tokens : 64;
    if (limit > 256) limit = 256;
    llama_token eos = llama_token_eos(g_model);
    llama_batch one = llama_batch_init(1, 0, 1);
    auto t_gen0 = std::chrono::steady_clock::now();
    bool first = true;
    for (int step = 0; step < limit; step++) {
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, id);
        if (id == eos) break;
        char piece[256];
        int wrote = llama_token_to_piece(g_model, id, piece, (int) sizeof(piece), 0, true);
        if (wrote > 0) out.append(piece, piece + wrote);
        if (first) {
            g_last_ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t_all).count();
            first = false;
        }
        one.token[0] = id;
        one.pos[0] = g_n_past;
        one.n_seq_id[0] = 1;
        one.seq_id[0][0] = 0;
        one.logits[0] = 1;
        one.n_tokens = 1;
        if (llama_decode(g_ctx, one) != 0) break;
        g_n_past++;
        g_last_gen_tokens = step + 1;
    }
    g_last_gen_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t_gen0).count();
    llama_batch_free(one);
    llama_sampler_free(smpl);
    LOGI("gen prompt_tok=%d gen_tok=%d prompt_ms=%lld gen_ms=%lld ttft_ms=%lld",
         g_last_prompt_tokens, g_last_gen_tokens,
         (long long) g_last_prompt_ms, (long long) g_last_gen_ms, (long long) g_last_ttft_ms);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    unload_locked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeLoaded(JNIEnv *, jobject) {
    return g_model != nullptr && g_ctx != nullptr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeLastPromptMs(JNIEnv *, jobject) { return (jlong) g_last_prompt_ms; }
extern "C" JNIEXPORT jlong JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeLastGenMs(JNIEnv *, jobject) { return (jlong) g_last_gen_ms; }
extern "C" JNIEXPORT jint JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeLastPromptTokens(JNIEnv *, jobject) { return g_last_prompt_tokens; }
extern "C" JNIEXPORT jint JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeLastGenTokens(JNIEnv *, jobject) { return g_last_gen_tokens; }
extern "C" JNIEXPORT jlong JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeLastTtftMs(JNIEnv *, jobject) { return (jlong) g_last_ttft_ms; }
extern "C" JNIEXPORT jlong JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeLoadMs(JNIEnv *, jobject) { return (jlong) g_load_ms; }
