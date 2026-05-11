package com.synapt.nexus.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

// ─── Boot Receiver ────────────────────────────────────────────────────────────

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("ARIA::Boot", "Boot completed — starting InferenceService")
            context.startForegroundService(
                Intent(context, InferenceService::class.java).apply {
                    action = InferenceService.ACTION_START
                }
            )
        }
    }
}

// ─── Screen Capture Service (stub — Sprint 2) ─────────────────────────────────

class ScreenCaptureService : android.app.Service() {
    // Full implementation in Sprint 2:
    // Uses MediaProjectionManager to capture screen
    // Streams frames to AI for context-aware assistance
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("ARIA::ScreenCapture", "ScreenCaptureService started (Sprint 2 stub)")
        return START_NOT_STICKY
    }
}

// ─── Model Download Service (stub) ────────────────────────────────────────────

class ModelDownloadService : android.app.Service() {
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("VECTOR::Download", "ModelDownloadService started (Sprint 2 stub)")
        return START_NOT_STICKY
    }
}

// ─── Accessibility Service (Trust Level 3) ────────────────────────────────────

/**
 * SynaptAccessibilityService — Sprint 2 (Trust Level 3)
 *
 * Quando ativado pelo usuário, permite ao agente:
 *   - Ler conteúdo de qualquer app na tela
 *   - Clicar em elementos de UI em nome do usuário
 *   - Preencher formulários automaticamente
 *   - Responder notificações
 *
 * É opt-in explícito via configurações + biometria Level 3.
 * Zero ação sem consentimento ativo do usuário.
 */
class SynaptAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ARIA::Accessibility"
        var isConnected = false
            private set
    }

    override fun onServiceConnected() {
        isConnected = true
        Log.i(TAG, "✅ Accessibility Service connected")

        serviceInfo = serviceInfo?.also {
            it.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            it.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            it.notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Sprint 2: forward relevant events to AgentViewModel for AI processing
        // Example: detect when user is composing an email → suggest AI draft
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        isConnected = false
        super.onDestroy()
    }
}
