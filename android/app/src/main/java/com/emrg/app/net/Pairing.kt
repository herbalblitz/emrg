package com.emrg.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A refused pairing, with the gateway's stable code. */
class PairingException(val code: String, message: String) : Exception(message)

data class PairOutcome(
    val deviceId: String,
    val deviceName: String,
    val token: String,
    val gatewayHost: String,
    val gatewayPort: Int,
    val apiVersion: String,
    val gatewayVersion: String,
)

/**
 * The second half of pairing.
 *
 * The connection is already pinned to the fingerprint the user confirmed, so
 * the one-shot code only travels to the certificate the host itself displayed.
 * The gateway answers `gw_pair_ok` with the device token exactly once and then
 * closes; this function returns that token to the caller, which stores it in
 * Android Keystore and immediately forgets the plaintext.
 */
object Pairing {

    suspend fun pair(
        host: String,
        port: Int,
        pin: String,
        code: String,
        deviceName: String,
        appVersion: String,
        timeoutMs: Long = 20_000,
    ): PairOutcome = withContext(Dispatchers.IO) {
        // A fingerprint the user typed by hand is often short a character or
        // two. That used to throw IllegalArgumentException out of
        // pinnedSocketFactory — an exception the Pair screen never caught, so
        // the app crashed instead of showing the error. Validate here and
        // report it as a pairing failure like any other.
        val normalized = Tls.normalizeFingerprint(pin)
        if (normalized.length != 64) {
            throw PairingException(
                "bad_fingerprint",
                "the certificate fingerprint looks incomplete — it should be 64 hex " +
                    "digits (the long string with colons printed by emrg-gateway pair)",
            )
        }
        val trust = Tls.pinningTrustManager(pin)
        val client = OkHttpClient.Builder()
            .sslSocketFactory(Tls.pinnedSocketFactory(pin), trust)
            .hostnameVerifier(Tls.pinnedHostnameVerifier)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        try {
            withTimeout(timeoutMs) { runPair(client, host, port, code, deviceName, appVersion) }
        } catch (e: TimeoutCancellationException) {
            throw PairingException(
                "timeout",
                "the host did not answer in ${timeoutMs / 1_000} seconds — " +
                    "check that emrg-gateway is running",
            )
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private suspend fun runPair(
        client: OkHttpClient,
        host: String,
        port: Int,
        code: String,
        deviceName: String,
        appVersion: String,
    ): PairOutcome = suspendCancellableCoroutine { cont ->
        val settled = AtomicBoolean(false)
        val request = Request.Builder().url(wsUrl(host, port, GatewayProtocol.PAIR_PATH)).build()

        fun fail(error: Throwable) {
            if (settled.compareAndSet(false, true)) cont.resumeWithException(error)
        }

        fun succeed(outcome: PairOutcome) {
            if (settled.compareAndSet(false, true)) cont.resume(outcome)
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val frame = buildJsonObject {
                    put("type", "gw_pair")
                    put("code", code)
                    put("device_name", deviceName)
                    put("app_version", appVersion)
                }
                webSocket.send(frame.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val value = runCatching { EmrgJson.parseToJsonElement(text) as JsonObject }.getOrNull()
                if (value == null) {
                    fail(PairingException("bad_frame", "the host sent a frame the app could not read"))
                    return
                }
                when (value.str("type")) {
                    "gw_pair_ok" -> {
                        val device = value.obj("device")
                        val gateway = value.obj("gateway")
                        val token = value.str("token")
                        val deviceId = device?.str("id")
                        if (token.isNullOrEmpty() || deviceId.isNullOrEmpty()) {
                            fail(PairingException("bad_frame", "the host's pairing answer was incomplete"))
                            return
                        }
                        succeed(
                            PairOutcome(
                                deviceId = deviceId,
                                deviceName = device.str("name") ?: deviceName,
                                token = token,
                                gatewayHost = gateway?.str("host") ?: host,
                                gatewayPort = gateway?.int("port") ?: port,
                                apiVersion = value.str("api_version") ?: GatewayProtocol.API_VERSION,
                                gatewayVersion = gateway?.str("gateway_version").orEmpty(),
                            )
                        )
                        webSocket.close(1000, "paired")
                    }
                    "gw_error" -> {
                        val errCode = value.str("code") ?: "pairing_rejected"
                        val message = value.str("message") ?: "the host refused pairing"
                        fail(PairingException(errCode, message))
                        webSocket.close(1008, "pairing refused")
                    }
                    else -> Unit // `gw_pair` produces exactly one of the two above.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val hint = if (response?.code == 404) " — is emrg-gateway running on that port?" else ""
                fail(
                    PairingException(
                        "unreachable",
                        "cannot reach the host at $host:$port$hint (${t.message ?: t.javaClass.simpleName})",
                    )
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                fail(
                    PairingException(
                        "closed",
                        "the host closed the pairing connection before answering ($reason)",
                    )
                )
            }
        }

        val socket = client.newWebSocket(request, listener)
        cont.invokeOnCancellation {
            settled.set(true)
            socket.cancel()
        }
    }

    /**
     * `wss://host:port/path`, with an IPv6 literal bracketed.
     *
     * A phone that pairs over link-local IPv6 is a normal case on a home LAN,
     * and an unbracketed literal is not a URL.
     */
    fun wsUrl(host: String, port: Int, path: String): String {
        val clean = host.trim().removePrefix("wss://").removePrefix("https://").trimEnd('/')
        val bracketed = if (clean.count { it == ':' } >= 2 && !clean.startsWith("[")
                            && clean.substringBefore(':').toIntOrNull() == null) {
            "[${clean.trim('[', ']')}]"
        } else {
            clean
        }
        // A pasted URL may already carry a port; the caller's port wins only
        // when the text has none.
        val authority = if (bracketed.substringAfterLast(':').toIntOrNull() != null &&
            !bracketed.endsWith("]")) bracketed else "$bracketed:$port"
        return "wss://$authority$path"
    }
}
