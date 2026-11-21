package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
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

/**
 * The `pair` route: the pairing panel, extracted from the old single screen.
 *
 * Standalone NavHost destination, reachable two ways: as the start
 * destination when no host is paired, and from Hosts (fresh "Pair new host"
 * or "Re-pair this host" for a known host). Re-pair pre-fills the known
 * host's endpoint/fingerprint and pre-ticks the scopes it already granted
 * (plus [requestedScope] when the user came from a scope gate); the fresh
 * QR still supplies the new pairing transaction, and the existing pairing
 * stays live until the new one succeeds. A fresh pairing pre-ticks read-only status only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairScreen(
    connection: CompanionConnection,
    navController: NavHostController,
    prefillEndpoint: String? = null,
    prefillFingerprint: String? = null,
    requestedScope: String? = null,
) {
    val pairedHost by connection.pairedHost.collectAsState()
    val isRePair = !prefillEndpoint.isNullOrBlank()
    // Pre-tick the known host's granted scopes (plus the scope the user came
    // to add) only when the prefill really is the paired host; otherwise the
    // panel default (read-only status) applies.
    val initialScopes: Set<PairingScope>? =
        if (isRePair && pairedHost != null && pairedHost!!.endpoint == prefillEndpoint) {
            val extra = requestedScope?.let { PairingScope.fromWireName(it) }
            (pairedHost!!.scopes + listOfNotNull(extra))
                .filter { it in PairingScope.REQUESTABLE }.toSet()
                .ifEmpty { null }
        } else {
            null
        }
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (isRePair) "Re-pair host" else "Pair host") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecurePairingPanel(
                connection = connection,
                prefillEndpoint = prefillEndpoint,
                prefillFingerprint = prefillFingerprint,
                initialScopes = initialScopes,
                preserveConnection = isRePair && pairedHost != null,
            )
            if (pairedHost != null) {
                Button(onClick = {
                    // Back to the Hosts entry already on the stack when we
                    // arrived from there; otherwise (Pair is the start
                    // destination) navigate there and drop Pair.
                    if (!navController.popBackStack(Route.Hosts, inclusive = false)) {
                        navController.navigate(Route.Hosts) {
                            popUpTo(Route.Pair) { inclusive = true }
                        }
                    }
                }) { Text(if (isRePair) "Back to Hosts" else "Open Hosts") }
            }
        }
    }
}
