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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
 *
 * The live socket is owned by the shared [CompanionConnection] passed in, so every
 * screen sees the same connection. Pairing v2, TLS pinning, the 180 s QR expiry and
 * the auth envelope/nonce are untouched by the scope picker: the picker only
 * changes which of the 20 frozen-contract scopes the signed request asks for.
 */
@Suppress("UnsafeOptInUsageError")
@Composable
internal fun SecurePairingPanel(
    connection: CompanionConnection,
    onConnectionChanged: (paired: Boolean, state: RemoteSocketState) -> Unit = { _, _ -> },
    /**
     * Re-pair prefill: endpoint + fingerprint of an already-known host.
     * Only a display hint — the fresh QR still supplies the pairing id and
     * its own fingerprint, which the verify step checks. Null = fresh pairing.
     */
    prefillEndpoint: String? = null,
    prefillFingerprint: String? = null,
    /** Scopes pre-ticked when the panel opens; null = least-privilege status-only default. */
    initialScopes: Set<PairingScope>? = null,
    /**
     * True when re-pairing a host that is already connected: clearing a
     * pairing transaction must not drop the live socket or the stored
     * credential — the existing pairing stays until the new one succeeds.
     */
    preserveConnection: Boolean = false,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var qrPayload by remember { mutableStateOf("") }
    var scannerVisible by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf(prefillEndpoint.orEmpty()) }
    var fingerprint by remember { mutableStateOf(prefillFingerprint.orEmpty()) }
    var pairingId by remember { mutableStateOf<String?>(null) }
    var descriptorVersion by remember { mutableStateOf<Int?>(null) }
    var state by remember { mutableStateOf(PairingState()) }
    var controller by remember { mutableStateOf<PairingV2SessionController?>(null) }
    var challenge by remember { mutableStateOf<PairingV2Challenge?>(null) }
    var activeHandle by remember { mutableStateOf<PairingV2ExchangeHandle?>(null) }
    var remoteStateText by remember { mutableStateOf("Not connected") }
    var statusText by remember { mutableStateOf("Scan or paste a host pairing v2 QR descriptor.") }
    var busy by remember { mutableStateOf(false) }
    // Fresh pairing requests read-only status only. Additional capabilities are
    // explicit opt-ins and must also fit within the host's configured scope grant.
    // Re-pair can preselect the existing grants plus a scope the user chose to add.
    var selectedScopes by remember {
        mutableStateOf(resolveInitialPairingScopes(initialScopes))
    }
    // Ignore callbacks queued by an older QR transaction after reset or rescan.
    var transactionGeneration by remember { mutableStateOf(0) }

    val remoteConnectionState by connection.state.collectAsState()
    val statusResultText by connection.lastStatusResult.collectAsState()
    val sessionsResultText by connection.lastSessionsResult.collectAsState()

    fun cancelActiveExchange() {
        activeHandle?.cancel()
        activeHandle = null
        busy = false
    }

    fun clearTransaction() {
        transactionGeneration += 1
        cancelActiveExchange()
        controller = null
        challenge = null
        if (!preserveConnection) {
            connection.disconnect()
            remoteStateText = "Not connected"
            onConnectionChanged(false, RemoteSocketState.CLOSED)
        }
        state = PairingState()
        statusText = "Pairing transaction cleared."
    }

    val latestHandle by rememberUpdatedState(activeHandle)
    DisposableEffect(Unit) {
        onDispose {
            latestHandle?.cancel()
        }
    }

    // Publish the shared socket state to the shell and keep the status line honest.
    // Only report a lost connection once a pairing exists; on first composition
    // the socket is simply not open yet.
    LaunchedEffect(remoteConnectionState) {
        remoteStateText = remoteConnectionState.name
        onConnectionChanged(state.phase == PairingPhase.PAIRED, remoteConnectionState)
        if (state.phase == PairingPhase.PAIRED &&
            (remoteConnectionState == RemoteSocketState.CLOSED ||
                remoteConnectionState == RemoteSocketState.FAILED ||
                remoteConnectionState == RemoteSocketState.AUTH_REJECTED ||
                remoteConnectionState == RemoteSocketState.IDENTITY_REJECTED)
        ) {
            statusText = "Pairing is stored; live host connection is ${remoteConnectionState.name.lowercase()}."
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
            val scopes = selectedScopes
            val protocolScopes = scopes.mapNotNull { it.wireName }.toSet()
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

    fun connectRemoteHost(host: PairedHost) {
        try {
            connection.connect(host)
            statusText = "Pairing saved. Establishing pinned, authenticated connection…"
        } catch (_: Exception) {
            statusText = "Pairing is saved, but the live host connection could not be started."
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
                connectRemoteHost(host)
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
            if (!prefillEndpoint.isNullOrBlank()) {
                Text(
                    "Re-pairing known host $prefillEndpoint. Your existing pairing " +
                        "and live connection stay untouched until the new pairing succeeds. " +
                        "Scan a fresh QR from this host — the QR supplies the new pairing transaction.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
                        // Re-pair guard: a QR for a different host than the one
                        // being re-paired is almost certainly the wrong QR.
                        // Warn loudly; the fingerprint-verify step still gates
                        // on the scanned QR itself, so this never weakens it.
                        if (!prefillEndpoint.isNullOrBlank() &&
                            (!parsed.descriptor.endpoint.equals(prefillEndpoint, ignoreCase = true) ||
                                !parsed.descriptor.fingerprintSha256.equals(prefillFingerprint.orEmpty(), ignoreCase = true))
                        ) {
                            statusText = "Warning: this QR is for ${parsed.descriptor.endpoint}, " +
                                "not the host being re-paired ($prefillEndpoint). " +
                                "Check you scanned the right host's QR. " + statusText
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
            if (descriptorVersion == 2 && pairingId != null && state.phase == PairingPhase.AWAITING_HOST_VERIFICATION) {
                Text("Scopes to request", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Default: read-only status. Select only the capabilities this device needs. " +
                        "The host must have enabled each selected scope; requesting a scope " +
                        "outside its configured grant fails pairing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { selectedScopes = PairingScope.REQUESTABLE.toSet() },
                        enabled = !busy && selectedScopes.size < PairingScope.REQUESTABLE.size,
                    ) { Text("Select all") }
                    Button(
                        onClick = { selectedScopes = setOf(PairingScope.READ_STATUS) },
                        enabled = !busy && selectedScopes != setOf(PairingScope.READ_STATUS),
                    ) { Text("Status only") }
                }
                PairingScope.GROUPED_BY_SCREEN.forEach { (group, scopes) ->
                    Text(group, style = MaterialTheme.typography.titleSmall)
                    scopes.forEach { scope ->
                        val checked = scope in selectedScopes
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                // At least one scope must stay selected.
                                enabled = !busy && (!checked || selectedScopes.size > 1),
                                onCheckedChange = { want ->
                                    selectedScopes = if (want) selectedScopes + scope
                                    else if (selectedScopes.size > 1) selectedScopes - scope else selectedScopes
                                },
                            )
                            Column {
                                Text(scope.wireName ?: scope.name, style = MaterialTheme.typography.bodyMedium)
                                Text(scope.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (state.phase == PairingPhase.AWAITING_HOST_APPROVAL || state.phase == PairingPhase.PAIRED) {
                Text(
                    "Requested scopes: " + selectedScopes.mapNotNull { it.wireName }.sorted().joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                )
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
                        enabled = canReconnectRemoteHost(remoteConnectionState),
                        onClick = {
                            state.pairedHost?.let(::connectRemoteHost)
                        },
                    ) { Text("Reconnect") }
                    Button(
                        onClick = {
                            connection.disconnect()
                            remoteStateText = "Disconnected"
                            onConnectionChanged(true, RemoteSocketState.CLOSED)
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
                    selectedScopes = PairingScope.DEFAULT_REQUESTED
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

internal fun canReconnectRemoteHost(state: RemoteSocketState): Boolean =
    state != RemoteSocketState.CONNECTING &&
        state != RemoteSocketState.TLS_IDENTITY_VERIFIED &&
        state != RemoteSocketState.AUTHENTICATING &&
        state != RemoteSocketState.AUTHENTICATED

internal fun canRefreshRemoteHost(state: RemoteSocketState): Boolean =
    state == RemoteSocketState.AUTHENTICATED

private fun itStatePhase(controller: PairingV2SessionController): PairingPhase =
    // Controller intentionally exposes only operations; pre-credential state is fixed.
    PairingPhase.AWAITING_HOST_APPROVAL
