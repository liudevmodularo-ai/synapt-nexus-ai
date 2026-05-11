package com.synapt.nexus.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.RandomAccessFile

/**
 * 🚀 SystemMonitor — PULSE
 *
 * Coleta métricas detalhadas do sistema em tempo real.
 * Leitura de sysfs + procfs + Android APIs.
 *
 * Dados coletados:
 *   - CPU: uso % por núcleo, frequência, governor, cluster (Big/Little)
 *   - Temperatura: todas as zonas térmicas disponíveis
 *   - RAM: breakdown completo do /proc/meminfo
 *   - Rede: velocidade TX/RX em tempo real + por app
 *   - Bateria: nível, temperatura, estado, voltagem, corrente
 *   - Processos: lista de apps ativos com uso de RAM
 *   - Storage: uso por partição
 */
class SystemMonitor(private val context: Context) {

    companion object {
        private const val TAG = "PULSE::SystemMonitor"
        private const val POLL_INTERVAL_MS = 800L   // ~1.25 FPS para o dashboard
        private const val N_CORES = 8               // Dimensity 7300
    }

    // ─── Data Models ─────────────────────────────────────────────────────────

    data class CpuCore(
        val id: Int,
        val usagePercent: Float,        // 0-100
        val frequencyMhz: Int,
        val maxFrequencyMhz: Int,
        val governor: String,
        val isOnline: Boolean,
        val cluster: CpuCluster         // BIG (A78) ou LITTLE (A55)
    )

    enum class CpuCluster { BIG, LITTLE }

    data class ThermalZone(
        val id: Int,
        val name: String,               // "cpu-cluster0", "gpu", etc.
        val temperatureCelsius: Float,
        val category: ThermalCategory
    )

    enum class ThermalCategory { CPU_LITTLE, CPU_BIG, GPU, BATTERY, SKIN, MODEM, NPU, UNKNOWN }

    data class RamInfo(
        val totalMb: Long,
        val availableMb: Long,
        val usedMb: Long,
        val cachedMb: Long,
        val buffersMb: Long,
        val swapTotalMb: Long,
        val swapUsedMb: Long,
        val activeAnonMb: Long,         // anon pages in use
        val mappedMb: Long,             // files mapped into memory
        val dirtyMb: Long,              // pages waiting to be written
        val usagePercent: Float
    )

    data class NetworkStats(
        val rxSpeedKbps: Float,
        val txSpeedKbps: Float,
        val totalRxMb: Long,
        val totalTxMb: Long,
        val topApps: List<AppNetworkUsage>
    )

    data class AppNetworkUsage(
        val packageName: String,
        val displayName: String,
        val rxKbps: Float,
        val txKbps: Float
    )

    data class BatteryInfo(
        val levelPercent: Int,
        val temperatureCelsius: Float,
        val isCharging: Boolean,
        val chargingType: String,       // "USB", "AC", "Wireless", "Não"
        val voltageMv: Int,
        val currentMa: Int,             // negativo = descarga
        val estimatedRemainingMin: Int,
        val health: String
    )

    data class ProcessInfo(
        val pid: Int,
        val packageName: String,
        val displayName: String,
        val memoryMb: Float,
        val isKillable: Boolean,        // false = system/essential
        val importance: Int             // ActivityManager importance level
    )

    data class StorageInfo(
        val internalTotalGb: Float,
        val internalFreeGb: Float,
        val externalTotalGb: Float,
        val externalFreeGb: Float
    )

    data class SystemSnapshot(
        val cores: List<CpuCore>,
        val thermalZones: List<ThermalZone>,
        val ram: RamInfo,
        val network: NetworkStats,
        val battery: BatteryInfo,
        val processes: List<ProcessInfo>,
        val storage: StorageInfo,
        val timestampMs: Long = System.currentTimeMillis()
    )

    // ─── State ───────────────────────────────────────────────────────────────

    private val _snapshot = MutableStateFlow<SystemSnapshot?>(null)
    val snapshot: StateFlow<SystemSnapshot?> = _snapshot.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    // Previous CPU stat for delta calculation
    private var prevCpuStats: Array<LongArray?> = arrayOfNulls(N_CORES)
    private var prevRxBytes = TrafficStats.getTotalRxBytes()
    private var prevTxBytes = TrafficStats.getTotalTxBytes()
    private var prevNetworkTs = System.currentTimeMillis()

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        if (monitorJob?.isActive == true) return
        Log.i(TAG, "📊 SystemMonitor starting (interval=${POLL_INTERVAL_MS}ms)")
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    _snapshot.value = collectSnapshot()
                } catch (e: Exception) {
                    Log.w(TAG, "Snapshot collection error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    // ─── CPU Per-Core ─────────────────────────────────────────────────────────

    private fun collectCpuCores(): List<CpuCore> {
        val cores = mutableListOf<CpuCore>()
        for (i in 0 until N_CORES) {
            val usage   = readCoreUsage(i)
            val freq    = readSysfs("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.toLongOrNull()?.div(1000)?.toInt() ?: 0
            val maxFreq = readSysfs("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.toLongOrNull()?.div(1000)?.toInt() ?: 0
            val online  = readSysfs("/sys/devices/system/cpu/cpu$i/online")?.trim() != "0"
            val governor = readSysfs("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor")?.trim() ?: "unknown"

            // Dimensity 7300: cores 0-3 = Little (A55), cores 4-7 = Big (A78)
            val cluster = if (i >= 4) CpuCluster.BIG else CpuCluster.LITTLE

            cores.add(CpuCore(
                id = i, usagePercent = usage, frequencyMhz = freq,
                maxFrequencyMhz = maxFreq, governor = governor,
                isOnline = online, cluster = cluster
            ))
        }
        return cores
    }

    private fun readCoreUsage(coreId: Int): Float {
        return try {
            val procStat = File("/proc/stat").readLines()
            val coreLine = procStat.firstOrNull { it.startsWith("cpu$coreId ") } ?: return 0f
            val parts = coreLine.split(" ").drop(1).map { it.toLongOrNull() ?: 0L }
            if (parts.size < 4) return 0f

            val user    = parts[0]; val nice   = parts[1]
            val system  = parts[2]; val idle   = parts[3]
            val iowait  = if (parts.size > 4) parts[4] else 0L
            val irq     = if (parts.size > 5) parts[5] else 0L
            val softirq = if (parts.size > 6) parts[6] else 0L

            val total = user + nice + system + idle + iowait + irq + softirq
            val work  = user + nice + system + irq + softirq

            val prev = prevCpuStats[coreId]
            val prevTotal = prev?.get(0) ?: 0L
            val prevWork  = prev?.get(1) ?: 0L

            prevCpuStats[coreId] = longArrayOf(total, work)

            val deltaTotal = total - prevTotal
            val deltaWork  = work - prevWork
            if (deltaTotal <= 0) return 0f
            (deltaWork.toFloat() / deltaTotal * 100f).coerceIn(0f, 100f)
        } catch (_: Exception) { 0f }
    }

    // ─── Thermal Zones ────────────────────────────────────────────────────────

    private fun collectThermalZones(): List<ThermalZone> {
        val zones = mutableListOf<ThermalZone>()
        val baseDir = File("/sys/class/thermal")
        if (!baseDir.exists()) return zones

        baseDir.listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 99 }
            ?.forEach { dir ->
                try {
                    val id   = dir.name.removePrefix("thermal_zone").toInt()
                    val temp = File(dir, "temp").readText().trim().toLong() / 1000f
                    val type = try { File(dir, "type").readText().trim() } catch (_: Exception) { "zone$id" }
                    if (temp in 0f..120f) {
                        zones.add(ThermalZone(id, type, temp, classifyThermal(type)))
                    }
                } catch (_: Exception) {}
            }

        // Add battery temperature from BatteryManager
        val batteryTemp = getBatteryTemperature()
        if (batteryTemp > 0f) {
            zones.add(ThermalZone(99, "battery", batteryTemp, ThermalCategory.BATTERY))
        }
        return zones
    }

    private fun classifyThermal(type: String): ThermalCategory = when {
        type.contains("cpu") && (type.contains("little") || type.contains("0")) -> ThermalCategory.CPU_LITTLE
        type.contains("cpu") || type.contains("cluster") -> ThermalCategory.CPU_BIG
        type.contains("gpu") -> ThermalCategory.GPU
        type.contains("battery") || type.contains("batt") -> ThermalCategory.BATTERY
        type.contains("skin") || type.contains("board") -> ThermalCategory.SKIN
        type.contains("modem") || type.contains("mdm") -> ThermalCategory.MODEM
        type.contains("npu") || type.contains("apu") -> ThermalCategory.NPU
        else -> ThermalCategory.UNKNOWN
    }

    // ─── RAM ─────────────────────────────────────────────────────────────────

    private fun collectRam(): RamInfo {
        val memInfo = mutableMapOf<String, Long>()
        try {
            File("/proc/meminfo").forEachLine { line ->
                val parts = line.split(":").map { it.trim() }
                if (parts.size >= 2) {
                    memInfo[parts[0]] = parts[1].replace(" kB", "").trim().toLongOrNull()?.div(1024) ?: 0L
                }
            }
        } catch (_: Exception) {}

        val total = memInfo["MemTotal"] ?: 0L
        val available = memInfo["MemAvailable"] ?: 0L
        val used = total - available
        return RamInfo(
            totalMb      = total,
            availableMb  = available,
            usedMb       = used,
            cachedMb     = memInfo["Cached"] ?: 0L,
            buffersMb    = memInfo["Buffers"] ?: 0L,
            swapTotalMb  = memInfo["SwapTotal"] ?: 0L,
            swapUsedMb   = (memInfo["SwapTotal"] ?: 0L) - (memInfo["SwapFree"] ?: 0L),
            activeAnonMb = memInfo["AnonPages"] ?: 0L,
            mappedMb     = memInfo["Mapped"] ?: 0L,
            dirtyMb      = memInfo["Dirty"] ?: 0L,
            usagePercent = if (total > 0) used.toFloat() / total * 100f else 0f
        )
    }

    // ─── Network ─────────────────────────────────────────────────────────────

    private fun collectNetwork(): NetworkStats {
        val now = System.currentTimeMillis()
        val rx  = TrafficStats.getTotalRxBytes()
        val tx  = TrafficStats.getTotalTxBytes()
        val dt  = (now - prevNetworkTs) / 1000f

        val rxKbps = if (dt > 0 && prevRxBytes > 0) ((rx - prevRxBytes) / dt / 1024f).coerceAtLeast(0f) else 0f
        val txKbps = if (dt > 0 && prevTxBytes > 0) ((tx - prevTxBytes) / dt / 1024f).coerceAtLeast(0f) else 0f

        prevRxBytes = rx; prevTxBytes = tx; prevNetworkTs = now

        return NetworkStats(
            rxSpeedKbps = rxKbps,
            txSpeedKbps = txKbps,
            totalRxMb   = rx / (1024 * 1024),
            totalTxMb   = tx / (1024 * 1024),
            topApps     = emptyList()  // UsageStatsManager needs special permission — Sprint 2
        )
    }

    // ─── Battery ─────────────────────────────────────────────────────────────

    private fun collectBattery(): BatteryInfo {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level    = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale    = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp     = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val status   = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged  = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val voltage  = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val health   = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Bom"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Superaquecido"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Crítico"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobretensão"
            else -> "Desconhecido"
        }

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentMa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        val chargingType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB      -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC       -> "AC"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Sem fio"
            else -> "Não"
        }

        val levelPct = if (scale > 0) (level * 100 / scale) else 0
        val remaining = if (!isCharging && currentMa < 0) {
            // Estimate: (available_mAh / discharge_rate) * 60
            // Approximation without capacity info
            ((levelPct / 100f * 5000f) / (-currentMa) * 60).toInt()
        } else -1

        return BatteryInfo(levelPct, temp, isCharging, chargingType, voltage, currentMa, remaining, health)
    }

    // ─── Processes ────────────────────────────────────────────────────────────

    private fun collectProcesses(): List<ProcessInfo> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = context.packageManager

        val protectedPackages = setOf(
            "android", "com.android.systemui", "com.android.phone",
            "com.android.settings", context.packageName
        )

        return am.getRunningAppProcesses()
            ?.filter { it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE }
            ?.map { proc ->
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val pids = intArrayOf(proc.pid)
                val memMb = try {
                    am.getProcessMemoryInfo(pids).firstOrNull()?.totalPss?.div(1024f) ?: 0f
                } catch (_: Exception) { 0f }

                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(proc.processName, 0)).toString()
                } catch (_: Exception) { proc.processName.substringAfterLast(".") }

                ProcessInfo(
                    pid = proc.pid,
                    packageName = proc.processName,
                    displayName = appName,
                    memoryMb = memMb,
                    isKillable = proc.processName !in protectedPackages,
                    importance = proc.importance
                )
            }
            ?.sortedByDescending { it.memoryMb }
            ?.take(20)
            ?: emptyList()
    }

    fun killProcess(packageName: String) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(packageName)
        Log.i(TAG, "🔪 Killed: $packageName")
    }

    // ─── Storage ─────────────────────────────────────────────────────────────

    private fun collectStorage(): StorageInfo {
        val internal = android.os.StatFs(context.filesDir.path)
        val ext = context.getExternalFilesDirs(null).firstOrNull()
        val extStat = ext?.let { runCatching { android.os.StatFs(it.path) }.getOrNull() }
        return StorageInfo(
            internalTotalGb = internal.totalBytes / 1e9f,
            internalFreeGb  = internal.freeBytes  / 1e9f,
            externalTotalGb = extStat?.totalBytes?.div(1e9f) ?: 0f,
            externalFreeGb  = extStat?.freeBytes?.div(1e9f)  ?: 0f
        )
    }

    private fun getBatteryTemperature(): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    }

    private fun readSysfs(path: String): String? = try { File(path).readText().trim() } catch (_: Exception) { null }

    private fun collectSnapshot() = SystemSnapshot(
        cores        = collectCpuCores(),
        thermalZones = collectThermalZones(),
        ram          = collectRam(),
        network      = collectNetwork(),
        battery      = collectBattery(),
        processes    = collectProcesses(),
        storage      = collectStorage()
    )
}
