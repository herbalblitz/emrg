package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import ai.emrg.companion.ProtocolStreamAction
import ai.emrg.companion.ProtocolStreamState
import ai.emrg.companion.ToolActivityView
import ai.emrg.companion.reduceProtocolStreams
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/**
 * The `tool-monitor` route — a PASSIVE view, nothing more.
 *
 * It renders tool-activity frames (`tool_start` / `tool_end`) arriving on the
 * chat stream, reduced with the existing [reduceProtocolStreams] reducer and
 * shown with the existing [ToolActivityView]. It has no exec path: no command
 * field, no send call, no repository. (The reference app's ToolMonitorScreen
 * runs a command via gw_exec; that behaviour belongs to Terminal, not here.)
 * Tool activity only appears while a chat turn is running on the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolMonitorScreen(connection: CompanionConnection, navController: NavHostController) {
    var streamState by remember { mutableStateOf(ProtocolStreamState()) }

    // Subscribe to the shared frame flow while this screen is visible. Tool
    // frames name a request id; the reducer only keeps frames for requests it
    // has seen started, so seed a Start for any new request id first.
    LaunchedEffect(connection) {
        connection.frames.collect { frame ->
            if (frame.type == "tool_start" || frame.type == "tool_end") {
                val id = frame.requestId ?: return@collect
                if (id !in streamState.requests) {
                    streamState = reduceProtocolStreams(
                        streamState,
                        ProtocolStreamAction.Start(id, frame.sessionId),
                    )
                }
                streamState = reduceProtocolStreams(
                    streamState,
                    ProtocolStreamAction.Receive(frame),
                )
            }
        }
    }

    val allCalls = streamState.requests.values.flatMap { it.toolCalls }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Tool monitor") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.MONITOR_TOOLS, navController) {
                ScreenNote(
                    "Read-only view of tool activity from live chat turns. " +
                        "This screen cannot run anything — use Terminal to run a command. " +
                        "Start a chat turn and tool calls will appear here.",
                )
                if (allCalls.isEmpty()) {
                    Text("No tool activity yet.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(allCalls) { call ->
                        ToolActivityView(listOf(call), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
