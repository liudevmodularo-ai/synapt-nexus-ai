package com.synapt.nexus.network

import android.content.Context
import android.util.Log
import com.synapt.nexus.inference.LlamaCppBridge
import com.synapt.nexus.model.ModelManager
import com.synapt.nexus.security.SecurityManager
import com.synapt.nexus.thermal.ThermalManager
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.time.Duration
import java.util.UUID
import javax.net.ssl.KeyManagerFactory

/**
 * 🌐 SynaptWebServer — NEXUS
 *
 * SNW v1 Protocol server.
 *
 * FIXES:
 *   ✅ B1: TLS agora aplicado corretamente ao Netty via sslConnector
 *   ✅ B6: CORS configurado para WebOS browser clients
 *   ✅ Copy-paste: return@delete corrigido
 */
class SynaptWebServer(
    private val context: Context,
    private val security: SecurityManager,
    private val thermal: ThermalManager,
    private val inference: LlamaCppBridge,
    private val modelManager: ModelManager
) {
    companion object {
        private const val TAG = "NEXUS::WebServer"
        const val SNW_PORT = 7474
        const val SNW_VERSION = "SNW/1.0"
        private const val TLS_STORE_PASS = "synapt_tls_2024"
        private const val TLS_KEY_ALIAS  = "synapt_nexus"
    }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var mdnsService: MdnsDiscovery? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val startTime = System.currentTimeMillis()

    @Volatile var isRunning = false
        private set

    // ─── DTOs ─────────────────────────────────────────────────────────────────

    @Serializable data class GenerateRequest(
        val model: String = "",
        val prompt: String,
        val maxTokens: Int = 512,
        val temperature: Float = 0.7f,
        val stream: Boolean = false,
        val sessionId: String? = null
    )

    @Serializable data class StreamToken(
        val token: String,
        val done: Boolean,
        val tokensPerSecond: Float = 0f,
        val totalTokens: Int = 0
    )

    @Serializable data class StatusResponse(
        val status: String,
        val version: String,
        val loadedModel: String?,
        val thermalMode: String,
        val temperatureCelsius: Float,
        val availableRamMb: Long,
        val activeClients: Int,
        val uptime: Long
    )

    @Serializable data class ErrorResponse(
        val error: String,
        val code: Int,
        val retryAfter: Int? = null
    )

    @Serializable data class ModelListResponse(val models: List<ModelInfo>)

    @Serializable data class ModelInfo(
        val id: String,
        val name: String,
        val format: String,
        val sizeBytes: Long,
        val isLoaded: Boolean
    )

    // ─── Start ────────────────────────────────────────────────────────────────

    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isRunning) return@runCatching

            // ── Build PKCS12 KeyStore from our TLS cert ─────────────────────
            val (cert, keyPair) = security.getOrCreateTlsCertificate()

            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(
                    TLS_KEY_ALIAS,
                    keyPair.private,
                    TLS_STORE_PASS.toCharArray(),
                    arrayOf(cert)
                )
            }

            // ── FIX B1: TLS applied via applicationEngineEnvironment ────────
            val environment = applicationEngineEnvironment {

                sslConnector(
                    keyStore = keyStore,
                    keyAlias = TLS_KEY_ALIAS,
                    keyStorePassword = { TLS_STORE_PASS.toCharArray() },
                    privateKeyPassword = { TLS_STORE_PASS.toCharArray() }
                ) {
                    host = "0.0.0.0"
                    port = SNW_PORT
                }

                // HTTP fallback on port 7475 for LAN clients that haven't updated
                connector {
                    host = "0.0.0.0"
                    port = SNW_PORT + 1
                }

                module {
                    // ── FIX B6: CORS for WebOS browser clients ──────────────
                    install(CORS) {
                        allowMethod(HttpMethod.Options)
                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)
                        allowMethod(HttpMethod.Delete)
                        allowHeader(HttpHeaders.Authorization)
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader("X-Synapt-Timestamp")
                        allowHeader("X-Synapt-Nonce")
                        allowCredentials = true
                        anyHost()  // Restrict to specific origin in production
                    }

                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true; prettyPrint = false })
                    }

                    install(WebSockets) {
                        pingPeriod = Duration.ofSeconds(15)
                        timeout = Duration.ofSeconds(60)
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }

                    configureRouting()
                }
            }

            server = embeddedServer(Netty, environment)
            server!!.start(wait = false)
            isRunning = true

            // mDNS announcement
            mdnsService = MdnsDiscovery(context, SNW_PORT)
            mdnsService?.announce()

            Log.i(TAG, "✅ SNW v1 server running | HTTPS port=$SNW_PORT | HTTP port=${SNW_PORT + 1}")
        }
    }

    fun stop() {
        mdnsService?.unannounce()
        server?.stop(1000, 3000)
        server = null
        isRunning = false
        Log.i(TAG, "🛑 SNW server stopped")
    }

    // ─── Routing ──────────────────────────────────────────────────────────────

    private fun Application.configureRouting() {
        routing {

            // ── Public ──────────────────────────────────────────────────────

            post("/v1/pair") {
                val body = runCatching { call.receive<SecurityManager.PairRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid body", 400)); return@post
                }
                security.initiatePairing(body, call.request.origin.remoteHost)
                    .onSuccess { call.respond(it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Error", 400)) }
            }

            post("/v1/pair/confirm") {
                val body = runCatching { call.receive<SecurityManager.PairConfirmRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid body", 400)); return@post
                }
                security.confirmPairing(body, call.request.origin.remoteHost)
                    .onSuccess { call.respond(it) }
                    .onFailure { call.respond(HttpStatusCode.Unauthorized, ErrorResponse(it.message ?: "Error", 401)) }
            }

            // Health ping — no auth, useful for client discovery
            get("/v1/ping") {
                call.respond(mapOf("pong" to true, "version" to SNW_VERSION))
            }

            // ── Authenticated ────────────────────────────────────────────────

            get("/v1/status") {
                authenticateOrReject(call) ?: return@get
                val snap = thermal.thermalState.value
                call.respond(StatusResponse(
                    status              = if (thermal.canAcceptInference()) "ready" else "thermal_pause",
                    version             = SNW_VERSION,
                    loadedModel         = inference.loadedModelPath.ifEmpty { null },
                    thermalMode         = snap.mode.name,
                    temperatureCelsius  = snap.temperatureCelsius,
                    availableRamMb      = snap.availableRamMb,
                    activeClients       = security.getActiveSessions().size,
                    uptime              = System.currentTimeMillis() - startTime
                ))
            }

            get("/v1/models") {
                authenticateOrReject(call) ?: return@get
                val models = modelManager.getAvailableModels().map {
                    ModelInfo(it.id, it.displayName, it.format.name.lowercase(),
                        it.sizeBytes, it.filePath == inference.loadedModelPath)
                }
                call.respond(ModelListResponse(models))
            }

            post("/v1/inference/load") {
                authenticateOrReject(call) ?: return@post
                val body = runCatching { call.receive<Map<String, String>>() }.getOrElse { emptyMap() }
                val modelId = body["model_id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("model_id required", 400)); return@post
                }
                val model = modelManager.findById(modelId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Model not found", 404)); return@post
                }
                val maxGb = thermal.getRecommendedMaxModelSizeGb()
                val modelGb = model.sizeBytes / (1024f * 1024 * 1024)
                if (modelGb > maxGb) {
                    call.respond(HttpStatusCode.InsufficientStorage,
                        ErrorResponse("Insufficient RAM: ${modelGb}GB needed, ${maxGb}GB available", 507))
                    return@post
                }
                inference.loadGgufModel(java.io.File(model.filePath))
                    .onSuccess { call.respond(mapOf("status" to "loaded", "model" to model.displayName)) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError,
                        ErrorResponse(it.message ?: "Load failed", 500)) }
            }

            post("/v1/generate") {
                authenticateOrReject(call) ?: return@post
                if (!thermal.canAcceptInference()) {
                    val snap = thermal.thermalState.value
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Thermal pause — retry in ${snap.retryAfterSeconds}s", 503, snap.retryAfterSeconds))
                    return@post
                }
                if (!inference.isModelLoaded) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No model loaded", 400)); return@post
                }
                val req = runCatching { call.receive<GenerateRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request", 400)); return@post
                }
                val sb = StringBuilder(); var finalTps = 0f
                inference.streamInference(LlamaCppBridge.InferenceRequest(
                    sessionId = req.sessionId ?: UUID.randomUUID().toString(),
                    prompt = req.prompt, maxTokens = req.maxTokens, temperature = req.temperature
                )).catch { }.collect { sb.append(it.token); finalTps = it.tokensPerSecond }
                call.respond(mapOf("response" to sb.toString(), "tokens_per_second" to finalTps.toString()))
            }

            webSocket("/v1/stream") {
                val authResult = security.authenticateRequest(
                    call.request.headers["Authorization"],
                    call.request.headers["X-Synapt-Timestamp"],
                    call.request.headers["X-Synapt-Nonce"],
                    call.request.origin.remoteHost
                )
                if (authResult is SecurityManager.AuthResult.Failure) {
                    send(Frame.Text(Json.encodeToString(ErrorResponse(authResult.reason, authResult.code))))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, authResult.reason))
                    return@webSocket
                }
                val sessionId = (authResult as SecurityManager.AuthResult.Success).clientId

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val req = runCatching { Json.decodeFromString<GenerateRequest>(frame.readText()) }
                        .getOrElse { send(Frame.Text(Json.encodeToString(ErrorResponse("Invalid JSON", 400)))); continue }

                    if (!thermal.canAcceptInference()) {
                        send(Frame.Text(Json.encodeToString(ErrorResponse("Thermal pause", 503,
                            thermal.thermalState.value.retryAfterSeconds)))); continue
                    }
                    if (!inference.isModelLoaded) {
                        send(Frame.Text(Json.encodeToString(ErrorResponse("No model loaded", 400)))); continue
                    }

                    inference.streamInference(LlamaCppBridge.InferenceRequest(
                        sessionId = sessionId, prompt = req.prompt,
                        maxTokens = req.maxTokens, temperature = req.temperature
                    )).catch { e ->
                        send(Frame.Text(Json.encodeToString(ErrorResponse("Error: ${e.message}", 500))))
                    }.collect { result ->
                        val delay = thermal.getTokenEmitDelay()
                        if (delay > 0) kotlinx.coroutines.delay(delay)
                        send(Frame.Text(Json.encodeToString(StreamToken(
                            token = result.token, done = result.isFinished,
                            tokensPerSecond = result.tokensPerSecond, totalTokens = result.tokensGenerated
                        ))))
                    }
                }
            }

            // FIX: return@delete (not return@get)
            delete("/v1/pair/{clientId}") {
                authenticateOrReject(call) ?: return@delete
                val targetId = call.parameters["clientId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("clientId required", 400)); return@delete
                }
                security.revokeClient(targetId)
                call.respond(mapOf("status" to "revoked", "clientId" to targetId))
            }
        }
    }

    private suspend fun authenticateOrReject(call: ApplicationCall): String? {
        val result = security.authenticateRequest(
            call.request.headers["Authorization"],
            call.request.headers["X-Synapt-Timestamp"],
            call.request.headers["X-Synapt-Nonce"],
            call.request.origin.remoteHost
        )
        return when (result) {
            is SecurityManager.AuthResult.Success -> result.clientId
            is SecurityManager.AuthResult.Failure -> {
                call.respond(HttpStatusCode(result.code, result.reason),
                    ErrorResponse(result.reason, result.code))
                null
            }
        }
    }
}
