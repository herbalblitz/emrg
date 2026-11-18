package ai.emrg.companion

import org.json.JSONArray
import org.json.JSONObject

/** Client-to-daemon payload builders. These do not send frames or provide transport/auth. */
internal sealed interface SessionCommand {
    fun toJson(): JSONObject

    data class ListSessions(val cwd: String) : SessionCommand {
        override fun toJson() = JSONObject().put("type", "list_sessions").put("cwd", requireCwd(cwd))
    }
    data class Resume(val sessionId: String, val cwd: String) : SessionCommand {
        override fun toJson() = JSONObject().put("type", "resume_session")
            .put("session_id", requireSession(sessionId)).put("cwd", requireCwd(cwd))
    }
    data class Rename(val sessionId: String, val title: String? = null) : SessionCommand {
        override fun toJson() = JSONObject().put("type", "rename_session")
            .put("session_id", requireSession(sessionId)).apply { title?.let { put("title", it) } }
    }
    data class Clear(val sessionId: String) : SessionCommand {
        override fun toJson() = JSONObject().put("type", "clear_session").put("session_id", requireSession(sessionId))
    }
    data class Delete(val sessionId: String) : SessionCommand {
        override fun toJson() = JSONObject().put("type", "delete_session").put("session_id", requireSession(sessionId))
    }
    data class Rewind(val sessionId: String, val recordIndex: Int) : SessionCommand {
        override fun toJson() = JSONObject().put("type", "rewind_session")
            .put("session_id", requireSession(sessionId)).put("record_index", require(recordIndex >= 0) { "record_index must be non-negative" }.let { recordIndex })
    }
}

internal data class TaskCommand(
    val id: String,
    val cwd: String,
    val prompt: String,
    val sessionId: String? = null,
    val tools: List<String>? = null,
    val timestamp: String? = null,
) {
    fun toJson(): JSONObject {
        require(id.isNotBlank()) { "request id must not be blank" }
        require(cwd.isNotBlank()) { "cwd must not be blank" }
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        return JSONObject().put("type", "task").put("id", id).put("cwd", cwd).put("prompt", prompt)
            .apply {
                sessionId?.let { put("session_id", requireSession(it)) }
                tools?.let { put("tools", JSONArray(it)) }
                timestamp?.let { put("timestamp", it) }
            }
    }
}

private fun requireCwd(cwd: String): String = require(cwd.isNotBlank()) { "cwd must not be blank" }.let { cwd }
private fun requireSession(sessionId: String): String =
    require(sessionId.isNotBlank()) { "session_id must not be blank" }.let { sessionId }
