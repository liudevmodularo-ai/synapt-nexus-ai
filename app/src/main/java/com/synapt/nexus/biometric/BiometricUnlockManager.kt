package com.synapt.nexus.biometric

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 🔐 BiometricUnlockManager — CIPHER
 *
 * Controla autenticação biométrica e o sistema de Trust Levels.
 *
 * TRUST LEVELS:
 *   LEVEL_1 (HUB)        → sem biometria, só inferência + mesh
 *   LEVEL_2 (ASSISTENTE) → biometria requerida: SMS, localização, overlay, câmera
 *   LEVEL_3 (AUTÔNOMO)   → biometria requerida: screen capture, audio, accessibility
 *
 * A biometria é solicitada ao tentar elevar o nível de confiança.
 * Uma vez autenticado, o nível persiste enquanto o app está em foreground.
 * Em background por >15min: volta ao Nível 1 automaticamente.
 */
class BiometricUnlockManager(private val context: Context) {

    companion object {
        private const val TAG = "CIPHER::Biometric"
        private const val SESSION_TIMEOUT_MS = 15 * 60 * 1000L  // 15 min
    }

    enum class TrustLevel(val level: Int, val label: String, val description: String) {
        LEVEL_1(1, "Hub",       "Inferência local e mesh neural"),
        LEVEL_2(2, "Assistente","SMS, localização, overlay, câmera"),
        LEVEL_3(3, "Autônomo",  "Captura de tela, áudio, accessibility service")
    }

    data class BiometricSession(
        val level: TrustLevel,
        val authenticatedAt: Long = System.currentTimeMillis()
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() - authenticatedAt > SESSION_TIMEOUT_MS
    }

    private val _currentLevel = MutableStateFlow(TrustLevel.LEVEL_1)
    val currentLevel: StateFlow<TrustLevel> = _currentLevel

    private var session: BiometricSession? = null

    // ─── Capability check ─────────────────────────────────────────────────────

    fun isBiometricAvailable(): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun getBiometricStatus(): String {
        return when (BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )) {
            BiometricManager.BIOMETRIC_SUCCESS          -> "Digital/Face disponível"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Hardware não disponível"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Hardware temporariamente indisponível"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Nenhuma biometria cadastrada"
            else -> "Status desconhecido"
        }
    }

    // ─── Elevation request ────────────────────────────────────────────────────

    /**
     * Solicita elevação do Trust Level via biometria.
     * Se nível 1 é solicitado, libera sem autenticação.
     */
    fun requestTrustLevel(
        activity: FragmentActivity,
        requestedLevel: TrustLevel,
        onSuccess: (TrustLevel) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (requestedLevel == TrustLevel.LEVEL_1) {
            setLevel(TrustLevel.LEVEL_1)
            onSuccess(TrustLevel.LEVEL_1)
            return
        }

        // Check if current session already covers requested level
        val current = session
        if (current != null && !current.isExpired && current.level.level >= requestedLevel.level) {
            Log.d(TAG, "Session valid, granting ${requestedLevel.label} without re-auth")
            onSuccess(requestedLevel)
            return
        }

        if (!isBiometricAvailable()) {
            onFailure("Biometria não disponível. Configure digital ou PIN no sistema.")
            return
        }

        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.i(TAG, "✅ Biometric auth succeeded → granting ${requestedLevel.label}")
                session = BiometricSession(requestedLevel)
                setLevel(requestedLevel)
                onSuccess(requestedLevel)
            }

            override fun onAuthenticationFailed() {
                Log.w(TAG, "Biometric authentication failed")
                // Don't call onFailure — user can retry
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.w(TAG, "Biometric error $errorCode: $errString")
                onFailure(errString.toString())
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Synapt Nexus — ${requestedLevel.label}")
            .setSubtitle("Autentique para ativar: ${requestedLevel.description}")
            .setDescription(
                if (requestedLevel == TrustLevel.LEVEL_3)
                    "⚠️ Nível Autônomo concede ao agente acesso a captura de tela, microfone e accessibility service."
                else
                    "Nível Assistente permite SMS, localização e câmera."
            )
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo)
    }

    /**
     * Revoga o nível de confiança atual (volta para LEVEL_1)
     */
    fun revokeElevation() {
        session = null
        setLevel(TrustLevel.LEVEL_1)
        Log.i(TAG, "Trust level revoked → LEVEL_1")
    }

    /**
     * Chamado quando app vai para background — inicia contagem de timeout
     */
    fun onAppBackgrounded() {
        // The session's isExpired property handles this via timestamp check
        // Active check happens when next permission is requested
        Log.d(TAG, "App backgrounded — session timeout countdown started")
    }

    fun hasPermission(requiredLevel: TrustLevel): Boolean {
        val current = session ?: return requiredLevel == TrustLevel.LEVEL_1
        if (current.isExpired) {
            session = null
            setLevel(TrustLevel.LEVEL_1)
            return requiredLevel == TrustLevel.LEVEL_1
        }
        return current.level.level >= requiredLevel.level
    }

    private fun setLevel(level: TrustLevel) {
        _currentLevel.value = level
    }
}
