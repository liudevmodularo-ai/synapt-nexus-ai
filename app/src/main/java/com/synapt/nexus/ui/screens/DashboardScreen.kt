package com.synapt.nexus.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synapt.nexus.thermal.ThermalManager

/**
 * 🎨 DashboardScreen — FLUX
 *
 * Real-time hub monitoring dashboard.
 * Shows: thermal mode, temperature, RAM, loaded model,
 *        connected clients, tokens/s, server status.
 *
 * Material You dynamic theming + dark mode optimized.
 */

// ─── Color Palette ────────────────────────────────────────────────────────────
private val NexusDark      = Color(0xFF0A0E1A)
private val NexusCard      = Color(0xFF111827)
private val NexusAccent    = Color(0xFF6C63FF)
private val NexusCyan      = Color(0xFF00D4FF)
private val NexusGreen     = Color(0xFF00E676)
private val NexusYellow    = Color(0xFFFFD600)
private val NexusRed       = Color(0xFFFF1744)
private val NexusBorder    = Color(0xFF1F2937)

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onToggleHub: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenClients: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = NexusDark
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 24.dp)
        ) {
            // ── Header
            item { HubHeader(uiState, onToggleHub) }

            // ── Thermal + Temperature Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ThermalModeCard(uiState.thermalMode, Modifier.weight(1f))
                    TemperatureCard(uiState.temperatureCelsius, Modifier.weight(1f))
                }
            }

            // ── RAM Usage
            item { RamCard(uiState.availableRamMb, uiState.totalRamMb) }

            // ── Active Model
            item { ModelCard(uiState.loadedModel, uiState.tokensPerSecond, onOpenModels) }

            // ── Server Info
            item { ServerCard(uiState.serverPort, uiState.mdnsName, uiState.isServerRunning) }

            // ── Connected Clients
            item {
                ConnectedClientsCard(
                    clients = uiState.connectedClients,
                    onManage = onOpenClients
                )
            }

            // ── Uptime
            item { UptimeCard(uiState.uptimeSeconds) }
        }
    }
}

// ─── Header ──────────────────────────────────────────────────────────────────

@Composable
fun HubHeader(state: DashboardUiState, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "SYNAPT NEXUS",
                color = NexusAccent,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "AI Hub  ·  SNW/1.0",
                color = Color.Gray,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Hub toggle button
        val hubColor by animateColorAsState(
            targetValue = if (state.isServerRunning) NexusGreen else Color.Gray,
            animationSpec = tween(300), label = "hub_color"
        )

        FilledIconButton(
            onClick = onToggle,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = hubColor.copy(alpha = 0.2f))
        ) {
            Icon(
                imageVector = if (state.isServerRunning) Icons.Default.RadioButtonChecked else Icons.Default.PowerSettingsNew,
                contentDescription = "Toggle Hub",
                tint = hubColor
            )
        }
    }
}

// ─── Thermal Mode Card ────────────────────────────────────────────────────────

@Composable
fun ThermalModeCard(mode: ThermalManager.ThermalMode, modifier: Modifier = Modifier) {
    val color = when (mode) {
        ThermalManager.ThermalMode.NORMAL  -> NexusGreen
        ThermalManager.ThermalMode.REDUCED -> NexusYellow
        ThermalManager.ThermalMode.SAFE    -> NexusRed
    }

    // Pulsing animation for SAFE mode
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = if (mode == ThermalManager.ThermalMode.SAFE) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    NexusCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = mode.emoji,
                fontSize = 24.sp
            )
            Text(
                text = mode.label,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "THERMAL",
                color = Color.Gray,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

// ─── Temperature Card ─────────────────────────────────────────────────────────

@Composable
fun TemperatureCard(temp: Float, modifier: Modifier = Modifier) {
    val color = when {
        temp >= 48f -> NexusRed
        temp >= 42f -> NexusYellow
        else        -> NexusCyan
    }

    NexusCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Thermostat, "Temp", tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${temp.toInt()}°C",
                color = color,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "CPU TEMP",
                color = Color.Gray,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

// ─── RAM Card ────────────────────────────────────────────────────────────────

@Composable
fun RamCard(availableMb: Long, totalMb: Long) {
    val usedMb = totalMb - availableMb
    val progress = usedMb.toFloat() / totalMb.toFloat()
    val color = when {
        progress > 0.85f -> NexusRed
        progress > 0.65f -> NexusYellow
        else             -> NexusGreen
    }

    NexusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, "RAM", tint = NexusCyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("RAM", color = Color.Gray, fontSize = 11.sp, letterSpacing = 1.sp)
                    Text(
                        "${availableMb}MB free / ${totalMb}MB",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = color,
                    trackColor = NexusBorder
                )
            }
        }
    }
}

// ─── Model Card ──────────────────────────────────────────────────────────────

@Composable
fun ModelCard(loadedModel: String?, tps: Float, onClick: () -> Unit) {
    NexusCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, "Model", tint = NexusAccent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = loadedModel?.substringAfterLast("/") ?: "No model loaded",
                        color = if (loadedModel != null) Color.White else Color.Gray,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    if (loadedModel != null) {
                        Text(
                            text = "${tps.toInt()} tokens/s",
                            color = NexusGreen,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
        }
    }
}

// ─── Server Card ─────────────────────────────────────────────────────────────

@Composable
fun ServerCard(port: Int, mdnsName: String, isRunning: Boolean) {
    NexusCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Router, "Server", tint = NexusCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("SNW Server", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = (if (isRunning) NexusGreen else Color.Gray).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = if (isRunning) "ONLINE" else "OFFLINE",
                        color = if (isRunning) NexusGreen else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            MonoRow("PORT", ":$port")
            MonoRow("mDNS", "$mdnsName.local")
            MonoRow("TLS", "ECDSA P-256 ✓")
            MonoRow("PROTO", "SNW/1.0")
        }
    }
}

// ─── Clients Card ─────────────────────────────────────────────────────────────

@Composable
fun ConnectedClientsCard(clients: List<ConnectedClientUi>, onManage: () -> Unit) {
    NexusCard {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Devices, "Clients", tint = NexusAccent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connected Clients", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = NexusAccent.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "${clients.size}",
                            color = NexusAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                TextButton(onClick = onManage) {
                    Text("Manage", color = NexusCyan, fontSize = 12.sp)
                }
            }

            if (clients.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No clients connected yet",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 28.dp)
                )
            } else {
                clients.forEach { client ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(NexusGreen)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(client.name, color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text(client.ip, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

// ─── Uptime Card ──────────────────────────────────────────────────────────────

@Composable
fun UptimeCard(uptimeSeconds: Long) {
    val h = uptimeSeconds / 3600
    val m = (uptimeSeconds % 3600) / 60
    val s = uptimeSeconds % 60

    NexusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Timer, "Uptime", tint = Color.Gray, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("UPTIME", color = Color.Gray, fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = "%02d:%02d:%02d".format(h, m, s),
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ─── Shared Components ────────────────────────────────────────────────────────

@Composable
fun NexusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NexusCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, NexusBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun MonoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 11.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = NexusCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

// ─── UI State ─────────────────────────────────────────────────────────────────

data class DashboardUiState(
    val isServerRunning: Boolean = false,
    val thermalMode: ThermalManager.ThermalMode = ThermalManager.ThermalMode.NORMAL,
    val temperatureCelsius: Float = 35f,
    val availableRamMb: Long = 8000L,
    val totalRamMb: Long = 12000L,
    val loadedModel: String? = null,
    val tokensPerSecond: Float = 0f,
    val serverPort: Int = 7474,
    val mdnsName: String = "synapt-nexus-device",
    val connectedClients: List<ConnectedClientUi> = emptyList(),
    val uptimeSeconds: Long = 0L
)

data class ConnectedClientUi(
    val id: String,
    val name: String,
    val ip: String
)
