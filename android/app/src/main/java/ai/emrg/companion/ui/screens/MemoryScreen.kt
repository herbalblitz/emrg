package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import ai.emrg.companion.userErrorMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import java.util.UUID

internal data class RemoteMemory(
    val id: String,
    val title: String,
    val file: String,
    val type: String,
    val status: String,
    val eventAt: String,
)

/**
 * The `memory` route.
 *
 * Lists memories via `memory.list` and reads one via `memory.read`
 * (memory.read scope, read-only). Scope "agent" reads the agent memory store;
 * scope "session" needs a session id. The server supplies the workspace from
 * the pairing record, so the client never sends a cwd.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var memories by remember { mutableStateOf<List<RemoteMemory>>(emptyList()) }
    var memoryScope by remember { mutableStateOf("agent") }
    var scopeExpanded by remember { mutableStateOf(false) }
    var sessionId by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var indexText by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedContent by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        indexText = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val sessionArg = sessionId.takeIf { it.isNotBlank() }
            val frame = connection.request(requestId) {
                connection.sendMemoryList(requestId, memoryScope, sessionArg)
            }
            loading = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or memory.read not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        indexText = frame.optResultString("index")?.take(2048)
                        memories = frame.resultObjects("entries", "memories").mapNotNull { row ->
                            val id = row.optNonBlank("id") ?: return@mapNotNull null
                            RemoteMemory(
                                id = id,
                                title = row.optNonBlank("title").orEmpty().ifEmpty {
                                    row.optNonBlank("file").orEmpty().ifEmpty { id }
                                },
                                file = row.optNonBlank("file").orEmpty(),
                                type = row.optNonBlank("type").orEmpty(),
                                status = row.optNonBlank("status").orEmpty(),
                                eventAt = row.optNonBlank("event_at").orEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }

    fun read(memory: RemoteMemory) {
        error = null
        selectedId = memory.id
        selectedContent = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val sessionArg = sessionId.takeIf { it.isNotBlank() }
            val frame = connection.request(requestId) {
                connection.sendMemoryRead(requestId, memory.id, memoryScope, sessionArg)
            }
            when {
                frame == null -> error = "Could not reach the host (not connected, or memory.read not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) error = daemonError
                    else selectedContent = frame.optResultString("content").orEmpty()
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Memory") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.READ_MEMORY, navController) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scopeExpanded = !scopeExpanded }) { Text("Scope: $memoryScope") }
                    if (scopeExpanded) {
                        DropdownMenu(expanded = scopeExpanded, onDismissRequest = { scopeExpanded = false }) {
                            listOf("agent", "session").forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s) },
                                    onClick = { memoryScope = s; scopeExpanded = false },
                                )
                            }
                        }
                    }
                    Button(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh") }
                }
                if (memoryScope == "session") {
                    OutlinedTextField(
                        value = sessionId,
                        onValueChange = { sessionId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Session id (session scope)") },
                        singleLine = true,
                    )
                    ScreenNote("Session scope reads that session's memory store. The session id is sent as part of the request; the workspace itself always comes from the pairing record.")
                }
                ScreenError(error)
                indexText?.let {
                    if (it.isNotBlank()) Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (memories.isEmpty() && !loading) {
                    Text("No memories yet. Tap Refresh to load them.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(memories, key = { it.id }) { memory ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(memory.title, style = MaterialTheme.typography.titleSmall)
                                val meta = listOfNotNull(
                                    memory.type.takeIf { it.isNotBlank() },
                                    memory.status.takeIf { it.isNotBlank() },
                                    memory.eventAt.takeIf { it.isNotBlank() },
                                    memory.file.takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (selectedId == memory.id) {
                                        OutlinedButton(onClick = {
                                            selectedId = null
                                            selectedContent = null
                                        }) { Text("Close") }
                                    } else {
                                        Button(onClick = { read(memory) }) { Text("Read") }
                                    }
                                }
                                if (selectedId == memory.id) {
                                    selectedContent?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
