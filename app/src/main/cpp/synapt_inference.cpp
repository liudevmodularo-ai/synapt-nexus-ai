/**
 * ⚙️ synapt_inference.cpp — VECTOR
 *
 * JNI bridge between Android/Kotlin and llama.cpp.
 * Compiled with ARM NEON + OpenMP for Cortex-A78 optimization.
 *
 * PULSE notes:
 *   - Thread affinity set to Big Cores (A78) via sched_setaffinity
 *   - OpenMP thread pool configured at init
 *   - NEON vectorization enabled via -DANDROID_ARM_NEON=ON
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <sched.h>
#include <unistd.h>

// llama.cpp headers (included via CMakeLists.txt)
#include "llama.h"
#include "ggml.h"

#define LOG_TAG "VECTOR::NativeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ─── PULSE: Pin threads to Big Cores (Cortex-A78 = cores 4-7 on Dimensity 7300) ───
static void set_big_core_affinity() {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    // Cortex-A78 cores on Dimensity 7300 (indices may vary by kernel)
    for (int cpu = 4; cpu <= 7; cpu++) {
        CPU_SET(cpu, &cpuset);
    }
    if (sched_setaffinity(gettid(), sizeof(cpu_set_t), &cpuset) == 0) {
        LOGI("✅ Thread %d pinned to Big Cores (A78 cluster)", gettid());
    } else {
        LOGE("⚠️ Could not set CPU affinity, running on any core");
    }
}

// ─── Model Load ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeLoadGguf(
        JNIEnv *env,
        jobject /* this */,
        jstring modelPath,
        jint contextSize,
        jint nGpuLayers,
        jint nThreads,
        jint nBatch,
        jboolean useMemLock,
        jint seed) {

    set_big_core_affinity();

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("📦 Loading GGUF model: %s", path);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = nGpuLayers;  // 0 for Dimensity 7300 (CPU-only)
    model_params.use_mlock = useMemLock;

    llama_model *model = llama_load_model_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("❌ Failed to load model from file");
        return 0L;
    }

    LOGI("✅ Model loaded | vocab_size=%d", llama_n_vocab(model));
    return reinterpret_cast<jlong>(model);
}

// ─── Context Creation ────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeCreateContext(
        JNIEnv *env,
        jobject /* this */,
        jlong modelHandle,
        jint contextSize,
        jint nThreads) {

    auto *model = reinterpret_cast<llama_model *>(modelHandle);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;
    ctx_params.flash_attn = true;    // Flash attention if supported

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("❌ Failed to create context");
        return 0L;
    }

    LOGI("✅ Context created | n_ctx=%d | n_threads=%d", contextSize, nThreads);
    return reinterpret_cast<jlong>(ctx);
}

// ─── Sampler ────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeCreateSampler(
        JNIEnv *env,
        jobject /* this */,
        jfloat temperature,
        jfloat topP,
        jint topK,
        jfloat repeatPenalty) {

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        64,             // last_n
        repeatPenalty,  // repeat
        0.0f,           // freq
        0.0f            // present
    ));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("✅ Sampler created | temp=%.2f | top_p=%.2f | top_k=%d", temperature, topP, topK);
    return reinterpret_cast<jlong>(sampler);
}

// ─── Tokenize ────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jintArray JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeTokenize(
        JNIEnv *env,
        jobject /* this */,
        jlong contextHandle,
        jstring text) {

    auto *ctx = reinterpret_cast<llama_context *>(contextHandle);
    const llama_model *model = llama_get_model(ctx);

    const char *cText = env->GetStringUTFChars(text, nullptr);
    int nPrompt = -llama_tokenize(model, cText, strlen(cText), nullptr, 0, true, true);

    std::vector<llama_token> tokens(nPrompt);
    llama_tokenize(model, cText, strlen(cText), tokens.data(), tokens.size(), true, true);
    env->ReleaseStringUTFChars(text, cText);

    jintArray result = env->NewIntArray(tokens.size());
    env->SetIntArrayRegion(result, 0, tokens.size(), reinterpret_cast<const jint*>(tokens.data()));
    LOGD("🔢 Tokenized %d tokens", (int)tokens.size());
    return result;
}

// ─── Eval + Sample ───────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeEvalTokens(
        JNIEnv *env,
        jobject /* this */,
        jlong contextHandle,
        jintArray tokens,
        jint maxNew) {

    auto *ctx = reinterpret_cast<llama_context *>(contextHandle);
    jint *tkns = env->GetIntArrayElements(tokens, nullptr);
    jsize len = env->GetArrayLength(tokens);

    llama_batch batch = llama_batch_get_one(
        reinterpret_cast<llama_token*>(tkns), len
    );

    if (llama_decode(ctx, batch) != 0) {
        LOGE("❌ llama_decode failed on prompt tokens");
    }

    env->ReleaseIntArrayElements(tokens, tkns, JNI_ABORT);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeSampleNext(
        JNIEnv *env,
        jobject /* this */,
        jlong contextHandle,
        jlong samplerHandle) {

    auto *ctx = reinterpret_cast<llama_context *>(contextHandle);
    auto *sampler = reinterpret_cast<llama_sampler *>(samplerHandle);

    llama_token token = llama_sampler_sample(sampler, ctx, -1);

    llama_batch batch = llama_batch_get_one(&token, 1);
    llama_decode(ctx, batch);

    return static_cast<jint>(token);
}

// ─── Token to String ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeTokenToString(
        JNIEnv *env,
        jobject /* this */,
        jlong modelHandle,
        jint token) {

    auto *model = reinterpret_cast<llama_model *>(modelHandle);
    char buf[256];
    int len = llama_token_to_piece(model, token, buf, sizeof(buf), 0, true);
    if (len < 0) len = 0;
    buf[len] = '\0';
    return env->NewStringUTF(buf);
}

// ─── EOS Token ───────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeGetEosToken(
        JNIEnv *env,
        jobject /* this */,
        jlong modelHandle) {

    auto *model = reinterpret_cast<llama_model *>(modelHandle);
    return static_cast<jint>(llama_token_eos(model));
}

// ─── KV Cache ────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeClearKvCache(
        JNIEnv *env,
        jobject /* this */,
        jlong contextHandle) {

    auto *ctx = reinterpret_cast<llama_context *>(contextHandle);
    llama_kv_cache_clear(ctx);
    LOGD("🔄 KV cache cleared");
}

// ─── Free ────────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeFreeModel(
        JNIEnv *env, jobject, jlong handle) {
    llama_free_model(reinterpret_cast<llama_model *>(handle));
    LOGI("🗑️ Model freed");
}

extern "C" JNIEXPORT void JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeFreeContext(
        JNIEnv *env, jobject, jlong handle) {
    llama_free(reinterpret_cast<llama_context *>(handle));
    LOGI("🗑️ Context freed");
}

extern "C" JNIEXPORT void JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeFreeSampler(
        JNIEnv *env, jobject, jlong handle) {
    llama_sampler_free(reinterpret_cast<llama_sampler *>(handle));
    LOGI("🗑️ Sampler freed");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_synapt_nexus_inference_LlamaCppBridge_nativeGetModelSize(
        JNIEnv *env, jobject, jlong handle) {
    auto *model = reinterpret_cast<llama_model *>(handle);
    return static_cast<jlong>(llama_model_size(model));
}
