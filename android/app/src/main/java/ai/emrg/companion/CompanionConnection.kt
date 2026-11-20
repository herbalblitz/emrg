package ai.emrg.companion

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single shared live-host connection for the whole app.
 *
 * Owns one [PinnedRemoteWebSocket] and fans its inbound frames out to every screen.
 * Screens never open their own socket: they call [request] for a request/response
 * frame pair, or collect [frames] for streaming frames (chat turns, tool activity).
 * Pairing itself stays in [SecurePairingPanel]; this holder only carries the live
 * socket a successful pairing produced.
 */
internal class CompanionConnection(context: Context) {
    private val credentialStore = AndroidKeystoreCredentialStore(context.applicationContext)

    private val _state = MutableStateFlow(RemoteSocketState.CLOSED)
    val state: StateFlow<RemoteSocketState> = _state.asStateFlow()

    private val _pairedHost = MutableStateFlow<PairedHost?>(null)
    val pairedHost: StateFlow<PairedHost?> = _pairedHost.asStateFlow()

    private val _frames = MutableSharedFlow<ProtocolFrame>(extraBufferCapacity = 128)
    /** Every inbound application frame, in arrival order. Buffer is best-effort. */
    val frames: SharedFlow<ProtocolFrame> = _frames.asSharedFlow()

    private val _lastStatusResult = MutableStateFlow<String?>(null)
    val lastStatusResult: StateFlow<String?> = _lastStatusResult.asStateFlow()

    private val _lastSessionsResult = MutableStateFlow<String?>(null)
    val lastSessionsResult: StateFlow<String?> = _lastSessionsResult.asStateFlow()

    val isAuthenticated: Boolean get() = _state.value == RemoteSocketState.AUTHENTICATED

    fun hasScope(scope: PairingScope): Boolean =
        isAuthenticated && _pairedHost.value?.scopes?.contains(scope) == true

    fun approvedWorkspaceId(): String? =
        if (isAuthenticated) _pairedHost.value?.approvedWorkspaceId else null

    private val socket: PinnedRemoteWebSocket = PinnedRemoteWebSocket(
        credentialStore = credentialStore,
        listener = object : RemoteSocketListener {
            override fun onState(state: RemoteSocketState, safeMessage: String?) {
                _state.value = state
                if (state == RemoteSocketState.AUTHENTICATED) {
                    refreshStatusAndSessions()
                }
                if (state == RemoteSocketState.CLOSED || state == RemoteSocketState.FAILED ||
                    state == RemoteSocketState.AUTH_REJECTED || state == RemoteSocketState.IDENTITY_REJECTED
                ) {
                    // Keep the paired host: the credential is still stored and a
                    // reconnect is possible. Only the live state is gone.
                }
            }

            override fun onFrame(frame: ProtocolFrame) {
                when (frame.type) {
                    "status.result" -> _lastStatusResult.value = frame.raw.toString().take(4096)
                    "sessions.result" -> _lastSessionsResult.value = frame.raw.toString().take(16 * 1024)
                }
                _frames.tryEmit(frame)
            }
        },
    )

    /** Open (or re-open) the pinned socket for an already-paired host. */
    fun connect(host: PairedHost) {
        _pairedHost.value = host
        socket.connect(host)
    }

    fun disconnect() {
        socket.close()
        _state.value = RemoteSocketState.CLOSED
    }

    fun close() = disconnect()

    private fun refreshStatusAndSessions() {
        val statusId = java.util.UUID.randomUUID().toString()
        val sessionsId = java.util.UUID.randomUUID().toString()
        // Best-effort: a scope the host did not grant makes the send return false.
        socket.sendStatus(statusId)
        socket.sendSessionsList(sessionsId)
    }

    /**
     * Send a frame, then wait for its `<type>.result` (or an `error` frame) with the
     * same request id. The collector subscribes before [send] runs, so a fast reply
     * cannot be missed. Returns null when the send was refused (unauthenticated or
     * scope not granted) or when no reply arrived within [timeoutMs].
     */
    suspend fun request(
        requestId: String,
        timeoutMs: Long = 30_000,
        send: () -> Boolean,
    ): ProtocolFrame? = coroutineScope {
        val waiting = async {
            withTimeoutOrNull(timeoutMs) {
                frames.first {
                    it.requestId == requestId &&
                        (it.type?.endsWith(".result") == true || it.type == "error")
                }
            }
        }
        if (!send()) {
            waiting.cancel()
            return@coroutineScope null
        }
        waiting.await()
    }

    // ── Typed sends, one per frozen-contract frame. Each delegates to the socket,
    // which enforces authentication + its scope gate before anything is sent. ──

    fun sendStatus(requestId: String): Boolean = socket.sendStatus(requestId)
    fun sendSessionsList(requestId: String): Boolean = socket.sendSessionsList(requestId)
    fun sendTask(command: TaskCommand): Boolean = socket.sendTask(command)
    fun sendChatCancel(requestId: String, sessionId: String?): Boolean =
        socket.sendChatCancel(requestId, sessionId)

    fun sendChatApprove(requestId: String, approvalRequestId: String, sessionId: String?, approved: Boolean): Boolean =
        socket.sendChatApprove(requestId, approvalRequestId, sessionId, approved)

    fun sendSessionsManage(
        requestId: String,
        type: String,
        sessionId: String? = null,
        title: String? = null,
        recordIndex: Int? = null,
        limit: Int? = null,
    ): Boolean = socket.sendSessionsManage(requestId, type, sessionId, title, recordIndex, limit)

    fun sendFilesList(requestId: String, path: String): Boolean = socket.sendFilesList(requestId, path)
    fun sendFilesRead(requestId: String, path: String, startLine: Int? = null, lineLimit: Int? = null): Boolean =
        socket.sendFilesRead(requestId, path, startLine, lineLimit)

    fun sendFilesWrite(requestId: String, path: String, content: String): Boolean =
        socket.sendFilesWrite(requestId, path, content)

    fun sendFilesDelete(requestId: String, path: String): Boolean = socket.sendFilesDelete(requestId, path)

    fun sendTerminalExec(requestId: String, command: String, mode: String, timeoutSeconds: Int, confirmDanger: Boolean): Boolean =
        socket.sendTerminalExec(requestId, command, mode, timeoutSeconds, confirmDanger)

    fun sendTerminalExecCancel(requestId: String, execId: String): Boolean =
        socket.sendTerminalExecCancel(requestId, execId)

    fun sendSandboxSet(requestId: String, mode: String, sessionId: String? = null): Boolean =
        socket.sendSandboxSet(requestId, mode, sessionId)

    fun sendSandboxRoots(requestId: String, op: String, path: String? = null, sessionId: String? = null): Boolean =
        socket.sendSandboxRoots(requestId, op, path, sessionId)
}

/** A host error frame surfaced to the user without leaking raw payloads. */
internal fun ProtocolFrame.userErrorMessage(): String {
    if (type != "error") return ""
    val code = raw.optString("code").takeIf { it.isNotBlank() }
    val message = raw.optString("message").takeIf { it.isNotBlank() }
    return listOfNotNull(code, message).joinToString(": ").ifEmpty { "Host returned an error." }
}
