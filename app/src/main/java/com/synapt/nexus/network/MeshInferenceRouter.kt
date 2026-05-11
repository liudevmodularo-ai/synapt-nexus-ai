package com.synapt.nexus.network

import android.util.Log
import com.synapt.nexus.inference.LlamaCppBridge
import com.synapt.nexus.thermal.ThermalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 🌐 MeshInferenceRouter — NEXUS + PULSE
 *
 * Ponto central de roteamento de inferência.
 * Toda requisição — do agente local, WebOS client, ou outro nó —
 * passa por aqui antes de ser executada.
 *
 * Decisão de roteamento:
 *   1. Coleta snapshots de todos os nós ativos (incluindo local)
 *   2. Calcula RouteScore para cada nó
 *   3. Roteia para o melhor nó
 *   4. Fallback automático para local se mesh indisponível
 *
 * RouteScore = deviceScore × (1 - load) × thermalFactor - latencyPenalty
 */
class MeshInferenceRouter(
    private val localBridge: LlamaCppBridge,
    private val thermalManager: ThermalManager,
    private val sessionManager: LocalSessionManager
) {
    companion object {
        private const val TAG = "NEXUS::MeshRouter"
        private const val LATENCY_PENALTY_PER_MS = 0.003f   // per ms RTT
        private const val LOCAL_BONUS = 0.15f                // prefer local when tied
    }

    // ─── Node Registry (populated by GossipProtocol) ──────────────────────────
    private val knownNodes = mutableMapOf<String, MeshNodeSnapshot>()

    @Serializable
    data class MeshNodeSnapshot(
        val nodeId: String,
        val displayName: String,
        val deviceScore: Int,
        val currentLoad: Float,        // 0.0–1.0
        val queueDepth: Int,
        val thermalMode: String,
        val loadedModel: String?,
        val tokensPerSecond: Float,
        val availableRamMb: Long,
        val vpnIp: String,             // WireGuard assigned IP
        val port: Int,
        val lastSeenMs: Long = System.currentTimeMillis(),
        val estimatedRttMs: Int = 0    // measured by gossip ping
    )

    data class RouteDecision(
        val nodeId: String,
        val isLocal: Boolean,
        val score: Float,
        val reason: String
    )

    sealed class InferenceResult {
        data class Token(
            val text: String,
            val done: Boolean,
            val tokensPerSecond: Float,
            val totalTokens: Int,
            val processedBy: String,   // nodeId que executou
            val isLocal: Boolean
        ) : InferenceResult()

        data class Error(val message: String, val code: Int) : InferenceResult()
    }

    // ─── Main Entry Point ─────────────────────────────────────────────────────

    /**
     * Roteia uma requisição de inferência para o melhor nó disponível.
     * Emite tokens conforme chegam (streaming).
     */
    fun routeInference(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        preferredModel: String? = null,
        sessionId: String = UUID.randomUUID().toString()
    ): Flow<InferenceResult> = flow {

        val decision = selectBestNode(preferredModel)
        Log.i(TAG, "🔀 Route decision: nodeId=${decision.nodeId} | local=${decision.isLocal} | score=${"%.2f".format(decision.score)} | reason=${decision.reason}")

        if (decision.isLocal) {
            // ── LOCAL INFERENCE ──────────────────────────────────────────────
            emitAll(
                routeToLocal(prompt, maxTokens, temperature, sessionId)
            )
        } else {
            // ── MESH INFERENCE ───────────────────────────────────────────────
            val node = knownNodes[decision.nodeId]!!
            emitAll(
                routeToMeshNode(node, prompt, maxTokens, temperature, sessionId)
            )
        }

    }.catch { e ->
        Log.e(TAG, "Router error: ${e.message} — falling back to local")
        emitAll(routeToLocal(prompt, maxTokens, temperature, sessionId))
    }

    // ─── Local Routing ────────────────────────────────────────────────────────

    private fun routeToLocal(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        sessionId: String
    ): Flow<InferenceResult> = flow {
        if (!localBridge.isModelLoaded) {
            emit(InferenceResult.Error("Nenhum modelo carregado. Vá em Modelos e carregue um.", 400))
            return@flow
        }

        localBridge.streamInference(
            LlamaCppBridge.InferenceRequest(
                sessionId = sessionId,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature
            )
        ).collect { token ->
            val delay = thermalManager.getTokenEmitDelay()
            if (delay > 0) kotlinx.coroutines.delay(delay)

            emit(InferenceResult.Token(
                text = token.token,
                done = token.isFinished,
                tokensPerSecond = token.tokensPerSecond,
                totalTokens = token.tokensGenerated,
                processedBy = "local",
                isLocal = true
            ))
        }
    }.flowOn(Dispatchers.IO)

    // ─── Mesh Node Routing ────────────────────────────────────────────────────

    private fun routeToMeshNode(
        node: MeshNodeSnapshot,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        sessionId: String
    ): Flow<InferenceResult> = flow {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val token = sessionManager.getTokenForNode(node.nodeId)
        val nonce = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis().toString()

        val body = """
            {
              "prompt": ${Json.encodeToString(kotlinx.serialization.serializer(), prompt)},
              "max_tokens": $maxTokens,
              "temperature": $temperature,
              "stream": true,
              "session_id": "$sessionId"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("https://${node.vpnIp}:${node.port}/v1/generate")
            .header("Authorization", "Bearer $token")
            .header("X-Synapt-Timestamp", timestamp)
            .header("X-Synapt-Nonce", nonce)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                emit(InferenceResult.Error("Node error: ${response.code}", response.code))
                return@use
            }

            val responseBody = response.body?.string() ?: ""
            // Parse streamed response and emit tokens
            responseBody.split("\n").forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val streamToken = Json.decodeFromString<SynaptWebServer.StreamToken>(line)
                    emit(InferenceResult.Token(
                        text = streamToken.token,
                        done = streamToken.done,
                        tokensPerSecond = streamToken.tokensPerSecond,
                        totalTokens = streamToken.totalTokens,
                        processedBy = node.displayName,
                        isLocal = false
                    ))
                } catch (_: Exception) { }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ─── Node Selection Algorithm (PULSE) ────────────────────────────────────

    private suspend fun selectBestNode(preferredModel: String?): RouteDecision =
        withContext(Dispatchers.IO) {
            val localSnapshot = buildLocalSnapshot()
            val localScore = computeScore(localSnapshot, isLocal = true)

            // No mesh nodes? Go local.
            val activeMeshNodes = knownNodes.values.filter { node ->
                val age = System.currentTimeMillis() - node.lastSeenMs
                age < 6_000L &&                                  // seen in last 6s
                node.thermalMode != "SAFE" &&                    // not overheating
                (preferredModel == null || node.loadedModel == preferredModel)
            }

            if (activeMeshNodes.isEmpty()) {
                return@withContext RouteDecision(
                    nodeId = "local",
                    isLocal = true,
                    score = localScore,
                    reason = if (knownNodes.isEmpty()) "Sem nós na mesh" else "Todos os nós indisponíveis"
                )
            }

            // Score all candidates including local
            data class Candidate(val nodeId: String, val isLocal: Boolean, val score: Float)

            val candidates = buildList {
                add(Candidate("local", true, localScore))
                activeMeshNodes.forEach { node ->
                    add(Candidate(node.nodeId, false, computeScore(node, false)))
                }
            }

            val best = candidates.maxBy { it.score }
            RouteDecision(
                nodeId = best.nodeId,
                isLocal = best.isLocal,
                score = best.score,
                reason = if (best.isLocal) "Local é o nó mais eficiente" else "Mesh node tem maior score"
            )
        }

    private fun computeScore(node: MeshNodeSnapshot, isLocal: Boolean): Float {
        val thermalFactor = when (node.thermalMode) {
            "NORMAL"  -> 1.0f
            "REDUCED" -> 0.5f
            "SAFE"    -> 0.0f
            else      -> 0.8f
        }
        val loadFactor    = (1f - node.currentLoad).coerceIn(0f, 1f)
        val latencyPenalty = if (isLocal) 0f else node.estimatedRttMs * LATENCY_PENALTY_PER_MS
        val localBonus    = if (isLocal) LOCAL_BONUS else 0f

        return (node.deviceScore / 100f) * loadFactor * thermalFactor - latencyPenalty + localBonus
    }

    private fun buildLocalSnapshot(): MeshNodeSnapshot {
        val thermal = thermalManager.thermalState.value
        val load = if (localBridge.isModelLoaded) {
            1f - (thermal.availableRamMb.toFloat() / thermal.totalRamMb)
        } else 0f

        return MeshNodeSnapshot(
            nodeId = "local",
            displayName = "Este dispositivo",
            deviceScore = 65,   // Will be replaced by real DeviceScore after Sprint 2
            currentLoad = load,
            queueDepth = 0,
            thermalMode = thermal.mode.name,
            loadedModel = localBridge.loadedModelPath.ifEmpty { null },
            tokensPerSecond = 0f,
            availableRamMb = thermal.availableRamMb,
            vpnIp = "127.0.0.1",
            port = SynaptWebServer.SNW_PORT,
            estimatedRttMs = 0
        )
    }

    // ─── Node Registry (called by GossipProtocol) ─────────────────────────────

    fun updateNodeSnapshot(snapshot: MeshNodeSnapshot) {
        knownNodes[snapshot.nodeId] = snapshot
        Log.d(TAG, "📡 Node updated: ${snapshot.displayName} | load=${snapshot.currentLoad} | thermal=${snapshot.thermalMode}")
    }

    fun removeNode(nodeId: String) {
        knownNodes.remove(nodeId)
        Log.i(TAG, "🔌 Node removed from mesh: $nodeId")
    }

    fun getMeshNodes(): List<MeshNodeSnapshot> = knownNodes.values.toList()
    fun getMeshSize(): Int = knownNodes.size

    // Placeholder for Sprint 3
    class LocalSessionManager {
        fun getTokenForNode(nodeId: String): String = "mesh-token-placeholder-$nodeId"
    }
}
