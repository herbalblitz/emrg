package ai.emrg.companion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** One tool-approval question raised by the host during a chat turn. */
internal data class PendingApproval(
    val approvalRequestId: String,
    val sessionId: String?,
    val question: String,
    val timeoutSeconds: Long? = null,
)

/**
 * Real chat transport over the paired host's pinned WSS socket. Replaces the
 * local [DemoTransport] whenever the device is authenticated and holds chat.send.
 *
 * Stream correlation follows what the daemon actually broadcasts:
 * - text deltas carry `request_id` (the chat request id), `content`, `delta: true`;
 * - the terminal `done` frame and `turn_end` carry only `session_id`, no request id;
 * - `approval_request` carries its own `request_id` (the question id) + `session_id`.
 * So frames are matched by request id when present, otherwise by the active
 * turn's session id. Tool lifecycle frames are reduced with the existing
 * [reduceProtocolStreams] reducer, which already knows `tool_start`/`tool_end`.
 *
 * The `session_id` is always sent: the host currently requires it on chat.send.
 * The host cwd is never sent — the server supplies it from the pairing record.
 */
internal class CompanionChatTransport(
    private val connection: CompanionConnection,
    private val sessionIdProvider: () -> String?,
) : ChatTransport {
    private val _toolCalls = MutableStateFlow<List<ToolCallState>>(emptyList())
    val toolCalls: StateFlow<List<ToolCallState>> = _toolCalls.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingApproval>> = _pendingApprovals.asStateFlow()

    @Volatile private var activeRequestId: String? = null
    @Volatile private var activeSessionId: String? = null

    override suspend fun send(prompt: String): String = stream(prompt) {}

    override suspend fun stream(prompt: String, onDelta: (String) -> Unit): String = coroutineScope {
        if (!connection.hasScope(PairingScope.SEND_CHAT)) {
            throw IllegalStateException("chat.send scope is not granted")
        }
        val requestId = UUID.randomUUID().toString()
        val sessionId = sessionIdProvider()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        activeRequestId = requestId
        activeSessionId = sessionId
        _toolCalls.value = emptyList()
        var protocolState = reduceProtocolStreams(
            ProtocolStreamState(),
            ProtocolStreamAction.Start(requestId, sessionId),
        )
        val text = StringBuilder()
        var failure: String? = null
        var cancelled = false

        val collecting = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(CHAT_TURN_TIMEOUT_MS) {
                connection.frames.first { frame ->
                    val forThisTurn = frame.requestId == requestId ||
                        (frame.sessionId == sessionId &&
                            (frame.requestId == null || frame.type in SESSION_SCOPED_TYPES))
                    if (!forThisTurn) return@first false
                    when (frame.type) {
                        "approval_request" -> {
                            val approvalId = frame.requestId ?: return@first false
                            val question = frame.raw.optString("question").ifBlank { "Approve this tool call?" }
                            val timeout = frame.raw.optLong("timeout_seconds").takeIf { it > 0 }
                            val pending = PendingApproval(approvalId, frame.sessionId, question, timeout)
                            if (_pendingApprovals.value.none { it.approvalRequestId == approvalId }) {
                                _pendingApprovals.value = _pendingApprovals.value + pending
                            }
                            return@first false
                        }
                        "approval_resolved" -> {
                            val approvalId = frame.requestId
                            if (approvalId != null) {
                                _pendingApprovals.value =
                                    _pendingApprovals.value.filterNot { it.approvalRequestId == approvalId }
                            }
                            return@first false
                        }
                    }
                    protocolState = reduceProtocolStreams(
                        protocolState,
                        ProtocolStreamAction.Receive(frame),
                    )
                    val request = protocolState.requests[requestId]
                    if (request != null) {
                        _toolCalls.value = request.toolCalls
                        val fresh = request.text.substring(text.length)
                        if (fresh.isNotEmpty()) {
                            text.append(fresh)
                            onDelta(fresh)
                        }
                        when (request.status) {
                            StreamStatus.COMPLETED -> return@first true
                            StreamStatus.CANCELLED -> { cancelled = true; return@first true }
                            StreamStatus.FAILED -> {
                                failure = request.error ?: "Host returned an error."
                                return@first true
                            }
                            StreamStatus.ACTIVE -> Unit
                        }
                    }
                    // The daemon's terminal `done` frame and `turn_end` carry no
                    // request id, so the reducer above may not see them as terminal.
                    if (frame.done == true) {
                        if (frame.cancelled == true) cancelled = true
                        return@first true
                    }
                    if (frame.type == "turn_end" || frame.type == "cancelled") {
                        if (frame.type == "cancelled") cancelled = true
                        return@first true
                    }
                    if (frame.type == "error") {
                        failure = frame.userErrorMessage().ifEmpty { "Host returned an error." }
                        return@first true
                    }
                    false
                }
            }
        }
        try {
            val sent = connection.sendTask(
                TaskCommand(id = requestId, cwd = "", prompt = prompt, sessionId = sessionId),
            )
            if (!sent) throw IllegalStateException("chat.send could not be sent")
            if (collecting.await() == null) {
                failure = "Host did not answer in time."
            }
        } catch (cancelledException: CancellationException) {
            // Our own Stop button: tell the host to stop the turn, then propagate.
            sendCancel(sessionId)
            throw cancelledException
        } finally {
            activeRequestId = null
            activeSessionId = null
        }
        if (cancelled) throw CancellationException("Chat turn cancelled")
        failure?.let { throw IllegalStateException(it) }
        return@coroutineScope text.toString()
    }

    /** Send chat.cancel for the active turn (Stop button). Best-effort. */
    fun cancelActive() {
        sendCancel(activeSessionId)
    }

    private fun sendCancel(sessionId: String?) {
        connection.sendChatCancel(UUID.randomUUID().toString(), sessionId)
    }

    /** Answer a pending approval question. Returns false when the frame was refused. */
    fun answerApproval(approval: PendingApproval, approved: Boolean): Boolean {
        val ok = connection.sendChatApprove(
            UUID.randomUUID().toString(),
            approval.approvalRequestId,
            approval.sessionId,
            approved,
        )
        if (ok) {
            _pendingApprovals.value =
                _pendingApprovals.value.filterNot { it.approvalRequestId == approval.approvalRequestId }
        }
        return ok
    }

    companion object {
        /** Stream frames that name only a session, not a request. */
        val SESSION_SCOPED_TYPES = setOf(
            "approval_request", "approval_resolved", "turn_start", "turn_end", "cancelled",
        )
        const val CHAT_TURN_TIMEOUT_MS = 600_000L
    }
}
