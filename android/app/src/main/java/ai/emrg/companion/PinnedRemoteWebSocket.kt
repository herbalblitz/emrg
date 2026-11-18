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
import java.util.concurrent.TimeUnit

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
 * Client-side WSS primitive. It uses system TLS validation and then additionally verifies
 * the leaf certificate SHA-256 fingerprint from the pairing descriptor before sending
 * the device credential. It intentionally implements no reconnect or command replay.
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
        socket = client.newWebSocket(request, object : WebSocketListener() {
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
                val auth = JSONObject()
                    .put("type", "auth")
                    .put("device_id", host.deviceId)
                    .put("credential", credential)
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
                when (frame.type) {
                    "auth_ok" -> {
                        authenticated = true
                        listener.onState(RemoteSocketState.AUTHENTICATED)
                    }
                    "auth_error", "auth_rejected" -> {
                        authenticated = false
                        terminalState = RemoteSocketState.AUTH_REJECTED
                        listener.onState(RemoteSocketState.AUTH_REJECTED, "Host rejected pairing credential")
                        webSocket.close(1008, "auth rejected")
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
        if (command.cwd.isBlank() || command.prompt.isBlank() || command.id.isBlank()) return false
        return socket?.send(command.toJson().toString()) == true
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
        internal fun certificateFingerprint(certificate: Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return digest.joinToString(":") { "%02X".format(it) }
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket is long-lived; no read timeout.
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
