package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import ai.emrg.companion.PinnedRemoteWebSocket
import ai.emrg.companion.userErrorMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

/**
 * The `logs` route.
 *
 * Tails one allowlisted log source via `logs.tail` (logs.read scope).
 * The source picker offers only the engine-owned logs the server allowlists
 * (daemon, crash, exit, start, gui); the line-count picker is capped at 400.
 * Log lines are shown as returned; the server bounds the response.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogsScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("daemon") }
    var lineCount by remember { mutableStateOf(100) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var tailSource by remember { mutableStateOf<String?>(null) }
    var tailPath by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        error = null
        loading = true
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId, timeoutMs = 60_000) {
                connection.sendLogsTail(requestId, source, lineCount)
            }
            loading = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or logs.read not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        // Frozen contract: lists arrive as `entries`; the gateway
                        // shape used `lines`. Accept either, entries first.
                        lines = frame.resultStrings("entries", "lines")
                        tailSource = frame.optResultString("source") ?: source
                        tailPath = frame.optResultString("path")
                        truncated = frame.optResultBoolean("truncated") == true
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Logs") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.READ_LOGS, navController) {
                ScreenNote(
                    "Read-only tail of one engine log. Only the sources the host " +
                        "allowlists are offered; log contents stay on this screen.",
                )
                Text("Source", style = MaterialTheme.typography.titleSmall)
                // Chunked rows: five sources do not fit one row on a narrow phone.
                PinnedRemoteWebSocket.LOG_SOURCES.chunked(3).forEach { rowSources ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowSources.forEach { name ->
                            OutlinedButton(
                                onClick = { source = name },
                                enabled = source != name && !loading,
                            ) { Text(name) }
                        }
                    }
                }
                Text("Lines (max 400)", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 100, 200, 400).forEach { count ->
                        OutlinedButton(
                            onClick = { lineCount = count },
                            enabled = lineCount != count && !loading,
                        ) { Text(count.toString()) }
                    }
                }
                Button(onClick = { refresh() }, enabled = !loading) {
                    Text(if (loading) "Loading…" else "Tail $source ($lineCount lines)")
                }
                ScreenError(error)
                tailSource?.let {
                    Text(
                        "Source: $it" + (tailPath?.let { path -> " · $path" } ?: "") +
                            if (truncated) " · truncated" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (lines.isEmpty() && !loading) {
                    Text("No lines yet — tap Tail to load.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Index keys: log lines repeat verbatim, so content is not a key.
                    itemsIndexed(lines) { _, line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
