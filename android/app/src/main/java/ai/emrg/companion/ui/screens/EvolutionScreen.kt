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

internal data class RemoteEvolutionRecord(
    val timestamp: String,
    val task: String,
    val work: String,
    val toolCount: Int,
    val recommendSlowdown: Boolean,
)

/**
 * The `evolution` route.
 *
 * Reads the evolution summary via `evolution.summary` (evolution.read scope,
 * read-only): the total cycle count plus the most recent cycle records.
 * There is deliberately no auto-evolve trigger here — `init_auto_evolve` is
 * host-lifecycle state and is never forwarded to a remote client.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EvolutionScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf<Int?>(null) }
    var records by remember { mutableStateOf<List<RemoteEvolutionRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId) { connection.sendEvolutionSummary(requestId, limit = 5) }
            loading = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or evolution.read not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        count = frame.raw.optInt("count")
                        records = frame.resultObjects("entries", "recent").mapNotNull { row ->
                            val timestamp = row.optNonBlank("timestamp") ?: return@mapNotNull null
                            RemoteEvolutionRecord(
                                timestamp = timestamp,
                                task = row.optNonBlank("task").orEmpty(),
                                work = row.optNonBlank("work").orEmpty(),
                                toolCount = row.optInt("tool_count"),
                                recommendSlowdown = row.optBoolean("recommend_slowdown"),
                            )
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Evolution") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.READ_EVOLUTION, navController) {
                ScreenNote("Read-only: this screen shows the evolution summary. It cannot start auto-evolution — that is host-only.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh") }
                }
                ScreenError(error)
                count?.let { Text("Total cycles: $it", style = MaterialTheme.typography.titleMedium) }
                if (records.isEmpty() && !loading) {
                    Text("No evolution records yet.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(records, key = { it.timestamp }) { record ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(record.timestamp, style = MaterialTheme.typography.titleSmall)
                                val summary = listOfNotNull(
                                    record.task.takeIf { it.isNotBlank() },
                                    record.work.takeIf { it.isNotBlank() },
                                ).joinToString(" — ")
                                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${record.toolCount} tools" +
                                        if (record.recommendSlowdown) " · slowdown recommended" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
