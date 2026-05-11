package com.synapt.nexus.inference

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ⚙️ LlamaCppBridge — VECTOR
 *
 * JNI bridge between Kotlin and llama.cpp native engine.
 * Handles model lifecycle, context management, and token streaming.
 *
 * Thread safety: All native calls dispatched on Dispatchers.IO
 * Memory: KV-cache persisted per session for repeat clients
 */
class LlamaCppBridge private constructor() {

    companion object {
        private const val TAG = "VECTOR::LlamaBridge"

        init {
            System.loadLibrary("synapt_inference")
            Log.i(TAG, "✅ Native library loaded: synapt_inference")
        }

        @Volatile
        private var instance: LlamaCppBridge? = null

        fun getInstance(): LlamaCppBridge = instance ?: synchronized(this) {
            instance ?: LlamaCppBridge().also { instance = it }
        }
    }

    // ─── Native handles (pointers as Long) ─────────────────────────────────
    private var modelHandle: Long = 0L
    private var contextHandle: Long = 0L
    private var samplerHandle: Long = 0L

    // ─── State ──────────────────────────────────────────────────────────────
    @Volatile var isModelLoaded: Boolean = false
        private set

    @Volatile var loadedModelPath: String = ""
        private set

    @Volatile var currentModelFormat: ModelFormat = ModelFormat.NONE
        private set

    // ─── Model Config ───────────────────────────────────────────────────────
    data class ModelConfig(
        val contextSize: Int = 4096,
        val nGpuLayers: Int = 0,          // Mali-G615 MC2: keep at 0, CPU-only
        val nThreads: Int = 4,            // Use Big Cores (A78) only
        val nBatch: Int = 512,
        val useMemoryLock: Boolean = true,
        val seed: Int = -1,               // -1 = random
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val topK: Int = 40,
        val repeatPenalty: Float = 1.1f
    )

    data class InferenceRequest(
        val sessionId: String,
        val prompt: String,
        val maxTokens: Int = 512,
        val temperature: Float = 0.7f,
        val stopSequences: List<String> = listOf("</s>", "[INST]", "[/INST]")
    )

    data class TokenResult(
        val token: String,
        val isFinished: Boolean,
        val tokensGenerated: Int,
        val tokensPerSecond: Float
    )

    enum class ModelFormat { NONE, GGUF, ONNX }

    // ─── Public API ─────────────────────────────────────────────────────────

    /**
     * Load a GGUF model file into memory.
     * Validates file format before loading.
     */
    suspend fun loadGgufModel(modelFile: File, config: ModelConfig = ModelConfig()): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(modelFile.exists()) { "Model file not found: ${modelFile.path}" }
                require(modelFile.extension == "gguf") { "Expected .gguf file" }
                require(validateGgufMagic(modelFile)) { "Invalid GGUF magic bytes" }

                if (isModelLoaded) {
                    Log.d(TAG, "Unloading previous model before loading new one")
                    unloadModel()
                }

                Log.i(TAG, "📦 Loading GGUF: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")

                modelHandle = nativeLoadGguf(
                    modelPath = modelFile.absolutePath,
                    contextSize = config.contextSize,
                    nGpuLayers = config.nGpuLayers,
                    nThreads = config.nThreads,
                    nBatch = config.nBatch,
                    useMemLock = config.useMemoryLock,
                    seed = config.seed
                )

                check(modelHandle != 0L) { "Native model load returned null handle" }

                contextHandle = nativeCreateContext(modelHandle, config.contextSize, config.nThreads)
                check(contextHandle != 0L) { "Context creation failed" }

                samplerHandle = nativeCreateSampler(
                    temperature = config.temperature,
                    topP = config.topP,
                    topK = config.topK,
                    repeatPenalty = config.repeatPenalty
                )

                isModelLoaded = true
                loadedModelPath = modelFile.absolutePath
                currentModelFormat = ModelFormat.GGUF
                Log.i(TAG, "✅ GGUF model loaded successfully: ${modelFile.name}")
            }
        }

    /**
     * Stream inference tokens as a Flow.
     * Emits TokenResult for each generated token.
     * Caller must handle stop sequences.
     */
    fun streamInference(request: InferenceRequest): Flow<TokenResult> = flow {
        check(isModelLoaded) { "No model loaded. Call loadGgufModel first." }

        Log.d(TAG, "🔄 Starting inference | session=${request.sessionId} | maxTokens=${request.maxTokens}")

        val startTime = System.currentTimeMillis()
        var tokenCount = 0

        // Tokenize the prompt
        val tokens = nativeTokenize(contextHandle, request.prompt)
        nativeEvalTokens(contextHandle, tokens, request.maxTokens)

        // KV-cache: evaluate prompt tokens
        var finished = false
        while (!finished && tokenCount < request.maxTokens) {
            val rawToken = nativeSampleNext(contextHandle, samplerHandle)
            val tokenStr = nativeTokenToString(modelHandle, rawToken)

            tokenCount++
            val elapsed = (System.currentTimeMillis() - startTime) / 1000f
            val tps = if (elapsed > 0) tokenCount / elapsed else 0f

            // Check stop sequences
            finished = request.stopSequences.any { tokenStr.contains(it) } || rawToken == nativeGetEosToken(modelHandle)

            emit(TokenResult(
                token = tokenStr,
                isFinished = finished,
                tokensGenerated = tokenCount,
                tokensPerSecond = tps
            ))
        }

        Log.d(TAG, "✅ Inference complete | tokens=$tokenCount | session=${request.sessionId}")
    }.flowOn(Dispatchers.IO)

    /**
     * Reset context KV-cache (call between unrelated requests from same session)
     */
    suspend fun resetContext(): Unit = withContext(Dispatchers.IO) {
        if (contextHandle != 0L) {
            nativeClearKvCache(contextHandle)
            Log.d(TAG, "🔄 KV-cache cleared")
        }
    }

    /**
     * Unload model and free all native memory
     */
    suspend fun unloadModel(): Unit = withContext(Dispatchers.IO) {
        if (samplerHandle != 0L) { nativeFreeSampler(samplerHandle); samplerHandle = 0L }
        if (contextHandle != 0L) { nativeFreeContext(contextHandle); contextHandle = 0L }
        if (modelHandle != 0L) { nativeFreeModel(modelHandle); modelHandle = 0L }
        isModelLoaded = false
        loadedModelPath = ""
        currentModelFormat = ModelFormat.NONE
        Log.i(TAG, "🗑️ Model unloaded, native memory freed")
    }

    /**
     * Returns current memory usage of the loaded model in bytes
     */
    fun getModelMemoryUsage(): Long {
        return if (modelHandle != 0L) nativeGetModelSize(modelHandle) else 0L
    }

    // ─── Validation ─────────────────────────────────────────────────────────

    private fun validateGgufMagic(file: File): Boolean {
        val magic = ByteArray(4)
        file.inputStream().use { it.read(magic) }
        // GGUF magic: 0x47 0x47 0x55 0x46 ("GGUF")
        return magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
    }

    // ─── Native Declarations ────────────────────────────────────────────────

    private external fun nativeLoadGguf(
        modelPath: String,
        contextSize: Int,
        nGpuLayers: Int,
        nThreads: Int,
        nBatch: Int,
        useMemLock: Boolean,
        seed: Int
    ): Long

    private external fun nativeCreateContext(modelHandle: Long, contextSize: Int, nThreads: Int): Long
    private external fun nativeCreateSampler(temperature: Float, topP: Float, topK: Int, repeatPenalty: Float): Long
    private external fun nativeTokenize(contextHandle: Long, text: String): IntArray
    private external fun nativeEvalTokens(contextHandle: Long, tokens: IntArray, maxNew: Int)
    private external fun nativeSampleNext(contextHandle: Long, samplerHandle: Long): Int
    private external fun nativeTokenToString(modelHandle: Long, token: Int): String
    private external fun nativeGetEosToken(modelHandle: Long): Int
    private external fun nativeClearKvCache(contextHandle: Long)
    private external fun nativeFreeModel(modelHandle: Long)
    private external fun nativeFreeContext(contextHandle: Long)
    private external fun nativeFreeSampler(samplerHandle: Long)
    private external fun nativeGetModelSize(modelHandle: Long): Long
}
