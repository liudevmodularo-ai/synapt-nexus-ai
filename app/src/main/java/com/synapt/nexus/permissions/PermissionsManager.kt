package com.synapt.nexus.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 🔐 PermissionsManager — CIPHER + ARIA
 *
 * Gerencia permissões em runtime agrupadas por Trust Level.
 * O app solicita permissões gradualmente conforme o usuário
 * eleva o nível de confiança via biometria.
 */
object PermissionsManager {

    // ─── Permission Groups by Trust Level ─────────────────────────────────────

    /** Sempre solicitado na primeira abertura */
    val LEVEL_1_PERMISSIONS = buildList {
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.ACCESS_WIFI_STATE)
        add(Manifest.permission.CHANGE_WIFI_STATE)
        add(Manifest.permission.CHANGE_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        add(Manifest.permission.NFC)
    }

    /** Solicitado ao ativar Trust Level 2 */
    val LEVEL_2_PERMISSIONS = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            @Suppress("DEPRECATION")
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** Solicitado ao ativar Trust Level 3 */
    val LEVEL_3_PERMISSIONS = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        // Screen capture via MediaProjection não tem permissão de Manifest,
        // é concedida via Intent em runtime (MediaProjectionManager)
        // Accessibility Service é habilitado via Settings, não Manifest
    }

    // ─── Special Permissions (requer Settings redirect) ─────────────────────

    data class SpecialPermission(
        val name: String,
        val description: String,
        val isGranted: (Context) -> Boolean,
        val openSettings: (Context) -> Unit
    )

    fun getSpecialPermissions(context: Context): List<SpecialPermission> = listOf(
        SpecialPermission(
            name = "Sobrepor outros apps",
            description = "Necessário para o overlay flutuante do agente",
            isGranted = { Settings.canDrawOverlays(it) },
            openSettings = {
                it.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${it.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        ),
        SpecialPermission(
            name = "Uso de apps",
            description = "Permite monitorar uso de rede por app",
            isGranted = { isUsageStatsGranted(it) },
            openSettings = {
                it.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        ),
        SpecialPermission(
            name = "Instalar apps desconhecidos",
            description = "Para distribuição do app fora da Play Store",
            isGranted = { if (Build.VERSION.SDK_INT >= 26) it.packageManager.canRequestPackageInstalls() else true },
            openSettings = {
                if (Build.VERSION.SDK_INT >= 26) {
                    it.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${it.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        ),
        SpecialPermission(
            name = "Serviço de acessibilidade",
            description = "Permite ao agente interagir com outros apps (Trust Level 3)",
            isGranted = { isAccessibilityEnabled(it) },
            openSettings = {
                it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        ),
        SpecialPermission(
            name = "Bateria sem otimização",
            description = "Mantém o hub rodando em background sem interrupção",
            isGranted = {
                val pm = it.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                pm.isIgnoringBatteryOptimizations(it.packageName)
            },
            openSettings = {
                it.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${it.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        )
    )

    // ─── Checkers ─────────────────────────────────────────────────────────────

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun getMissingPermissions(context: Context, permissions: List<String>): List<String> =
        permissions.filter { !isGranted(context, it) }

    fun isLevel1Complete(context: Context): Boolean =
        getMissingPermissions(context, LEVEL_1_PERMISSIONS).isEmpty()

    fun isLevel2Complete(context: Context): Boolean =
        getMissingPermissions(context, LEVEL_2_PERMISSIONS).isEmpty()

    fun isLevel3Complete(context: Context): Boolean =
        getMissingPermissions(context, LEVEL_3_PERMISSIONS).isEmpty()

    private fun isUsageStatsGranted(context: Context): Boolean {
        val am = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = am.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), context.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        return try {
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
            if (accessibilityEnabled == 0) return false
            val services = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            services.contains("${context.packageName}/.service.SynaptAccessibilityService")
        } catch (_: Exception) { false }
    }
}
