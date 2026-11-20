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

/** Keep inbound authenticated WSS text frames within the host's 256 KiB protocol limit. */
internal const val MAX_REMOTE_FRAME_BYTES = 256 * 1024

internal fun isRemoteFrameWithinLimit(text: String): Boolean =
    text.toByteArray(Charsets.UTF_8).size <= MAX_REMOTE_FRAME_BYTES

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

/** Events from a paired host socket. Authentication is not ready until host auth.ok. */
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
 * The host listener implements pinned Pairing v2 exchange and device-scoped authentication.
 * Its authenticated dispatch covers the 20 frozen-contract scopes; every send method below
 * enforces its scope client-side before a frame leaves the device.
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
                // This device-scoped frame matches the dedicated Companion WSS listener;
                // it is separate from the desktop daemon's local token authentication.
                val auth = createAuthFrame(host.deviceId, credential)
                if (!webSocket.send(auth.toString())) {
                    terminalState = RemoteSocketState.FAILED
                    listener.onState(RemoteSocketState.FAILED, "Authentication frame could not be sent")
                    webSocket.close(1011, "auth send failed")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (attemptGeneration != generation) return
                if (!isRemoteFrameWithinLimit(text)) {
                    authenticated = false
                    terminalState = RemoteSocketState.FAILED
                    listener.onState(RemoteSocketState.FAILED, "Host frame exceeds the allowed size")
                    webSocket.close(1009, "frame too large")
                    return
                }
                val frame = try { ProtocolFrame.parse(text) } catch (_: Exception) {
                    authenticated = false
                    terminalState = RemoteSocketState.FAILED
                    listener.onState(RemoteSocketState.FAILED, "Invalid host frame")
                    webSocket.close(1007, "invalid frame")
                    return
                }
                if (!isAllowedFrameInAuthState(authenticated, frame.type)) {
                    authenticated = false
                    terminalState = RemoteSocketState.AUTH_REJECTED
                    listener.onState(
                        RemoteSocketState.AUTH_REJECTED,
                        "Host sent a frame outside the authentication state",
                    )
                    webSocket.close(1008, "unexpected authentication state")
                    return
                }
                when (frame.type) {
                    "auth.ok" -> {
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
                authenticated = false
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

    /** Request the host's bounded status snapshot after authentication. */
    fun sendStatus(requestId: String): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.READ_STATUS !in host.scopes) return false
        return socket?.send(companionStatusFrame(requestId).toString()) == true
    }

    /** Request read-only session metadata for the exact workspace approved by the host. */
    fun sendSessionsList(requestId: String): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.LIST_SESSIONS !in host.scopes) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionSessionsListFrame(requestId, workspaceId).toString()) == true
    }

    /**
     * Only chat-task payloads are exposed here; arbitrary daemon commands are not.
     * The host cwd is never sent by the client: the server supplies it from the
     * pairing record, so this method no longer gates on any client-side cwd.
     */
    fun sendTask(command: TaskCommand): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.SEND_CHAT !in host.scopes) return false
        if (command.prompt.isBlank() || command.id.isBlank()) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionChatFrame(command, workspaceId).toString()) == true
    }

    /** Cancel the running turn in a session. Gated by the chat.send scope. */
    fun sendChatCancel(requestId: String, sessionId: String?): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.SEND_CHAT !in host.scopes) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionChatCancelFrame(requestId, workspaceId, sessionId).toString()) == true
    }

    /** Answer a tool-approval question raised during a chat turn. Gated by chat.send. */
    fun sendChatApprove(requestId: String, approvalRequestId: String, sessionId: String?, approved: Boolean): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.SEND_CHAT !in host.scopes) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionChatApproveFrame(requestId, workspaceId, approvalRequestId, sessionId, approved).toString()) == true
    }

    /**
     * One sessions.* management frame (resume/rename/delete/clear/compact/rewind/
     * history/resolve_cwd). Gated by sessions.manage; sessions.list stays read-only.
     */
    fun sendSessionsManage(
        requestId: String,
        type: String,
        sessionId: String? = null,
        title: String? = null,
        recordIndex: Int? = null,
        limit: Int? = null,
    ): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.MANAGE_SESSIONS !in host.scopes) return false
        if (type !in SESSION_MANAGE_TYPES) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(
            companionSessionsManageFrame(requestId, workspaceId, type, sessionId, title, recordIndex, limit).toString()
        ) == true
    }

    /** List files under a workspace-relative path. Gated by files.read. */
    fun sendFilesList(requestId: String, path: String): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.READ_FILES !in host.scopes) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionFilesListFrame(requestId, workspaceId, path).toString()) == true
    }

    /** Read a file at a workspace-relative path. Gated by files.read. */
    fun sendFilesRead(requestId: String, path: String, startLine: Int? = null, lineLimit: Int? = null): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.READ_FILES !in host.scopes) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionFilesReadFrame(requestId, workspaceId, path, startLine, lineLimit).toString()) == true
    }

    /** Write (create/overwrite) a file at a workspace-relative path. Gated by files.write. */
    fun sendFilesWrite(requestId: String, path: String, content: String): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.WRITE_FILES !in host.scopes) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionFilesWriteFrame(requestId, workspaceId, path, content).toString()) == true
    }

    /** Delete a file at a workspace-relative path. Gated by files.write. */
    fun sendFilesDelete(requestId: String, path: String): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.WRITE_FILES !in host.scopes) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionFilesDeleteFrame(requestId, workspaceId, path).toString()) == true
    }

    /** Run a command in the approved workspace. Gated by terminal.exec. */
    fun sendTerminalExec(
        requestId: String,
        command: String,
        mode: String,
        timeoutSeconds: Int,
        confirmDanger: Boolean,
    ): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.EXEC_TERMINAL !in host.scopes) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(
            companionTerminalExecFrame(requestId, workspaceId, command, mode, timeoutSeconds, confirmDanger).toString()
        ) == true
    }

    /** Cancel a running exec by its exec_id. Gated by terminal.exec. */
    fun sendTerminalExecCancel(requestId: String, execId: String): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.EXEC_TERMINAL !in host.scopes) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionTerminalExecCancelFrame(requestId, workspaceId, execId).toString()) == true
    }

    /** Set the sandbox mode for a session. Gated by sandbox.manage. */
    fun sendSandboxSet(requestId: String, mode: String, sessionId: String? = null): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.MANAGE_SANDBOX !in host.scopes) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionSandboxSetFrame(requestId, workspaceId, mode, sessionId).toString()) == true
    }

    /** List, add or remove a sandbox root (workspace-relative). Gated by sandbox.manage. */
    fun sendSandboxRoots(requestId: String, op: String, path: String? = null, sessionId: String? = null): Boolean {
        val host = pairedHost ?: return false
        if (!authenticated || PairingScope.MANAGE_SANDBOX !in host.scopes) return false
        if (op !in SANDBOX_ROOT_OPS) return false
        val workspaceId = host.approvedWorkspaceId ?: return false
        return socket?.send(companionSandboxRootsFrame(requestId, workspaceId, op, path, sessionId).toString()) == true
    }

    /** True when this socket is authenticated and the paired host granted [scope]. */
    fun hasScope(scope: PairingScope): Boolean =
        authenticated && pairedHost?.scopes?.contains(scope) == true

    /** The approved workspace id, or null when unpaired/unauthenticated. */
    fun approvedWorkspaceId(): String? = if (authenticated) pairedHost?.approvedWorkspaceId else null

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
            // Only the host's exact auth.ok frame may complete authentication. Once
            // authenticated, reject all known auth-control spellings rather than
            // accidentally forwarding a desktop-protocol alias as an application event.
            val authControlFrames = setOf(
                "auth.ok", "auth_ok", "auth.error", "auth_error", "auth.rejected", "auth_rejected",
            )
            return frameType?.let {
                if (authenticated) it !in authControlFrames else it == "auth.ok"
            } == true
        }

        internal fun isValidAuthSuccess(frame: ProtocolFrame, host: PairedHost): Boolean {
            if (frame.type != "auth.ok" || frame.raw.opt("protocol") != "1.0") return false

            val returnedWorkspace = frame.raw.opt("workspace_id") as? String
                ?: return false
            if (returnedWorkspace.isBlank()) return false
            if (!host.approvedWorkspaceId.isNullOrBlank() &&
                returnedWorkspace != host.approvedWorkspaceId
            ) return false

            val expectedScopes = mutableSetOf<String>()
            for (scope in host.scopes) {
                // The two legacy placeholder values have no wire name and can never
                // be granted: a host echoing them fails authentication.
                expectedScopes.add(scope.wireName ?: return false)
            }
            val returnedScopeArray = frame.raw.optJSONArray("scopes") ?: return false
            if (returnedScopeArray.length() != expectedScopes.size) return false
            val returnedScopes = mutableSetOf<String>()
            for (index in 0 until returnedScopeArray.length()) {
                val value = returnedScopeArray.opt(index)
                if (value !is String || value.isBlank() || !returnedScopes.add(value)) return false
            }
            return returnedScopes == expectedScopes
        }

        internal fun companionStatusFrame(requestId: String): JSONObject {
            require(requestId.isNotBlank() && requestId.toByteArray(Charsets.UTF_8).size <= 128) {
                "invalid request ID"
            }
            return JSONObject().put("type", "status").put("request_id", requestId)
        }

        internal fun companionSessionsListFrame(requestId: String, workspaceId: String): JSONObject {
            require(requestId.isNotBlank() && requestId.toByteArray(Charsets.UTF_8).size <= 128) {
                "invalid request ID"
            }
            require(workspaceId.isNotBlank() && workspaceId.toByteArray(Charsets.UTF_8).size <= 128) {
                "approved workspace is invalid"
            }
            return JSONObject()
                .put("type", "sessions.list")
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
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

        internal fun companionChatCancelFrame(requestId: String, workspaceId: String, sessionId: String?): JSONObject {
            requireValidRequestId(requestId)
            requireValidWorkspaceId(workspaceId)
            return JSONObject()
                .put("type", "chat.cancel")
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
                .apply { sessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", it) } }
        }

        internal fun companionChatApproveFrame(
            requestId: String,
            workspaceId: String,
            approvalRequestId: String,
            sessionId: String?,
            approved: Boolean,
        ): JSONObject {
            requireValidRequestId(requestId)
            requireValidWorkspaceId(workspaceId)
            require(approvalRequestId.isNotBlank() && approvalRequestId.toByteArray(Charsets.UTF_8).size <= 128) {
                "invalid approval request ID"
            }
            return JSONObject()
                .put("type", "chat.approve")
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
                .put("approval_request_id", approvalRequestId)
                .put("approved", approved)
                .apply { sessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", it) } }
        }

        internal fun companionSessionsManageFrame(
            requestId: String,
            workspaceId: String,
            type: String,
            sessionId: String?,
            title: String?,
            recordIndex: Int?,
            limit: Int?,
        ): JSONObject {
            requireValidRequestId(requestId)
            requireValidWorkspaceId(workspaceId)
            require(type in SESSION_MANAGE_TYPES) { "unsupported sessions management frame" }
            return JSONObject()
                .put("type", type)
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
                .apply {
                    sessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", it) }
                    title?.let { put("title", it) }
                    recordIndex?.let { require(it >= 0) { "record_index must be non-negative" }; put("record_index", it) }
                    limit?.let { require(it in 1..500) { "limit out of range" }; put("limit", it) }
                }
        }

        internal fun companionFilesListFrame(requestId: String, workspaceId: String, path: String): JSONObject {
            requireValidRequestId(requestId)
            requireValidWorkspaceId(workspaceId)
            return JSONObject()
                .put("type", "files.list")
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
                .put("path", requireWorkspaceRelativePath(path))
        }

        internal fun companionFilesReadFrame(
            requestId: String,
            workspaceId: String,
            path: String,
            startLine: Int?,
            lineLimit: Int?,
        ): JSONObject {
            requireValidRequestId(requestId)
            requireValidWorkspaceId(workspaceId)
            return JSONObject()
                .put("type", "files.read")
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
                .put("path", requireWorkspaceRelativePath(path))
                .apply {
                    startLine?.let { require(it >= 0) { "start_line must be non-negative" }; put("start_line", it) }
                    lineLimit?.let { require(it in 1..5000) { "line_limit out of range" }; put("line_limit", it) }
                }
        }

        internal fun companionFilesWriteFrame(requestId: String, workspaceId: String, path: String, content: String): JSONObject {
            requireValidRequestId(requestId)
            requireValidWorkspaceId(workspaceId)
            require(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_WRITE_BYTES) { "file content too large" }
            return JSONObject()
                .put("type", "files.write")
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
                .put("path", requireWorkspaceRelativePath(path))
                .put("content", content)
        }

        internal fun companionFilesDeleteFrame(requestId: String, workspaceId: String, path: String): JSONObject {
            requireValidRequestId(requestId)
            requireValidWorkspaceId(workspaceId)
            return JSONObject()
                .put("type", "files.delete")
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
                .put("path", requireWorkspaceRelativePath(path))
        }

        internal fun companionTerminalExecFrame(
            requestId: String,
            workspaceId: String,
            command: String,
            mode: String,
            timeoutSeconds: Int,
            confirmDanger: Boolean,
        ): JSONObject {
            requireValidRequestId(requestId)
            requireValidWorkspaceId(workspaceId)
            require(command.isNotBlank() && command.toByteArray(Charsets.UTF_8).size <= MAX_TERMINAL_COMMAND_BYTES) {
                "invalid command"
            }
            require(mode in TERMINAL_MODES) { "unsupported terminal mode" }
            require(timeoutSeconds in 1..600) { "timeout out of range" }
            return JSONObject()
                .put("type", "terminal.exec")
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
                .put("command", command)
                .put("mode", mode)
                .put("timeout_seconds", timeoutSeconds)
                .put("confirm_danger", confirmDanger)
        }

        internal fun companionTerminalExecCancelFrame(requestId: String, workspaceId: String, execId: String): JSONObject {
            requireValidRequestId(requestId)
            requireValidWorkspaceId(workspaceId)
            require(execId.isNotBlank() && execId.toByteArray(Charsets.UTF_8).size <= 128) { "invalid exec ID" }
            return JSONObject()
                .put("type", "terminal.exec_cancel")
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
                .put("exec_id", execId)
        }

        internal fun companionSandboxSetFrame(requestId: String, workspaceId: String, mode: String, sessionId: String?): JSONObject {
            requireValidRequestId(requestId)
            requireValidWorkspaceId(workspaceId)
            require(mode in SANDBOX_MODES) { "unsupported sandbox mode" }
            return JSONObject()
                .put("type", "sandbox.set")
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
                .put("mode", mode)
                .apply { sessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", it) } }
        }

        internal fun companionSandboxRootsFrame(
            requestId: String,
            workspaceId: String,
            op: String,
            path: String?,
            sessionId: String?,
        ): JSONObject {
            requireValidRequestId(requestId)
            requireValidWorkspaceId(workspaceId)
            require(op in SANDBOX_ROOT_OPS) { "unsupported sandbox roots operation" }
            return JSONObject()
                .put("type", "sandbox.roots")
                .put("request_id", requestId)
                .put("workspace_id", workspaceId)
                .put("op", op)
                .apply {
                    path?.let { put("path", requireWorkspaceRelativePath(it)) }
                    sessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", it) }
                }
        }

        private fun requireValidRequestId(requestId: String) {
            require(requestId.isNotBlank() && requestId.toByteArray(Charsets.UTF_8).size <= 128) {
                "invalid request ID"
            }
        }

        private fun requireValidWorkspaceId(workspaceId: String) {
            require(workspaceId.isNotBlank() && workspaceId.toByteArray(Charsets.UTF_8).size <= 128) {
                "approved workspace is invalid"
            }
        }

        /**
         * Paths are workspace-relative only. Absolute paths, `..` escapes and empty
         * paths are rejected client-side; the server joins the path to the bound cwd
         * and enforces containment again.
         */
        internal fun requireWorkspaceRelativePath(path: String): String {
            val normalized = path.trim().replace('\\', '/').trimStart('/')
            require(normalized.isNotEmpty()) { "path must not be empty" }
            require(normalized.toByteArray(Charsets.UTF_8).size <= 4096) { "path too long" }
            val segments = normalized.split("/").filter { it.isNotEmpty() && it != "." }
            require(segments.isNotEmpty() && segments.none { it == ".." }) {
                "path must stay inside the approved workspace"
            }
            return segments.joinToString("/")
        }

        internal val SESSION_MANAGE_TYPES = setOf(
            "sessions.resume", "sessions.rename", "sessions.delete", "sessions.clear",
            "sessions.compact", "sessions.rewind", "sessions.history", "sessions.resolve_cwd",
        )
        internal val TERMINAL_MODES = setOf("read-only", "workspace-write", "danger-full-access")
        internal val SANDBOX_MODES = TERMINAL_MODES
        internal val SANDBOX_ROOT_OPS = setOf("list", "add", "remove")
        internal const val MAX_FILE_WRITE_BYTES = 1024 * 1024
        internal const val MAX_TERMINAL_COMMAND_BYTES = 64 * 1024

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
