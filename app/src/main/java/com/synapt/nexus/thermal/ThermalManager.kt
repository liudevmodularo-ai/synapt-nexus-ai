package com.synapt.nexus.thermal

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * 🚀 ThermalManager — PULSE
 *
 * Adaptive thermal management for Dimensity 7300 (Redmi Note 14 Pro).
 * Monitors CPU temperature and adjusts inference parameters in real-time.
 *
 * 3 thermal modes:
 *   NORMAL  → <42°C  | 4 Big Cores | batch=512 | full speed
 *   REDUCED → 42-48°C | 2 Big Cores | batch=256 | 200ms token delay
 *   SAFE    → >48°C  | pause inference | notify clients with retry_after
 *
 * Also monitors RAM headroom and adjusts model selection accordingly.
 */
class ThermalManager(private val context: Context) {

    companion object {
        private const val TAG = "PULSE::ThermalManager"
        private const val POLL_INTERVAL_MS = 3_000L      // Check temp every 3 seconds

        // Temperature thresholds (°C)
        private const val TEMP_NORMAL_MAX = 42f
        private const val TEMP_REDUCED_MAX = 48f

        // Cortex-A78 core indices on Dimensity 7300
        // Actual indices vary by kernel, these are common values
        private val BIG_CORES = intArrayOf(4, 5, 6, 7)
        private val ALL_CORES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

        // Thermal zone files (Linux sysfs)
        private val THERMAL_ZONE_PATHS = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone2/temp",
        )
    }

    enum class ThermalMode(
        val label: String,
        val emoji: String,
        val nThreads: Int,
        val batchSize: Int,
        val tokenDelayMs: Long,
        val maxConcurrentRequests: Int
    ) {
        NORMAL(
            label = "Normal",
            emoji = "🟢",
            nThreads = 4,
            batchSize = 512,
            tokenDelayMs = 0L,
            maxConcurrentRequests = 3
        ),
        REDUCED(
            label = "Throttled",
            emoji = "🟡",
            nThreads = 2,
            batchSize = 256,
            tokenDelayMs = 200L,
            maxConcurrentRequests = 1
        ),
        SAFE(
            label = "Thermal Pause",
            emoji = "🔴",
            nThreads = 0,
            batchSize = 0,
            tokenDelayMs = -1L,           // -1 = inference paused
            maxConcurrentRequests = 0
        )
    }

    data class ThermalSnapshot(
        val temperatureCelsius: Float,
        val mode: ThermalMode,
        val availableRamMb: Long,
        val totalRamMb: Long,
        val ramUsagePercent: Float,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val isInferencePaused: Boolean get() = mode == ThermalMode.SAFE
        val retryAfterSeconds: Int get() = if (isInferencePaused) 15 else 0
    }

    // ─── State ───────────────────────────────────────────────────────────────
    private val _thermalState = MutableStateFlow(
        ThermalSnapshot(
            temperatureCelsius = 35f,
            mode = ThermalMode.NORMAL,
            availableRamMb = 4000L,
            totalRamMb = 8000L,
            ramUsagePercent = 0.5f
        )
    )

    val thermalState: StateFlow<ThermalSnapshot> = _thermalState.asStateFlow()
    val currentMode: ThermalMode get() = _thermalState.value.mode

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager

    // ─── Public API ───────────────────────────────────────────────────────────

    fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        Log.i(TAG, "🌡️ Starting thermal monitoring (interval=${POLL_INTERVAL_MS}ms)")

        monitorJob = scope.launch {
            while (isActive) {
                val temp = readCpuTemperature()
                val ramInfo = getRamInfo()
                val mode = computeMode(temp)
                val previous = _thermalState.value.mode

                if (mode != previous) {
                    Log.w(TAG, "🌡️ Thermal mode change: ${previous.emoji} ${previous.label} → ${mode.emoji} ${mode.label} | temp=${temp}°C")

                    if (mode == ThermalMode.SAFE) {
                        Log.e(TAG, "🔴 THERMAL PAUSE — inference suspended until temp drops below ${TEMP_REDUCED_MAX}°C")
                    }
                }

                _thermalState.value = ThermalSnapshot(
                    temperatureCelsius = temp,
                    mode = mode,
                    availableRamMb = ramInfo.first,
                    totalRamMb = ramInfo.second,
                    ramUsagePercent = 1f - (ramInfo.first.toFloat() / ramInfo.second)
                )

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        Log.i(TAG, "🌡️ Thermal monitoring stopped")
    }

    /**
     * Returns true if safe to start a new inference request
     */
    fun canAcceptInference(): Boolean {
        return _thermalState.value.mode != ThermalMode.SAFE
    }

    /**
     * Returns current recommended thread count for inference
     */
    fun getRecommendedThreadCount(): Int = currentMode.nThreads

    /**
     * Returns current recommended batch size
     */
    fun getRecommendedBatchSize(): Int = currentMode.batchSize

    /**
     * Returns delay in ms between token emissions (for stream throttling)
     */
    fun getTokenEmitDelay(): Long = currentMode.tokenDelayMs

    /**
     * Estimates max model size that can safely load given available RAM
     * Returns size in GB
     */
    fun getRecommendedMaxModelSizeGb(): Float {
        val availableGb = _thermalState.value.availableRamMb / 1024f
        // Keep 1.5GB headroom for OS + app overhead
        return (availableGb - 1.5f).coerceAtLeast(0f)
    }

    // ─── Temperature Reading ──────────────────────────────────────────────────

    private fun readCpuTemperature(): Float {
        // Method 1: Linux sysfs thermal zones
        for (path in THERMAL_ZONE_PATHS) {
            try {
                val tempMilliCelsius = File(path).readText().trim().toLongOrNull() ?: continue
                val tempCelsius = tempMilliCelsius / 1000f
                if (tempCelsius in 0f..120f) {
                    return tempCelsius
                }
            } catch (_: Exception) { /* zone not available */ }
        }

        // Method 2: Android PowerManager thermal status (API 29+)
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (pm.thermalStatus) {
                PowerManager.THERMAL_STATUS_NONE,
                PowerManager.THERMAL_STATUS_LIGHT -> 35f
                PowerManager.THERMAL_STATUS_MODERATE -> 43f
                PowerManager.THERMAL_STATUS_SEVERE -> 50f
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> 60f
                else -> 35f
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read temperature via PowerManager: ${e.message}")
            35f  // Safe default
        }
    }

    private fun getRamInfo(): Pair<Long, Long> {
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return Pair(
            memInfo.availMem / (1024 * 1024),   // MB available
            memInfo.totalMem / (1024 * 1024)    // MB total
        )
    }

    private fun computeMode(temp: Float): ThermalMode = when {
        temp >= TEMP_REDUCED_MAX -> ThermalMode.SAFE
        temp >= TEMP_NORMAL_MAX  -> ThermalMode.REDUCED
        else                     -> ThermalMode.NORMAL
    }
}
