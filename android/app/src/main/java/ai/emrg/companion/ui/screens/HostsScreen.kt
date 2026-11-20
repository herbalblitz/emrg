package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.ConnectionStatusPanel
import ai.emrg.companion.PairingScope
import ai.emrg.companion.RemoteSocketState
import ai.emrg.companion.ui.navigation.Route
import ai.emrg.companion.userErrorMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
 * The `hosts` route — navigation hub for the 20 screens.
 *
 * Shows the live connection, the scopes this device was granted, the real
 * host/agent list from `hosts.list` (daemon `list_agents`), and a link to
 * every screen. The hub links and the granted-scope list stay; only the
 * workspace-listing part is the real Batch C list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostsScreen(connection: CompanionConnection, navController: NavHostController) {
    val pairedHost by connection.pairedHost.collectAsState()
    val state by connection.state.collectAsState()
    val statusResult by connection.lastStatusResult.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Hosts") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ConnectionStatusPanel(pairedHost != null, state)
            if (pairedHost == null) {
                ScreenNote("No paired host yet. Pair with a host to unlock the screens below.")
                Button(onClick = { navController.navigate(Route.Pair) }) { Text("Pair a host") }
                return@Column
            }
            val host = pairedHost!!
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Paired host", style = MaterialTheme.typography.titleSmall)
                    Text(host.endpoint, style = MaterialTheme.typography.bodySmall)
                    host.approvedWorkspaceId?.let { Text("Workspace: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "Granted scopes (${host.scopes.size}): " +
                            host.scopes.mapNotNull { it.wireName }.sorted().joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            statusResult?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Host status", style = MaterialTheme.typography.titleSmall)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            // Real host/agent list (hosts.list → daemon list_agents). The hub
            // stays usable without the scope; only this section is gated.
            if (PairingScope.LIST_HOSTS in host.scopes && state == RemoteSocketState.AUTHENTICATED) {
                HostsListSection(connection)
            } else if (PairingScope.LIST_HOSTS !in host.scopes) {
                ScreenNote("Host list needs the hosts.list scope — re-pair with that scope to see the registered agents here.")
            }
            Text("Screens", style = MaterialTheme.typography.titleMedium)
            screenLinks.forEach { (route, label, scope) ->
                val granted = scope == null || scope in host.scopes
                Button(
                    onClick = { navController.navigate(route) },
                    // Pair and About are always reachable; the rest need a live socket.
                    enabled = state == RemoteSocketState.AUTHENTICATED ||
                        route == Route.Pair || route == Route.About,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label + if (granted) "" else " — scope not granted")
                }
            }
        }
    }
}

/** Every route, its hub label, and the scope that gates it (null = no scope). */
internal val screenLinks: List<Triple<String, String, PairingScope?>> = listOf(
    Triple(Route.Pair, "Pair host", null),
    Triple(Route.Sessions, "Sessions", PairingScope.MANAGE_SESSIONS),
    Triple(Route.Chat, "Chat", PairingScope.SEND_CHAT),
    Triple(Route.ToolMonitor, "Tool monitor", PairingScope.MONITOR_TOOLS),
    Triple(Route.Files, "Files", PairingScope.READ_FILES),
    Triple(Route.Editor, "Editor", PairingScope.WRITE_FILES),
    Triple(Route.Terminal, "Terminal", PairingScope.EXEC_TERMINAL),
    Triple(Route.Sandbox, "Sandbox", PairingScope.MANAGE_SANDBOX),
    Triple(Route.Memory, "Memory", PairingScope.READ_MEMORY),
    Triple(Route.Models, "Models", PairingScope.MANAGE_MODELS),
    Triple(Route.Rant, "Rant", PairingScope.SUBMIT_RANT),
    Triple(Route.Evolution, "Evolution", PairingScope.READ_EVOLUTION),
    Triple(Route.Tasks, "Tasks", PairingScope.MANAGE_TASKS),
    Triple(Route.Skills, "Skills", PairingScope.MANAGE_SKILLS),
    Triple(Route.Logs, "Logs", PairingScope.READ_LOGS),
    Triple(Route.Audit, "Audit", PairingScope.READ_AUDIT),
    Triple(Route.Devices, "Devices", PairingScope.MANAGE_DEVICES),
    Triple(Route.Settings, "Settings", PairingScope.MANAGE_SETTINGS),
    Triple(Route.About, "About", null),
)

internal data class RemoteAgent(
    val name: String,
    val path: String,
    val repo: String,
    val latestSessionAt: String,
)

/**
 * The real host/agent list for the hub, from `hosts.list` (daemon
 * `list_agents`). Shown only when the scope is granted and the socket is
 * authenticated; the caller renders a note otherwise. The outer hub column
 * scrolls, so the list is plain cards, not a nested lazy list.
 */
@Composable
private fun HostsListSection(connection: CompanionConnection) {
    val scope = rememberCoroutineScope()
    var agents by remember { mutableStateOf<List<RemoteAgent>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        error = null
        loading = true
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId) { connection.sendHostsList(requestId) }
            loading = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or hosts.list not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        // Frozen contract: lists arrive as `entries`; the daemon
                        // shape used `agents`. Accept either, entries first.
                        agents = frame.resultObjects("entries", "agents").mapNotNull { row ->
                            val path = row.optNonBlank("path") ?: return@mapNotNull null
                            RemoteAgent(
                                name = row.optNonBlank("name").orEmpty(),
                                path = path,
                                repo = row.optNonBlank("repo").orEmpty(),
                                latestSessionAt = row.optNonBlank("latest_session_at").orEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }

    Text("Hosts (${agents.size})", style = MaterialTheme.typography.titleMedium)
    Button(onClick = { refresh() }, enabled = !loading) {
        Text(if (loading) "Loading…" else "Refresh hosts")
    }
    ScreenError(error)
    if (agents.isEmpty() && !loading) {
        Text("No hosts yet — tap Refresh hosts to load.", style = MaterialTheme.typography.bodySmall)
    }
    agents.forEach { agent ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    agent.name.ifBlank { agent.path.substringAfterLast("/") },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(agent.path, style = MaterialTheme.typography.bodySmall)
                if (agent.repo.isNotBlank()) Text("Repo: ${agent.repo}", style = MaterialTheme.typography.bodySmall)
                if (agent.latestSessionAt.isNotBlank()) {
                    Text("Latest session: ${agent.latestSessionAt}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
