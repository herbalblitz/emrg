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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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

internal data class RemoteRant(
    val timestamp: String,
    val message: String,
    val status: String,
    val agent: String,
)

/**
 * The `rant` route.
 *
 * Lists rants via `rant.list` and submits one via `rant.submit`
 * (rant.submit scope). Rants are appended to the host's rants log for
 * evolution analysis; submitting is a write to that log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RantScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var rants by remember { mutableStateOf<List<RemoteRant>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId) { connection.sendRantList(requestId) }
            loading = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or rant.submit not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        rants = frame.resultObjects("entries", "rants").mapNotNull { row ->
                            val text = row.optNonBlank("message") ?: return@mapNotNull null
                            RemoteRant(
                                timestamp = row.optNonBlank("timestamp").orEmpty(),
                                message = text,
                                status = row.optNonBlank("status").orEmpty(),
                                agent = row.optNonBlank("agent").orEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }

    fun submit() {
        val text = message.trim()
        if (text.isEmpty()) return
        submitting = true
        error = null
        result = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val agentArg = agent.trim().takeIf { it.isNotBlank() }
            val frame = connection.request(requestId) {
                connection.sendRantSubmit(requestId, text, agentArg)
            }
            submitting = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or rant.submit not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        val count = frame.raw.optInt("count")
                        result = if (count > 0) "Recorded ($count total)" else "Recorded"
                        message = ""
                        refresh()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Rant / feedback") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.SUBMIT_RANT, navController) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Feedback") },
                    minLines = 4,
                    maxLines = 8,
                )
                OutlinedTextField(
                    value = agent,
                    onValueChange = { agent = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Target agent (optional)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { submit() }, enabled = !submitting && message.isNotBlank()) {
                        Text(if (submitting) "Sending…" else "Send rant")
                    }
                    Button(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh list") }
                }
                ScreenError(error)
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (rants.isEmpty() && !loading) {
                    Text("No rants recorded yet.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rants, key = { "${it.timestamp}:${it.message.hashCode()}" }) { rant ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(rant.message, style = MaterialTheme.typography.bodyMedium)
                                val meta = listOfNotNull(
                                    rant.timestamp.takeIf { it.isNotBlank() },
                                    rant.status.takeIf { it.isNotBlank() },
                                    rant.agent.takeIf { it.isNotBlank() }?.let { "agent: $it" },
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
