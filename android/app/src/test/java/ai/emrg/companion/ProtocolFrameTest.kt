package ai.emrg.companion

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolFrameTest {
    private fun fixtures(): JSONArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("protocol-fixtures/frames.json")!!
        return JSONArray(stream.bufferedReader().use { it.readText() })
    }

    @Test fun parsesAllSharedFixturesAndKeepsUnknownFrames() {
        val rows = fixtures()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val frame = ProtocolFrame.fromJson(row.getJSONObject("frame"))
            assertNotNull("fixture: ${row.getString("name")}", frame.raw)
        }
        val future = ProtocolFrame.fromJson(
            fixtures().getJSONObject(10).getJSONObject("frame")
        )
        assertEquals("future_event", future.type)
        assertTrue(future.raw.has("new_field"))
    }

    @Test fun deltaMaintainsRequestAndSessionCorrelation() {
        val frame = ProtocolFrame.parse("""{"request_id":"r","session_id":"s","content":"chunk","done":false,"delta":true}""")
        assertEquals("r", frame.requestId)
        assertEquals("s", frame.sessionId)
        assertTrue(frame.isDelta)
        assertFalse(frame.isTerminal)
    }

    @Test fun terminalDoneAndCancellationAreRecognized() {
        val normal = ProtocolFrame.parse("""{"request_id":"r","done":true,"content":"final"}""")
        assertTrue(normal.isTerminal)
        assertFalse(normal.cancelled == true)
        val cancelled = ProtocolFrame.parse("""{"done":true,"cancelled":true}""")
        assertTrue(cancelled.isTerminal)
        assertTrue(cancelled.cancelled == true)
    }

    @Test fun toolLifecycleSupportsOptionalCallLabel() {
        val labeled = ProtocolFrame.parse("""{"type":"tool_start","tool_name":"t","tool_call_id":"c","arguments":{"n":1},"intent":"test","call_label":"(2/3)"}""")
        assertEquals("t", labeled.toolName)
        assertEquals("c", labeled.toolCallId)
        assertEquals("(2/3)", labeled.callLabel)
        assertEquals(1, labeled.arguments!!.getInt("n"))
        val old = ProtocolFrame.parse("""{"type":"tool_start","tool_name":"old","arguments":{}}""")
        assertNull(old.callLabel)
    }

    @Test fun errorCanBeTerminalAndUnknownOptionalFieldsAreIgnored() {
        val frame = ProtocolFrame.parse("""{"type":"error","error":"oops","request_id":"r","extra":42}""")
        assertTrue(frame.isTerminal)
        assertEquals("oops", frame.error)
        assertEquals(42, frame.raw.getInt("extra"))
    }

    @Test fun rejectsMalformedJsonAndNonObjectRoot() {
        assertThrows(JSONException::class.java) { ProtocolFrame.parse("{bad") }
        assertThrows(JSONException::class.java) { ProtocolFrame.parse("[]") }
    }

    @Test fun nullAndAbsentOptionalValuesRemainNull() {
        val frame = ProtocolFrame.parse("""{"content":null,"delta":null,"cancelled":null}""")
        assertNull(frame.content)
        assertNull(frame.delta)
        assertNull(frame.cancelled)
    }
}
