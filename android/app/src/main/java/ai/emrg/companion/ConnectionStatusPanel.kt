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

/** Main-screen connection status backed by the real paired-host WSS lifecycle. */
@Composable
internal fun ConnectionStatusPanel(paired: Boolean, state: RemoteSocketState) {
    val label = when {
        !paired -> "Disconnected"
        state == RemoteSocketState.AUTHENTICATED -> "Connected"
        state == RemoteSocketState.CONNECTING -> "Connecting"
        state == RemoteSocketState.TLS_IDENTITY_VERIFIED -> "Verifying host"
        state == RemoteSocketState.AUTHENTICATING -> "Authenticating"
        state == RemoteSocketState.AUTH_REJECTED -> "Authentication failed"
        state == RemoteSocketState.IDENTITY_REJECTED -> "Host identity mismatch"
        state == RemoteSocketState.FAILED -> "Connection failed"
        else -> "Disconnected"
    }
    val description = when {
        !paired -> "No paired Windows host. Scan or paste its QR descriptor to begin secure pairing."
        state == RemoteSocketState.AUTHENTICATED ->
            "Windows host is authenticated over pinned WSS. Host status and read-only session metadata are available; chat is still local demo."
        state == RemoteSocketState.CONNECTING -> "Opening the pinned secure connection to the paired Windows host."
        state == RemoteSocketState.TLS_IDENTITY_VERIFIED -> "The Windows host certificate matches the QR-pinned identity."
        state == RemoteSocketState.AUTHENTICATING -> "Secure transport is open; validating the device credential with Windows."
        state == RemoteSocketState.AUTH_REJECTED -> "Windows rejected the paired-device credential. Re-pairing may be required."
        state == RemoteSocketState.IDENTITY_REJECTED -> "The host certificate no longer matches the saved QR identity. Do not continue; verify and pair again."
        state == RemoteSocketState.FAILED -> "The secure host connection failed. Check that the Windows companion listener is running, then reconnect."
        else -> "Pairing is saved on this device, but no live Windows connection is open."
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Host connection · $label", style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Text(
                if (paired && state == RemoteSocketState.AUTHENTICATED)
                    "Live WSS transport · read-only host access"
                else
                    "No authenticated live transport",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
