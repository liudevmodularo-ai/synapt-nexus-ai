package com.synapt.nexus.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.view.WindowManager.LayoutParams.*
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 🌐 OverlayService — NEXUS + FLUX
 *
 * Serviço que exibe uma bolha flutuante sobre outros apps.
 * A bolha mostra status da mesh e permite acesso rápido ao agente.
 *
 * Requer: SYSTEM_ALERT_WINDOW permission (solicitada via Settings)
 *
 * Estados da bolha:
 *   COLLAPSED → ícone flutuante 56dp (arrastável)
 *   EXPANDED  → painel compacto com status + ação rápida
 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "NEXUS::OverlayService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "synapt_overlay_channel"

        const val ACTION_START = "com.synapt.nexus.overlay.START"
        const val ACTION_STOP  = "com.synapt.nexus.overlay.STOP"
        const val ACTION_UPDATE = "com.synapt.nexus.overlay.UPDATE"

        // Extras for UPDATE action
        const val EXTRA_MODEL_STATUS   = "model_status"
        const val EXTRA_MESH_NODES     = "mesh_nodes"
        const val EXTRA_TOKENS_PER_SEC = "tokens_per_sec"
        const val EXTRA_THERMAL        = "thermal_mode"
    }

    // ─── Lifecycle boilerplate for Compose in Service ─────────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    // ─── Overlay state (shared with Compose) ─────────────────────────────────
    private val overlayState = OverlayState()

    data class OverlayState(
        val modelStatus: String = "Sem modelo",
        val meshNodes: Int = 0,
        val tokensPerSec: Float = 0f,
        val thermalMode: String = "NORMAL",
        val isExpanded: Boolean = false,
        val posX: Int = 100,
        val posY: Int = 400
    )

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        when (intent?.action) {
            ACTION_START -> showOverlay()
            ACTION_STOP  -> { removeOverlay(); stopSelf() }
            ACTION_UPDATE -> updateState(intent)
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Overlay Window ───────────────────────────────────────────────────────

    private fun showOverlay() {
        if (overlayView != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") TYPE_PHONE,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 400
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                OverlayBubble(
                    state = overlayState,
                    onDrag = { dx, dy ->
                        params.x = (params.x + dx.toInt()).coerceAtLeast(0)
                        params.y = (params.y + dy.toInt()).coerceAtLeast(0)
                        windowManager?.updateViewLayout(overlayView, params)
                    },
                    onTap = { openMainApp() }
                )
            }
        }

        overlayView = composeView

        // Required lifecycle setup for ComposeView in Service
        val viewModelStore = ViewModelStore()
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore = viewModelStore
        }

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        windowManager?.addView(overlayView, params)
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager?.removeViewImmediate(it)
            overlayView = null
        }
    }

    private fun updateState(intent: Intent) {
        // State updates will trigger Compose recomposition via state holders
        // Full implementation uses StateFlow injected into the service
    }

    private fun openMainApp() {
        val appIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        appIntent?.let { startActivity(it) }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Synapt Overlay", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Synapt AI Overlay ativo")
            .setContentText("Toque para abrir o app")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true).build()
    }
}

// ─── Overlay Compose UI ───────────────────────────────────────────────────────

@Composable
fun OverlayBubble(
    state: OverlayService.OverlayState,
    onDrag: (Float, Float) -> Unit,
    onTap: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val NexusAccent = Color(0xFF6C63FF)
    val NexusCyan   = Color(0xFF00D4FF)
    val NexusGreen  = Color(0xFF00E676)
    val NexusDark   = Color(0xE50A0E1A)

    Box(
        modifier = Modifier
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        if (!expanded) {
            // ── Collapsed: floating bubble ─────────────────────────────────
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(NexusAccent, NexusCyan)))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { /* tap detection */ },
                            onDrag = { change, amount ->
                                change.consume()
                                onDrag(amount.x, amount.y)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Psychology, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    val thermalColor = when (state.thermalMode) {
                        "SAFE" -> Color.Red; "REDUCED" -> Color.Yellow; else -> NexusGreen
                    }
                    Box(Modifier.size(6.dp).clip(CircleShape).background(thermalColor))
                }
            }
        } else {
            // ── Expanded: status panel ─────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = NexusDark,
                modifier = Modifier.width(200.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Psychology, null, tint = NexusAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("SYNAPT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { expanded = false }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(state.modelStatus, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    if (state.meshNodes > 0) {
                        Text("${state.meshNodes} nós • ${state.tokensPerSec.toInt()} t/s", color = NexusGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onTap,
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NexusAccent)
                    ) {
                        Text("Abrir Agente", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
