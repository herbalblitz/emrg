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
    fun toolActivityUpdatesDuringActiveRequestAndClearsOnCompletion() {
        val started = reduceChatState(ChatUiState(draft = "run"), ChatAction.Submit).state
        val calls = listOf(ToolCallState(callId = "c1", name = "search", label = "Search"))
        val active = reduceChatState(started, ChatAction.ToolCallsUpdated(calls)).state
        assertEquals(calls, active.toolCalls)
        val completed = reduceChatState(active, ChatAction.ResponseReceived("done")).state
        assertTrue(completed.toolCalls.isEmpty())
    }

    @Test
    fun toolActivityUpdatesAreIgnoredWhenNoRequestIsActive() {
        val initial = ChatUiState()
        val calls = listOf(ToolCallState(callId = "c1", name = "search", label = "Search"))
        assertEquals(initial, reduceChatState(initial, ChatAction.ToolCallsUpdated(calls)).state)
    }

    @Test
    fun savedSnapshotDoesNotRestoreTransientToolActivity() {
        val state = ChatUiState(
            messages = listOf(ChatMessage("You", "run tool")),
            draft = "next",
            isSending = true,
            streamingText = "partial",
            toolCalls = listOf(ToolCallState(callId = "c1", name = "search", label = "Search"))
        )
        val restored = restoreChatUiStateFromSnapshot(saveChatUiState(state))
        assertFalse(restored.isSending)
        assertEquals("", restored.streamingText)
        assertTrue(restored.toolCalls.isEmpty())
        assertEquals(state.messages, restored.messages)
        assertEquals(state.draft, restored.draft)
    }

    @Test
    fun staleResponseWhenNotSendingIsIgnored() {
        val initial = ChatUiState()
        assertEquals(initial, reduceChatState(initial, ChatAction.ResponseReceived("stale")).state)
    }

    @Test
    fun protocolSnapshotProjectsOnlyBoundRequestIntoUi() {
        val started = reduceChatState(ChatUiState(draft = "prompt"), ChatAction.Submit).state
        val bound = reduceChatState(started, ChatAction.BindRequest("req-1")).state
        val snapshot = StreamRequest(text = "partial", toolCalls = listOf(
            ToolCallState(callId = "tool-1", name = "lookup", label = "Lookup")
        ))
        val projected = reduceChatState(bound, ChatAction.ProtocolSnapshot("req-1", snapshot)).state
        assertEquals("partial", projected.streamingText)
        assertEquals(snapshot.toolCalls, projected.toolCalls)

        val stale = reduceChatState(projected, ChatAction.ProtocolSnapshot("req-2", snapshot)).state
        assertEquals(projected, stale)
    }

    @Test
    fun protocolCompletionCommitsTextAndClearsRequestBinding() {
        val started = reduceChatState(ChatUiState(draft = "prompt"), ChatAction.Submit).state
        val bound = reduceChatState(started, ChatAction.BindRequest("req-1")).state
        val completed = reduceChatState(bound, ChatAction.ProtocolSnapshot(
            "req-1", StreamRequest(text = "final", status = StreamStatus.COMPLETED)
        )).state
        assertFalse(completed.isSending)
        assertNull(completed.activeRequestId)
        assertEquals(ChatMessage("EMRG", "final"), completed.messages.last())
    }

    @Test
    fun protocolTerminalStatesFailOrCancelAndClearBinding() {
        val started = reduceChatState(ChatUiState(draft = "prompt"), ChatAction.Submit).state
        val bound = reduceChatState(started, ChatAction.BindRequest("req-1")).state
        val failed = reduceChatState(bound, ChatAction.ProtocolSnapshot(
            "req-1", StreamRequest(status = StreamStatus.FAILED, error = "host error")
        )).state
        assertFalse(failed.isSending)
        assertNull(failed.activeRequestId)
        assertEquals(ChatMessage("EMRG", CHAT_FAILURE_MESSAGE), failed.messages.last())

        val cancelledStarted = reduceChatState(ChatUiState(draft = "prompt"), ChatAction.Submit).state
        val cancelledBound = reduceChatState(cancelledStarted, ChatAction.BindRequest("req-2")).state
        val partial = reduceChatState(cancelledBound, ChatAction.ProtocolSnapshot(
            "req-2", StreamRequest(text = "partial", status = StreamStatus.CANCELLED)
        )).state
        assertFalse(partial.isSending)
        assertNull(partial.activeRequestId)
        assertEquals(ChatMessage("EMRG", "partial\n[Stopped]"), partial.messages.last())
    }


    @Test
    fun protocolSnapshotStreamsTextAndToolActivityForBoundRequest() {
        var chat = reduceChatState(ChatUiState(draft = "prompt"), ChatAction.Submit).state
        chat = reduceChatState(chat, ChatAction.BindRequest("req-1")).state
        val tool = ToolCallState(callId = "t1", name = "lookup", label = "Lookup", arguments = "{\"q\":\"x\"}")
        val snapshot = StreamRequest(text = "partial answer", toolCalls = listOf(tool))
        chat = reduceChatState(chat, ChatAction.ProtocolSnapshot("req-1", snapshot)).state
        assertEquals("partial answer", chat.streamingText)
        assertEquals(snapshot.toolCalls, chat.toolCalls)
        assertTrue(chat.isSending)
    }

    @Test
    fun protocolSnapshotIgnoresEventsForUnboundOrDifferentRequest() {
        val sending = reduceChatState(ChatUiState(draft = "prompt"), ChatAction.Submit).state
        val bound = reduceChatState(sending, ChatAction.BindRequest("req-a")).state
        val snapshot = StreamRequest(text = "should not leak")
        assertEquals(bound, reduceChatState(bound, ChatAction.ProtocolSnapshot("req-b", snapshot)).state)
        assertEquals(sending, reduceChatState(sending, ChatAction.ProtocolSnapshot("req-a", snapshot)).state)
    }

    @Test
    fun protocolTerminalCompletionMovesStreamIntoHistoryAndClearsTransientState() {
        var chat = reduceChatState(ChatUiState(draft = "prompt"), ChatAction.Submit).state
        chat = reduceChatState(chat, ChatAction.BindRequest("req-1")).state
        chat = reduceChatState(chat, ChatAction.ProtocolSnapshot("req-1", StreamRequest(text = "answer", toolCalls = listOf(ToolCallState(callId = "t", name = "tool", label = "Tool"))))).state
        chat = reduceChatState(chat, ChatAction.ProtocolSnapshot("req-1", StreamRequest(text = "answer-final", status = StreamStatus.COMPLETED))).state
        assertFalse(chat.isSending)
        assertEquals(ChatMessage("EMRG", "answer-final"), chat.messages.last())
        assertEquals("", chat.streamingText)
        assertTrue(chat.toolCalls.isEmpty())
        assertNull(chat.activeRequestId)
    }

    @Test
    fun protocolFailureAndCancellationUseChatTerminalTransitions() {
        var failed = reduceChatState(ChatUiState(draft = "x"), ChatAction.Submit).state
        failed = reduceChatState(failed, ChatAction.BindRequest("fail")).state
        failed = reduceChatState(failed, ChatAction.ProtocolSnapshot("fail", StreamRequest(text = "partial", status = StreamStatus.FAILED, error = "daemon error"))).state
        assertFalse(failed.isSending)
        assertTrue(failed.messages.last().text.contains(CHAT_FAILURE_MESSAGE))
        assertNull(failed.activeRequestId)

        var cancelled = reduceChatState(ChatUiState(draft = "x"), ChatAction.Submit).state
        cancelled = reduceChatState(cancelled, ChatAction.BindRequest("cancel")).state
        cancelled = reduceChatState(cancelled, ChatAction.ProtocolSnapshot("cancel", StreamRequest(text = "partial", status = StreamStatus.CANCELLED))).state
        assertFalse(cancelled.isSending)
        assertEquals("partial\n[Stopped]", cancelled.messages.last().text)
        assertNull(cancelled.activeRequestId)
    }
}
