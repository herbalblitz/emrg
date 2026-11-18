package ai.emrg.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private, device-local snapshot for the offline companion preview.
 * It intentionally stores chat text only, never credentials or connection settings.
 */
internal class ChatHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ChatUiState {
        val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return ChatUiState()
        return try {
            val root = JSONObject(raw)
            val entries = root.optJSONArray(KEY_MESSAGES) ?: JSONArray()
            val messages = buildList {
                for (i in 0 until entries.length()) {
                    val item = entries.optJSONObject(i) ?: continue
                    val role = item.optString("role", "")
                    val text = item.optString("text", "")
                    if (role.isNotBlank()) add(ChatMessage(role, text))
                }
            }
            restoreChatUiState(messages, root.optString(KEY_DRAFT, ""))
        } catch (_: Exception) {
            ChatUiState()
        }
    }

    fun save(state: ChatUiState) {
        val root = JSONObject()
        val entries = JSONArray()
        state.messages.takeLast(MAX_SAVED_MESSAGES).forEach { message ->
            entries.put(JSONObject().put("role", message.role).put("text", message.text))
        }
        root.put(KEY_MESSAGES, entries)
        root.put(KEY_DRAFT, state.draft)
        preferences.edit().putString(KEY_SNAPSHOT, root.toString()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "emrg_chat_history"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_MESSAGES = "messages"
        private const val KEY_DRAFT = "draft"
    }
}
