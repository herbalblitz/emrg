package ai.emrg.companion

/** Events emitted after the pairing state machine and encrypted credential store respond. */
internal interface PairingV2SessionListener {
    fun onChallenge(challenge: PairingV2Challenge)
    fun onPaired(host: PairedHost)
    fun onFailure(safeCode: String)
}

/**
 * Binds the QR transaction, Pairing v2 transport, state reducer, and encrypted credential
 * store. Only status and session-list scopes are enabled here; chat execution remains
 * disabled until its workspace/cwd authorization contract is implemented.
 */
internal class PairingV2SessionController(
    private val coordinator: SecurePairingCoordinator,
    private val transport: PairingV2SessionTransport,
    private val endpoint: String,
    private val fingerprintSha256: String,
    private val pairingId: String,
    private val deviceId: String,
    private val credentialAlias: String,
    scopes: Set<PairingScope> = setOf(PairingScope.READ_STATUS),
) {
    private val scopes: Set<PairingScope> = scopes.toSet()
    private var challenge: PairingV2Challenge? = null

    init {
        require(isSecurePairingEndpoint(endpoint))
        require(isSha256Fingerprint(fingerprintSha256))
        require(AndroidKeystoreCredentialStore.isValidCredentialAlias(credentialAlias))
        require(deviceId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(endpoint == transport.endpoint &&
            fingerprintSha256.equals(transport.fingerprintSha256, ignoreCase = true) &&
            pairingId == transport.pairingId &&
            deviceId == transport.deviceId
        ) { "Session metadata must match the pinned pairing transport identity" }
        require(this.scopes.isNotEmpty() && this.scopes.all {
            it == PairingScope.READ_STATUS || it == PairingScope.LIST_SESSIONS
        }) { "Requested pairing scopes are not enabled for this client" }
        val protocolScopes = this.scopes.mapTo(mutableSetOf()) {
            when (it) {
                PairingScope.READ_STATUS -> "status"
                PairingScope.LIST_SESSIONS -> "sessions.list"
                else -> error("Unsupported pairing scope")
            }
        }
        require(protocolScopes == transport.requestedScopes) {
            "Stored host scopes must exactly match the signed pairing request"
        }
    }

    @Synchronized
    fun requestPairingChallenge(listener: PairingV2SessionListener): PairingV2ExchangeHandle {
        val state = coordinator.state
        if (state.phase != PairingPhase.AWAITING_HOST_APPROVAL ||
            state.candidateEndpoint != endpoint ||
            !state.candidateFingerprint.equals(fingerprintSha256, ignoreCase = true) ||
            state.candidatePairingId != pairingId
        ) {
            listener.onFailure("PAIRING_STATE_INVALID")
            return PairingV2ExchangeHandle { }
        }
        challenge = null
        val flowListener = object : PairingV2FlowListener {
            override fun onChallenge(challenge: PairingV2Challenge) {
                synchronized(this@PairingV2SessionController) {
                    if (challenge.pairingId != pairingId) {
                        listener.onFailure("PAIRING_TRANSACTION_MISMATCH")
                        return
                    }
                    this@PairingV2SessionController.challenge = challenge
                }
                listener.onChallenge(challenge)
            }

            override fun onCredential(credential: String) {
                listener.onFailure("PAIRING_STATE_INVALID")
            }

            override fun onFailure(safeCode: String) = listener.onFailure(safeCode)
        }
        return transport.requestChallenge(flowListener)
    }

    /**
     * Redeem the challenge proof. The host's local approval is authoritative; before
     * approval the server returns a generic unavailable response and the same signed
     * request may safely be retried. No Android-side action can mark the host approved.
     */
    @Synchronized
    fun redeemChallenge(listener: PairingV2SessionListener): PairingV2ExchangeHandle {
        val currentPhase = coordinator.state.phase
        val retryingCredentialPersistence =
            currentPhase == PairingPhase.FAILED &&
                coordinator.state.errorCode == "CREDENTIAL_STORAGE_FAILED"
        if (currentPhase != PairingPhase.AWAITING_HOST_APPROVAL &&
            currentPhase != PairingPhase.HOST_APPROVED &&
            !retryingCredentialPersistence
        ) {
            listener.onFailure("PAIRING_STATE_INVALID")
            return PairingV2ExchangeHandle { }
        }
        val accepted = challenge
        if (accepted == null) {
            listener.onFailure("PAIRING_CHALLENGE_MISSING")
            return PairingV2ExchangeHandle { }
        }

        return transport.redeem(accepted, object : PairingV2FlowListener {
            override fun onChallenge(challenge: PairingV2Challenge) {
                listener.onFailure("PAIRING_RESPONSE_INVALID")
            }

            override fun onCredential(credential: String) {
                val host = PairedHost(
                    endpoint = endpoint,
                    hostFingerprintSha256 = fingerprintSha256.uppercase(),
                    deviceId = deviceId,
                    credentialAlias = credentialAlias,
                    scopes = scopes,
                )
                // Serialize duplicate/overlapping responses. A second valid response for
                // the same transaction must not turn an already-paired state into FAILED.
                val state = synchronized(this@PairingV2SessionController) {
                    val current = coordinator.state
                    if (current.phase == PairingPhase.PAIRED) {
                        if (current.pairedHost != host) {
                            listener.onFailure("PAIRING_TRANSACTION_MISMATCH")
                            return@onCredential
                        }
                        current
                    } else {
                        // Only a valid credential response proves the host approved and issued.
                        if (current.phase == PairingPhase.AWAITING_HOST_APPROVAL) {
                            coordinator.dispatch(PairingAction.HostApproved)
                        }
                        coordinator.dispatch(PairingAction.CredentialIssued(host), credential)
                    }
                }
                if (state.phase == PairingPhase.PAIRED && state.pairedHost != null) {
                    listener.onPaired(state.pairedHost)
                } else {
                    listener.onFailure(state.errorCode ?: "CREDENTIAL_STORAGE_FAILED")
                }
            }

            override fun onFailure(safeCode: String) = listener.onFailure(safeCode)
        })
    }
}
