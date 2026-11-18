package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePairingCoordinatorTest {
    private class FakeCredentialStore : CredentialStore {
        val values = mutableMapOf<String, String>()
        val deleted = mutableListOf<String>()
        var failDelete = false
        override fun put(alias: String, credential: String) { values[alias] = credential }
        override fun get(alias: String): String? = values[alias]
        override fun delete(alias: String) { if (failDelete) throw IllegalStateException(); values.remove(alias); deleted += alias }
    }

    private val fingerprint = (1..32).joinToString(":") { "%02X".format(it) }
    private val endpoint = "wss://host.example/companion"
    private val host = PairedHost(endpoint, fingerprint, "device", "device-01", setOf(PairingScope.SEND_CHAT), "workspace-1", "/workspace/approved")

    private fun approved(store: FakeCredentialStore): SecurePairingCoordinator {
        val coordinator = SecurePairingCoordinator(store)
        coordinator.dispatch(PairingAction.PairingQrReceived(endpoint, fingerprint))
        coordinator.dispatch(PairingAction.HostIdentityConfirmed(fingerprint))
        coordinator.dispatch(PairingAction.HostApproved)
        return coordinator
    }

    @Test
    fun credentialIsStoredOnlyAfterApprovalAndValidatedPairing() {
        val store = FakeCredentialStore()
        val coordinator = SecurePairingCoordinator(store)
        coordinator.dispatch(PairingAction.PairingQrReceived(endpoint, fingerprint))
        coordinator.dispatch(PairingAction.HostIdentityConfirmed(fingerprint))
        val premature = coordinator.dispatch(PairingAction.CredentialIssued(host), "secret")
        assertEquals(PairingPhase.FAILED, premature.phase)
        assertTrue(store.values.isEmpty())

        val store2 = FakeCredentialStore()
        val approvedCoordinator = approved(store2)
        val paired = approvedCoordinator.dispatch(PairingAction.CredentialIssued(host), "host-scoped-secret")
        assertEquals(PairingPhase.PAIRED, paired.phase)
        assertEquals("host-scoped-secret", store2.values["device-01"])
    }

    @Test
    fun rejectsMissingCredentialAndUnsafeAliasWithoutPersisting() {
        val store = FakeCredentialStore()
        val coordinator = approved(store)
        val missing = coordinator.dispatch(PairingAction.CredentialIssued(host))
        assertEquals(PairingPhase.FAILED, missing.phase)
        assertNull(missing.pairedHost)
        assertTrue(store.values.isEmpty())

        val store2 = FakeCredentialStore()
        val unsafe = approved(store2).dispatch(
            PairingAction.CredentialIssued(host.copy(credentialAlias = "../unsafe")),
            "credential",
        )
        assertEquals(PairingPhase.FAILED, unsafe.phase)
        assertTrue(store2.values.isEmpty())
    }

    @Test
    fun revocationDeletesPersistedCredentialAndClearsPairing() {
        val store = FakeCredentialStore()
        val coordinator = approved(store)
        coordinator.dispatch(PairingAction.CredentialIssued(host), "credential")
        val revoked = coordinator.dispatch(PairingAction.Revoked)
        assertEquals(PairingPhase.REVOKED, revoked.phase)
        assertNull(revoked.pairedHost)
        assertTrue(store.values.isEmpty())
        assertEquals(listOf("device-01"), store.deleted)
    }
    @Test
    fun failedRevocationKeepsAliasSoUserCanRetryAndDoesNotClaimRevoked() {
        val store = FakeCredentialStore()
        val coordinator = approved(store)
        coordinator.dispatch(PairingAction.CredentialIssued(host), "credential")
        store.failDelete = true
        val failed = coordinator.dispatch(PairingAction.Revoked)
        assertEquals(PairingPhase.FAILED, failed.phase)
        assertEquals("CREDENTIAL_REVOCATION_FAILED", failed.errorCode)
        assertEquals(host, failed.pairedHost)
        assertEquals("credential", store.values["device-01"])

        store.failDelete = false
        val retried = coordinator.dispatch(PairingAction.Revoked)
        assertEquals(PairingPhase.REVOKED, retried.phase)
        assertNull(retried.pairedHost)
        assertTrue(store.values.isEmpty())
    }

}
