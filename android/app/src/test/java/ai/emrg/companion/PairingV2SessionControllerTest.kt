package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingV2SessionControllerTest {
    private class Store : CredentialStore {
        val values = mutableMapOf<String, String>()
        var failWrite = false
        override fun put(alias: String, credential: String) {
            if (failWrite) throw IllegalStateException("storage unavailable")
            values[alias] = credential
        }
        override fun get(alias: String): String? = values[alias]
        override fun delete(alias: String) { values.remove(alias) }
    }

    private class FakeTransport(
        override val requestedScopes: Set<String> = setOf("status"),
    ) : PairingV2SessionTransport {
        var challengeListener: PairingV2FlowListener? = null
        var challenge: PairingV2Challenge? = null

        override fun requestChallenge(listener: PairingV2FlowListener): PairingV2ExchangeHandle {
            challengeListener = listener
            challenge?.let(listener::onChallenge)
            return PairingV2ExchangeHandle { }
        }

        override fun redeem(
            challenge: PairingV2Challenge,
            listener: PairingV2FlowListener,
        ): PairingV2ExchangeHandle {
            check(this.challenge == challenge)
            listener.onCredential("a".repeat(64))
            return PairingV2ExchangeHandle { }
        }
    }

    private class Listener : PairingV2SessionListener {
        var challenge: PairingV2Challenge? = null
        var paired: PairedHost? = null
        var failure: String? = null
        override fun onChallenge(challenge: PairingV2Challenge) { this.challenge = challenge }
        override fun onPaired(host: PairedHost) { paired = host }
        override fun onFailure(safeCode: String) { failure = safeCode }
    }

    private val endpoint = "wss://host.example/pair"
    private val fingerprint = "AA:".repeat(31) + "AA"
    private val pairingId = "A".repeat(43)
    private val challenge = PairingV2Challenge(pairingId, "B".repeat(22), "C".repeat(100))

    private fun approvedCoordinator(store: Store): SecurePairingCoordinator {
        val coordinator = SecurePairingCoordinator(store)
        coordinator.dispatch(PairingAction.PairingQrReceived(endpoint, fingerprint, pairingId))
        coordinator.dispatch(PairingAction.HostIdentityConfirmed(fingerprint))
        return coordinator
    }

    private fun controller(
        coordinator: SecurePairingCoordinator,
        transport: FakeTransport,
    ) = PairingV2SessionController(
        coordinator = coordinator,
        transport = transport,
        endpoint = endpoint,
        fingerprintSha256 = fingerprint,
        pairingId = pairingId,
        deviceId = "android-device",
        credentialAlias = "host-credential",
        scopes = setOf(PairingScope.READ_STATUS),
    )

    @Test
    fun credentialIsPersistedOnlyAfterHostIssuesAValidCredential() {
        val store = Store()
        val coordinator = approvedCoordinator(store)
        val transport = FakeTransport().apply { challenge = this@PairingV2SessionControllerTest.challenge }
        val controller = controller(coordinator, transport)
        val listener = Listener()

        controller.requestPairingChallenge(listener)
        assertEquals(challenge, listener.challenge)
        controller.redeemChallenge(listener)

        assertEquals(PairingPhase.PAIRED, coordinator.state.phase)
        assertEquals("a".repeat(64), store.values["host-credential"])
        assertEquals("host-credential", listener.paired?.credentialAlias)
        assertTrue(listener.failure == null)
    }

    @Test
    fun storageFailureNeverReportsPaired() {
        val store = Store().apply { failWrite = true }
        val coordinator = approvedCoordinator(store)
        val transport = FakeTransport().apply { challenge = this@PairingV2SessionControllerTest.challenge }
        val controller = controller(coordinator, transport)
        val listener = Listener()

        controller.requestPairingChallenge(listener)
        controller.redeemAfterConfirmedHostApproval(listener)

        assertEquals(PairingPhase.FAILED, coordinator.state.phase)
        assertEquals("CREDENTIAL_STORAGE_FAILED", listener.failure)
        assertTrue(listener.paired == null)
        assertTrue(store.values.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun storedScopesMustMatchTheSignedTransportRequest() {
        val store = Store()
        val coordinator = approvedCoordinator(store)
        controller(coordinator, FakeTransport(setOf("sessions.list")))
    }
}
