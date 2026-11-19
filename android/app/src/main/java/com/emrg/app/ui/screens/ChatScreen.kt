package com.emrg.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send




import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import com.emrg.app.net.str
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Turn a raw daemon frame into one readable line, or null to hide it.
 * Error frames carry no "type" or "content" — only "error" — so showing
 * "[$type]" rendered them as the meaningless "[]", hiding the real failure
 * (e.g. an LLM auth error) from the user. Nothing is ever invisible now:
 * unknown frames fall back to their raw JSON. The purely structural frames
 * (turn_start, turn_end, done, task_queued, and the daemon's user_message
 * echo, which carries no text) are hidden instead — on the phone they
 * drowned the chat in "[turn_start] [user_message] [done] [turn_end]"
 * noise around every real line. The user's own message is shown from the
 * synthetic frame sendPrompt() appends below, which does carry the text.
 * Tool calls and approval questions carry their own readable fields
 * (tool_name + intent, question), so they render as one line each instead
 * of a bare "[tool_start]" / "[approval_request]" tag. */
private fun frameDisplayText(frame: JsonObject): String? {
    val type = frame.str("type").orEmpty()
    if (type == "approval_request") {
        val question = frame.str("question").orEmpty()
        return if (question.isNotBlank()) "⏳ Xin quyền: $question" else "⏳ Agent xin quyền"
    }
    if (type == "approval_resolved") {
        return when (frame.str("outcome").orEmpty()) {
            "approved" -> "✅ Đã đồng ý"
            "refused" -> "❌ Đã từ chối"
            "timed_out" -> "⏰ Hết hạn chờ duyệt — yêu cầu bị từ chối"
            "cancelled" -> "Đã hủy yêu cầu"
            else -> null
        }
    }
    if (type == "tool_start") {
        val name = frame.str("tool_name").orEmpty()
        val detail = frame.str("intent").orEmpty().ifBlank { frame.str("call_label").orEmpty() }
        return when {
            name.isBlank() -> "🔧 Đang chạy công cụ…"
            detail.isBlank() -> "🔧 $name"
            else -> "🔧 $name: $detail"
        }
    }
    if (type == "task_queued") return null
    val content = frame.str("content").orEmpty()
    if (content.isNotBlank()) {
        // The sandbox refusal names the fix but buries it in a long
        // paragraph; surface the one action that unblocks the agent.
        if (content.contains("no sandbox backend is usable")) {
            return content + "\n💡 Bật danger-full-access trong Settings để agent chạy lệnh (vẫn cần bạn duyệt từng lần), hoặc làm việc trong TUI Termux."
        }
        return content
    }
    val error = frame.str("error").orEmpty()
    if (error.isNotBlank()) return "Error: $error"
    val delta = frame.str("delta").orEmpty()
    if (delta.isNotBlank()) return delta
    val text = frame.str("text").orEmpty()
    if (text.isNotBlank()) return text
    if (type in setOf("turn_start", "turn_end", "user_message")) return null
    if (frame.containsKey("done")) return null
    if (type.isNotBlank()) return "[$type]"
    return frame.toString().take(300)
}

@Composable
fun ChatScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
    sessionId: String = "",
    cwd: String = "",
) {
    val scope = rememberCoroutineScope()
    val effectiveCwd = cwd.takeIf { it.isNotBlank() } ?: repository.currentWorkspace()
    var frameList by remember { mutableStateOf(emptyList<JsonObject>()) }
    LaunchedEffect(Unit) {
        scope.launch {
            repository.frames.collect { frame ->
                frameList = frameList + frame
            }
        }
    }

    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val linkState by repository.state.collectAsState()

    // Approval questions the daemon is still waiting on. The daemon times
    // a question out (and refuses the escalation) if nobody answers, and
    // until now the app had no way to answer at all — the request only
    // showed as a bare "[approval_request]" line. The card below the list
    // carries the two buttons; answering here sends approval_response
    // through the repository's answerApproval().
    var answeredApprovals by remember { mutableStateOf(setOf<String>()) }
    val resolvedIds = frameList
        .filter { it.str("type") == "approval_resolved" }
        .mapNotNull { it.str("request_id") }
        .toSet()
    val pendingApproval = frameList.lastOrNull { frame ->
        frame.str("type") == "approval_request" && run {
            val id = frame.str("request_id").orEmpty()
            id.isNotBlank() && id !in answeredApprovals && id !in resolvedIds
        }
    }

    fun sendPrompt() {
        val text = input.trim()
        if (text.isEmpty() || sessionId.isEmpty()) return
        input = ""
        error = null
        // Show the user's own message: the daemon's user_message frame
        // carries no text, so without this the sent message would vanish
        // from the screen until the reply arrives.
        frameList = frameList + buildJsonObject {
            put("type", "user_message")
            put("content", text)
        }
        try {
            repository.sendPrompt(
                sessionId = sessionId,
                cwd = effectiveCwd,
                prompt = text,
            )
        } catch (e: GatewayRequestException) {
            error = e.message
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Session") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(
                    text = e,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            // The client already retries the socket on its own, but a dead
            // gateway stays dead until someone restarts it in Termux — the
            // user used to see only "the connection closed" with no next
            // step. Say what died, how to revive it, and offer an immediate
            // reconnect instead of waiting for the backoff loop.
            if (linkState is com.emrg.app.net.LinkState.Offline) {
                val reason = (linkState as com.emrg.app.net.LinkState.Offline).reason
                androidx.compose.material3.Text(
                    text = "Mất kết nối: $reason",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
                androidx.compose.material3.Text(
                    text = "Mở Termux, chạy lại `emrg-gateway serve` rồi bấm Nối lại — app sẽ tự nối lại khi gateway chạy.",
                )
                Button(onClick = {
                    scope.launch {
                        try {
                            repository.reconnectLastHost()
                        } catch (e: GatewayRequestException) {
                            error = e.message
                        }
                    }
                }) {
                    Text(text = "Nối lại ngay")
                }
            }
            // Index keys: two identical frames (e.g. the same message sent
            // twice) would collide on a content key and crash the list.
            val visibleFrames = frameList.mapIndexedNotNull { index, frame ->
                frameDisplayText(frame)?.let { index to it }
            }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = padding,
            ) {
                items(visibleFrames, key = { it.first }) { (_, line) ->
                    androidx.compose.material3.Text(
                        text = line,
                    )
                }
            }
            // The pending approval, pinned above the input so it stays
            // visible while the user reads the turn that asked for it.
            pendingApproval?.let { frame ->
                val requestId = frame.str("request_id").orEmpty()
                val question = frame.str("question").orEmpty()
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Text(
                        text = question.ifBlank { "Agent xin quyền chạy lệnh." },
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        repository.answerApproval(requestId, true)
                        answeredApprovals = answeredApprovals + requestId
                    }) {
                        Text(text = "Đồng ý")
                    }
                    Button(onClick = {
                        repository.answerApproval(requestId, false)
                        answeredApprovals = answeredApprovals + requestId
                    }) {
                        Text(text = "Từ chối")
                    }
                }
            }
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(text = "Message") },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                )
                IconButton(onClick = { sendPrompt() }) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}
