package ai.emrg.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class LocalSession(
    val id: String,
    val title: String,
    val chat: ChatUiState = ChatUiState(),
    val updatedAt: Long = 0L
)

internal data class LocalSessionsState(
    val sessions: List<LocalSession>,
    val activeId: String
) {
    val active: LocalSession
        get() = sessions.firstOrNull { it.id == activeId } ?: sessions.first()
}

internal fun newLocalSessionsState(id: String = "local-default"): LocalSessionsState =
    LocalSessionsState(listOf(LocalSession(id, "New session")), id)

internal fun createLocalSession(state: LocalSessionsState, id: String, title: String = "New session"): LocalSessionsState {
    if (state.sessions.any { it.id == id } || id.isBlank()) return state
    val bounded = state.sessions.takeLast(MAX_LOCAL_SESSIONS - 1)
    val created = LocalSession(id, title.trim().ifBlank { "New session" })
    return LocalSessionsState(bounded + created, created.id)
}

internal fun selectLocalSession(state: LocalSessionsState, id: String): LocalSessionsState =
    if (state.sessions.any { it.id == id }) state.copy(activeId = id) else state

internal fun updateActiveLocalChat(state: LocalSessionsState, chat: ChatUiState): LocalSessionsState =
    updateLocalChat(state, state.activeId, chat)

internal fun updateLocalChat(state: LocalSessionsState, sessionId: String, chat: ChatUiState): LocalSessionsState =
    state.copy(sessions = state.sessions.map { session ->
        if (session.id == sessionId) session.copy(chat = chat.copy(isSending = false)) else session
    })

internal fun renameLocalSession(state: LocalSessionsState, id: String, title: String): LocalSessionsState {
    val normalized = title.trim().take(MAX_SESSION_TITLE_LENGTH)
    if (normalized.isBlank()) return state
    return state.copy(sessions = state.sessions.map { if (it.id == id) it.copy(title = normalized) else it })
}

internal fun deleteLocalSession(state: LocalSessionsState, id: String, replacementId: String): LocalSessionsState {
    if (state.sessions.none { it.id == id }) return state
    if (state.sessions.size == 1) return newLocalSessionsState(replacementId)
    val remaining = state.sessions.filterNot { it.id == id }
    val activeId = if (state.activeId == id) remaining.last().id else state.activeId
    return LocalSessionsState(remaining, activeId)
}

internal const val MAX_LOCAL_SESSIONS = 20
internal const val MAX_SESSION_TITLE_LENGTH = 80

/** Offline-only session snapshots. Does not mirror or synchronize daemon sessions. */
internal class LocalSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): LocalSessionsState {
        val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return newLocalSessionsState()
        return try {
            val root = JSONObject(raw)
            val entries = root.optJSONArray("sessions") ?: JSONArray()
            val sessions = buildList {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val messagesArray = item.optJSONArray("messages") ?: JSONArray()
                    val messages = buildList {
                        for (i in 0 until messagesArray.length()) {
                            val message = messagesArray.optJSONObject(i) ?: continue
                            val role = message.optString("role")
                            if (role.isNotBlank()) add(ChatMessage(role, message.optString("text")))
                        }
                    }
                    val chat = restoreChatUiState(messages, item.optString("draft"))
                    add(LocalSession(id, item.optString("title").ifBlank { "New session" }, chat, item.optLong("updatedAt")))
                }
            }.takeLast(MAX_LOCAL_SESSIONS)
            if (sessions.isEmpty()) return newLocalSessionsState()
            val activeId = root.optString("activeId").takeIf { value -> sessions.any { it.id == value } } ?: sessions.last().id
            LocalSessionsState(sessions, activeId)
        } catch (_: Exception) {
            newLocalSessionsState()
        }
    }

    fun save(state: LocalSessionsState) {
        val root = JSONObject().put("activeId", state.activeId)
        val entries = JSONArray()
        state.sessions.takeLast(MAX_LOCAL_SESSIONS).forEach { session ->
            val messages = JSONArray()
            session.chat.messages.takeLast(MAX_SAVED_MESSAGES).forEach { message ->
                messages.put(JSONObject().put("role", message.role).put("text", message.text))
            }
            entries.put(JSONObject()
                .put("id", session.id)
                .put("title", session.title.take(MAX_SESSION_TITLE_LENGTH))
                .put("updatedAt", session.updatedAt)
                .put("messages", messages)
                .put("draft", session.chat.draft))
        }
        root.put("sessions", entries)
        preferences.edit().putString(KEY_SNAPSHOT, root.toString()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "emrg_local_sessions"
        private const val KEY_SNAPSHOT = "snapshot"
    }
}
