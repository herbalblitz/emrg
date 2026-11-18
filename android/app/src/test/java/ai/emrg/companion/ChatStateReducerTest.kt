package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateReducerTest {
    @Test
    fun submitTrimsPromptAndClearsDraft() {
        val transition = reduceChatState(ChatUiState(draft = "  hello  "), ChatAction.Submit)

        assertEquals("hello", transition.promptToSend)
        assertEquals("", transition.state.draft)
        assertTrue(transition.state.isSending)
        assertEquals(ChatMessage("You", "hello"), transition.state.messages.last())
    }

    @Test
    fun blankSubmitDoesNotChangeStateOrSend() {
        val initial = ChatUiState(draft = " \n ")
        val transition = reduceChatState(initial, ChatAction.Submit)

        assertEquals(initial, transition.state)
        assertNull(transition.promptToSend)
    }

    @Test
    fun duplicateSubmitWhileSendingIsIgnored() {
        val sending = ChatUiState(draft = "hello", isSending = true)
        val transition = reduceChatState(sending, ChatAction.Submit)

        assertEquals(sending, transition.state)
        assertNull(transition.promptToSend)
    }

    @Test
    fun draftChangesAreBlockedWhileSending() {
        val sending = ChatUiState(draft = "locked", isSending = true)
        assertEquals(sending, reduceChatState(sending, ChatAction.DraftChanged("new")).state)
    }

    @Test
    fun responseCompletesRequestAndAppendsAssistantMessage() {
        val sending = reduceChatState(ChatUiState(draft = "hi"), ChatAction.Submit).state
        val completed = reduceChatState(sending, ChatAction.ResponseReceived("reply")).state

        assertFalse(completed.isSending)
        assertEquals(ChatMessage("EMRG", "reply"), completed.messages.last())
    }

    @Test
    fun failureCompletesRequestWithSafeFallback() {
        val sending = reduceChatState(ChatUiState(draft = "hi"), ChatAction.Submit).state
        val completed = reduceChatState(sending, ChatAction.RequestFailed).state

        assertFalse(completed.isSending)
        assertEquals(ChatMessage("EMRG", CHAT_FAILURE_MESSAGE), completed.messages.last())
    }

    @Test
    fun savedStateRestoresMessagesAndDraftButNotInFlightFlag() {
        val state = ChatUiState(
            messages = listOf(ChatMessage("You", "question"), ChatMessage("EMRG", "answer")),
            draft = "unfinished",
            isSending = true
        )
        val restored = restoreChatUiStateFromSnapshot(saveChatUiState(state)) ?: error("Saved state could not be restored")

        assertEquals(state.messages, restored.messages)
        assertEquals(state.draft, restored.draft)
        assertFalse(restored.isSending)
    }

    @Test
    fun savedStateLimitsHistoryAndRestoresLatestMessages() {
        val history = (0..MAX_SAVED_MESSAGES).map { ChatMessage("You", "message-$it") }
        val restored = restoreChatUiStateFromSnapshot(saveChatUiState(ChatUiState(messages = history))) ?: error("Saved state could not be restored")

        assertEquals(MAX_SAVED_MESSAGES, restored.messages.size)
        assertEquals("message-$MAX_SAVED_MESSAGES", restored.messages.last().text)
    }

    @Test
    fun responseDeltasAccumulateAndFinalResponseClearsStreamingState() {
        val started = reduceChatState(ChatUiState(draft = "hi"), ChatAction.Submit).state
        val first = reduceChatState(started, ChatAction.ResponseDelta("part ")).state
        val second = reduceChatState(first, ChatAction.ResponseDelta("two")).state
        assertEquals("part two", second.streamingText)

        val finished = reduceChatState(second, ChatAction.ResponseReceived("complete")).state
        assertFalse(finished.isSending)
        assertEquals(ChatMessage("EMRG", "complete"), finished.messages.last())
        assertEquals("", finished.streamingText)
    }

    @Test
    fun cancelPreservesPartialTextAndStopsSending() {
        val started = reduceChatState(ChatUiState(draft = "hi"), ChatAction.Submit).state
        val partial = reduceChatState(started, ChatAction.ResponseDelta("partial output")).state
        val cancelled = reduceChatState(partial, ChatAction.Cancel).state

        assertFalse(cancelled.isSending)
        assertEquals(ChatMessage("EMRG", "partial output\n[Stopped]"), cancelled.messages.last())
        assertEquals("", cancelled.streamingText)
    }

    @Test
    fun staleResponseWhenNotSendingIsIgnored() {
        val initial = ChatUiState()
        assertEquals(initial, reduceChatState(initial, ChatAction.ResponseReceived("stale")).state)
    }
}
