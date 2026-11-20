package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import ai.emrg.companion.userErrorMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

internal data class RemoteAuditEntry(
    val ts: String,
    val device: String,
    val action: String,
    val decision: String,
    val detail: String,
)

/**
 * The `audit` route.
 *
 * Read-only tail of the host audit log via `audit.tail` (audit.read scope).
 * Shows timestamp, device, action and decision per entry. The server redacts
 * payload contents at write time and again at tail time; this screen never
 * renders any payload field — only the redacted detail string the server
 * returns, when present.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuditScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<RemoteAuditEntry>>(emptyList()) }
    var truncated by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        error = null
        loading = true
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId, timeoutMs = 60_000) {
                connection.sendAuditTail(requestId, 400)
            }
            loading = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or audit.read not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        entries = frame.resultObjects("entries").mapNotNull { row ->
                            val ts = row.optNonBlank("ts") ?: return@mapNotNull null
                            RemoteAuditEntry(
                                ts = ts,
                                device = row.optNonBlank("device_name")
                                    ?: row.optNonBlank("device_id").orEmpty(),
                                action = row.optNonBlank("action").orEmpty(),
                                decision = row.optNonBlank("decision").orEmpty(),
                                // Server-redacted detail only; no payload field is read.
                                detail = row.optNonBlank("detail").orEmpty(),
                            )
                        }
                        truncated = frame.optResultBoolean("truncated") == true
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Audit") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.READ_AUDIT, navController) {
                ScreenNote(
                    "Read-only audit log. Payload contents are redacted by the host " +
                        "and never shown here — only who did what, and whether it was allowed.",
                )
                Button(onClick = { refresh() }, enabled = !loading) {
                    Text(if (loading) "Loading…" else "Refresh")
                }
                ScreenError(error)
                Text(
                    "Audit entries (${entries.size})" + if (truncated) " · truncated" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (entries.isEmpty() && !loading) {
                    Text("No audit entries yet — tap Refresh to load.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Index keys: two audit rows can share ts/action/device.
                    itemsIndexed(entries) { _, entry ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "[${entry.ts}] ${entry.device} | ${entry.action} | ${entry.decision}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (entry.detail.isNotBlank()) {
                                    Text(
                                        entry.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
