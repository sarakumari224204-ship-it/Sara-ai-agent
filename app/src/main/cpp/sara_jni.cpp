#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>
#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "sara_llama", __VA_ARGS__)

static std::mutex g_mu;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static int g_n_past = 0;

static void unload_locked() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }
    g_n_past = 0;
}

static std::string jstr(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeLoad(JNIEnv * env, jobject, jstring path, jint n_ctx, jint threads) {
    std::lock_guard<std::mutex> lock(g_mu);
    unload_locked();
    llama_backend_init();
    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    std::string p = jstr(env, path);
    g_model = llama_load_model_from_file(p.c_str(), mparams);
    if (!g_model) return env->NewStringUTF("model file could not be loaded");
    auto cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 128 ? (uint32_t) n_ctx : 1024;
    cparams.n_threads = threads > 0 ? threads : 4;
    cparams.n_batch = 128;
    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (!g_ctx) {
        unload_locked();
        return env->NewStringUTF("context could not be created");
    }
    LOGI("model loaded");
    return env->NewStringUTF("ok");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_saraai_offline_llm_LlamaBridge_nativeGenerate(JNIEnv * env, jobject, jstring prompt, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_model || !g_ctx) return env->NewStringUTF("");
    std::string text = jstr(env, prompt);
    std::vector<llama_token> tokens(text.size() + 8);
    int n = llama_tokenize(g_model, text.c_str(), (int) text.size(), tokens.data(), (int) tokens.size(), true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(g_model, text.c_str(), (int) text.size(), tokens.data(), (int) tokens.size(), true, true);
    }
    if (n <= 0) return env->NewStringUTF("");
    tokens.resize(n);
    llama_kv_cache_clear(g_ctx);
    g_n_past = 0;
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == n - 1);
    }
    batch.n_tokens = n;
    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }
    g_n_past = n;
    llama_batch_free(batch);
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.6f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));
    std::string out;
    int limit = max_tokens > 0 ? max_tokens : 128;
    llama_token eos = llama_token_eos(g_model);
    for (int step = 0; step < limit; step++) {
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, id);
        if (id == eos) break;
        char piece[256];
        int wrote = llama_token_to_piece(g_model, id, piece, (int) sizeof(piece), 0, true);
        if (wrote > 0) out.append(piece, piece + wrote);
        llama_batch one = llama_batch_init(1, 0, 1);
        one.token[0] = id;
        one.pos[0] = g_n_past;
        one.n_seq_id[0] = 1;
        one.seq_id[0][0] = 0;
        one.logits[0] = true;
        one.n_tokens = 1;
        if (llama_decode(g_ctx, one) != 0) { llama_batch_free(one); break; }
        llama_batch_free(one);
        g_n_past++;
    }
    llama_sampler_free(smpl);
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
