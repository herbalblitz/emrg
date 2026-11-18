package ai.emrg.companion

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver

internal data class ChatMessage(val role: String, val text: String)

internal data class ChatUiState(
    val messages: List<ChatMessage> = listOf(
        ChatMessage("EMRG", "Welcome. This is the Android UI preview using a local mock transport.")
    ),
    val draft: String = "",
    val isSending: Boolean = false,
    val streamingText: String = ""
)

internal sealed interface ChatAction {
    data class DraftChanged(val value: String) : ChatAction
    data object Submit : ChatAction
    data class ResponseDelta(val text: String) : ChatAction
    data class ResponseReceived(val text: String) : ChatAction
    data object RequestFailed : ChatAction
    data object Cancel : ChatAction
}

internal data class ChatTransition(val state: ChatUiState, val promptToSend: String? = null)

internal const val MAX_SAVED_MESSAGES = 60

internal fun restoreChatUiState(messages: List<ChatMessage>, draft: String): ChatUiState =
    ChatUiState(messages = messages.takeLast(MAX_SAVED_MESSAGES), draft = draft, isSending = false)

internal fun saveChatUiState(state: ChatUiState): Map<String, Any> = mapOf(
    "messages" to state.messages.takeLast(MAX_SAVED_MESSAGES).flatMap { listOf(it.role, it.text) },
    "draft" to state.draft
)

internal val ChatUiStateSaver = mapSaver(
    save = { state -> saveChatUiState(state) },
    restore = { saved ->
        val rawMessages = saved["messages"] as? List<*>
        val messages = rawMessages.orEmpty().chunked(2).mapNotNull { pair ->
            val role = pair.getOrNull(0) as? String
            val text = pair.getOrNull(1) as? String
            if (role != null && text != null) ChatMessage(role, text) else null
        }
        restoreChatUiState(messages, saved["draft"] as? String ?: "")
    }
)

internal fun reduceChatState(state: ChatUiState, action: ChatAction): ChatTransition =
    when (action) {
        is ChatAction.DraftChanged ->
            if (state.isSending) ChatTransition(state)
            else ChatTransition(state.copy(draft = action.value))
        ChatAction.Submit -> {
            val prompt = state.draft.trim()
            if (state.isSending || prompt.isEmpty()) ChatTransition(state)
            else ChatTransition(
                state.copy(
                    messages = state.messages + ChatMessage("You", prompt),
                    draft = "",
                    isSending = true,
                    streamingText = ""
                ),
                promptToSend = prompt
            )
        }
        is ChatAction.ResponseDelta ->
            if (!state.isSending) ChatTransition(state)
            else ChatTransition(state.copy(streamingText = state.streamingText + action.text))
        is ChatAction.ResponseReceived ->
            if (!state.isSending) ChatTransition(state)
            else ChatTransition(
                state.copy(messages = state.messages + ChatMessage("EMRG", action.text.ifEmpty { state.streamingText }), isSending = false, streamingText = "")
            )
        ChatAction.Cancel ->
            if (!state.isSending) ChatTransition(state)
            else ChatTransition(
                state.copy(
                    messages = state.messages + (if (state.streamingText.isNotEmpty()) listOf(ChatMessage("EMRG", state.streamingText + "\n[Stopped]")) else emptyList()),
                    isSending = false,
                    streamingText = ""
                )
            )
        ChatAction.RequestFailed ->
            if (!state.isSending) ChatTransition(state)
            else ChatTransition(
                state.copy(messages = state.messages + ChatMessage("EMRG", if (state.streamingText.isEmpty()) CHAT_FAILURE_MESSAGE else state.streamingText + "\n" + CHAT_FAILURE_MESSAGE), isSending = false, streamingText = "")
            )
    }
