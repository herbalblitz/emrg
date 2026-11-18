package ai.emrg.companion

/** Pure reducer for protocol-driven request streams. State is keyed by request ID, not UI order. */
internal data class StreamRequest(
    val sessionId: String? = null,
    val text: String = "",
    val toolCalls: List<ToolCallState> = emptyList(),
    val status: StreamStatus = StreamStatus.ACTIVE,
    val error: String? = null,
)

internal data class ToolCallState(
    val callId: String?,
    val name: String?,
    val label: String?,
    val arguments: String? = null,
    val result: String? = null,
    val failed: Boolean = false,
)

internal enum class StreamStatus { ACTIVE, COMPLETED, CANCELLED, FAILED }

internal data class ProtocolStreamState(
    val requests: Map<String, StreamRequest> = emptyMap(),
)

internal sealed interface ProtocolStreamAction {
    data class Start(val requestId: String, val sessionId: String? = null) : ProtocolStreamAction
    data class Receive(val frame: ProtocolFrame) : ProtocolStreamAction
    data class Cancel(val requestId: String) : ProtocolStreamAction
    data object ConnectionLost : ProtocolStreamAction
}

internal fun reduceProtocolStreams(
    state: ProtocolStreamState,
    action: ProtocolStreamAction,
): ProtocolStreamState {\n    return when (action) {
    is ProtocolStreamAction.Start -> {
        if (action.requestId.isBlank() || state.requests[action.requestId]?.status == StreamStatus.ACTIVE) state
        else state.copy(requests = state.requests + (action.requestId to StreamRequest(sessionId = action.sessionId)))
    }
    is ProtocolStreamAction.Cancel -> finish(state, action.requestId, StreamStatus.CANCELLED)
    ProtocolStreamAction.ConnectionLost -> state.copy(requests = state.requests.mapValues { (_, request) ->
        if (request.status == StreamStatus.ACTIVE) request.copy(status = StreamStatus.FAILED, error = "Connection lost")
        else request
    })
    is ProtocolStreamAction.Receive -> {
        val frame = action.frame
        val id = frame.requestId ?: return state
        val existing = state.requests[id] ?: return state
        if (existing.status != StreamStatus.ACTIVE) return state
        val updated = when {
            frame.type == "tool_start" -> existing.copy(toolCalls = existing.toolCalls + ToolCallState(
                callId = frame.toolCallId, name = frame.toolName, label = frame.callLabel,
                arguments = frame.arguments?.toString(),
            ))
            frame.type == "tool_end" -> {
                val index = existing.toolCalls.indexOfLast {\n                    if (frame.toolCallId != null) it.callId == frame.toolCallId\n                    else it.callId == null && it.name == frame.toolName\n                }
                if (index < 0) existing else existing.copy(toolCalls = existing.toolCalls.toMutableList().also {
                    it[index] = it[index].copy(result = frame.content, failed = frame.error != null && frame.error != "false")
                })
            }
            frame.isDelta -> existing.copy(text = existing.text + (frame.content ?: ""))
            frame.type == "error" || frame.error != null -> existing.copy(status = StreamStatus.FAILED, error = frame.error ?: frame.content)
            frame.done == true -> existing.copy(status = if (frame.cancelled == true) StreamStatus.CANCELLED else StreamStatus.COMPLETED, text = frame.content ?: existing.text)
            else -> existing
        }
        state.copy(requests = state.requests + (id to updated))
    }
}

private fun finish(state: ProtocolStreamState, id: String, status: StreamStatus): ProtocolStreamState {
    val request = state.requests[id] ?: return state
    if (request.status != StreamStatus.ACTIVE) return state
    return state.copy(requests = state.requests + (id to request.copy(status = status)))
}
