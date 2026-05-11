package com.synapt.nexus.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * ⚙️ ModelManager — VECTOR
 *
 * Gerencia o ciclo de vida de modelos no dispositivo:
 *   - Scan de modelos GGUF e ONNX no armazenamento
 *   - Download de modelos (Sprint 2: HuggingFace Hub)
 *   - Metadados: tamanho, tier, formato, estado
 *   - Seleção automática baseada no DeviceProfile (Sprint 2)
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "VECTOR::ModelManager"
        private val MODEL_DIRS = listOf("models", "Download/models", "SynaptNexus/models")
    }

    enum class ModelFormat { GGUF, ONNX, EXECUTORCH }
    enum class ModelTier { LOW, MID, HIGH }
    enum class ModelStatus { AVAILABLE, DOWNLOADING, LOADED, ERROR }

    data class ModelInfo(
        val id: String,
        val displayName: String,
        val filePath: String,
        val format: ModelFormat,
        val sizeBytes: Long,
        val tier: ModelTier,
        val status: ModelStatus = ModelStatus.AVAILABLE,
        val downloadProgress: Float = 0f
    ) {
        val sizeMb: Long get() = sizeBytes / (1024 * 1024)
        val sizeGb: Float get() = sizeBytes / (1024f * 1024 * 1024)
    }

    // ─── Curated model catalog (downloadable) ─────────────────────────────────

    val catalog: List<CatalogEntry> = listOf(
        // TIER LOW
        CatalogEntry("gemma-2-2b-q4",   "Gemma 2 2B",     ModelTier.LOW,  ModelFormat.GGUF,
            "https://huggingface.co/bartowski/gemma-2-2b-GGUF/resolve/main/gemma-2-2b-Q4_K_M.gguf",
            1_600_000_000L),
        CatalogEntry("phi3-mini-onnx",   "Phi-3 Mini",     ModelTier.LOW,  ModelFormat.ONNX,
            "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-onnx-mobile/resolve/main/phi3-mini-4k-instruct-mobile-int4.onnx",
            2_500_000_000L),

        // TIER MID
        CatalogEntry("phi3-mini-q4",     "Phi-3 Mini Q4",  ModelTier.MID,  ModelFormat.GGUF,
            "https://huggingface.co/bartowski/Phi-3-mini-4k-instruct-GGUF/resolve/main/Phi-3-mini-4k-instruct-Q4_K_M.gguf",
            2_300_000_000L),
        CatalogEntry("llama32-3b-q4",    "Llama 3.2 3B",   ModelTier.MID,  ModelFormat.GGUF,
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            2_000_000_000L),
        CatalogEntry("mistral-7b-q4",    "Mistral 7B Q4",  ModelTier.MID,  ModelFormat.GGUF,
            "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF/resolve/main/mistral-7b-instruct-v0.2.Q4_K_M.gguf",
            4_100_000_000L),

        // TIER HIGH
        CatalogEntry("qwen25-7b-q4",     "Qwen 2.5 7B",    ModelTier.HIGH, ModelFormat.GGUF,
            "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf",
            4_400_000_000L),
        CatalogEntry("mistral-7b-q5",    "Mistral 7B Q5",  ModelTier.HIGH, ModelFormat.GGUF,
            "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF/resolve/main/mistral-7b-instruct-v0.2.Q5_K_M.gguf",
            5_100_000_000L),
    )

    data class CatalogEntry(
        val id: String,
        val displayName: String,
        val tier: ModelTier,
        val format: ModelFormat,
        val downloadUrl: String,
        val approximateSizeBytes: Long
    )

    // ─── Scan local storage ───────────────────────────────────────────────────

    fun getAvailableModels(): List<ModelInfo> {
        val found = mutableListOf<ModelInfo>()

        // Scan internal models dir
        val internalDir = File(context.filesDir, "models")
        if (internalDir.exists()) scanDir(internalDir, found)

        // Scan external storage
        context.getExternalFilesDirs("models").forEach { dir ->
            dir?.let { if (it.exists()) scanDir(it, found) }
        }

        Log.d(TAG, "📦 Found ${found.size} models on device")
        return found
    }

    private fun scanDir(dir: File, result: MutableList<ModelInfo>) {
        dir.listFiles()?.forEach { file ->
            when {
                file.name.endsWith(".gguf") -> result.add(fileToModelInfo(file, ModelFormat.GGUF))
                file.name.endsWith(".onnx") -> result.add(fileToModelInfo(file, ModelFormat.ONNX))
                file.name.endsWith(".pte")  -> result.add(fileToModelInfo(file, ModelFormat.EXECUTORCH))
            }
        }
    }

    private fun fileToModelInfo(file: File, format: ModelFormat): ModelInfo {
        val id = file.nameWithoutExtension
        val sizeBytes = file.length()
        val tier = when {
            sizeBytes < 3_000_000_000L -> ModelTier.LOW
            sizeBytes < 5_500_000_000L -> ModelTier.MID
            else                       -> ModelTier.HIGH
        }
        // Match against catalog for better display name
        val catalogEntry = catalog.find { it.id == id || file.name.contains(it.id, ignoreCase = true) }
        return ModelInfo(
            id          = id,
            displayName = catalogEntry?.displayName ?: id,
            filePath    = file.absolutePath,
            format      = format,
            sizeBytes   = sizeBytes,
            tier        = tier
        )
    }

    fun findById(id: String): ModelInfo? =
        getAvailableModels().find { it.id == id }

    fun getModelsDir(): File =
        File(context.filesDir, "models").also { it.mkdirs() }

    fun getTotalStorageUsedMb(): Long =
        getAvailableModels().sumOf { it.sizeMb }
}
