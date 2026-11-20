package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import ai.emrg.companion.ui.navigation.Route
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class RemoteSession(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val messageCount: Int,
)

/**
 * The `sessions` route.
 *
 * Lists remote sessions via `sessions.list` (sessions.list scope, read-only).
 * Mutations — resume (new), rename, delete, clear, compact, rewind, history —
 * send `sessions.*` frames (sessions.manage scope). History and rewind need a
 * record index; the history view shows the index next to each record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<RemoteSession>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var historyText by remember { mutableStateOf<String?>(null) }
    var rewindIndex by remember { mutableStateOf("") }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId) { connection.sendSessionsList(requestId) }
            loading = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or sessions.list not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> sessions = frame.resultObjects("sessions").mapNotNull { row ->
                    val id = row.optNonBlank("session_id") ?: return@mapNotNull null
                    RemoteSession(
                        id = id,
                        title = row.optNonBlank("title").orEmpty().ifEmpty { "Untitled session" },
                        createdAt = row.optNonBlank("created_at").orEmpty(),
                        updatedAt = row.optNonBlank("updated_at").orEmpty(),
                        messageCount = row.optInt("message_count"),
                    )
                }
            }
        }
    }

    /** One sessions.manage call. Returns the result frame, or null when refused. */
    fun manage(
        type: String,
        sessionId: String?,
        title: String? = null,
        recordIndex: Int? = null,
        onResult: (ai.emrg.companion.ProtocolFrame?) -> Unit = {},
    ) {
        error = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId, timeoutMs = 120_000) {
                connection.sendSessionsManage(requestId, type, sessionId, title, recordIndex)
            }
            if (frame == null) error = "Could not reach the host (not connected, or sessions.manage not granted)."
            else if (frame.type == "error") error = frame.userErrorMessage()
            onResult(frame)
            refresh()
        }
    }

    fun newSessionId(): String {
        val stamp = SimpleDateFormat("yyMMdd_HHmm", Locale.US).format(Date())
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "s_${stamp}_$suffix"
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sessions") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.LIST_SESSIONS, navController) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh") }
                    OutlinedButton(
                        enabled = !loading && connection.hasScope(PairingScope.MANAGE_SESSIONS),
                        onClick = { manage("sessions.resume", newSessionId()) },
                    ) { Text("New session") }
                }
                if (!connection.hasScope(PairingScope.MANAGE_SESSIONS)) {
                    ScreenNote("Read-only: sessions.manage was not granted, so rename, delete, clear, compact and rewind are unavailable.")
                }
                ScreenError(error)
                if (sessions.isEmpty() && !loading) {
                    Text("No sessions yet.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val selected = selectedId == session.id
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(session.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${session.id} · ${session.messageCount} messages" +
                                        if (session.updatedAt.isNotBlank()) " · ${session.updatedAt}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { navController.navigate(Route.chat(session.id)) }) {
                                        Text("Open chat")
                                    }
                                    OutlinedButton(onClick = {
                                        selectedId = if (selected) null else session.id
                                        renameDraft = session.title
                                        historyText = null
                                    }) { Text(if (selected) "Close" else "Manage") }
                                }
                                if (selected) {
                                    OutlinedTextField(
                                        value = renameDraft,
                                        onValueChange = { renameDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("New title") },
                                        singleLine = true,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            enabled = renameDraft.isNotBlank(),
                                            onClick = { manage("sessions.rename", session.id, title = renameDraft) },
                                        ) { Text("Rename") }
                                        OutlinedButton(onClick = { manage("sessions.delete", session.id) }) { Text("Delete") }
                                        OutlinedButton(onClick = { manage("sessions.clear", session.id) }) { Text("Clear") }
                                        OutlinedButton(onClick = { manage("sessions.compact", session.id) }) { Text("Compact") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = rewindIndex,
                                            onValueChange = { rewindIndex = it.filter { c -> c.isDigit() } },
                                            modifier = Modifier.weight(1f),
                                            label = { Text("Record index") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        )
                                        Button(
                                            enabled = rewindIndex.isNotBlank(),
                                            onClick = {
                                                manage("sessions.rewind", session.id, recordIndex = rewindIndex.toInt())
                                            },
                                        ) { Text("Rewind") }
                                    }
                                    OutlinedButton(onClick = {
                                        manage("sessions.history", session.id) { frame ->
                                            historyText = frame?.raw?.optJSONArray("messages")?.let { messages ->
                                                (0 until messages.length()).mapNotNull { i ->
                                                    val row = messages.optJSONObject(i) ?: return@mapNotNull null
                                                    val index = row.optInt("record_index")
                                                    val role = row.optNonBlank("role") ?: "?"
                                                    val content = row.optNonBlank("content").orEmpty()
                                                    "#$index $role: $content"
                                                }.joinToString("\n")
                                            } ?: frame?.raw?.toString()?.take(4096)
                                        }
                                    }) { Text("Show history") }
                                    historyText?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
