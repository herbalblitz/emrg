package ai.emrg.companion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun connectionPhaseLabel(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.DISCONNECTED -> "Disconnected"
    ConnectionPhase.CONNECTING -> "Connecting"
    ConnectionPhase.AUTHENTICATING -> "Authenticating"
    ConnectionPhase.RECONCILING -> "Reconcile host state"
    ConnectionPhase.READY -> "Ready"
    ConnectionPhase.STATE_UNKNOWN -> "Host state unknown"
    ConnectionPhase.RETRY_WAIT -> "Waiting to retry"
    ConnectionPhase.AUTH_FAILED -> "Authentication failed"
    ConnectionPhase.IDENTITY_FAILED -> "Host identity mismatch"
}

internal fun connectionPhaseDescription(state: ReconnectionState): String = when (state.phase) {
    ConnectionPhase.DISCONNECTED -> "No daemon connection. Pairing and transport are not active."
    ConnectionPhase.CONNECTING -> "Connection attempt in progress."
    ConnectionPhase.AUTHENTICATING -> "Verifying host authentication."
    ConnectionPhase.RECONCILING -> "Authenticated; synchronizing state before enabling controls."
    ConnectionPhase.READY -> "Host snapshot reconciled. Controls may be enabled by a future transport integration."
    ConnectionPhase.STATE_UNKNOWN -> "Synchronization failed. Remote task state is unknown; do not retry non-idempotent actions."
    ConnectionPhase.RETRY_WAIT -> state.retryDelayMillis?.let { "Retry scheduled in ${it / 1000} seconds." } ?: "Reconnect is waiting."
    ConnectionPhase.AUTH_FAILED -> state.lastError ?: "Authentication rejected. Pair again to continue."
    ConnectionPhase.IDENTITY_FAILED -> state.lastError ?: "Host identity changed. Re-pairing is required."
}

@Composable
internal fun ConnectionStatusPanel(state: ReconnectionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Host connection · ${connectionPhaseLabel(state.phase)}", style = MaterialTheme.typography.titleSmall)
            Text(connectionPhaseDescription(state), style = MaterialTheme.typography.bodySmall)
            Text("Connection state model only · no live transport", style = MaterialTheme.typography.labelSmall)
        }
    }
}
