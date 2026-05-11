package com.synapt.nexus.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synapt.nexus.biometric.BiometricUnlockManager

private val BG     = Color(0xFF0A0E1A)
private val CARD   = Color(0xFF111827)
private val BORDER = Color(0xFF1F2937)
private val ACCENT = Color(0xFF6C63FF)
private val GREEN  = Color(0xFF00E676)
private val RED    = Color(0xFFFF1744)
private val YELLOW = Color(0xFFFFD600)
private val MONO   = FontFamily.Monospace

/**
 * 🧿 PrivacyManifestScreen — ORACLE + FLUX
 *
 * Tela de transparência total: mostra em tempo real
 * o que o app está ou não está usando.
 * Princípio ORACLE: "Confiança é o produto."
 */
@Composable
fun PrivacyManifestScreen(
    currentLevel: BiometricUnlockManager.TrustLevel,
    isMicActive: Boolean,
    isCameraActive: Boolean,
    isLocationActive: Boolean,
    isScreenCapturing: Boolean,
    isOverlayActive: Boolean,
    meshNodeCount: Int,
    onRequestLevel: (BiometricUnlockManager.TrustLevel) -> Unit,
    onRevokeLevel: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BG),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PrivacyHeader(currentLevel, onRevokeLevel) }
        item { ActiveSensorsPanel(isMicActive, isCameraActive, isLocationActive, isScreenCapturing, isOverlayActive) }
        item { DataFlowPanel(meshNodeCount) }
        item { TrustLevelPanel(currentLevel, onRequestLevel) }
        item { PrivacyPledgePanel() }
    }
}

@Composable
fun PrivacyHeader(level: BiometricUnlockManager.TrustLevel, onRevoke: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("PRIVACIDADE", color = ACCENT, fontSize = 18.sp,
                fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontFamily = MONO)
            Text("Transparência em tempo real", color = Color.Gray, fontSize = 11.sp)
        }
        if (level != BiometricUnlockManager.TrustLevel.LEVEL_1) {
            OutlinedButton(
                onClick = onRevoke,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RED),
                border = androidx.compose.foundation.BorderStroke(1.dp, RED.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Revogar Nível ${level.level}", fontSize = 11.sp, fontFamily = MONO)
            }
        }
    }
}

@Composable
fun ActiveSensorsPanel(
    mic: Boolean, camera: Boolean, location: Boolean,
    screen: Boolean, overlay: Boolean
) {
    Surface(color = CARD, shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)) {
        Column(Modifier.padding(16.dp)) {
            Text("SENSORES AGORA", color = Color.Gray, fontSize = 9.sp,
                letterSpacing = 2.sp, fontFamily = MONO)
            Spacer(Modifier.height(12.dp))

            val sensors = listOf(
                Triple(Icons.Default.Mic,         "Microfone",        mic),
                Triple(Icons.Default.Videocam,    "Câmera",           camera),
                Triple(Icons.Default.LocationOn,  "Localização",      location),
                Triple(Icons.Default.ScreenShare, "Captura de Tela",  screen),
                Triple(Icons.Default.Layers,      "Overlay Ativo",    overlay),
            )

            sensors.forEach { (icon, label, active) ->
                SensorRow(icon, label, active)
            }
        }
    }
}

@Composable
fun SensorRow(icon: ImageVector, label: String, active: Boolean) {
    val color = if (active) RED else GREEN
    val status = if (active) "EM USO" else "INATIVO"

    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))

        if (active) {
            // Pulsing dot for active sensors
            val inf = rememberInfiniteTransition(label = "${label}_pulse")
            val alpha by inf.animateFloat(1f, 0.2f,
                infiniteRepeatable(androidx.compose.animation.core.tween(700),
                    androidx.compose.animation.core.RepeatMode.Reverse), "${label}_a")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(RED.copy(alpha = alpha)))
                Spacer(Modifier.width(6.dp))
                Text(status, color = RED, fontSize = 9.sp, fontFamily = MONO, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(status, color = GREEN, fontSize = 9.sp, fontFamily = MONO)
        }
    }
}

@Composable
fun DataFlowPanel(meshNodes: Int) {
    Surface(color = CARD, shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)) {
        Column(Modifier.padding(16.dp)) {
            Text("FLUXO DE DADOS", color = Color.Gray, fontSize = 9.sp,
                letterSpacing = 2.sp, fontFamily = MONO)
            Spacer(Modifier.height(12.dp))

            DataFlowRow(Icons.Default.PhoneAndroid, "Processamento Local", "On-device, zero nuvem", GREEN)
            DataFlowRow(Icons.Default.Hub, "Mesh Neural ($meshNodes nós)",
                if (meshNodes > 0) "Somente devices do usuário (VPN)" else "Offline", if (meshNodes > 0) YELLOW else GREEN)
            DataFlowRow(Icons.Default.Cloud, "Servidores Externos", "NUNCA. Zero dados na nuvem.", GREEN)
            DataFlowRow(Icons.Default.Analytics, "Telemetria / Analytics", "Desabilitado completamente.", GREEN)
        }
    }
}

@Composable
fun DataFlowRow(icon: ImageVector, title: String, detail: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 12.sp)
            Text(detail, color = Color.Gray, fontSize = 10.sp)
        }
    }
}

@Composable
fun TrustLevelPanel(
    current: BiometricUnlockManager.TrustLevel,
    onRequest: (BiometricUnlockManager.TrustLevel) -> Unit
) {
    Surface(color = CARD, shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)) {
        Column(Modifier.padding(16.dp)) {
            Text("NÍVEIS DE CONFIANÇA", color = Color.Gray, fontSize = 9.sp,
                letterSpacing = 2.sp, fontFamily = MONO)
            Spacer(Modifier.height(12.dp))

            BiometricUnlockManager.TrustLevel.entries.forEach { level ->
                TrustLevelRow(level, current, onRequest)
                if (level != BiometricUnlockManager.TrustLevel.entries.last()) {
                    HorizontalDivider(color = BORDER, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
fun TrustLevelRow(
    level: BiometricUnlockManager.TrustLevel,
    current: BiometricUnlockManager.TrustLevel,
    onRequest: (BiometricUnlockManager.TrustLevel) -> Unit
) {
    val isActive = current.level >= level.level
    val color = when (level) {
        BiometricUnlockManager.TrustLevel.LEVEL_1 -> GREEN
        BiometricUnlockManager.TrustLevel.LEVEL_2 -> YELLOW
        BiometricUnlockManager.TrustLevel.LEVEL_3 -> RED
    }
    val icon = when (level) {
        BiometricUnlockManager.TrustLevel.LEVEL_1 -> Icons.Default.Lock
        BiometricUnlockManager.TrustLevel.LEVEL_2 -> Icons.Default.Fingerprint
        BiometricUnlockManager.TrustLevel.LEVEL_3 -> Icons.Default.VerifiedUser
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = if (isActive) color.copy(alpha = 0.15f) else BORDER,
            shape = CircleShape, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (isActive) color else Color.Gray, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Nível ${level.level}: ${level.label}", color = if (isActive) color else Color.Gray,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(level.description, color = Color.Gray, fontSize = 10.sp)
        }
        if (!isActive) {
            TextButton(onClick = { onRequest(level) }) {
                Icon(Icons.Default.Fingerprint, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ativar", fontSize = 11.sp)
            }
        } else if (level == current) {
            Surface(color = color.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp)) {
                Text("ATIVO", color = color, fontSize = 9.sp, fontFamily = MONO, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
fun PrivacyPledgePanel() {
    Surface(color = ACCENT.copy(alpha = 0.08f), shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ACCENT.copy(alpha = 0.3f))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = ACCENT, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("COMPROMISSO SYNAPT", color = ACCENT, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, fontFamily = MONO)
            }
            Spacer(Modifier.height(8.dp))
            val pledges = listOf(
                "Seus dados nunca saem do seu hardware",
                "Processamento 100% local ou na sua mesh pessoal",
                "Zero coleta de dados, zero telemetria, zero anúncios",
                "Código auditável — open source em breve",
                "Você controla cada permissão, pode revogar a qualquer momento"
            )
            pledges.forEach { pledge ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                    Text("→", color = ACCENT, fontSize = 11.sp, fontFamily = MONO,
                        modifier = Modifier.padding(end = 6.dp, top = 1.dp))
                    Text(pledge, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp,
                        lineHeight = 16.sp)
                }
            }
        }
    }
}
