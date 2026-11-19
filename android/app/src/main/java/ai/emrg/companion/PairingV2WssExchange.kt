package ai.emrg.companion

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One-frame request/response channel for the host's dedicated Pairing v2 WSS endpoint. */
internal interface PairingV2ExchangeListener {
    fun onResponse(response: JSONObject)
    fun onFailure(safeCode: String)
}

internal fun interface PairingV2ExchangeHandle {
    fun cancel()
}

/**
 * Sends exactly one pairing.request or pairing.redeem frame after the QR-pinned TLS
 * handshake succeeds. The host responds once and closes the socket; this is deliberately
 * separate from the long-lived authenticated companion socket.
 */
internal class PairingV2WssExchange(
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    fun exchange(
        endpoint: String,
        fingerprintSha256: String,
        requestFrame: JSONObject,
        listener: PairingV2ExchangeListener,
    ): PairingV2ExchangeHandle {
        require(isSecurePairingEndpoint(endpoint)) { "Only secure WSS endpoints are allowed" }
        require(isSha256Fingerprint(fingerprintSha256)) { "Host certificate fingerprint is invalid" }
        val requestType = requestFrame.opt("type")
        require(requestType == "pairing.request" || requestType == "pairing.redeem") {
            "Unsupported pairing exchange frame"
        }
        require(requestFrame.opt("protocol") == "2.0" &&
            requestFrame.opt("pairing_id") is String) { "Pairing exchange frame is invalid" }

        val completed = AtomicBoolean(false)
        fun fail(code: String) {
            if (completed.compareAndSet(false, true)) listener.onFailure(code)
        }
        val client = PinnedRemoteWebSocket.createPinnedClient(baseClient, fingerprintSha256)
        val socket = client.newWebSocket(
            Request.Builder().url(endpoint).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val peerFingerprint = response.handshake?.peerCertificates
                        ?.firstOrNull()?.let(PinnedRemoteWebSocket::certificateFingerprint)
                    if (peerFingerprint == null ||
                        !peerFingerprint.equals(fingerprintSha256, ignoreCase = true)) {
                        fail("HOST_IDENTITY_MISMATCH")
                        webSocket.close(1008, "identity mismatch")
                        return
                    }
                    // No application bytes are sent before the certificate pin is checked.
                    if (!webSocket.send(requestFrame.toString())) {
                        fail("PAIRING_SEND_FAILED")
                        webSocket.close(1011, "pairing send failed")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (completed.get()) return
                    if (text.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
                        fail("PAIRING_RESPONSE_TOO_LARGE")
                        webSocket.close(1009, "response too large")
                        return
                    }
                    val parsed = try { JSONObject(text) } catch (_: Exception) { null }
                    if (parsed == null || !isExpectedResponse(requestFrame, parsed)) {
                        fail("PAIRING_RESPONSE_INVALID")
                        webSocket.close(1008, "invalid pairing response")
                        return
                    }
                    if (completed.compareAndSet(false, true)) listener.onResponse(parsed)
                    webSocket.close(1000, "pairing exchange complete")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    fail("PAIRING_BINARY_FRAME_REJECTED")
                    webSocket.close(1003, "text frames only")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!completed.get()) fail("PAIRING_RESPONSE_MISSING")
                    webSocket.close(code, null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail("PAIRING_CONNECTION_FAILED")
                }
            },
        )
        return PairingV2ExchangeHandle {
            if (completed.compareAndSet(false, true)) socket.cancel()
        }
    }

    internal companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024

        fun isExpectedResponse(request: JSONObject, response: JSONObject): Boolean {
            val pairingId = request.optString("pairing_id")
            if (response.opt("protocol") != "2.0" ||
                response.opt("pairing_id") != pairingId) return false
            return when (request.opt("type")) {
                "pairing.request" ->
                    (response.opt("type") == "pairing.challenge" &&
                        response.length() == 5 &&
                        response.opt("challenge_id") is String &&
                        response.opt("challenge") is String) ||
                    (response.opt("type") == "pairing.error" &&
                        response.length() == 4 && response.opt("code") is String)
                "pairing.redeem" ->
                    (response.opt("type") == "pairing.credential" &&
                        response.length() == 5 &&
                        response.opt("device_id") == request.opt("device_id") &&
                        response.opt("credential") is String) ||
                    (response.opt("type") == "pairing.error" &&
                        response.length() == 4 && response.opt("code") is String)
                else -> false
            }
        }
    }
}
