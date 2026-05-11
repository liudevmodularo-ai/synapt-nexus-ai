package com.synapt.nexus.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.synapt.nexus.inference.LlamaCppBridge
import com.synapt.nexus.model.ModelManager
import com.synapt.nexus.monitor.SystemMonitor
import com.synapt.nexus.network.MeshConnectivityManager
import com.synapt.nexus.network.MeshInferenceRouter
import com.synapt.nexus.network.SynaptWebServer
import com.synapt.nexus.security.SecurityManager
import com.synapt.nexus.thermal.ThermalManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * 🤖 InferenceService — ARIA
 *
 * FIXES:
 *   ✅ B2a: ServiceCompat.startForeground() com tipo correto (Android 14)
 *   ✅ B2b: onDestroy usa NonCancellable para evitar race condition
 *   ✅ WakeLock renovado a cada 2h (evita expiração em sessões longas)
 *   ✅ onTaskRemoved: mantém serviço vivo mesmo com app fechado
 *   ✅ START_STICKY: reinicia automaticamente se o OS matar o processo
 */
class InferenceService : Service() {

    companion object {
        private const val TAG = "ARIA::InferenceService"
        private const val NOTIFICATION_ID   = 1001
        private const val CHANNEL_ID        = "synapt_nexus_channel"
        private const val WAKELOCK_TIMEOUT  = 2 * 60 * 60 * 1000L   // 2h — renewed automatically
        private const val WAKELOCK_RENEW_MS = 90 * 60 * 1000L       // renew every 90min

        const val ACTION_START  = "com.synapt.nexus.START"
        const val ACTION_STOP   = "com.synapt.nexus.STOP"
    }

    // ─── Components ───────────────────────────────────────────────────────────
    private lateinit var securityManager:      SecurityManager
    private lateinit var thermalManager:       ThermalManager
    private lateinit var llamaBridge:          LlamaCppBridge
    private lateinit var modelManager:         ModelManager
    private lateinit var webServer:            SynaptWebServer
    private lateinit var meshRouter:           MeshInferenceRouter
    private lateinit var meshConnectivity:     MeshConnectivityManager
    private lateinit var systemMonitor:        SystemMonitor

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initComponents()
        Log.i(TAG, "✅ InferenceService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else        -> startHub()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App foi fechado pelo usuário — mantemos o serviço vivo
        Log.i(TAG, "App task removed — service continues running as background hub")
        val restartIntent = Intent(applicationContext, InferenceService::class.java).apply {
            action = ACTION_START; setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000, pendingIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "InferenceService destroying...")

        // FIX B2b: usar NonCancellable para garantir shutdown limpo
        // mesmo quando serviceScope está sendo cancelado
        runBlocking(NonCancellable) {
            withTimeout(5000L) {
                try {
                    meshConnectivity.stop()
                    webServer.stop()
                    thermalManager.stopMonitoring()
                    systemMonitor.stop()
                    llamaBridge.unloadModel()
                } catch (e: Exception) {
                    Log.w(TAG, "Shutdown error (non-critical): ${e.message}")
                }
            }
        }

        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "InferenceService destroyed cleanly")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        serviceScope.launch {
            if (llamaBridge.isModelLoaded) {
                llamaBridge.unloadModel()
                updateNotification("⚠️ Memória Baixa", "Modelo descarregado. Recarregue quando disponível.")
            }
        }
    }

    // ─── Init ─────────────────────────────────────────────────────────────────

    private fun initComponents() {
        securityManager  = SecurityManager(applicationContext)
        thermalManager   = ThermalManager(applicationContext)
        llamaBridge      = LlamaCppBridge.getInstance()
        modelManager     = ModelManager(applicationContext)
        systemMonitor    = SystemMonitor(applicationContext)
        meshRouter       = MeshInferenceRouter(
            localBridge    = llamaBridge,
            thermalManager = thermalManager,
            sessionManager = MeshInferenceRouter.LocalSessionManager()
        )
        webServer = SynaptWebServer(applicationContext, securityManager,
            thermalManager, llamaBridge, modelManager)
        meshConnectivity = MeshConnectivityManager(applicationContext, meshRouter)
    }

    // ─── Hub Start ────────────────────────────────────────────────────────────

    private fun startHub() {
        // FIX B2a: ServiceCompat.startForeground com tipo explícito para Android 14
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("🚀 Iniciando…", "Synapt Nexus AI Hub"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        acquireWakeLock()
        thermalManager.startMonitoring()
        systemMonitor.start()

        serviceScope.launch {
            webServer.start()
                .onSuccess {
                    updateNotification("🟢 Hub Online", "Porta ${SynaptWebServer.SNW_PORT}")
                }
                .onFailure {
                    updateNotification("❌ Erro no Servidor", it.message ?: "Falha ao iniciar")
                    Log.e(TAG, "Server start failed", it)
                }
        }

        serviceScope.launch { meshConnectivity.start() }

        // Live notification updates from thermal + mesh
        serviceScope.launch {
            thermalManager.thermalState.collectLatest { snap ->
                val model = llamaBridge.loadedModelPath
                    .substringAfterLast("/").ifEmpty { "Sem modelo" }
                val meshNodes = meshRouter.getMeshSize()
                val meshInfo  = if (meshNodes > 0) " · Mesh: $meshNodes nós" else ""
                updateNotification(
                    "${snap.mode.emoji} ${snap.temperatureCelsius.toInt()}°C · ${snap.availableRamMb}MB livre",
                    "Modelo: $model$meshInfo"
                )
            }
        }

        // WakeLock renewal to prevent expiry on long sessions
        serviceScope.launch {
            while (isActive) {
                delay(WAKELOCK_RENEW_MS)
                renewWakeLock()
            }
        }
    }

    // ─── WakeLock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SynaptNexus::InferenceLock"
        ).also { it.acquire(WAKELOCK_TIMEOUT) }
        Log.d(TAG, "WakeLock acquired (${WAKELOCK_TIMEOUT / 60_000}min timeout)")
    }

    private fun renewWakeLock() {
        val wl = wakeLock ?: return
        if (wl.isHeld) wl.release()
        wl.acquire(WAKELOCK_TIMEOUT)
        Log.d(TAG, "WakeLock renewed")
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Synapt Nexus AI Hub",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Status do hub de IA"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, InferenceService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_delete, "Parar Hub", stopPi)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, content))
    }
}
