package ai.emrg.companion

import java.net.URI

/** A verified host identity is required before any credential can be stored or used. */
internal data class PairedHost(
    val endpoint: String,
    val hostFingerprintSha256: String,
    val deviceId: String,
    val credentialAlias: String,
    val scopes: Set<PairingScope>,
)

internal enum class PairingScope {
    READ_STATUS,
    LIST_SESSIONS,
    CONTINUE_SESSIONS,
    SEND_CHAT,
    VIEW_TOOL_ACTIVITY,
}

internal enum class PairingPhase {
    DISCONNECTED,
    AWAITING_HOST_VERIFICATION,
    AWAITING_HOST_APPROVAL,
    PAIRED,
    REVOKED,
    FAILED,
}

internal data class PairingState(
    val phase: PairingPhase = PairingPhase.DISCONNECTED,
    val candidateEndpoint: String? = null,
    val candidateFingerprint: String? = null,
    val pairedHost: PairedHost? = null,
    val errorCode: String? = null,
)

internal sealed interface PairingAction {
    data class PairingQrReceived(val endpoint: String, val fingerprintSha256: String) : PairingAction
    data class HostIdentityConfirmed(val fingerprintSha256: String) : PairingAction
    data object HostApproved : PairingAction
    data class CredentialIssued(val host: PairedHost) : PairingAction
    data object Revoked : PairingAction
    data object Failed : PairingAction
}

internal fun isSecurePairingEndpoint(endpoint: String): Boolean = try {
    val uri = URI(endpoint)
    uri.scheme.equals("wss", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.fragment == null
} catch (_: Exception) {
    false
}

internal fun isSha256Fingerprint(value: String): Boolean =
    value.matches(Regex("(?i)([0-9a-f]{2}:){31}[0-9a-f]{2}"))

internal fun reducePairingState(state: PairingState, action: PairingAction): PairingState =
    when (action) {
        is PairingAction.PairingQrReceived -> {
            if (!isSecurePairingEndpoint(action.endpoint) || !isSha256Fingerprint(action.fingerprintSha256)) {
                state.copy(phase = PairingPhase.FAILED, errorCode = "INVALID_PAIRING_DESCRIPTOR")
            } else {
                PairingState(
                    phase = PairingPhase.AWAITING_HOST_VERIFICATION,
                    candidateEndpoint = action.endpoint,
                    candidateFingerprint = action.fingerprintSha256.uppercase(),
                )
            }
        }
        is PairingAction.HostIdentityConfirmed ->
            if (state.phase != PairingPhase.AWAITING_HOST_VERIFICATION ||
                !action.fingerprintSha256.equals(state.candidateFingerprint, ignoreCase = true)
            ) state.copy(phase = PairingPhase.FAILED, errorCode = "HOST_IDENTITY_MISMATCH")
            else state.copy(phase = PairingPhase.AWAITING_HOST_APPROVAL)
        PairingAction.HostApproved ->
            if (state.phase == PairingPhase.AWAITING_HOST_APPROVAL) state else state
        is PairingAction.CredentialIssued ->
            if (state.phase != PairingPhase.AWAITING_HOST_APPROVAL ||
                action.host.endpoint != state.candidateEndpoint ||
                !action.host.hostFingerprintSha256.equals(state.candidateFingerprint, ignoreCase = true) ||
                action.host.deviceId.isBlank() ||
                action.host.credentialAlias.isBlank() ||
                action.host.scopes.isEmpty()
            ) state.copy(phase = PairingPhase.FAILED, errorCode = "INVALID_DEVICE_CREDENTIAL")
            else state.copy(phase = PairingPhase.PAIRED, pairedHost = action.host, errorCode = null)
        PairingAction.Revoked -> PairingState(phase = PairingPhase.REVOKED)
        PairingAction.Failed -> PairingState(phase = PairingPhase.FAILED, errorCode = "PAIRING_FAILED")
    }
