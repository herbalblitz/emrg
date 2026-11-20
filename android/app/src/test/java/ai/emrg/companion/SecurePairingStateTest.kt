package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePairingStateTest {
    private val fingerprint = (1..32).joinToString(":") { "%02X".format(it) }
    private val endpoint = "wss://emrg-host.example:56031/companion"

    @Test
    fun rejectsPlaintextAndMalformedPairingDescriptors() {
        val plain = reducePairingState(PairingState(), PairingAction.PairingQrReceived(
            "ws://host:56031", fingerprint
        ))
        assertEquals(PairingPhase.FAILED, plain.phase)
        assertNull(plain.candidateEndpoint)
        assertFalse(isSecurePairingEndpoint("https://host/path"))
        assertFalse(isSecurePairingEndpoint("wss://user:pass@host/path"))
        assertFalse(isSecurePairingEndpoint("wss://host/path?token=secret"))
        assertFalse(isSecurePairingEndpoint("wss://host:0/path"))
        assertFalse(isSecurePairingEndpoint("wss://host:99999/path"))
        assertFalse(isSha256Fingerprint("not-a-fingerprint"))
    }

    @Test
    fun preservesCanonicalV2PairingIdThroughHostIdentityVerification() {
        val pairingId = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { it.toByte() })
        val candidate = reducePairingState(
            PairingState(),
            PairingAction.PairingQrReceived(endpoint, fingerprint, pairingId),
        )
        assertEquals(PairingPhase.AWAITING_HOST_VERIFICATION, candidate.phase)
        assertEquals(pairingId, candidate.candidatePairingId)
        val verified = reducePairingState(
            candidate,
            PairingAction.HostIdentityConfirmed(fingerprint),
        )
        assertEquals(PairingPhase.AWAITING_HOST_APPROVAL, verified.phase)
        assertEquals(pairingId, verified.candidatePairingId)
    }

    @Test
    fun invalidQrReplacesAndClearsPreviousCandidateTransaction() {
        val validPairingId = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { it.toByte() })
        val candidate = reducePairingState(
            PairingState(),
            PairingAction.PairingQrReceived(endpoint, fingerprint, validPairingId),
        )
        assertEquals(validPairingId, candidate.candidatePairingId)

        val rejected = reducePairingState(
            candidate,
            PairingAction.PairingQrReceived("ws://attacker.example", fingerprint, validPairingId),
        )
        assertEquals(PairingPhase.FAILED, rejected.phase)
        assertEquals("INVALID_PAIRING_DESCRIPTOR", rejected.errorCode)
        assertNull(rejected.candidateEndpoint)
        assertNull(rejected.candidateFingerprint)
        assertNull(rejected.candidatePairingId)
        assertNull(rejected.pairedHost)
    }

    @Test
    fun rejectsMalformedV2PairingId() {
        val rejected = reducePairingState(
            PairingState(),
            PairingAction.PairingQrReceived(endpoint, fingerprint, "not-a-canonical-pairing-id"),
        )
        assertEquals(PairingPhase.FAILED, rejected.phase)
        assertEquals("INVALID_PAIRING_DESCRIPTOR", rejected.errorCode)
    }

    @Test
    fun requiresExactHostFingerprintConfirmationBeforeApproval() {
        val candidate = reducePairingState(PairingState(), PairingAction.PairingQrReceived(endpoint, fingerprint))
        assertEquals(PairingPhase.AWAITING_HOST_VERIFICATION, candidate.phase)

        val mismatch = reducePairingState(candidate, PairingAction.HostIdentityConfirmed("00:$fingerprint"))
        assertEquals(PairingPhase.FAILED, mismatch.phase)

        val verified = reducePairingState(candidate, PairingAction.HostIdentityConfirmed(fingerprint.lowercase()))
        assertEquals(PairingPhase.AWAITING_HOST_APPROVAL, verified.phase)
    }

    @Test
    fun acceptsOnlyCredentialBoundToVerifiedHostAndNonEmptyScopes() {
        val candidate = reducePairingState(PairingState(), PairingAction.PairingQrReceived(endpoint, fingerprint))
        val verified = reducePairingState(candidate, PairingAction.HostIdentityConfirmed(fingerprint))
        val host = PairedHost(endpoint, fingerprint, "device-1", "emrg.device.device-1", setOf(PairingScope.SEND_CHAT), "workspace-1", "/workspace/approved")
        val premature = reducePairingState(verified, PairingAction.CredentialIssued(host))
        assertEquals(PairingPhase.FAILED, premature.phase)

        val approved = reducePairingState(verified, PairingAction.HostApproved)
        assertEquals(PairingPhase.HOST_APPROVED, approved.phase)
        val paired = reducePairingState(approved, PairingAction.CredentialIssued(host))
        assertEquals(PairingPhase.PAIRED, paired.phase)
        assertEquals(host, paired.pairedHost)

        val wrongHost = reducePairingState(approved, PairingAction.CredentialIssued(host.copy(endpoint = "wss://other.example")))
        assertEquals(PairingPhase.FAILED, wrongHost.phase)
        assertNull(wrongHost.pairedHost)

        val noScopes = reducePairingState(approved, PairingAction.CredentialIssued(host.copy(scopes = emptySet())))
        assertEquals(PairingPhase.FAILED, noScopes.phase)
    }

    @Test
    fun sessionListCredentialRequiresApprovedWorkspaceBinding() {
        val candidate = reducePairingState(PairingState(), PairingAction.PairingQrReceived(endpoint, fingerprint))
        val verified = reducePairingState(candidate, PairingAction.HostIdentityConfirmed(fingerprint))
        val approved = reducePairingState(verified, PairingAction.HostApproved)
        val unbound = PairedHost(
            endpoint, fingerprint, "device-1", "alias",
            setOf(PairingScope.READ_STATUS, PairingScope.LIST_SESSIONS),
        )
        val rejected = reducePairingState(approved, PairingAction.CredentialIssued(unbound))
        assertEquals(PairingPhase.FAILED, rejected.phase)
        assertNull(rejected.pairedHost)

        val bound = reducePairingState(
            approved,
            PairingAction.CredentialIssued(unbound.copy(approvedWorkspaceId = "workspace-1")),
        )
        assertEquals(PairingPhase.PAIRED, bound.phase)
        assertEquals("workspace-1", bound.pairedHost?.approvedWorkspaceId)
    }

    @Test
    fun revocationClearsAllPairedHostMetadata() {
        val paired = PairingState(phase = PairingPhase.PAIRED,
            pairedHost = PairedHost(endpoint, fingerprint, "device-1", "alias", setOf(PairingScope.SEND_CHAT)))
        val revoked = reducePairingState(paired, PairingAction.Revoked)
        assertEquals(PairingPhase.REVOKED, revoked.phase)
        assertNull(revoked.pairedHost)
        assertNull(revoked.candidateEndpoint)
        assertNull(revoked.candidateFingerprint)
    }
    @Test
    fun chatCredentialRequiresApprovedWorkspaceBinding() {
        val candidate = reducePairingState(PairingState(), PairingAction.PairingQrReceived(endpoint, fingerprint))
        val verified = reducePairingState(candidate, PairingAction.HostIdentityConfirmed(fingerprint))
        val approved = reducePairingState(verified, PairingAction.HostApproved)
        val unbound = PairedHost(endpoint, fingerprint, "device-1", "alias", setOf(PairingScope.SEND_CHAT))
        val rejected = reducePairingState(approved, PairingAction.CredentialIssued(unbound))
        assertEquals(PairingPhase.FAILED, rejected.phase)
        assertNull(rejected.pairedHost)
    }

}
