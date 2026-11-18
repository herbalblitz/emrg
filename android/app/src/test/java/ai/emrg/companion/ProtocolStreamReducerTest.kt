package ai.emrg.companion

import org.junit.Assert.*
import org.junit.Test

class ProtocolStreamReducerTest {
    private fun start(id: String, session: String? = null) = ProtocolStreamAction.Start(id, session)
    private fun frame(json: String) = ProtocolStreamAction.Receive(ProtocolFrame.parse(json))

    @Test fun tracksRequestsIndependentlyAndKeepsSessions() {
        var s = reduceProtocolStreams(ProtocolStreamState(), start("a", "session-a"))
        s = reduceProtocolStreams(s, start("b", "session-b"))
        s = reduceProtocolStreams(s, frame("""{"request_id":"b","content":"B","delta":true}"""))
        s = reduceProtocolStreams(s, frame("""{"request_id":"a","content":"A","delta":true}"""))
        assertEquals("session-a", s.requests["a"]!!.sessionId)
        assertEquals("session-b", s.requests["b"]!!.sessionId)
        assertEquals("A", s.requests["a"]!!.text)
        assertEquals("B", s.requests["b"]!!.text)
    }

    @Test fun appendsDeltaChunksInOrderAndTerminalFinalContent() {
        var s = reduceProtocolStreams(ProtocolStreamState(), start("r"))
        s = reduceProtocolStreams(s, frame("""{"request_id":"r","content":"one","delta":true}"""))
        s = reduceProtocolStreams(s, frame("""{"request_id":"r","content":"two","delta":true}"""))
        assertEquals("onetwo", s.requests["r"]!!.text)
        s = reduceProtocolStreams(s, frame("""{"request_id":"r","content":"final","done":true}"""))
        assertEquals("final", s.requests["r"]!!.text)
        assertEquals(StreamStatus.COMPLETED, s.requests["r"]!!.status)
    }

    @Test fun duplicateTerminalAndLateFramesAreIgnored() {
        var s = reduceProtocolStreams(ProtocolStreamState(), start("r"))
        s = reduceProtocolStreams(s, frame("""{"request_id":"r","done":true,"content":"end"}"""))
        val terminal = s.requests["r"]
        s = reduceProtocolStreams(s, frame("""{"request_id":"r","content":"late","delta":true}"""))
        s = reduceProtocolStreams(s, frame("""{"request_id":"r","done":true,"content":"duplicate"}"""))
        assertEquals(terminal, s.requests["r"])
    }

    @Test fun cancellationAndErrorTerminalStatesAreDistinct() {
        var s = reduceProtocolStreams(ProtocolStreamState(), start("cancel"))
        s = reduceProtocolStreams(s, ProtocolStreamAction.Cancel("cancel"))
        assertEquals(StreamStatus.CANCELLED, s.requests["cancel"]!!.status)
        s = reduceProtocolStreams(s, start("error"))
        s = reduceProtocolStreams(s, frame("""{"request_id":"error","type":"error","error":"oops"}"""))
        assertEquals(StreamStatus.FAILED, s.requests["error"]!!.status)
        assertEquals("oops", s.requests["error"]!!.error)
    }

    @Test fun connectionLossFailsOnlyActiveRequests() {
        var s = reduceProtocolStreams(ProtocolStreamState(), start("a"))
        s = reduceProtocolStreams(s, start("b"))
        s = reduceProtocolStreams(s, frame("""{"request_id":"b","done":true,"content":"ok"}"""))
        s = reduceProtocolStreams(s, ProtocolStreamAction.ConnectionLost)
        assertEquals(StreamStatus.FAILED, s.requests["a"]!!.status)
        assertEquals("Connection lost", s.requests["a"]!!.error)
        assertEquals(StreamStatus.COMPLETED, s.requests["b"]!!.status)
    }

    @Test fun toolLifecycleUpdatesMatchingCall() {
        var s = reduceProtocolStreams(ProtocolStreamState(), start("r"))
        s = reduceProtocolStreams(s, frame("""{"request_id":"r","type":"tool_start","tool_name":"lookup","tool_call_id":"c","call_label":"Lookup","arguments":{"q":"x"}}"""))
        s = reduceProtocolStreams(s, frame("""{"request_id":"r","type":"tool_end","tool_name":"lookup","tool_call_id":"c","content":"found"}"""))
        val call = s.requests["r"]!!.toolCalls.single()
        assertEquals("Lookup", call.label)
        assertEquals("found", call.result)
        assertFalse(call.failed)
        assertTrue(call.completed)
        assertEquals("Completed", toolActivityStatus(call))
    }

    @Test fun toolActivityStatusDistinguishesRunningFailedAndCompleted() {
        val running = ToolCallState(callId = "r", name = "search")
        val failed = ToolCallState(callId = "f", name = "write", failed = true, completed = true)
        val completed = ToolCallState(callId = "c", name = "lookup", completed = true)
        assertEquals("Running", toolActivityStatus(running))
        assertEquals("Failed", toolActivityStatus(failed))
        assertEquals("Completed", toolActivityStatus(completed))
    }

    @Test fun emptyTerminalContentPreservesAccumulatedStream() {
        var s = reduceProtocolStreams(ProtocolStreamState(), start("r"))
        s = reduceProtocolStreams(s, frame("""{"request_id":"r","content":"partial","delta":true}"""))
        s = reduceProtocolStreams(s, frame("""{"request_id":"r","content":"","done":true}"""))
        assertEquals("partial", s.requests["r"]!!.text)
        assertEquals(StreamStatus.COMPLETED, s.requests["r"]!!.status)
    }

    @Test fun ignoresFramesWithoutMatchingActiveRequest() {
        val initial = ProtocolStreamState()
        assertEquals(initial, reduceProtocolStreams(initial, frame("""{"request_id":"missing","content":"x","delta":true}""")))
        assertEquals(initial, reduceProtocolStreams(initial, frame("""{"content":"x","delta":true}""")))
    }
}
