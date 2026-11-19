package ai.emrg.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Local-only pairing UI foundation. It verifies descriptor shape and requires explicit
 * fingerprint confirmation, but intentionally does not claim a connection or issue credentials.
 */
@Suppress("UnsafeOptInUsageError")
@Composable
internal fun SecurePairingPanel() {
    var qrPayload by remember { mutableStateOf("") }
    var scannerVisible by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var pairingId by remember { mutableStateOf<String?>(null) }
    var descriptorVersion by remember { mutableStateOf<Int?>(null) }
    var state by remember { mutableStateOf(PairingState()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Secure host pairing", style = MaterialTheme.typography.titleMedium)
            Text(
                "QR identity validation is available. Live daemon pairing transport is not yet implemented.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { scannerVisible = true }) { Text("Scan QR with camera") }
            OutlinedTextField(
                value = qrPayload,
                onValueChange = { qrPayload = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pairing QR payload (paste)") },
                minLines = 2,
                maxLines = 4
            )
            Button(enabled = qrPayload.isNotBlank(), onClick = {
                when (val parsed = PairingDescriptorParser.parse(qrPayload, System.currentTimeMillis() / 1000)) {
                    is PairingDescriptorResult.Valid -> {
                        endpoint = parsed.descriptor.endpoint
                        fingerprint = parsed.descriptor.fingerprintSha256
                        pairingId = parsed.descriptor.pairingId
                        descriptorVersion = parsed.descriptor.version
                        state = reducePairingState(
                            PairingState(),
                            PairingAction.PairingQrReceived(endpoint, fingerprint, pairingId),
                        )
                    }
                    is PairingDescriptorResult.Invalid -> {
                        state = PairingState(phase = PairingPhase.FAILED, errorCode = parsed.reason)
                    }
                }
            }) { Text("Parse QR payload") }
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Host endpoint (wss://)") },
                singleLine = true
            )
            OutlinedTextField(
                value = fingerprint,
                onValueChange = { fingerprint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("SHA-256 fingerprint (colon-separated)") },
                singleLine = true
            )
            if (descriptorVersion == 2 && pairingId != null) {
                Text("Pairing v2 transaction: $pairingId")
            }
            Button(
                enabled = endpoint.isNotBlank() && fingerprint.isNotBlank(),
                onClick = {
                    state = reducePairingState(
                        PairingState(),
                        PairingAction.PairingQrReceived(endpoint.trim(), fingerprint.trim(), pairingId)
                    )
                }
            ) { Text("Validate descriptor") }

            when (state.phase) {
                PairingPhase.AWAITING_HOST_VERIFICATION -> {
                    Text("Review host identity carefully:", style = MaterialTheme.typography.titleSmall)
                    Text("Endpoint: ${state.candidateEndpoint}")
                    Text(state.candidateFingerprint.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        state = reducePairingState(
                            state,
                            PairingAction.HostIdentityConfirmed(state.candidateFingerprint.orEmpty())
                        )
                    }) { Text("Fingerprint matches — continue") }
                }
                PairingPhase.AWAITING_HOST_APPROVAL -> {
                    Text("Host identity accepted locally. Waiting for approval from the host.")
                    Text(
                        "This device cannot approve its own pairing request. The host must explicitly approve it through the daemon pairing flow.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                PairingPhase.HOST_APPROVED -> {
                    Text("Local approval recorded. No credential received and no host connected.")
                    Text("Waiting for a real daemon handshake implementation.", style = MaterialTheme.typography.bodySmall)
                }
                PairingPhase.FAILED -> {
                    Text("Validation failed: ${state.errorCode ?: "PAIRING_FAILED"}", color = MaterialTheme.colorScheme.error)
                }
                PairingPhase.PAIRED -> Text("Paired", style = MaterialTheme.typography.titleSmall)
                PairingPhase.REVOKED -> Text("Pairing revoked")
                PairingPhase.DISCONNECTED -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    state = PairingState()
                    qrPayload = ""
                    endpoint = ""
                    fingerprint = ""
                }) { Text("Reset") }
            }
        }
    }
    if (scannerVisible) {
        CameraQrScannerDialog(
            onQrDetected = { value -> qrPayload = value; scannerVisible = false },
            onDismiss = { scannerVisible = false }
        )
    }
}
