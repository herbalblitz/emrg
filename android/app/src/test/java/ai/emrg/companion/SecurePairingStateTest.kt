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
        assertFalse(isSha256Fingerprint("not-a-fingerprint"))
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
    fun revocationClearsAllPairedHostMetadata() {
        val paired = PairingState(phase = PairingPhase.PAIRED,
            pairedHost = PairedHost(endpoint, fingerprint, "device-1", "alias", setOf(PairingScope.SEND_CHAT)))
        val revoked = reducePairingState(paired, PairingAction.Revoked)
        assertEquals(PairingPhase.REVOKED, revoked.phase)
        assertNull(revoked.pairedHost)
        assertNull(revoked.candidateEndpoint)
        assertNull(revoked.candidateFingerprint)
    }
}
