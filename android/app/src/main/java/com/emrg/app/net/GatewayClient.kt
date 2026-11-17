package com.emrg.app.net

import com.emrg.app.model.HostProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The link's lifecycle as the UI renders it. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data class Online(
        val engineVersion: String,
        val apiVersion: String,
        val replayed: Int,
        val seq: Long,
    ) : LinkState

    data class Offline(val reason: String, val retryInMs: Long = 0) : LinkState
}

/** A refusal the app can render without a stack trace. */
class GatewayRequestException(
    val code: String,
    message: String,
    val retryable: Boolean = false,
) : Exception(message)

/**
 * The phone's side of the gateway protocol.
 *
 * One WebSocket carries two layers, as `emrg/gateway/protocol.py` describes:
 * daemon frames forwarded unchanged, and `gw_*` operations answered by the
 * gateway. The client therefore keeps two ways to wait:
 *
 * * [call] for a `gw_*` operation, correlated by its `rid`;
 * * [request] for a daemon frame, correlated by the response's `type` — the
 *   daemon's contract has no request ids, and inventing a second one here
 *   would drift from the desktop client.
 *
 * Chat is neither: it is a stream, read from [frames] by
 * [com.emrg.app.data.ChatStore].
 *
 * The connection is owned by a supervisor loop rather than by the last screen.
 * A phone that locks its screen drops the socket; the gateway's per-device link
 * keeps the turn alive, and this loop reconnects with `since_seq` so the frames
 * emitted while the app was away are replayed instead of lost.
 */
class GatewayClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _frames = MutableSharedFlow<JsonObject>(
        replay = 0,
        extraBufferCapacity = 2048,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<JsonObject> = _frames.asSharedFlow()

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private var profile: HostProfile? = null
    private var token: String = ""
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    private var connectionJob: Job? = null
    private var desired = false
    private var connectWaiter: CompletableDeferred<Unit>? = null
    private var closedSignal: CompletableDeferred<Unit>? = null
    private var engineVersion: String = ""

    private val lastSeq = AtomicLong(0)
    private val pending = CopyOnWriteArrayList<Waiter>()
    private val sendLock = Any()

    private class Waiter(
        val match: (JsonObject) -> Boolean,
        val deferred: CompletableDeferred<JsonObject>,
    )

    /** Open (or keep open) the link to `profile`, and return once attached. */
    suspend fun connect(profile: HostProfile, token: String) {
        stopLoop()
        this.profile = profile
        this.token = token
        this.desired = true
        this.engineVersion = ""
        val waiter = CompletableDeferred<Unit>()
        connectWaiter = waiter
        _state.value = LinkState.Connecting
        startLoop()
        try {
            withTimeout(25_000) { waiter.await() }
        } catch (e: Exception) {
            if (e is GatewayRequestException) throw e
            throw GatewayRequestException(
                "timeout",
                "the host did not answer in 25 seconds — check that emrg-gateway is running " +
                    "and that the phone is on the same network",
                retryable = true,
            )
        }
    }

    fun disconnect() {
        desired = false
        stopLoop()
        _state.value = LinkState.Idle
    }

    fun close() {
        desired = false
        stopLoop()
        scope.cancel()
    }

    /** The last `gw_seq` seen; sent on reconnect so the gateway replays the gap. */
    fun seenSeq(): Long = lastSeq.get()

    // ── sending ──────────────────────────────────────────────────────

    private fun sendRaw(value: JsonObject): Boolean {
        val ws = socket ?: return false
        return ws.send(value.toString())
    }

    /** Forward one frame to the daemon through the gateway. */
    fun send(type: String, fields: JsonObject = JsonObject(emptyMap())): Boolean =
        sendRaw(GatewayProtocol.daemon(type, fields))

    /** Send one frame and wait for the first reply matching ``match``. */
    suspend fun sendAndAwait(
        type: String,
        fields: JsonObject = JsonObject(emptyMap()),
        match: (JsonObject) -> Boolean,
        timeoutMs: Long = 25_000,
        timeoutMessage: String = "the host did not answer",
    ): JsonObject {
        val id = GatewayProtocol.rid()
        val frame = GatewayProtocol.frame(type, id, fields)
        return await(
            match = match,
            timeoutMs = timeoutMs,
            timeoutMessage = timeoutMessage,
        ) { sendRaw(frame) }
    }

    /** A gateway-owned operation, awaited by its `rid`. */
    suspend fun call(
        type: String,
        fields: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 20_000,
    ): JsonObject {
        val rid = GatewayProtocol.rid()
        val frame = GatewayProtocol.frame(type, rid, fields)
        return await(
            match = { it.str("rid") == rid },
            timeoutMs = timeoutMs,
            timeoutMessage = "the host did not answer $type",
        ) { sendRaw(frame) }
    }

    /**
     * A daemon request whose answer is one frame of `expectType`.
     *
     * The daemon does not echo a request id, so the response type is the
     * correlation — exactly what the desktop client does.
     */
    suspend fun request(
        type: String,
        fields: JsonObject = JsonObject(emptyMap()),
        expectType: String,
        timeoutMs: Long = 25_000,
    ): JsonObject = await(
        match = { it.str("type") == expectType },
        timeoutMs = timeoutMs,
        timeoutMessage = "the host did not answer $type",
    ) { send(type, fields) }

    private suspend fun await(
        match: (JsonObject) -> Boolean,
        timeoutMs: Long,
        timeoutMessage: String,
        action: () -> Boolean,
    ): JsonObject {
        val waiter = Waiter(match, CompletableDeferred())
        pending.add(waiter)
        try {
            if (!action()) {
                throw GatewayRequestException(
                    "offline",
                    "the host is not connected — reconnect from the hosts screen",
                    retryable = true,
                )
            }
            return withTimeout(timeoutMs) { waiter.deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw GatewayRequestException("timeout", "$timeoutMessage within ${timeoutMs / 1000}s", true)
        } finally {
            pending.remove(waiter)
        }
    }

    // ── connection loop ──────────────────────────────────────────────

    private fun startLoop() {
        connectionJob = scope.launch {
            var attempt = 0
            while (isActive && desired) {
                val target = profile ?: break
                val closed = CompletableDeferred<Unit>()
                closedSignal = closed
                val reason = try {
                    openSocket(target)
                    closed.await()
                    "the connection closed"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                if (!desired || !isActive) break
                attempt += 1
                val pause = minOf(15_000L, 1_000L shl minOf(attempt, 4))
                _state.value = LinkState.Offline(reason, pause)
                delay(pause)
            }
        }
    }

    private fun stopLoop() {
        connectionJob?.cancel()
        connectionJob = null
        socket?.close(1000, "client closing")
        socket = null
        val closed = closedSignal
        closedSignal = null
        closed?.complete(Unit)
        failWaiters("the connection was closed")
        connectWaiter = null
    }

    private fun openSocket(target: HostProfile) {
        if (client == null) {
            val trust = Tls.pinningTrustManager(target.fingerprint)
            client = OkHttpClient.Builder()
                .sslSocketFactory(Tls.pinnedSocketFactory(target.fingerprint), trust)
                .hostnameVerifier(Tls.pinnedHostnameVerifier)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
        val request = Request.Builder()
            .url(Pairing.wsUrl(target.host, target.port, GatewayProtocol.SOCKET_PATH))
            .build()
        socket = client!!.newWebSocket(request, Listener(target))
    }

    private inner class Listener(private val target: HostProfile) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val auth = buildJsonObject {
                put("type", "gw_auth")
                put("token", token)
                put("api_version", GatewayProtocol.API_VERSION)
                put("device_id", target.deviceId)
                put("since_seq", lastSeq.get())
                put("app_version", "android/${com.emrg.app.BuildConfig.VERSION_NAME}")
            }
            webSocket.send(auth.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val value = runCatching { EmrgJson.parseToJsonElement(text) as JsonObject }.getOrNull()
                ?: return
            value.long("gw_seq")?.let { seq ->
                if (seq > lastSeq.get()) lastSeq.set(seq)
            }
            when (value.str("type")) {
                "gw_hello_ok" -> {
                    engineVersion = value.str("engine_version").orEmpty()
                }
                "gw_attached" -> {
                    val state = LinkState.Online(
                        engineVersion = engineVersion,
                        apiVersion = GatewayProtocol.API_VERSION,
                        replayed = value.int("replayed") ?: 0,
                        seq = value.long("seq") ?: lastSeq.get(),
                    )
                    // A replayed gap covers the last few seconds only; the
                    // repository reloads history after a reconnect either way.
                    _state.value = state
                    connectWaiter?.complete(Unit)
                    connectWaiter = null
                }
                "gw_error" -> handleError(value)
            }
            val matched = pending.filter { runCatching { it.match(value) }.getOrDefault(false) }
            for (waiter in matched) {
                pending.remove(waiter)
                waiter.deferred.complete(value)
            }
            if (value.str("type") !in HANDSHAKE) _frames.tryEmit(value)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = when {
                response?.code == 401 -> "the host rejected the connection (HTTP 401)"
                response?.code == 404 -> "the gateway endpoint was not found on ${target.host}:${target.port}"
                t is javax.net.ssl.SSLException -> "certificate pinning failed: ${t.message}"
                else -> t.message ?: t.javaClass.simpleName
            }
            endLink(webSocket, message)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            endLink(webSocket, if (reason.isBlank()) "the host closed the connection" else reason)
        }

        private fun endLink(webSocket: WebSocket, message: String) {
            if (socket === webSocket) socket = null
            val waiter = connectWaiter
            if (_state.value is LinkState.Connecting || _state.value is LinkState.Offline) {
                waiter?.completeExceptionally(
                    GatewayRequestException("unreachable", message, retryable = true)
                )
            } else {
                _state.value = LinkState.Offline(message)
            }
            connectWaiter = null
            closedSignal?.complete(Unit)
        }
    }

    private fun handleError(value: JsonObject) {
        val code = value.str("code") ?: "internal"
        val message = value.str("message") ?: "the host refused the request"
        val retryable = value.bool("retryable") ?: false
        val rid = value.str("rid")
        if (rid != null) {
            val waiter = pending.firstOrNull { it.match(value) }
            if (waiter != null) {
                pending.remove(waiter)
                waiter.deferred.completeExceptionally(GatewayRequestException(code, message, retryable))
                return
            }
        }
        // A refusal at attach is terminal for this configuration: retrying with
        // the same token or API version can only fail the same way.
        if (code == "auth_failed" || code == "api_version" || (code == "daemon_unavailable" && !retryable)) {
            desired = false
            _state.value = LinkState.Offline(message)
            connectWaiter?.completeExceptionally(GatewayRequestException(code, message, retryable))
            connectWaiter = null
        }
    }

    private fun failWaiters(message: String) {
        for (waiter in pending) {
            if (!waiter.deferred.isCompleted) {
                waiter.deferred.completeExceptionally(GatewayRequestException("offline", message, true))
            }
        }
        pending.clear()
    }

    private companion object {
        val HANDSHAKE = setOf("gw_hello_ok", "gw_attached", "gw_pong")
    }
}
