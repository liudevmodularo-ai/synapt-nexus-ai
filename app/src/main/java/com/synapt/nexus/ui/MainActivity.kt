package com.synapt.nexus.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synapt.nexus.biometric.BiometricUnlockManager
import com.synapt.nexus.monitor.SystemMonitor
import com.synapt.nexus.service.InferenceService
import com.synapt.nexus.ui.screens.*

/**
 * 🤖 MainActivity — ARIA + FLUX
 *
 * 5 abas:
 *   Hub      → DashboardScreen (status do hub + mesh)
 *   Agente   → AgentScreen (chat com mesh neural)
 *   Cockpit  → SystemDashboard (monitoramento em tempo real)
 *   Mesh     → MeshScreen (Sprint 3)
 *   Privacidade → PrivacyManifestScreen
 *
 * FIXES:
 *   ✅ rememberSaveable (estado persistido na rotação)
 *   ✅ biometricManager inicializado
 *   ✅ SystemMonitor iniciado
 */
class MainActivity : ComponentActivity() {

    private lateinit var biometricManager: BiometricUnlockManager
    private lateinit var systemMonitor: SystemMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        biometricManager = BiometricUnlockManager(applicationContext)
        systemMonitor = SystemMonitor(applicationContext).also { it.start() }

        startForegroundService(
            Intent(this, InferenceService::class.java).apply {
                action = InferenceService.ACTION_START
            }
        )

        setContent {
            SynaptNexusTheme {
                SynaptNexusApp(
                    biometricManager = biometricManager,
                    systemMonitor = systemMonitor,
                    activity = this
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        biometricManager.onAppBackgrounded()
    }

    override fun onDestroy() {
        systemMonitor.stop()
        super.onDestroy()
    }
}

// ─── Navigation ───────────────────────────────────────────────────────────────

enum class NavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HUB      ("Hub",         Icons.Filled.Router,       Icons.Outlined.Router),
    AGENT    ("Agente",      Icons.Filled.Psychology,   Icons.Outlined.Psychology),
    COCKPIT  ("Cockpit",     Icons.Filled.Dashboard,    Icons.Outlined.Dashboard),
    MESH     ("Mesh",        Icons.Filled.Hub,           Icons.Outlined.Hub),
    PRIVACY  ("Privacidade", Icons.Filled.Shield,        Icons.Outlined.Shield)
}

@Composable
fun SynaptNexusApp(
    biometricManager: BiometricUnlockManager,
    systemMonitor: SystemMonitor,
    activity: MainActivity
) {
    // FIX: rememberSaveable preserves tab on rotation
    var currentTab by rememberSaveable { mutableStateOf(NavTab.AGENT) }

    val systemSnapshot by systemMonitor.snapshot.collectAsState()
    val trustLevel by biometricManager.currentLevel.collectAsState()

    val dashState = remember {
        DashboardUiState(
            isServerRunning = true, temperatureCelsius = 38f,
            availableRamMb = 7200L, totalRamMb = 12000L,
            serverPort = 7474, mdnsName = "synapt-nexus-device"
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF111827),
                tonalElevation = 0.dp
            ) {
                NavTab.entries.forEach { tab ->
                    val selected = tab == currentTab
                    NavigationBarItem(
                        selected    = selected,
                        onClick     = { currentTab = tab },
                        icon        = {
                            Icon(
                                if (selected) tab.selectedIcon else tab.unselectedIcon,
                                tab.label
                            )
                        },
                        label       = {
                            Text(tab.label, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        },
                        colors      = NavigationBarItemDefaults.colors(
                            selectedIconColor   = Color(0xFF6C63FF),
                            selectedTextColor   = Color(0xFF6C63FF),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor      = Color(0xFF6C63FF).copy(alpha = 0.15f)
                        )
                    )
                }
            }
        },
        containerColor = Color(0xFF0A0E1A)
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(
                targetState  = currentTab,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label        = "tab"
            ) { tab ->
                when (tab) {
                    NavTab.HUB -> DashboardScreen(
                        uiState       = dashState,
                        onToggleHub   = { },
                        onOpenModels  = { currentTab = NavTab.COCKPIT },
                        onOpenClients = { currentTab = NavTab.MESH }
                    )

                    NavTab.AGENT -> AgentScreen(
                        uiState       = AgentUiState(
                            meshNodeCount = 0,   // Sprint 2: connect real router
                            totalMeshTps  = 0f
                        ),
                        onInputChange = { },
                        onSend        = { },
                        onStop        = { },
                        onClear       = { }
                    )

                    NavTab.COCKPIT -> SystemDashboard(
                        snapshot      = systemSnapshot,
                        onKillProcess = { pkg ->
                            // Requires KILL_BACKGROUND_PROCESSES — granted at Level 1
                        },
                        onOpenOverlay = {
                            // Check SYSTEM_ALERT_WINDOW before starting
                        }
                    )

                    NavTab.MESH -> ComingSoonScreen(
                        "Neural Mesh", "Sprint 3 →",
                        Icons.Outlined.Hub, Color(0xFF00E676)
                    )

                    NavTab.PRIVACY -> PrivacyManifestScreen(
                        currentLevel      = trustLevel,
                        isMicActive       = false,   // Sprint 2: bind to real sensor state
                        isCameraActive    = false,
                        isLocationActive  = false,
                        isScreenCapturing = false,
                        isOverlayActive   = false,
                        meshNodeCount     = 0,
                        onRequestLevel    = { level ->
                            biometricManager.requestTrustLevel(
                                activity  = activity,
                                requestedLevel = level,
                                onSuccess = { },
                                onFailure = { }
                            )
                        },
                        onRevokeLevel = { biometricManager.revokeElevation() }
                    )
                }
            }
        }
    }
}

@Composable
fun ComingSoonScreen(title: String, sprint: String, icon: ImageVector, color: Color = Color(0xFF6C63FF)) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(14.dp))
            Text(title, color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(sprint, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun SynaptNexusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary      = Color(0xFF6C63FF),
            secondary    = Color(0xFF00D4FF),
            background   = Color(0xFF0A0E1A),
            surface      = Color(0xFF111827),
            onPrimary    = Color.White,
            onBackground = Color.White,
            onSurface    = Color.White
        ),
        content = content
    )
}

// Needed import alias for collectAsState
@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsState(): State<T> =
    collectAsState(initial = value)
