package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/**
 * Placeholder for a Batch B/C route whose screen is not built yet. The scope is
 * already in the registry, so the screen can say exactly what it will need.
 * This is a navigation stub, not the screen itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotYetAvailableScreen(
    label: String,
    scope: PairingScope,
    connection: CompanionConnection,
    navController: NavHostController,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(label) }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenNote(
                "$label is scheduled for a later batch. It will need the scope " +
                    "${scope.wireName} (${scope.description})",
            )
            ScopeGate(connection, scope, navController) {
                Text("The scope is granted, but this screen is not built yet.")
            }
            Button(onClick = { navController.navigate(Route.Hosts) }) { Text("Back to Hosts") }
        }
    }
}
