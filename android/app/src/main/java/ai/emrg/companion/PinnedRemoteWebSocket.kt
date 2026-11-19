package ai.emrg.companion

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit

/** Trust only the exact certificate approved by QR/user confirmation. */
internal class ExactPinTrustManager(expectedFingerprint: String) : X509TrustManager {
    private val expected = normalizePin(expectedFingerprint)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw java.security.cert.CertificateException("Missing peer certificate")
        val leaf = chain[0]
        try {
            leaf.checkValidity()
        } catch (_: Exception) {
            throw java.security.cert.CertificateException("Pinned certificate is expired or not yet valid")
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!MessageDigest.isEqual(actual, expected)) {
            throw java.security.cert.CertificateException("Peer certificate pin mismatch")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        throw java.security.cert.CertificateException("Client certificates are not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun normalizePin(value: String): ByteArray =
        value.replace(":", "").replace(" ", "").trim().uppercase()
            .takeIf { it.matches(Regex("[0-9A-F]{64}")) }
            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            ?: throw IllegalArgumentException("Host certificate fingerprint is invalid")
}

/** Events from a paired host socket. Authentication is not considered ready until host auth_ok. */
internal interface RemoteSocketListener {
    fun onState(state: RemoteSocketState, safeMessage: String? = null)
    fun onFrame(frame: ProtocolFrame)
}

internal enum class RemoteSocketState {
    CONNECTING, TLS_IDENTITY_VERIFIED, AUTHENTICATING, AUTHENTICATED,
    AUTH_REJECTED, IDENTITY_REJECTED, CLOSED, FAILED,
}

/**
 * Client-side WSS primitive. A pairing descriptor's exact leaf SHA-256 pin is installed
 * as the trust anchor for this connection, permitting a self-signed host cert. OkHttp's
 * default hostname verifier remains enabled. The pin is checked during TLS handshake,
 * before any credential frame can be sent. No reconnect or command replay is implemented.
 *
 * Host-side remote pairing/auth endpoints are not implemented in EMRG daemon yet.
 */
internal class PinnedRemoteWebSocket(
    private val credentialStore: CredentialStore,
    private val listener: RemoteSocketListener,
    private val client: OkHttpClient = defaultClient(),
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var pairedHost: PairedHost? = null
    @Volatile private var authenticated = false
    @Volatile private var generation = 0L
    @Volatile private var terminalState: RemoteSocketState? = null

    @Synchronized
    fun connect(host: PairedHost) {
        close()
        require(isSecurePairingEndpoint(host.endpoint)) { "Only secure WSS endpoints are allowed" }
        require(isSha256Fingerprint(host.hostFingerprintSha256)) { "Host certificate fingerprint is invalid" }
        require(host.deviceId.isNotBlank()) { "Device ID is missing" }
        require(host.scopes.isNotEmpty()) { "No approved pairing scopes" }
        val credential = credentialStore.get(host.credentialAlias)
            ?: throw IllegalStateException("Paired credential is unavailable")
        require(credential.isNotBlank()) { "Paired credential is blank" }
        pairedHost = host
        authenticated = false
        terminalState = null
        val attemptGeneration = generation
        listener.onState(RemoteSocketState.CONNECTING)

        val request = Request.Builder().url(host.endpoint).build()
        val pinnedClient = createPinnedClient(client, host.hostFingerprintSha256)
        socket = pinnedClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (attemptGeneration != generation) { webSocket.cancel(); return }
                val peerCerts = response.handshake?.peerCertificates.orEmpty()
                val actual = peerCerts.firstOrNull()?.let(::certificateFingerprint)
                if (actual == null || !actual.equals(host.hostFingerprintSha256, ignoreCase = true)) {
                    terminalState = RemoteSocketState.IDENTITY_REJECTED
                    listener.onState(RemoteSocketState.IDENTITY_REJECTED, "Host certificate identity mismatch")
                    webSocket.close(1008, "identity mismatch")
                    socket = null
                    pairedHost = null
                    return
                }
                listener.onState(RemoteSocketState.TLS_IDENTITY_VERIFIED)
                listener.onState(RemoteSocketState.AUTHENTICATING)
                // This client frame is a proposed companion-auth contract; the daemon currently
                // only supports its local desktop token auth and must not accept this yet.
                val auth = createAuthFrame(host.deviceId, credential)
                if (!webSocket.send(auth.toString())) {
                    terminalState = RemoteSocketState.FAILED
                    listener.onState(RemoteSocketState.FAILED, "Authentication frame could not be sent")
                    webSocket.close(1011, "auth send failed")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (attemptGeneration != generation) return
                val frame = try { ProtocolFrame.parse(text) } catch (_: Exception) {
                    terminalState = RemoteSocketState.FAILED
                    listener.onState(RemoteSocketState.FAILED, "Invalid host frame")
                    webSocket.close(1007, "invalid frame")
                    return
                }
                if (!isAllowedFrameInAuthState(authenticated, frame.type)) {
                    terminalState = RemoteSocketState.AUTH_REJECTED
                    listener.onState(
                        RemoteSocketState.AUTH_REJECTED,
                        "Host sent a frame outside the authentication state",
                    )
                    webSocket.close(1008, "unexpected authentication state")
                    return
                }
                when (frame.type) {
                    "auth_ok" -> {
                        val expectedHost = pairedHost
                        if (expectedHost == null || !isValidAuthSuccess(frame, expectedHost)) {
                            authenticated = false
                            terminalState = RemoteSocketState.AUTH_REJECTED
                            listener.onState(RemoteSocketState.AUTH_REJECTED, "Host authentication response is invalid")
                            webSocket.close(1008, "invalid auth response")
                            return
                        }
                        authenticated = true
                        listener.onState(RemoteSocketState.AUTHENTICATED)
                    }
                    "auth_error", "auth_rejected" -> {
                        authenticated = false
                        terminalState = RemoteSocketState.AUTH_REJECTED
                        listener.onState(RemoteSocketState.AUTH_REJECTED, "Host rejected pairing credential")
                        webSocket.close(1008, "auth rejected")
                        return
                    }
                }
                listener.onFrame(frame)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (attemptGeneration != generation) return
                terminalState = RemoteSocketState.FAILED
                listener.onState(RemoteSocketState.FAILED, "Binary frames are unsupported")
                webSocket.close(1003, "text frames only")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (attemptGeneration != generation) return
                authenticated = false
                if (terminalState == null) listener.onState(RemoteSocketState.CLOSED)
                webSocket.close(code, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (attemptGeneration != generation) return
                authenticated = false
                socket = null
                if (terminalState == null) listener.onState(RemoteSocketState.FAILED, "Secure host connection failed")
            }
        })
    }

    /** Only chat-task payloads are exposed here; arbitrary daemon commands are not. */
    fun sendTask(command: TaskCommand): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.SEND_CHAT !in host.scopes) return false
        val approvedCwd = host.approvedCwd ?: return false
        if (command.cwd != approvedCwd) return false
        if (command.cwd.isBlank() || command.prompt.isBlank() || command.id.isBlank()) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionChatFrame(command, workspaceId).toString()) == true
    }

    @Synchronized
    fun close() {
        generation += 1
        authenticated = false
        pairedHost = null
        socket?.close(1000, "client disconnect")
        socket = null
    }

    companion object {
        internal fun isAllowedFrameInAuthState(authenticated: Boolean, frameType: String?): Boolean {
            val authFrames = setOf("auth_ok", "auth_error", "auth_rejected")
            return frameType?.let {
                if (authenticated) it !in authFrames else it in authFrames
            } == true
        }

        internal fun isValidAuthSuccess(frame: ProtocolFrame, host: PairedHost): Boolean {
            if (frame.type != "auth_ok" || frame.raw.optString("protocol") != "1.0") return false
            val returnedWorkspace = frame.raw.optString("workspace_id").takeIf { it.isNotBlank() }
            if (PairingScope.SEND_CHAT in host.scopes) {
                return !host.approvedWorkspaceId.isNullOrBlank() &&
                    returnedWorkspace == host.approvedWorkspaceId
            }
            return host.approvedWorkspaceId == null || returnedWorkspace == host.approvedWorkspaceId
        }

        internal fun companionChatFrame(command: TaskCommand, workspaceId: String): JSONObject {
            require(workspaceId.isNotBlank() && workspaceId.toByteArray(Charsets.UTF_8).size <= 128) { "approved workspace is invalid" }
            require(command.id.isNotBlank() && command.id.toByteArray(Charsets.UTF_8).size <= 128) { "invalid request ID" }
            require(command.prompt.isNotBlank() && command.prompt.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "invalid prompt" }
            require(command.sessionId == null || (command.sessionId.isNotBlank() && command.sessionId.toByteArray(Charsets.UTF_8).size <= 128)) { "invalid session ID" }
            return JSONObject()
                .put("type", "chat.send")
                .put("request_id", command.id)
                .put("workspace_id", workspaceId)
                .put("prompt", command.prompt)
                .put("session_id", command.sessionId)
        }

        internal fun createAuthFrame(deviceId: String, credential: String): JSONObject =
            JSONObject()
                .put("type", "auth")
                .put("protocol", "1.0")
                .put("device_id", deviceId)
                .put("credential", credential)
                .put("client_nonce", java.util.UUID.randomUUID().toString())

        internal fun certificateFingerprint(certificate: Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return digest.joinToString(":") { "%02X".format(it) }
        }

        internal fun createPinnedClient(base: OkHttpClient, expectedFingerprint: String): OkHttpClient {
            require(isSha256Fingerprint(expectedFingerprint)) { "Host certificate fingerprint is invalid" }
            val trustManager = ExactPinTrustManager(expectedFingerprint)
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager), java.security.SecureRandom())
            return base.newBuilder()
                .sslSocketFactory(context.socketFactory, trustManager)
                .build()
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket is long-lived; no read timeout.
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
