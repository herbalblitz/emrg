package ai.emrg.companion

/** Pure event bridge: protocol events update request state, then project only the bound request into chat. */
internal data class ProtocolChatBridgeTransition(
    val protocolState: ProtocolStreamState,
    val chatTransition: ChatTransition,
)

internal fun reduceProtocolChatBridge(
    chatState: ChatUiState,
    protocolState: ProtocolStreamState,
    action: ProtocolStreamAction,
): ProtocolChatBridgeTransition {
    val nextProtocol = reduceProtocolStreams(protocolState, action)
    val requestId = when (action) {
        is ProtocolStreamAction.Start -> action.requestId
        is ProtocolStreamAction.Receive -> action.frame.requestId
        is ProtocolStreamAction.Cancel -> action.requestId
        ProtocolStreamAction.ConnectionLost -> chatState.activeRequestId
    }
    val chatAction = when (action) {
        is ProtocolStreamAction.Start ->
            if (chatState.activeRequestId == null || chatState.activeRequestId == action.requestId)
                ChatAction.BindRequest(action.requestId)
            else null
        else -> requestId?.let { id ->
            nextProtocol.requests[id]?.let { request -> ChatAction.ProtocolSnapshot(id, request) }
        }
    }
    val transition = if (chatAction == null) ChatTransition(chatState)
    else reduceChatState(chatState, chatAction)
    return ProtocolChatBridgeTransition(nextProtocol, transition)
}
