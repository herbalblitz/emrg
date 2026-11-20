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
import androidx.compose.runtime.rememberUpdatedState
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
    var remoteSocket by remember { mutableStateOf<PinnedRemoteWebSocket?>(null) }
    var remoteStateText by remember { mutableStateOf("Not connected") }
    var statusResultText by remember { mutableStateOf<String?>(null) }
    var sessionsResultText by remember { mutableStateOf<String?>(null) }
    var statusRequestId by remember { mutableStateOf<String?>(null) }
    var sessionsRequestId by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Scan or paste a host pairing v2 QR descriptor.") }
    var busy by remember { mutableStateOf(false) }
    // Ignore callbacks queued by an older QR transaction after reset or rescan.
    var transactionGeneration by remember { mutableStateOf(0) }
    // Ignore callbacks from a superseded live socket, even within the same pairing transaction.
    var remoteConnectionGeneration by remember { mutableStateOf(0) }

    fun cancelActiveExchange() {
        activeHandle?.cancel()
        activeHandle = null
        busy = false
    }

    fun clearTransaction() {
        transactionGeneration += 1
        remoteConnectionGeneration += 1
        cancelActiveExchange()
        controller = null
        challenge = null
        remoteSocket?.close()
        remoteSocket = null
        remoteStateText = "Not connected"
        statusResultText = null
        sessionsResultText = null
        statusRequestId = null
        sessionsRequestId = null
        state = PairingState()
        statusText = "Pairing transaction cleared."
    }

    val latestHandle by rememberUpdatedState(activeHandle)
    val latestRemoteSocket by rememberUpdatedState(remoteSocket)
    DisposableEffect(Unit) {
        onDispose {
            latestHandle?.cancel()
            latestRemoteSocket?.close()
        }
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
            val scopes = setOf(PairingScope.READ_STATUS, PairingScope.LIST_SESSIONS)
            val protocolScopes = setOf("status", "sessions.list")
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

    fun sessionListener(): PairingV2SessionListener {
        val callbackGeneration = transactionGeneration
        return object : PairingV2SessionListener {
        private fun isCurrentTransaction(): Boolean = callbackGeneration == transactionGeneration

        override fun onChallenge(value: PairingV2Challenge) {
            mainHandler.post {
                if (!isCurrentTransaction()) return@post
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
                if (!isCurrentTransaction()) return@post
                busy = false
                state = PairingState(phase = PairingPhase.PAIRED, pairedHost = host)
                statusText = "Paired securely. Credential stored in Android Keystore-backed storage."
                statusResultText = null
                sessionsResultText = null
                statusRequestId = UUID.randomUUID().toString()
                sessionsRequestId = UUID.randomUUID().toString()
                remoteConnectionGeneration += 1
                val callbackRemoteGeneration = remoteConnectionGeneration
                val socket = PinnedRemoteWebSocket(
                    credentialStore = AndroidKeystoreCredentialStore(context),
                    listener = object : RemoteSocketListener {
                        override fun onState(state: RemoteSocketState, safeMessage: String?) {
                            mainHandler.post {
                                if (!isCurrentTransaction() || callbackRemoteGeneration != remoteConnectionGeneration) return@post
                                remoteStateText = safeMessage ?: state.name
                                if (state == RemoteSocketState.AUTHENTICATED) {
                                    val current = remoteSocket
                                    val statusId = statusRequestId
                                    val sessionsId = sessionsRequestId
                                    if (current != null && statusId != null && sessionsId != null) {
                                        current.sendStatus(statusId)
                                        current.sendSessionsList(sessionsId)
                                    }
                                }
                                if (state == RemoteSocketState.CLOSED ||
                                    state == RemoteSocketState.FAILED ||
                                    state == RemoteSocketState.AUTH_REJECTED ||
                                    state == RemoteSocketState.IDENTITY_REJECTED
                                ) {
                                    statusText = "Pairing is stored; live host connection is ${state.name.lowercase()}."
                                }
                            }
                        }

                        override fun onFrame(frame: ProtocolFrame) {
                            mainHandler.post {
                                if (!isCurrentTransaction() || callbackRemoteGeneration != remoteConnectionGeneration) return@post
                                when (frame.type) {
                                    "status.result" -> {
                                        if (frame.requestId == statusRequestId) {
                                            statusResultText = frame.raw.toString().take(4096)
                                        }
                                    }
                                    "sessions.result" -> {
                                        if (frame.requestId == sessionsRequestId) {
                                            sessionsResultText = frame.raw.toString().take(16 * 1024)
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
                remoteSocket?.close()
                remoteSocket = socket
                try {
                    socket.connect(host)
                    statusText = "Pairing saved. Establishing pinned, authenticated read-only connection…"
                } catch (_: Exception) {
                    remoteStateText = "Connection could not be started"
                    statusText = "Pairing is saved, but the live host connection could not be started."
                }
            }
        }

        override fun onFailure(safeCode: String) {
            mainHandler.post {
                if (!isCurrentTransaction()) return@post
                busy = false
                statusText = if (safeCode == "PAIRING_UNAVAILABLE") {
                    "Host has not approved yet (or pairing is unavailable). You can retry; the original pairing request and signed proof are reused."
                } else {
                    "Pairing failed: $safeCode"
                }
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
                PairingPhase.PAIRED -> {
                    Text("Paired securely", style = MaterialTheme.typography.titleSmall)
                    Text("Live host: $remoteStateText", style = MaterialTheme.typography.bodySmall)
                    if (statusResultText != null) {
                        Text("Host status", style = MaterialTheme.typography.titleSmall)
                        Text(statusResultText!!, style = MaterialTheme.typography.bodySmall)
                    }
                    if (sessionsResultText != null) {
                        Text("Read-only sessions", style = MaterialTheme.typography.titleSmall)
                        Text(sessionsResultText!!, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        enabled = remoteStateText == RemoteSocketState.AUTHENTICATED.name,
                        onClick = {
                            statusRequestId = UUID.randomUUID().toString()
                            sessionsRequestId = UUID.randomUUID().toString()
                            statusResultText = null
                            sessionsResultText = null
                            val current = remoteSocket ?: return@Button
                            current.sendStatus(statusRequestId!!)
                            current.sendSessionsList(sessionsRequestId!!)
                        },
                    ) { Text("Refresh host status and sessions") }
                    Button(
                        enabled = remoteSocket != null,
                        onClick = {
                            // Invalidate queued close/frame callbacks before closing the user-disconnected socket.
                            remoteConnectionGeneration += 1
                            remoteSocket?.close()
                            remoteSocket = null
                            remoteStateText = "Disconnected"
                        },
                    ) { Text("Disconnect") }
                }
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
