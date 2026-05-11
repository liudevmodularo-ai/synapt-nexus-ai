package com.synapt.nexus.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * 🌐 MdnsDiscovery — NEXUS
 *
 * Anuncia o hub Synapt na rede local via mDNS (Bonjour/Zeroconf)
 * e descobre outros nós na mesma rede.
 *
 * Service type: _synapt._tcp.local.
 * Nome: SynaptNexus-{deviceHash}
 */
class MdnsDiscovery(
    private val context: Context,
    private val port: Int
) {
    companion object {
        private const val TAG          = "NEXUS::MdnsDiscovery"
        private const val SERVICE_TYPE = "_synapt._tcp.local."
    }

    data class DiscoveredNode(
        val name: String,
        val address: String,
        val port: Int,
        val version: String,
        val model: String
    )

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryCallback: ((DiscoveredNode) -> Unit)? = null

    var isRunning = false
        private set

    // ─── Announce ─────────────────────────────────────────────────────────────

    fun announce() {
        acquireMulticastLock()
        try {
            val localIp = getLocalIpAddress()
            jmdns = JmDNS.create(InetAddress.getByName(localIp))

            val serviceInfo = ServiceInfo.create(
                SERVICE_TYPE,
                "SynaptNexus-${getDeviceHash()}",
                port,
                "version=SNW/1.0 model=${getCurrentModel()} device=${android.os.Build.MODEL}"
            )

            jmdns?.registerService(serviceInfo)
            isRunning = true
            Log.i(TAG, "✅ mDNS announced: SynaptNexus-${getDeviceHash()} on $localIp:$port")
        } catch (e: Exception) {
            Log.e(TAG, "mDNS announce failed: ${e.message}")
        }
    }

    fun unannounce() {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
            jmdns = null
        } catch (_: Exception) {}
        releaseMulticastLock()
        isRunning = false
        Log.i(TAG, "🛑 mDNS unannounced")
    }

    // ─── Discovery ────────────────────────────────────────────────────────────

    fun startDiscovery(onNodeFound: (DiscoveredNode) -> Unit) {
        discoveryCallback = onNodeFound

        try {
            val dns = jmdns ?: run {
                acquireMulticastLock()
                val localIp = getLocalIpAddress()
                JmDNS.create(InetAddress.getByName(localIp)).also { jmdns = it }
            }

            dns.addServiceListener(SERVICE_TYPE, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    dns.requestServiceInfo(event.type, event.name, 1000)
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info
                    val address = info.inetAddresses.firstOrNull()?.hostAddress ?: return

                    val node = DiscoveredNode(
                        name    = event.name,
                        address = address,
                        port    = info.port,
                        version = info.getPropertyString("version") ?: "SNW/1.0",
                        model   = info.getPropertyString("model") ?: "unknown"
                    )
                    Log.i(TAG, "✨ mDNS discovered: ${node.name} at ${node.address}:${node.port}")
                    discoveryCallback?.invoke(node)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    Log.i(TAG, "👋 mDNS node left: ${event.name}")
                }
            })
            Log.i(TAG, "🔍 mDNS discovery started for $SERVICE_TYPE")
        } catch (e: Exception) {
            Log.e(TAG, "mDNS discovery failed: ${e.message}")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("SynaptNexusMDNS").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    private fun getLocalIpAddress(): String {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff, ip shr 8 and 0xff,
            ip shr 16 and 0xff, ip shr 24 and 0xff
        )
    }

    private fun getDeviceHash(): String =
        android.os.Build.SERIAL.takeLast(6).ifBlank {
            android.os.Build.MODEL.replace(" ", "").take(6)
        }

    private fun getCurrentModel(): String = "none" // injected via InferenceService in Sprint 2
}
