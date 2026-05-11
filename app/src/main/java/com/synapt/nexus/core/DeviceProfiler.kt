package com.synapt.nexus.core

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 🤖 DeviceProfiler — ARIA + PULSE
 *
 * Executa na primeira instalação e detecta automaticamente
 * as capacidades do hardware para selecionar o tier de modelos.
 *
 * Sem input do usuário — o app se adapta sozinho.
 */
class DeviceProfiler(private val context: Context) {

    companion object {
        private const val TAG = "ARIA::DeviceProfiler"
    }

    enum class ModelTier { LOW, MID, HIGH }

    data class DeviceProfile(
        // Hardware
        val socName: String,
        val cpuCores: Int,
        val cpuMaxFreqMhz: Int,
        val totalRamMb: Long,
        val availableRamMb: Long,
        val totalStorageMb: Long,
        val gpuRenderer: String,

        // Capabilities
        val hasNnapi: Boolean,
        val hasVulkan: Boolean,
        val hasOpenCl: Boolean,
        val isArm64: Boolean,
        val hasNeon: Boolean,

        // Scoring
        val deviceScore: Int,           // 0–100
        val recommendedTier: ModelTier,
        val recommendedRamForModelsMb: Long,

        // Device identity
        val manufacturer: String = Build.MANUFACTURER,
        val model: String        = Build.MODEL,
        val androidApi: Int      = Build.VERSION.SDK_INT
    )

    // ─── Profile Detection ────────────────────────────────────────────────────

    suspend fun buildProfile(): DeviceProfile = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔍 Building device profile for ${Build.MODEL}...")

        val ram      = getRamInfo()
        val storage  = getStorageInfo()
        val cpu      = getCpuInfo()
        val gpu      = getGpuInfo()
        val caps     = getCapabilities()

        val score    = computeDeviceScore(ram.first, cpu.first, cpu.second, caps)
        val tier     = computeTier(ram.first, score)
        val ramForModels = (ram.first * 0.55).toLong()  // 55% of RAM for models

        val profile = DeviceProfile(
            socName              = cpu.third,
            cpuCores             = cpu.first,
            cpuMaxFreqMhz        = cpu.second,
            totalRamMb           = ram.first,
            availableRamMb       = ram.second,
            totalStorageMb       = storage,
            gpuRenderer          = gpu,
            hasNnapi             = caps.hasNnapi,
            hasVulkan            = caps.hasVulkan,
            hasOpenCl            = caps.hasOpenCl,
            isArm64              = caps.isArm64,
            hasNeon              = caps.hasNeon,
            deviceScore          = score,
            recommendedTier      = tier,
            recommendedRamForModelsMb = ramForModels
        )

        Log.i(TAG, """
            ✅ Device Profile Built:
               Device:    ${profile.manufacturer} ${profile.model}
               SoC:       ${profile.socName}
               RAM:       ${profile.totalRamMb}MB total | ${profile.availableRamMb}MB free
               CPU:       ${profile.cpuCores} cores @ ${profile.cpuMaxFreqMhz}MHz
               GPU:       ${profile.gpuRenderer}
               ARM64:     ${profile.isArm64} | NEON: ${profile.hasNeon}
               NNAPI:     ${profile.hasNnapi} | Vulkan: ${profile.hasVulkan}
               Score:     ${profile.deviceScore}/100
               Tier:      ${profile.recommendedTier}
               RAM→Model: ${profile.recommendedRamForModelsMb}MB
        """.trimIndent())

        profile
    }

    // ─── Hardware Detection ───────────────────────────────────────────────────

    private fun getRamInfo(): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return Pair(mi.totalMem / (1024 * 1024), mi.availMem / (1024 * 1024))
    }

    private fun getStorageInfo(): Long {
        val stat = StatFs(context.filesDir.path)
        return stat.totalBytes / (1024 * 1024)
    }

    /** Returns (coreCount, maxFreqMHz, socName) */
    private fun getCpuInfo(): Triple<Int, Int, String> {
        val cores = Runtime.getRuntime().availableProcessors()

        // Read max CPU freq from sysfs
        var maxFreqKhz = 0
        for (i in 0 until cores) {
            try {
                val freqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                val freq = freqFile.readText().trim().toIntOrNull() ?: 0
                if (freq > maxFreqKhz) maxFreqKhz = freq
            } catch (_: Exception) {}
        }

        // Read SoC name from cpuinfo
        val socName = try {
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Hardware") || it.startsWith("model name") }
                ?.substringAfter(":")?.trim() ?: Build.HARDWARE
        } catch (_: Exception) { Build.HARDWARE }

        return Triple(cores, maxFreqKhz / 1000, socName)
    }

    private fun getGpuInfo(): String {
        return try {
            GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unknown GPU"
        } catch (_: Exception) { "Unknown GPU" }
    }

    data class Capabilities(
        val hasNnapi: Boolean,
        val hasVulkan: Boolean,
        val hasOpenCl: Boolean,
        val isArm64: Boolean,
        val hasNeon: Boolean
    )

    private fun getCapabilities(): Capabilities {
        val isArm64 = Build.SUPPORTED_ABIS.contains("arm64-v8a")
        val hasVulkan = context.packageManager.hasSystemFeature("android.hardware.vulkan.level")
        val hasNnapi  = Build.VERSION.SDK_INT >= 27   // NNAPI available API 27+

        // NEON is standard on all ARM64
        val hasNeon = isArm64

        // OpenCL: probe by checking common lib paths
        val hasOpenCl = listOf(
            "/system/lib64/libOpenCL.so",
            "/vendor/lib64/libOpenCL.so",
            "/system/lib/libOpenCL.so"
        ).any { File(it).exists() }

        return Capabilities(hasNnapi, hasVulkan, hasOpenCl, isArm64, hasNeon)
    }

    // ─── Scoring ──────────────────────────────────────────────────────────────

    private fun computeDeviceScore(
        ramMb: Long,
        cpuCores: Int,
        cpuMaxMhz: Int,
        caps: Capabilities
    ): Int {
        var score = 0

        // RAM score (0–40 pts)
        score += when {
            ramMb >= 12_000 -> 40
            ramMb >= 8_000  -> 32
            ramMb >= 6_000  -> 22
            ramMb >= 4_000  -> 12
            else            -> 5
        }

        // CPU score (0–30 pts)
        score += when {
            cpuMaxMhz >= 3000 -> 30
            cpuMaxMhz >= 2500 -> 24
            cpuMaxMhz >= 2000 -> 16
            cpuMaxMhz >= 1800 -> 10
            else              -> 5
        }
        if (cpuCores >= 8) score += 5

        // Accelerator bonuses (0–20 pts)
        if (caps.hasNnapi)  score += 8
        if (caps.hasVulkan) score += 7
        if (caps.hasOpenCl) score += 5

        // ARM64 + NEON (0–10 pts)
        if (caps.isArm64) score += 7
        if (caps.hasNeon) score += 3

        return score.coerceIn(0, 100)
    }

    private fun computeTier(ramMb: Long, score: Int): ModelTier {
        // Both RAM AND score must qualify
        val ramTier = when {
            ramMb >= 10_000 -> ModelTier.HIGH
            ramMb >= 6_000  -> ModelTier.MID
            else            -> ModelTier.LOW
        }
        val scoreTier = when {
            score >= 65 -> ModelTier.HIGH
            score >= 35 -> ModelTier.MID
            else        -> ModelTier.LOW
        }
        // Use the lower of the two (conservative)
        return minOf(ramTier, scoreTier, compareBy { it.ordinal })
    }

    private fun <T> minOf(a: T, b: T, comparator: Comparator<T>): T =
        if (comparator.compare(a, b) <= 0) a else b
}
