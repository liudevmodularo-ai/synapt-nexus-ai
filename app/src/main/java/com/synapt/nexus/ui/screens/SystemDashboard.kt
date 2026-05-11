package com.synapt.nexus.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.synapt.nexus.monitor.SystemMonitor
import kotlin.math.*

// ─── Palette ─────────────────────────────────────────────────────────────────
private val BG          = Color(0xFF0A0E1A)
private val CARD        = Color(0xFF111827)
private val BORDER      = Color(0xFF1F2937)
private val ACCENT      = Color(0xFF6C63FF)
private val CYAN        = Color(0xFF00D4FF)
private val GREEN       = Color(0xFF00E676)
private val YELLOW      = Color(0xFFFFD600)
private val RED         = Color(0xFFFF1744)
private val ORANGE      = Color(0xFFFF6D00)
private val MONO        = FontFamily.Monospace

/**
 * 🎨 SystemDashboard — FLUX
 *
 * Cockpit de monitoramento em tempo real. Layout em grid responsivo.
 * Dados vêm do SystemMonitor via StateFlow.
 */
@Composable
fun SystemDashboard(
    snapshot: SystemMonitor.SystemSnapshot?,
    onKillProcess: (String) -> Unit,
    onOpenOverlay: () -> Unit
) {
    if (snapshot == null) {
        Box(Modifier.fillMaxSize().background(BG), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ACCENT)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BG),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Top action bar
        item { DashboardTopBar(snapshot, onOpenOverlay) }

        // ── CPU per-core grid
        item { CpuCoresPanel(snapshot.cores) }

        // ── Temperature sensors
        item { TemperaturePanel(snapshot.thermalZones) }

        // ── RAM breakdown
        item { RamDetailPanel(snapshot.ram) }

        // ── Battery
        item { BatteryPanel(snapshot.battery) }

        // ── Network
        item { NetworkPanel(snapshot.network) }

        // ── Storage
        item { StoragePanel(snapshot.storage) }

        // ── Running processes (killable)
        item {
            Text("PROCESSOS ATIVOS", color = Color.Gray, fontSize = 10.sp,
                letterSpacing = 2.sp, fontFamily = MONO, modifier = Modifier.padding(start = 4.dp))
        }
        items(snapshot.processes) { proc ->
            ProcessRow(proc, onKillProcess)
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ─── Top Bar ─────────────────────────────────────────────────────────────────

@Composable
fun DashboardTopBar(snapshot: SystemMonitor.SystemSnapshot, onOpenOverlay: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("SYSTEM COCKPIT", color = ACCENT, fontSize = 16.sp,
                fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontFamily = MONO)
            Text("${snapshot.cores.size} cores • ${snapshot.ram.totalMb}MB RAM",
                color = Color.Gray, fontSize = 10.sp, fontFamily = MONO)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Overlay toggle
            SmallButton("OVERLAY", CYAN) { onOpenOverlay() }
        }
    }
}

// ─── CPU Cores ────────────────────────────────────────────────────────────────

@Composable
fun CpuCoresPanel(cores: List<SystemMonitor.CpuCore>) {
    DashCard(title = "PROCESSAMENTO — ${cores.size} NÚCLEOS") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Cluster labels
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ClusterBadge("LITTLE A55 (Eficiência)", Color(0xFF3D8BFF))
                ClusterBadge("BIG A78 (Performance)", ACCENT)
            }
            Spacer(Modifier.height(4.dp))

            // Core bars
            cores.forEach { core ->
                CoreBar(core)
            }

            Spacer(Modifier.height(4.dp))

            // Summary
            val totalUsage = cores.filter { it.isOnline }.map { it.usagePercent }.average().toFloat()
            val maxFreq    = cores.maxOf { it.frequencyMhz }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MonoStat("Médio", "${totalUsage.toInt()}%")
                MonoStat("Pico", "${maxFreq}MHz")
                MonoStat("Governor", cores.lastOrNull()?.governor?.take(10) ?: "-")
            }
        }
    }
}

@Composable
fun CoreBar(core: SystemMonitor.CpuCore) {
    val clusterColor = if (core.cluster == SystemMonitor.CpuCluster.BIG) ACCENT else Color(0xFF3D8BFF)
    val usageColor = when {
        core.usagePercent > 85f -> RED
        core.usagePercent > 65f -> YELLOW
        else -> clusterColor
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Core label
        Text(
            text = if (core.cluster == SystemMonitor.CpuCluster.BIG) "A78·${core.id}" else "A55·${core.id}",
            color = clusterColor, fontSize = 9.sp, fontFamily = MONO,
            modifier = Modifier.width(52.dp)
        )

        // Usage bar
        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(3.dp)).background(BORDER)) {
            val animUsage by animateFloatAsState(core.usagePercent / 100f, tween(600), label = "core${core.id}")
            Box(Modifier.fillMaxHeight().fillMaxWidth(animUsage)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(clusterColor.copy(alpha = 0.7f), usageColor))))
        }

        // Percent
        Text("${core.usagePercent.toInt()}%", color = usageColor, fontSize = 9.sp,
            fontFamily = MONO, modifier = Modifier.width(30.dp).padding(start = 4.dp))

        // Frequency
        Text("${core.frequencyMhz}M", color = Color.Gray, fontSize = 8.sp,
            fontFamily = MONO, modifier = Modifier.width(40.dp))
    }
}

@Composable
fun ClusterBadge(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, color = color, fontSize = 9.sp, fontFamily = MONO)
    }
}

// ─── Temperature Panel ────────────────────────────────────────────────────────

@Composable
fun TemperaturePanel(zones: List<SystemMonitor.ThermalZone>) {
    DashCard(title = "SENSORES DE TEMPERATURA (${zones.size} zonas)") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(zones.filter { it.temperatureCelsius > 0f }) { zone ->
                ThermalGauge(zone)
            }
        }
    }
}

@Composable
fun ThermalGauge(zone: SystemMonitor.ThermalZone) {
    val color = when {
        zone.temperatureCelsius > 55f -> RED
        zone.temperatureCelsius > 45f -> ORANGE
        zone.temperatureCelsius > 38f -> YELLOW
        else -> GREEN
    }

    val icon = when (zone.category) {
        SystemMonitor.ThermalCategory.CPU_BIG, SystemMonitor.ThermalCategory.CPU_LITTLE -> Icons.Default.Memory
        SystemMonitor.ThermalCategory.GPU     -> Icons.Default.GraphicEq
        SystemMonitor.ThermalCategory.BATTERY -> Icons.Default.Battery5Bar
        SystemMonitor.ThermalCategory.SKIN    -> Icons.Default.PhoneAndroid
        SystemMonitor.ThermalCategory.MODEM   -> Icons.Default.SignalCellularAlt
        SystemMonitor.ThermalCategory.NPU     -> Icons.Default.Psychology
        else -> Icons.Default.Thermostat
    }

    val normalizedLabel = when (zone.category) {
        SystemMonitor.ThermalCategory.CPU_LITTLE -> "CPU-L"
        SystemMonitor.ThermalCategory.CPU_BIG    -> "CPU-B"
        SystemMonitor.ThermalCategory.GPU        -> "GPU"
        SystemMonitor.ThermalCategory.BATTERY    -> "Bateria"
        SystemMonitor.ThermalCategory.SKIN       -> "Skin"
        SystemMonitor.ThermalCategory.MODEM      -> "Modem"
        SystemMonitor.ThermalCategory.NPU        -> "NPU"
        else -> zone.name.take(6)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).background(BORDER, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(4.dp))
        Text("${zone.temperatureCelsius.toInt()}°", color = color, fontSize = 16.sp,
            fontWeight = FontWeight.Bold, fontFamily = MONO)
        Text(normalizedLabel, color = Color.Gray, fontSize = 8.sp, fontFamily = MONO)
    }
}

// ─── RAM Panel ────────────────────────────────────────────────────────────────

@Composable
fun RamDetailPanel(ram: SystemMonitor.RamInfo) {
    DashCard(title = "MEMÓRIA RAM — ${ram.totalMb}MB total") {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // Main usage bar
            RamSegmentBar(ram)
            Spacer(Modifier.height(2.dp))
            // Breakdown
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    RamRow("Usado",     ram.usedMb,    RED.copy(alpha = 0.8f))
                    RamRow("Cache",     ram.cachedMb,  YELLOW.copy(alpha = 0.8f))
                    RamRow("Buffers",   ram.buffersMb, CYAN.copy(alpha = 0.7f))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    RamRow("Livre",     ram.availableMb, GREEN)
                    RamRow("Swap Uso",  ram.swapUsedMb,  ORANGE.copy(alpha = 0.8f))
                    RamRow("Dirty",     ram.dirtyMb,     Color.Gray)
                }
            }
        }
    }
}

@Composable
fun RamSegmentBar(ram: SystemMonitor.RamInfo) {
    val total = ram.totalMb.toFloat().coerceAtLeast(1f)
    Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp)).background(BORDER)) {
        Row(Modifier.fillMaxSize()) {
            val usedFraction  = (ram.usedMb - ram.cachedMb).coerceAtLeast(0L) / total
            val cacheFraction = ram.cachedMb / total
            if (usedFraction > 0)  Box(Modifier.fillMaxHeight().fillMaxWidth(usedFraction).background(RED.copy(alpha = 0.7f)))
            if (cacheFraction > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(cacheFraction).background(YELLOW.copy(alpha = 0.5f)))
        }
        Text("${ram.usagePercent.toInt()}% usado — ${ram.availableMb}MB livre",
            color = Color.White, fontSize = 9.sp, fontFamily = MONO,
            modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
fun RamRow(label: String, valueMb: Long, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.Gray, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.weight(1f))
        Text("${valueMb}MB", color = color, fontSize = 10.sp, fontFamily = MONO)
    }
}

// ─── Battery Panel ────────────────────────────────────────────────────────────

@Composable
fun BatteryPanel(battery: SystemMonitor.BatteryInfo) {
    val battColor = when {
        battery.levelPercent < 20 -> RED
        battery.levelPercent < 40 -> YELLOW
        else -> GREEN
    }

    DashCard(title = "BATERIA") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Level arc (simple)
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { battery.levelPercent / 100f },
                    color = battColor, trackColor = BORDER, strokeWidth = 5.dp,
                    modifier = Modifier.fillMaxSize())
                Text("${battery.levelPercent}%", color = battColor, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, fontFamily = MONO)
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MonoStat("Temp", "${battery.temperatureCelsius}°C")
                    MonoStat("Carga", battery.chargingType)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MonoStat("Voltagem", "${battery.voltageMv}mV")
                    MonoStat("Corrente", "${battery.currentMa}mA")
                }
                if (battery.estimatedRemainingMin > 0) {
                    val h = battery.estimatedRemainingMin / 60
                    val m = battery.estimatedRemainingMin % 60
                    MonoStat("Restante", "${h}h ${m}min")
                }
                MonoStat("Saúde", battery.health)
            }
        }
    }
}

// ─── Network Panel ────────────────────────────────────────────────────────────

@Composable
fun NetworkPanel(network: SystemMonitor.NetworkStats) {
    DashCard(title = "REDE") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            NetworkSpeed("↓ RX", network.rxSpeedKbps, GREEN)
            NetworkSpeed("↑ TX", network.txSpeedKbps, CYAN)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TOTAL SESS.", color = Color.Gray, fontSize = 8.sp, fontFamily = MONO)
                Text("↓ ${network.totalRxMb}MB", color = GREEN, fontSize = 10.sp, fontFamily = MONO)
                Text("↑ ${network.totalTxMb}MB", color = CYAN, fontSize = 10.sp, fontFamily = MONO)
            }
        }
    }
}

@Composable
fun NetworkSpeed(label: String, kbps: Float, color: Color) {
    val (value, unit) = if (kbps > 1024) Pair(kbps / 1024f, "MB/s") else Pair(kbps, "KB/s")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 8.sp, fontFamily = MONO)
        Text("${"%.1f".format(value)}", color = color, fontSize = 20.sp,
            fontWeight = FontWeight.Bold, fontFamily = MONO)
        Text(unit, color = color.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = MONO)
    }
}

// ─── Storage Panel ────────────────────────────────────────────────────────────

@Composable
fun StoragePanel(storage: SystemMonitor.StorageInfo) {
    DashCard(title = "ARMAZENAMENTO") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StorageBar("Interno", storage.internalFreeGb, storage.internalTotalGb)
            if (storage.externalTotalGb > 0f) {
                StorageBar("Externo", storage.externalFreeGb, storage.externalTotalGb)
            }
        }
    }
}

@Composable
fun StorageBar(label: String, freeGb: Float, totalGb: Float) {
    val usedFraction = ((totalGb - freeGb) / totalGb.coerceAtLeast(0.1f)).coerceIn(0f, 1f)
    val color = when { usedFraction > 0.9f -> RED; usedFraction > 0.7f -> YELLOW; else -> CYAN }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.Gray, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.width(60.dp))
        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(3.dp)).background(BORDER)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(usedFraction).background(color.copy(alpha = 0.7f)))
        }
        Text("${"%.1f".format(freeGb)}GB livre", color = color, fontSize = 9.sp,
            fontFamily = MONO, modifier = Modifier.width(70.dp).padding(start = 6.dp))
    }
}

// ─── Process Row ─────────────────────────────────────────────────────────────

@Composable
fun ProcessRow(proc: SystemMonitor.ProcessInfo, onKill: (String) -> Unit) {
    Surface(color = CARD, shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BORDER), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(proc.displayName, color = Color.White, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(proc.packageName, color = Color.Gray, fontSize = 9.sp,
                    fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${"%.0f".format(proc.memoryMb)}MB", color = CYAN, fontSize = 11.sp,
                fontFamily = MONO, modifier = Modifier.padding(horizontal = 8.dp))
            if (proc.isKillable) {
                SmallButton("PARAR", RED.copy(alpha = 0.8f)) { onKill(proc.packageName) }
            } else {
                Surface(color = BORDER, shape = RoundedCornerShape(4.dp)) {
                    Text("ESSENCIAL", color = Color.Gray, fontSize = 8.sp, fontFamily = MONO,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
        }
    }
}

// ─── Shared Components ────────────────────────────────────────────────────────

@Composable
fun DashCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = CARD, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, BORDER), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Color.Gray, fontSize = 9.sp, letterSpacing = 2.sp, fontFamily = MONO)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun MonoStat(label: String, value: String) {
    Column {
        Text(label, color = Color.Gray, fontSize = 8.sp, fontFamily = MONO)
        Text(value, color = Color.White, fontSize = 11.sp, fontFamily = MONO, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SmallButton(label: String, color: Color, onClick: () -> Unit) {
    Surface(color = color.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        onClick = onClick) {
        Text(label, color = color, fontSize = 9.sp, fontFamily = MONO,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
