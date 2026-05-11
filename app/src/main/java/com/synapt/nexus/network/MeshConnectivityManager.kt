package com.synapt.nexus.network

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 🌐 MeshConnectivityManager — NEXUS
 *
 * Gerencia o estado dual da mesh neural:
 *   OFFLINE → WiFi Direct P2P (sem roteador, sem internet)
 *   ONLINE  → LAN via mDNS  +  Internet via WireGuard VPN
 *   HYBRID  → mix de nós locais e remotos
 *
 * A mesh é inteligente: detecta mudanças de conectividade e
 * promove/rebaixa nós automaticamente sem interromper inferências.
 *
 * Fluxo de decisão:
 *   1. Ao subir: tenta mDNS discovery na LAN
 *   2. Se nenhum nó encontrado e WiFi Direct disponível: inicia P2P discovery
 *   3. Se nós remotos configurados (via WireGuard): conecta simultaneamente
 *   4. Monitora qualidade dos links e remove nós degradados
 */
class MeshConnectivityManager(
    private val context: Context,
    private val router: MeshInferenceRouter
) {
    companion object {
        private const val TAG = "NEXUS::MeshConnectivity"
        private const val P2P_SERVICE_TYPE = "_synapt._tcp."
        private const val LINK_CHECK_INTERVAL_MS = 5_000L
        private const val NODE_TIMEOUT_MS = 10_000L
    }

    // ─── Mesh State ──────────────────────────────────────────────────────────

    enum class MeshMode {
        ISOLATED,       // Nenhum nó conectado — somente local
        OFFLINE_MESH,   // WiFi Direct P2P apenas — sem internet
        LAN_MESH,       // Mesma rede Wi-Fi — mDNS discovery
        INTERNET_MESH,  // Nós remotos via WireGuard VPN
        HYBRID_MESH     // Combinação de LAN + Internet
    }

    data class MeshStatus(
        val mode: MeshMode = MeshMode.ISOLATED,
        val totalNodes: Int = 0,
        val onlineNodes: Int = 0,
        val offlineNodes: Int = 0,
        val combinedTps: Float = 0f,
        val isDiscovering: Boolean = false
    )

    private val _meshStatus = MutableStateFlow(MeshStatus())
    val meshStatus: StateFlow<MeshStatus> = _meshStatus.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── WiFi Direct (Offline Mesh) ──────────────────────────────────────────

    private val wifiP2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pDiscoveryActive = false

    // ─── Network Callbacks ───────────────────────────────────────────────────

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "📶 Network available — triggering mesh re-evaluation")
            scope.launch { evaluateAndAdaptMesh() }
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "📵 Network lost — checking offline mesh fallback")
            scope.launch { handleNetworkLoss() }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val hasWifi    = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Log.d(TAG, "🔄 Network capabilities: wifi=$hasWifi internet=$hasInternet")
            scope.launch { evaluateAndAdaptMesh() }
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        Log.i(TAG, "🌐 MeshConnectivityManager starting...")

        // Register network callback
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build(),
            networkCallback
        )

        // Init WiFi Direct channel
        p2pChannel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)

        // Start periodic node health checks
        scope.launch {
            while (isActive) {
                checkNodeHealth()
                delay(LINK_CHECK_INTERVAL_MS)
            }
        }

        scope.launch { evaluateAndAdaptMesh() }
        Log.i(TAG, "✅ MeshConnectivityManager started")
    }

    fun stop() {
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        stopP2pDiscovery()
        scope.cancel()
        Log.i(TAG, "🛑 MeshConnectivityManager stopped")
    }

    // ─── Core: Mesh Evaluation ────────────────────────────────────────────────

    private suspend fun evaluateAndAdaptMesh() = withContext(Dispatchers.IO) {
        val network = connectivityManager.activeNetwork
        val caps    = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val hasWifi    = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val meshNodes  = router.getMeshNodes()

        Log.d(TAG, "🧠 Evaluating mesh | wifi=$hasWifi internet=$hasInternet knownNodes=${meshNodes.size}")

        when {
            // Internet available → online + LAN mesh
            hasInternet && hasWifi -> {
                startMdnsDiscovery()
                connectWireGuardPeers()
                updateMode(MeshMode.INTERNET_MESH)
            }

            // WiFi but no internet → LAN only
            hasWifi && !hasInternet -> {
                startMdnsDiscovery()
                updateMode(MeshMode.LAN_MESH)
            }

            // No WiFi at all → try P2P offline mesh
            !hasWifi -> {
                startP2pDiscovery()
                updateMode(MeshMode.OFFLINE_MESH)
            }
        }

        // Recalculate combined TPS after mode update
        recalculateMeshStats()
    }

    private suspend fun handleNetworkLoss() = withContext(Dispatchers.IO) {
        Log.w(TAG, "⚠️ Network lost — activating offline P2P fallback")

        // Don't immediately drop mesh nodes — give 10s grace period
        delay(10_000L)

        val stillConnected = connectivityManager.activeNetwork != null
        if (!stillConnected) {
            // Full offline: rely on P2P
            startP2pDiscovery()
            updateMode(MeshMode.OFFLINE_MESH)
            Log.i(TAG, "📡 Switched to OFFLINE_MESH mode (WiFi Direct)")
        } else {
            evaluateAndAdaptMesh()
        }
    }

    // ─── mDNS Discovery (LAN / Online) ───────────────────────────────────────

    private var mdnsDiscovery: MdnsDiscovery? = null

    private fun startMdnsDiscovery() {
        if (mdnsDiscovery?.isRunning == true) return
        Log.i(TAG, "🔍 Starting mDNS discovery on LAN...")

        mdnsDiscovery = MdnsDiscovery(context, SynaptWebServer.SNW_PORT)
        mdnsDiscovery?.startDiscovery { nodeInfo ->
            Log.i(TAG, "✨ mDNS: discovered node ${nodeInfo.name} at ${nodeInfo.address}:${nodeInfo.port}")
            // TODO Sprint 3: exchange WireGuard keys and create VPN tunnel to this node
        }
    }

    // ─── WiFi Direct Discovery (Offline Mesh) ────────────────────────────────

    private fun startP2pDiscovery() {
        val manager = wifiP2pManager ?: run {
            Log.w(TAG, "WiFi Direct not available on this device")
            return
        }
        val channel = p2pChannel ?: return
        if (p2pDiscoveryActive) return

        Log.i(TAG, "📡 Starting WiFi Direct P2P discovery...")

        // Register Synapt service on P2P network
        val record = mapOf(
            "port"    to "${SynaptWebServer.SNW_PORT}",
            "version" to "SNW/1.0",
            "name"    to android.os.Build.MODEL
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            "SynaptNexus",
            "_synapt._tcp",
            record
        )

        manager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.i(TAG, "✅ P2P service registered") }
            override fun onFailure(reason: Int) { Log.w(TAG, "P2P service registration failed: $reason") }
        })

        // Discover other Synapt nodes
        manager.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, resourceType ->
                if (instanceName.startsWith("SynaptNexus")) {
                    Log.i(TAG, "✨ P2P: found Synapt node '$instanceName' at ${resourceType.deviceAddress}")
                    // TODO Sprint 3: P2P connection + key exchange
                }
            },
            { fullDomainName, record, device ->
                val port = record["port"]?.toIntOrNull() ?: SynaptWebServer.SNW_PORT
                Log.i(TAG, "📋 P2P service record: $fullDomainName port=$port")
            }
        )

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        manager.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { }
            override fun onFailure(reason: Int) { Log.w(TAG, "P2P service request failed: $reason") }
        })

        manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                p2pDiscoveryActive = true
                Log.i(TAG, "✅ P2P discovery started")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "P2P discovery failed: $reason — falling back to isolated mode")
                updateMode(MeshMode.ISOLATED)
            }
        })
    }

    private fun stopP2pDiscovery() {
        val manager = wifiP2pManager ?: return
        val channel = p2pChannel ?: return
        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { p2pDiscoveryActive = false }
            override fun onFailure(p0: Int) {}
        })
    }

    // ─── WireGuard (Internet Mesh) ───────────────────────────────────────────

    private fun connectWireGuardPeers() {
        // Sprint 3: WireGuardManager.connectKnownPeers()
        // For MVP: peers are added via pairing flow (NFC/QR/WiFi Direct)
        Log.d(TAG, "🔒 WireGuard peer connection — Sprint 3 implementation")
    }

    // ─── Node Health Checks ───────────────────────────────────────────────────

    private suspend fun checkNodeHealth() = withContext(Dispatchers.IO) {
        val nodes = router.getMeshNodes()
        val now = System.currentTimeMillis()
        var changed = false

        nodes.forEach { node ->
            if (now - node.lastSeenMs > NODE_TIMEOUT_MS) {
                Log.w(TAG, "💀 Node ${node.displayName} timed out — removing from mesh")
                router.removeNode(node.nodeId)
                changed = true
            }
        }

        if (changed) recalculateMeshStats()
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    private fun recalculateMeshStats() {
        val nodes = router.getMeshNodes()
        val onlineNodes = nodes.count { it.thermalMode != "SAFE" }
        val combinedTps = nodes.sumOf { it.tokensPerSecond.toDouble() }.toFloat()

        val currentMode = _meshStatus.value.mode
        val newMode = when {
            nodes.isEmpty() -> MeshMode.ISOLATED
            else            -> currentMode
        }

        _meshStatus.value = MeshStatus(
            mode         = newMode,
            totalNodes   = nodes.size,
            onlineNodes  = onlineNodes,
            offlineNodes = nodes.size - onlineNodes,
            combinedTps  = combinedTps,
            isDiscovering = p2pDiscoveryActive || mdnsDiscovery?.isRunning == true
        )
    }

    private fun updateMode(mode: MeshMode) {
        _meshStatus.update { it.copy(mode = mode) }
        Log.i(TAG, "🌐 Mesh mode → ${mode.name}")
    }
}
