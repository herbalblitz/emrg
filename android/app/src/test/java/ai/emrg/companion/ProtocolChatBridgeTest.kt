package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolChatBridgeTest {
    @Test
    fun startAndIncomingFramesProjectOnlyBoundRequest() {
        var chat = reduceChatState(ChatUiState(draft = "hello"), ChatAction.Submit).state
        var protocol = ProtocolStreamState()

        val started = reduceProtocolChatBridge(chat, protocol, ProtocolStreamAction.Start("req-1", "session-1"))
        chat = started.chatTransition.state
        protocol = started.protocolState
        assertEquals("req-1", chat.activeRequestId)

        val delta = reduceProtocolChatBridge(chat, protocol, ProtocolStreamAction.Receive(
            ProtocolFrame.parse("""{"request_id":"req-1","content":"stream","delta":true}""")
        ))
        chat = delta.chatTransition.state
        protocol = delta.protocolState
        assertEquals("stream", chat.streamingText)

        val toolStart = reduceProtocolChatBridge(chat, protocol, ProtocolStreamAction.Receive(
            ProtocolFrame.parse("""{"request_id":"req-1","type":"tool_start","tool_call_id":"t1","tool_name":"search","call_label":"Search","arguments":{"q":"test"}}""")
        ))
        chat = toolStart.chatTransition.state
        protocol = toolStart.protocolState
        assertEquals("Running", toolActivityStatus(chat.toolCalls.single()))

        val toolEnd = reduceProtocolChatBridge(chat, protocol, ProtocolStreamAction.Receive(
            ProtocolFrame.parse("""{"request_id":"req-1","type":"tool_end","tool_call_id":"t1","tool_name":"search","content":"found"}""")
        ))
        chat = toolEnd.chatTransition.state
        protocol = toolEnd.protocolState
        assertEquals("Completed", toolActivityStatus(chat.toolCalls.single()))
        assertEquals("found", chat.toolCalls.single().result)

        val terminal = reduceProtocolChatBridge(chat, protocol, ProtocolStreamAction.Receive(
            ProtocolFrame.parse("""{"request_id":"req-1","done":true,"content":"final"}""")
        ))
        chat = terminal.chatTransition.state
        assertFalse(chat.isSending)
        assertNull(chat.activeRequestId)
        assertEquals(ChatMessage("EMRG", "final"), chat.messages.last())
        assertTrue(chat.toolCalls.isEmpty())
    }

    @Test
    fun eventForDifferentRequestDoesNotMutateChat() {
        var chat = reduceChatState(ChatUiState(draft = "hello"), ChatAction.Submit).state
        val start = reduceProtocolChatBridge(chat, ProtocolStreamState(), ProtocolStreamAction.Start("active"))
        chat = start.chatTransition.state
        val other = reduceProtocolChatBridge(chat, start.protocolState, ProtocolStreamAction.Start("other"))
        assertEquals(chat, other.chatTransition.state)

        val wrongFrame = reduceProtocolChatBridge(chat, other.protocolState, ProtocolStreamAction.Receive(
            ProtocolFrame.parse("""{"request_id":"other","content":"wrong","delta":true}""")
        ))
        assertEquals(chat, wrongFrame.chatTransition.state)
    }

    @Test
    fun connectionLossFailsBoundChatRequestAndClearsBinding() {
        var chat = reduceChatState(ChatUiState(draft = "hello"), ChatAction.Submit).state
        val started = reduceProtocolChatBridge(chat, ProtocolStreamState(), ProtocolStreamAction.Start("req"))
        chat = started.chatTransition.state
        val lost = reduceProtocolChatBridge(chat, started.protocolState, ProtocolStreamAction.ConnectionLost)
        assertFalse(lost.chatTransition.state.isSending)
        assertNull(lost.chatTransition.state.activeRequestId)
        assertTrue(lost.chatTransition.state.messages.last().text.contains(CHAT_FAILURE_MESSAGE))
        assertEquals(StreamStatus.FAILED, lost.protocolState.requests["req"]!!.status)
    }
    @Test
    fun connectionEpochActionsDoNotMutateChatAndStaleFramesAreIgnored() {
        var chat = reduceChatState(ChatUiState(draft = "prompt"), ChatAction.Submit).state
        val started = reduceProtocolChatBridge(chat, ProtocolStreamState(), ProtocolStreamAction.Start("req"))
        chat = started.chatTransition.state
        val epochBound = reduceProtocolChatBridge(chat, started.protocolState, ProtocolStreamAction.SetConnectionEpoch(9))
        assertEquals(chat, epochBound.chatTransition.state)
        val stale = reduceProtocolChatBridge(chat, epochBound.protocolState, ProtocolStreamAction.Receive(
            ProtocolFrame.parse("""{"request_id":"req","content":"stale","delta":true}"""),
            connectionEpoch = 8,
        ))
        assertEquals(chat, stale.chatTransition.state)
        assertEquals("", stale.protocolState.requests["req"]!!.text)
        val fresh = reduceProtocolChatBridge(chat, stale.protocolState, ProtocolStreamAction.Receive(
            ProtocolFrame.parse("""{"request_id":"req","content":"fresh","delta":true}"""),
            connectionEpoch = 9,
        ))
        assertEquals("fresh", fresh.chatTransition.state.streamingText)
    }

}
