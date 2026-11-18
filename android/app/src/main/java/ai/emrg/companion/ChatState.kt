package ai.emrg.companion

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver

internal data class ChatMessage(val role: String, val text: String)

internal data class ChatUiState(
    val messages: List<ChatMessage> = listOf(
        ChatMessage("EMRG", "Welcome. This is the Android UI preview using a local mock transport.")
    ),
    val draft: String = "",
    val isSending: Boolean = false
)

internal sealed interface ChatAction {
    data class DraftChanged(val value: String) : ChatAction
    data object Submit : ChatAction
    data class ResponseReceived(val text: String) : ChatAction
    data object RequestFailed : ChatAction
}

internal data class ChatTransition(val state: ChatUiState, val promptToSend: String? = null)

internal const val MAX_SAVED_MESSAGES = 60

internal fun restoreChatUiState(messages: List<ChatMessage>, draft: String): ChatUiState =
    ChatUiState(messages = messages.takeLast(MAX_SAVED_MESSAGES), draft = draft, isSending = false)

internal val ChatUiStateSaver = mapSaver(
    save = { state ->
        mapOf(
            "messages" to state.messages.takeLast(MAX_SAVED_MESSAGES).flatMap { listOf(it.role, it.text) },
            "draft" to state.draft
        )
    },
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
                    isSending = true
                ),
                promptToSend = prompt
            )
        }
        is ChatAction.ResponseReceived ->
            if (!state.isSending) ChatTransition(state)
            else ChatTransition(
                state.copy(messages = state.messages + ChatMessage("EMRG", action.text), isSending = false)
            )
        ChatAction.RequestFailed ->
            if (!state.isSending) ChatTransition(state)
            else ChatTransition(
                state.copy(messages = state.messages + ChatMessage("EMRG", CHAT_FAILURE_MESSAGE), isSending = false)
            )
    }
