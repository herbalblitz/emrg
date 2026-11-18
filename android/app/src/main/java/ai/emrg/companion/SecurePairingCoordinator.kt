package ai.emrg.companion

/**
 * Coordinates the fail-closed pairing reducer with encrypted credential persistence.
 * Credentials are persisted only after host approval and successful state validation.
 */
internal class SecurePairingCoordinator(
    private val credentialStore: CredentialStore,
    initialState: PairingState = PairingState(),
) {
    var state: PairingState = initialState
        private set

    @Synchronized
    fun dispatch(action: PairingAction, issuedCredential: String? = null): PairingState {
        if (action is PairingAction.CredentialIssued) {
            val next = reducePairingState(state, action)
            if (next.phase != PairingPhase.PAIRED) {
                state = next
                return state
            }
            val credential = issuedCredential
            if (credential.isNullOrBlank() ||
                !AndroidKeystoreCredentialStore.isValidCredentialAlias(action.host.credentialAlias)
            ) {
                state = next.copy(phase = PairingPhase.FAILED, pairedHost = null, errorCode = "INVALID_CREDENTIAL")
                return state
            }
            try {
                credentialStore.put(action.host.credentialAlias, credential)
            } catch (_: Exception) {
                state = next.copy(phase = PairingPhase.FAILED, pairedHost = null, errorCode = "CREDENTIAL_STORAGE_FAILED")
                return state
            }
            state = next
            return state
        }

        if (action == PairingAction.Revoked) {
            val alias = state.pairedHost?.credentialAlias
            try {
                if (alias != null) credentialStore.delete(alias)
            } catch (_: Exception) {
                state = state.copy(phase = PairingPhase.FAILED, errorCode = "CREDENTIAL_REVOCATION_FAILED")
                return state
            }
        }
        state = reducePairingState(state, action)
        return state
    }
}
