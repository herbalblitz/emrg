package ai.emrg.companion

import org.json.JSONException
import org.json.JSONObject

/**
 * Defensive, transport-neutral representation of the current EMRG JSON frame.
 * Unknown frame types and optional fields are preserved in [raw] for forward compatibility.
 */
internal data class ProtocolFrame(
    val type: String?,
    val requestId: String?,
    val sessionId: String?,
    val content: String?,
    val done: Boolean?,
    val delta: Boolean?,
    val cancelled: Boolean?,
    val error: String?,
    val toolName: String?,
    val toolCallId: String?,
    val callLabel: String?,
    val arguments: JSONObject?,
    val intent: String?,
    val raw: JSONObject,
) {
    val isTerminal: Boolean get() = done == true || type == "error"
    val isDelta: Boolean get() = delta == true && done != true

    companion object {
        @Throws(JSONException::class)
        fun parse(json: String): ProtocolFrame {
            val obj = JSONObject(json)
            return fromJson(obj)
        }

        internal fun fromJson(obj: JSONObject): ProtocolFrame {
            return ProtocolFrame(
                type = obj.optString("type").takeIf { it.isNotBlank() },
                requestId = obj.optString("request_id").takeIf { it.isNotBlank() },
                sessionId = obj.optString("session_id").takeIf { it.isNotBlank() },
                content = obj.optString("content").takeIf { obj.has("content") && !obj.isNull("content") },
                done = obj.optBoolean("done").takeIf { obj.has("done") && !obj.isNull("done") },
                delta = obj.optBoolean("delta").takeIf { obj.has("delta") && !obj.isNull("delta") },
                cancelled = obj.optBoolean("cancelled").takeIf { obj.has("cancelled") && !obj.isNull("cancelled") },
                error = obj.optString("error").takeIf { obj.has("error") && !obj.isNull("error") },
                toolName = obj.optString("tool_name").takeIf { it.isNotBlank() },
                toolCallId = obj.optString("tool_call_id").takeIf { it.isNotBlank() },
                callLabel = obj.optString("call_label").takeIf { it.isNotBlank() },
                arguments = obj.optJSONObject("arguments"),
                intent = obj.optString("intent").takeIf { it.isNotBlank() },
                raw = JSONObject(obj.toString()),
            )
        }
    }
}
