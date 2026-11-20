package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import ai.emrg.companion.ProtocolFrame
import ai.emrg.companion.RemoteSocketState
import ai.emrg.companion.ui.navigation.Route
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gate for a screen that needs one granted scope. When the host is not connected
 * or the scope was not granted at pair time, the screen shows why and offers the
 * re-pair route instead of a dead control. Never silently sends a frame the host
 * would reject.
 */
@Composable
internal fun ScopeGate(
    connection: CompanionConnection,
    scope: PairingScope,
    navController: NavHostController,
    content: @Composable () -> Unit,
) {
    val state by connection.state.collectAsState()
    val pairedHost by connection.pairedHost.collectAsState()
    val granted = connection.hasScope(scope)
    if (pairedHost == null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No paired host", style = MaterialTheme.typography.titleSmall)
                Text("Pair with the host first.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { navController.navigate(Route.Pair) }) { Text("Pair") }
            }
        }
        return
    }
    if (state != RemoteSocketState.AUTHENTICATED) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Host is ${state.name.lowercase()}", style = MaterialTheme.typography.titleSmall)
                Text("Reconnect from the Pair screen to use this feature.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { navController.navigate(Route.Pair) }) { Text("Go to Pair") }
            }
        }
        return
    }
    if (!granted) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scope not granted: ${scope.wireName}", style = MaterialTheme.typography.titleSmall)
                Text(scope.description, style = MaterialTheme.typography.bodySmall)
                Text(
                    "This host did not grant this scope when the device was paired. " +
                        "Re-pair and tick this scope to enable it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { navController.navigate(Route.Pair) }) { Text("Re-pair with this scope") }
            }
        }
        return
    }
    content()
}

/** A short note card shown at the top of a screen. */
@Composable
internal fun ScreenNote(text: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ScreenError(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    Text(
        text,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Read a string field from a result frame, tolerating a missing key. */
internal fun ProtocolFrame.optResultString(key: String): String? =
    raw.optString(key).takeIf { it.isNotBlank() }

/** Read a boolean field from a result frame. */
internal fun ProtocolFrame.optResultBoolean(key: String): Boolean? =
    if (raw.has(key) && !raw.isNull(key)) raw.optBoolean(key) else null

/** Read an array field from a result frame as JSONObjects, tolerating shape drift. */
internal fun ProtocolFrame.resultObjects(vararg keys: String): List<JSONObject> {
    for (key in keys) {
        val array = raw.optJSONArray(key) ?: continue
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
    return emptyList()
}

/** Read an array field as plain strings (some list results use bare strings). */
internal fun ProtocolFrame.resultStrings(vararg keys: String): List<String> {
    for (key in keys) {
        val array = raw.optJSONArray(key) ?: continue
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
    }
    return emptyList()
}

internal fun JSONObject.optNonBlank(key: String): String? = optString(key).takeIf { it.isNotBlank() }

internal fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
