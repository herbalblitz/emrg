package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.ConnectionStatusPanel
import ai.emrg.companion.PairingScope
import ai.emrg.companion.RemoteSocketState
import ai.emrg.companion.ui.navigation.Route
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/**
 * The `hosts` route — navigation hub for the 20 screens.
 *
 * Batch 0 spine only: it shows the live connection, the scopes this device was
 * granted, and a link to every screen. Batch C replaces the workspace-listing
 * part with the real host list (hosts.list → list_agents); the hub links stay.
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
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
