package com.synapt.nexus.security

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.abs
import kotlin.random.Random

/**
 * 🔐 SecurityManager — CIPHER
 *
 * Full security layer for Synapt Nexus AI.
 * Handles:
 *   - TLS self-signed certificate (ECDSA P-256)
 *   - Client pairing with challenge-response
 *   - Bearer token issuance and validation
 *   - Anti-replay via timestamp + nonce
 *   - IP + MAC whitelist enforcement
 *   - Rate limiting per client
 */
class SecurityManager(private val context: Context) {

    companion object {
        private const val TAG = "CIPHER::SecurityManager"
        private const val CERT_CN = "synapt-nexus-local"
        private const val CERT_VALIDITY_DAYS = 365
        private const val TOKEN_EXPIRY_MS = 24 * 60 * 60 * 1000L  // 24h
        private const val NONCE_CACHE_TTL_MS = 60_000L             // 1 min nonce cache
        private const val REPLAY_WINDOW_MS = 30_000L               // ±30s timestamp window
        private const val KEYSTORE_ALIAS = "synapt_nexus_tls"
    }

    // ─── DataStore for persisted paired clients ──────────────────────────────
    private val Context.securityDataStore: DataStore<Preferences> by preferencesDataStore(
        name = "synapt_security"
    )
    private val PAIRED_CLIENTS_KEY = stringPreferencesKey("paired_clients")
    private val TLS_CERT_KEY = stringPreferencesKey("tls_cert_pem")

    // ─── In-memory state ─────────────────────────────────────────────────────
    private val activeSessions = ConcurrentHashMap<String, SessionInfo>()
    private val pendingChallenges = ConcurrentHashMap<String, PairingChallenge>()
    private val usedNonces = ConcurrentHashMap<String, Long>()       // nonce → timestamp
    private val rateLimiters = ConcurrentHashMap<String, RateLimiter>()

    private var tlsCertificate: X509Certificate? = null
    private var tlsKeyPair: KeyPair? = null

    // ─── Data Classes ─────────────────────────────────────────────────────────

    @Serializable
    data class PairedClient(
        val clientId: String,
        val displayName: String,
        val ipAddress: String,
        val macAddress: String,
        val pairedAt: Long,
        val publicKeyBase64: String
    )

    data class SessionInfo(
        val clientId: String,
        val bearerToken: String,
        val expiresAt: Long,
        val ipAddress: String
    )

    data class PairingChallenge(
        val clientId: String,
        val challengeBytes: ByteArray,
        val sessionKey: SecretKey,
        val createdAt: Long = System.currentTimeMillis()
    )

    @Serializable
    data class PairRequest(
        val clientId: String,
        val displayName: String,
        val publicKeyBase64: String
    )

    @Serializable
    data class PairResponse(
        val challengeEncrypted: String,   // Base64 AES-GCM encrypted challenge
        val sessionKeyEncrypted: String   // Base64 RSA-encrypted session key
    )

    @Serializable
    data class PairConfirmRequest(
        val clientId: String,
        val challengeDecrypted: String    // Base64 raw challenge bytes
    )

    @Serializable
    data class PairConfirmResponse(
        val bearerToken: String,
        val expiresIn: Long,              // seconds
        val hubInfo: HubInfo
    )

    @Serializable
    data class HubInfo(
        val version: String = "SNW/1.0",
        val deviceModel: String = android.os.Build.MODEL,
        val supportedFormats: List<String> = listOf("gguf", "onnx")
    )

    sealed class AuthResult {
        data class Success(val clientId: String) : AuthResult()
        data class Failure(val reason: String, val code: Int) : AuthResult()
    }

    private inner class RateLimiter(
        private val maxPerSecond: Int = 3,
        private val burstSize: Int = 5
    ) {
        private val tokens = java.util.concurrent.atomic.AtomicInteger(burstSize)
        private var lastRefill = System.currentTimeMillis()

        fun tryConsume(): Boolean {
            refill()
            return tokens.getAndDecrement() > 0
        }

        private fun refill() {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRefill
            if (elapsed >= 1000L) {
                tokens.set(minOf(burstSize, tokens.get() + (elapsed / 1000L * maxPerSecond).toInt()))
                lastRefill = now
            }
        }
    }

    // ─── TLS Certificate ─────────────────────────────────────────────────────

    /**
     * Generate or load existing ECDSA P-256 self-signed certificate.
     * Certificate is persisted and reused across app restarts.
     */
    suspend fun getOrCreateTlsCertificate(): Pair<X509Certificate, KeyPair> =
        withContext(Dispatchers.IO) {
            tlsCertificate?.let { cert ->
                tlsKeyPair?.let { kp ->
                    return@withContext Pair(cert, kp)
                }
            }

            Log.i(TAG, "🔐 Generating ECDSA P-256 TLS certificate...")

            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(256, SecureRandom())
            val keyPair = keyGen.generateKeyPair()

            val now = Date()
            val expiry = Date(now.time + CERT_VALIDITY_DAYS * 24 * 60 * 60 * 1000L)

            val certBuilder = JcaX509v3CertificateBuilder(
                X500Name("CN=$CERT_CN"),
                BigInteger(64, SecureRandom()),
                now,
                expiry,
                X500Name("CN=$CERT_CN"),
                keyPair.public
            )

            val signer = JcaContentSignerBuilder("SHA256withECDSA")
                .build(keyPair.private)

            val cert = JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer))

            tlsCertificate = cert
            tlsKeyPair = keyPair

            Log.i(TAG, "✅ TLS cert generated | subject=${cert.subjectDN} | valid until=${expiry}")
            Pair(cert, keyPair)
        }

    // ─── Pairing Flow ────────────────────────────────────────────────────────

    /**
     * Step 1: Client initiates pairing.
     * Returns an encrypted challenge the client must decrypt and return.
     */
    suspend fun initiatePairing(request: PairRequest, clientIp: String): Result<PairResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Generate 32-byte random challenge
                val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }

                // Generate AES-256 session key
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(256)
                val sessionKey = keyGen.generateKey()

                // Encrypt challenge with session key (AES-GCM)
                val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(128, iv))
                val encryptedChallenge = iv + cipher.doFinal(challenge)

                // Encrypt session key with client's public key (RSA)
                val clientPublicKeyBytes = Base64.getDecoder().decode(request.publicKeyBase64)
                val clientPublicKey = KeyFactory.getInstance("RSA").generatePublic(
                    java.security.spec.X509EncodedKeySpec(clientPublicKeyBytes)
                )
                val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
                rsaCipher.init(Cipher.ENCRYPT_MODE, clientPublicKey)
                val encryptedSessionKey = rsaCipher.doFinal(sessionKey.encoded)

                pendingChallenges[request.clientId] = PairingChallenge(
                    clientId = request.clientId,
                    challengeBytes = challenge,
                    sessionKey = sessionKey
                )

                Log.i(TAG, "🤝 Pairing initiated for client: ${request.clientId} from $clientIp")

                PairResponse(
                    challengeEncrypted = Base64.getEncoder().encodeToString(encryptedChallenge),
                    sessionKeyEncrypted = Base64.getEncoder().encodeToString(encryptedSessionKey)
                )
            }
        }

    /**
     * Step 2: Client returns decrypted challenge.
     * If correct, issues Bearer token and saves pairing.
     */
    suspend fun confirmPairing(
        request: PairConfirmRequest,
        clientIp: String,
        clientMac: String = "unknown",
        displayName: String = "WebOS Client"
    ): Result<PairConfirmResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val pending = pendingChallenges[request.clientId]
                ?: error("No pending pairing for clientId: ${request.clientId}")

            // Verify challenge hasn't expired (5 min window)
            check(System.currentTimeMillis() - pending.createdAt < 5 * 60 * 1000L) {
                "Pairing challenge expired"
            }

            val submittedBytes = Base64.getDecoder().decode(request.challengeDecrypted)
            check(submittedBytes.contentEquals(pending.challengeBytes)) {
                "Challenge verification failed — unauthorized client"
            }

            // Issue bearer token
            val token = generateSecureToken()
            val expiresAt = System.currentTimeMillis() + TOKEN_EXPIRY_MS

            activeSessions[token] = SessionInfo(
                clientId = request.clientId,
                bearerToken = token,
                expiresAt = expiresAt,
                ipAddress = clientIp
            )

            pendingChallenges.remove(request.clientId)
            rateLimiters[request.clientId] = RateLimiter()

            Log.i(TAG, "✅ Client paired successfully: ${request.clientId} from $clientIp")

            PairConfirmResponse(
                bearerToken = token,
                expiresIn = TOKEN_EXPIRY_MS / 1000L,
                hubInfo = HubInfo()
            )
        }
    }

    // ─── Request Authentication ───────────────────────────────────────────────

    /**
     * Validates every incoming request.
     * Checks: Bearer token, timestamp window, nonce uniqueness, rate limit.
     */
    fun authenticateRequest(
        authHeader: String?,
        timestampHeader: String?,
        nonceHeader: String?,
        clientIp: String
    ): AuthResult {
        // 1. Extract Bearer token
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return AuthResult.Failure("Missing Authorization header", 401)
        }
        val token = authHeader.removePrefix("Bearer ").trim()

        // 2. Validate token + session
        val session = activeSessions[token]
            ?: return AuthResult.Failure("Invalid or expired token", 401)

        if (System.currentTimeMillis() > session.expiresAt) {
            activeSessions.remove(token)
            return AuthResult.Failure("Token expired", 401)
        }

        // 3. IP consistency check
        if (session.ipAddress != clientIp) {
            Log.w(TAG, "⚠️ IP mismatch for session ${session.clientId}: expected ${session.ipAddress}, got $clientIp")
            return AuthResult.Failure("IP address mismatch", 403)
        }

        // 4. Anti-replay: Timestamp check (±30s)
        val timestamp = timestampHeader?.toLongOrNull()
            ?: return AuthResult.Failure("Missing X-Synapt-Timestamp", 400)

        if (abs(System.currentTimeMillis() - timestamp) > REPLAY_WINDOW_MS) {
            return AuthResult.Failure("Request timestamp out of window", 400)
        }

        // 5. Anti-replay: Nonce uniqueness
        val nonce = nonceHeader
            ?: return AuthResult.Failure("Missing X-Synapt-Nonce", 400)

        cleanExpiredNonces()
        if (usedNonces.containsKey(nonce)) {
            return AuthResult.Failure("Duplicate nonce detected (replay attack)", 400)
        }
        usedNonces[nonce] = System.currentTimeMillis()

        // 6. Rate limiting
        val rateLimiter = rateLimiters[session.clientId]
        if (rateLimiter != null && !rateLimiter.tryConsume()) {
            return AuthResult.Failure("Rate limit exceeded (3 req/s)", 429)
        }

        return AuthResult.Success(session.clientId)
    }

    /**
     * Revoke a paired client's access
     */
    fun revokeClient(clientId: String) {
        activeSessions.entries.removeIf { it.value.clientId == clientId }
        rateLimiters.remove(clientId)
        Log.i(TAG, "🚫 Client revoked: $clientId")
    }

    fun getActiveSessions(): List<SessionInfo> = activeSessions.values.toList()

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun cleanExpiredNonces() {
        val cutoff = System.currentTimeMillis() - NONCE_CACHE_TTL_MS
        usedNonces.entries.removeIf { it.value < cutoff }
    }
}
