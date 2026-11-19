package ai.emrg.companion

import androidx.camera.core.ExperimentalGetImage
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.util.UUID

/**
 * Pairing v2 UI. Host approval remains authoritative; this panel only submits
 * the signed device proof and stores a credential after the controller validates it.
 */
@Suppress("UnsafeOptInUsageError")
@Composable
internal fun SecurePairingPanel() {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var qrPayload by remember { mutableStateOf("") }
    var scannerVisible by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var pairingId by remember { mutableStateOf<String?>(null) }
    var descriptorVersion by remember { mutableStateOf<Int?>(null) }
    var state by remember { mutableStateOf(PairingState()) }
    var controller by remember { mutableStateOf<PairingV2SessionController?>(null) }
    var challenge by remember { mutableStateOf<PairingV2Challenge?>(null) }
    var activeHandle by remember { mutableStateOf<PairingV2ExchangeHandle?>(null) }
    var statusText by remember { mutableStateOf("Scan or paste a host pairing v2 QR descriptor.") }
    var busy by remember { mutableStateOf(false) }

    fun cancelActiveExchange() {
        activeHandle?.cancel()
        activeHandle = null
        busy = false
    }

    fun clearTransaction() {
        cancelActiveExchange()
        controller = null
        challenge = null
        state = PairingState()
        statusText = "Pairing transaction cleared."
    }

    DisposableEffect(Unit) {
        onDispose { activeHandle?.cancel() }
    }

    fun createController(): PairingV2SessionController? {
        val id = pairingId ?: return null
        if (descriptorVersion != 2) return null
        return try {
            val preferences = context.getSharedPreferences("emrg_pairing_identity", android.content.Context.MODE_PRIVATE)
            val deviceId = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                check(preferences.edit().putString("device_id", it).commit())
            }
            val keyAlias = "emrg_device_" + deviceId.replace("-", "")
            val credentialAlias = "host_" + MessageDigest.getInstance("SHA-256")
                .digest((endpoint + "|" + fingerprint + "|" + id).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(48)
            val scopes = setOf(PairingScope.READ_STATUS)
            val protocolScopes = setOf("status")
            val client = PairingV2Client(
                keys = AndroidKeystoreDeviceKeyManager(),
                deviceId = deviceId,
                keyAlias = keyAlias,
                requestedScopes = protocolScopes,
                expectedHostIdentity = fingerprint,
            )
            val transport = PairingV2Flow(
                endpoint = endpoint,
                fingerprintSha256 = fingerprint,
                pairingId = id,
                client = client,
                exchange = PairingV2WssExchange(),
            )
            val coordinator = SecurePairingCoordinator(AndroidKeystoreCredentialStore(context))
            coordinator.dispatch(PairingAction.PairingQrReceived(endpoint, fingerprint, id))
            coordinator.dispatch(PairingAction.HostIdentityConfirmed(fingerprint))
            PairingV2SessionController(
                coordinator = coordinator,
                transport = transport,
                endpoint = endpoint,
                fingerprintSha256 = fingerprint,
                pairingId = id,
                deviceId = deviceId,
                credentialAlias = credentialAlias,
                scopes = scopes,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun sessionListener(): PairingV2SessionListener = object : PairingV2SessionListener {
        override fun onChallenge(value: PairingV2Challenge) {
            mainHandler.post {
                challenge = value
                busy = false
                statusText = "Challenge received. Submit the signed proof; the host must approve it locally."
                state = controller?.let { itState ->
                    // The coordinator remains awaiting host approval until a valid host credential arrives.
                    PairingState(
                        phase = itStatePhase(itState),
                        candidateEndpoint = endpoint,
                        candidateFingerprint = fingerprint.uppercase(),
                        candidatePairingId = pairingId,
                    )
                } ?: state
            }
        }

        override fun onPaired(host: PairedHost) {
            mainHandler.post {
                busy = false
                state = PairingState(phase = PairingPhase.PAIRED, pairedHost = host)
                statusText = "Paired securely. Credential stored in Android Keystore-backed storage."
            }
        }

        override fun onFailure(safeCode: String) {
            mainHandler.post {
                busy = false
                statusText = if (safeCode == "PAIRING_UNAVAILABLE") {
                    "Host has not approved yet (or pairing is unavailable). You can retry; the signed proof is reused."
                } else {
                    "Pairing failed: $safeCode"
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Secure host pairing", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pairing v2 uses a QR-pinned WSS connection and a non-exportable Android Keystore device key. Host approval is always performed on the host.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { scannerVisible = true }, enabled = !busy) { Text("Scan QR with camera") }
            OutlinedTextField(
                value = qrPayload,
                onValueChange = {
                    qrPayload = it
                    endpoint = ""
                    fingerprint = ""
                    pairingId = null
                    descriptorVersion = null
                    clearTransaction()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pairing QR payload (paste)") },
                minLines = 2,
                maxLines = 4,
                enabled = !busy
            )
            Button(enabled = qrPayload.isNotBlank() && !busy, onClick = {
                clearTransaction()
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
                        statusText = if (parsed.descriptor.version == 2) {
                            "QR v2 validated. Verify the host fingerprint before connecting."
                        } else {
                            "QR v1 is valid for identity display only; live Pairing v2 requires a v2 QR."
                        }
                    }
                    is PairingDescriptorResult.Invalid -> {
                        endpoint = ""
                        fingerprint = ""
                        pairingId = null
                        descriptorVersion = null
                        state = PairingState(phase = PairingPhase.FAILED, errorCode = parsed.reason)
                        statusText = "QR validation failed: ${parsed.reason}"
                    }
                }
            }) { Text("Parse QR payload") }

            if (endpoint.isNotBlank()) {
                Text("Endpoint: $endpoint", style = MaterialTheme.typography.bodySmall)
                Text("SHA-256: $fingerprint", style = MaterialTheme.typography.bodySmall)
            }
            if (descriptorVersion == 2 && pairingId != null) {
                Text("Pairing v2 transaction is ready.", style = MaterialTheme.typography.bodySmall)
            }
            when (state.phase) {
                PairingPhase.AWAITING_HOST_VERIFICATION -> {
                    Text("Verify this fingerprint against the host's trusted display:", style = MaterialTheme.typography.titleSmall)
                    Button(
                        enabled = descriptorVersion == 2 && pairingId != null && !busy,
                        onClick = {
                            val next = reducePairingState(
                                state,
                                PairingAction.HostIdentityConfirmed(state.candidateFingerprint.orEmpty())
                            )
                            if (next.phase == PairingPhase.AWAITING_HOST_APPROVAL) {
                                state = next
                                controller = createController()
                                if (controller == null) {
                                    state = PairingState(phase = PairingPhase.FAILED, errorCode = "PAIRING_SETUP_FAILED")
                                    statusText = "Could not initialize secure pairing. Check device Keystore support and QR details."
                                } else {
                                    statusText = "Host identity verified. Request a signed challenge to begin pairing."
                                }
                            } else {
                                state = next
                            }
                        }
                    ) { Text("Fingerprint matches — continue") }
                }
                PairingPhase.AWAITING_HOST_APPROVAL -> {
                    Text("Waiting for host approval. This device cannot approve itself.")
                    Button(
                        enabled = controller != null && !busy && challenge == null,
                        onClick = {
                            val current = controller ?: return@Button
                            busy = true
                            statusText = "Requesting host challenge…"
                            activeHandle = current.requestPairingChallenge(sessionListener())
                        }
                    ) { Text("Request pairing challenge") }
                    Button(
                        enabled = controller != null && challenge != null && !busy,
                        onClick = {
                            val current = controller ?: return@Button
                            busy = true
                            statusText = "Submitting signed proof; waiting for host approval…"
                            activeHandle = current.redeemChallenge(sessionListener())
                        }
                    ) { Text(if (challenge == null) "Signed proof unavailable" else "Submit proof / retry approval") }
                }
                PairingPhase.HOST_APPROVED -> Text("Host approval validated.")
                PairingPhase.FAILED -> Text("Pairing setup failed: ${state.errorCode ?: "PAIRING_FAILED"}", color = MaterialTheme.colorScheme.error)
                PairingPhase.PAIRED -> Text("Paired securely", style = MaterialTheme.typography.titleSmall)
                PairingPhase.REVOKED -> Text("Pairing revoked")
                PairingPhase.DISCONNECTED -> Unit
            }
            Text(statusText, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    clearTransaction()
                    qrPayload = ""
                    endpoint = ""
                    fingerprint = ""
                    pairingId = null
                    descriptorVersion = null
                }, enabled = !busy) { Text("Reset") }
            }
        }
    }
    if (scannerVisible) {
        CameraQrScannerDialog(
            onQrDetected = { value ->
                qrPayload = value
                scannerVisible = false
                endpoint = ""
                fingerprint = ""
                pairingId = null
                descriptorVersion = null
                clearTransaction()
            },
            onDismiss = { scannerVisible = false }
        )
    }
}

private fun itStatePhase(controller: PairingV2SessionController): PairingPhase =
    // Controller intentionally exposes only operations; pre-credential state is fixed.
    PairingPhase.AWAITING_HOST_APPROVAL
