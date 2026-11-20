package ai.emrg.companion.ui.screens

import ai.emrg.companion.BuildConfig
import ai.emrg.companion.ui.navigation.Route
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/** The `about` route. Local only — no host call, no scope. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreen(navController: NavHostController) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("About") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("EMRG Android companion", style = MaterialTheme.typography.titleMedium)
            Text("App version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            Text("Package ${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Pairs with a Windows EMRG host over pinned WSS using Pairing v2 " +
                    "(P-256 device key, scope binding, QR descriptor expires after 180 s). " +
                    "Every screen only sends frames its granted scopes allow.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { navController.navigate(Route.Hosts) }) { Text("Back to Hosts") }
        }
    }
}
