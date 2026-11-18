package ai.emrg.companion

import org.junit.Assert.*
import org.json.JSONObject
import org.junit.Test

class ProtocolCommandTest {
    @Test fun taskCommandHasRequiredFieldsAndOptionalSessionToolsTimestamp() {
        val json = TaskCommand("req", "/workspace", "hello", "sess", listOf("read"), "2026-01-01T00:00:00").toJson()
        assertEquals("task", json.getString("type"))
        assertEquals("req", json.getString("id"))
        assertEquals("/workspace", json.getString("cwd"))
        assertEquals("hello", json.getString("prompt"))
        assertEquals("sess", json.getString("session_id"))
        assertEquals("read", json.getJSONArray("tools").getString(0))
        assertEquals("2026-01-01T00:00:00", json.getString("timestamp"))
    }

    @Test fun taskPreservesToolsAbsentVsEmptyAllowlist() {
        assertFalse(TaskCommand("a", "/", "x", tools = null).toJson().has("tools"))
        assertEquals(0, TaskCommand("b", "/", "x", tools = emptyList()).toJson().getJSONArray("tools").length())
    }

    @Test fun sessionCommandsMatchDocumentedWireTypes() {
        assertEquals("list_sessions", SessionCommand.ListSessions("/").toJson().getString("type"))
        val resume = SessionCommand.Resume("s", "/cwd").toJson()
        assertEquals("resume_session", resume.getString("type"))
        assertEquals("s", resume.getString("session_id"))
        assertEquals("/cwd", resume.getString("cwd"))
        val rename = SessionCommand.Rename("s", "new title").toJson()
        assertEquals("rename_session", rename.getString("type"))
        assertEquals("new title", rename.getString("title"))
        assertFalse(SessionCommand.Rename("s").toJson().has("title"))
        assertEquals("clear_session", SessionCommand.Clear("s").toJson().getString("type"))
        assertEquals("delete_session", SessionCommand.Delete("s").toJson().getString("type"))
        val rewind = SessionCommand.Rewind("s", 4).toJson()
        assertEquals("rewind_session", rewind.getString("type"))
        assertEquals(4, rewind.getInt("record_index"))
    }

    @Test fun commandBuildersRejectMissingRequiredFieldsAndInvalidRewind() {
        assertThrows(IllegalArgumentException::class.java) { TaskCommand("", "/", "x").toJson() }
        assertThrows(IllegalArgumentException::class.java) { TaskCommand("r", " ", "x").toJson() }
        assertThrows(IllegalArgumentException::class.java) { TaskCommand("r", "/", " ").toJson() }
        assertThrows(IllegalArgumentException::class.java) { SessionCommand.Resume(" ", "/").toJson() }
        assertThrows(IllegalArgumentException::class.java) { SessionCommand.Rewind("s", -1).toJson() }
    }
}
