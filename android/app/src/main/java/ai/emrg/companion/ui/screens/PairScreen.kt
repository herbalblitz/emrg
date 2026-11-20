package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.RemoteSocketState
import ai.emrg.companion.SecurePairingPanel
import ai.emrg.companion.ui.navigation.Route
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/** The `pair` route: the pairing panel, extracted from the old single screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairScreen(connection: CompanionConnection, navController: NavHostController) {
    val pairedHost by connection.pairedHost.collectAsState()
    val state by connection.state.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Pair host") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecurePairingPanel(connection = connection) { _, _ -> }
            if (pairedHost != null && state == RemoteSocketState.AUTHENTICATED) {
                Button(onClick = {
                    navController.navigate(Route.Hosts) {
                        popUpTo(Route.Pair) { inclusive = true }
                    }
                }) { Text("Open Hosts") }
            }
        }
    }
}
