package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        override val endpoint: String = "wss://host.example/pair",
        override val fingerprintSha256: String = "AA:".repeat(31) + "AA",
        override val pairingId: String = "A".repeat(43),
        override val deviceId: String = "android-device",
    ) : PairingV2SessionTransport {
        var challengeListener: PairingV2FlowListener? = null
        var challenge: PairingV2Challenge? = null
        var redeemCalls = 0
        var lastRedeemListener: PairingV2FlowListener? = null
        var unavailableFirst = false

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
            redeemCalls += 1
            lastRedeemListener = listener
            if (unavailableFirst && redeemCalls == 1) {
                listener.onFailure("PAIRING_UNAVAILABLE")
            } else {
                listener.onCredential("a".repeat(64))
            }
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
    private val challenge = PairingV2Challenge(
        pairingId, "B".repeat(22), "C".repeat(100), "workspace-1",
    )

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
        assertEquals("workspace-1", listener.paired?.approvedWorkspaceId)
        assertTrue(listener.failure == null)

        // A duplicated transport callback must be harmless and must not demote PAIRED.
        transport.lastRedeemListener!!.onCredential("a".repeat(64))
        assertEquals(PairingPhase.PAIRED, coordinator.state.phase)
        assertEquals("host-credential", coordinator.state.pairedHost?.credentialAlias)
    }


    @Test
    fun mismatchedDuplicateCredentialDoesNotDemotePairedState() {
        val store = Store()
        val coordinator = approvedCoordinator(store)
        val transport = FakeTransport().apply { challenge = this@PairingV2SessionControllerTest.challenge }
        val controller = controller(coordinator, transport)
        val listener = Listener()

        controller.requestPairingChallenge(listener)
        controller.redeemChallenge(listener)
        assertEquals(PairingPhase.PAIRED, coordinator.state.phase)

        // A response bound to a different credential/transaction must be rejected
        // without replacing the already persisted host credential or paired state.
        transport.lastRedeemListener!!.onCredential("d".repeat(64))

        assertEquals(PairingPhase.PAIRED, coordinator.state.phase)
        assertEquals("PAIRING_TRANSACTION_MISMATCH", listener.failure)
        assertEquals("a".repeat(64), store.values["host-credential"])
        assertEquals("host-credential", coordinator.state.pairedHost?.credentialAlias)
    }

    @Test
    fun genericUnavailableDoesNotMarkHostApprovedAndSameChallengeCanBeRetried() {
        val store = Store()
        val coordinator = approvedCoordinator(store)
        val transport = FakeTransport().apply {
            challenge = this@PairingV2SessionControllerTest.challenge
            unavailableFirst = true
        }
        val controller = controller(coordinator, transport)
        val listener = Listener()

        controller.requestPairingChallenge(listener)
        controller.redeemChallenge(listener)

        assertEquals(PairingPhase.AWAITING_HOST_APPROVAL, coordinator.state.phase)
        assertEquals("PAIRING_UNAVAILABLE", listener.failure)
        assertTrue(store.values.isEmpty())

        controller.redeemChallenge(listener)

        assertEquals(PairingPhase.PAIRED, coordinator.state.phase)
        assertEquals(2, transport.redeemCalls)
        assertEquals("a".repeat(64), store.values["host-credential"])
        assertEquals("host-credential", listener.paired?.credentialAlias)
    }

    @Test
    fun storageFailureNeverReportsPaired() {
        val store = Store().apply { failWrite = true }
        val coordinator = approvedCoordinator(store)
        val transport = FakeTransport().apply { challenge = this@PairingV2SessionControllerTest.challenge }
        val controller = controller(coordinator, transport)
        val listener = Listener()

        controller.requestPairingChallenge(listener)
        controller.redeemChallenge(listener)

        assertEquals(PairingPhase.FAILED, coordinator.state.phase)
        assertEquals("CREDENTIAL_STORAGE_FAILED", listener.failure)
        assertTrue(listener.paired == null)
        assertTrue(store.values.isEmpty())

        // The host already issued this credential; a transient local write failure
        // must not force the user to restart pairing or obtain a second challenge.
        store.failWrite = false
        controller.redeemChallenge(listener)

        assertEquals(PairingPhase.PAIRED, coordinator.state.phase)
        assertEquals("host-credential", listener.paired?.credentialAlias)
        assertEquals("a".repeat(64), store.values["host-credential"])
    }


    @Test
    fun concurrentDuplicateCredentialCallbacksPreservePairedState() {
        val store = Store()
        val coordinator = approvedCoordinator(store)
        val transport = FakeTransport().apply { challenge = this@PairingV2SessionControllerTest.challenge }
        val controller = controller(coordinator, transport)
        val listener = Listener()

        controller.requestPairingChallenge(listener)
        controller.redeemChallenge(listener)
        assertEquals(PairingPhase.PAIRED, coordinator.state.phase)

        val callback = transport.lastRedeemListener!!
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map {
                pool.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    callback.onCredential("a".repeat(64))
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(PairingPhase.PAIRED, coordinator.state.phase)
        assertEquals("host-credential", coordinator.state.pairedHost?.credentialAlias)
        assertEquals("a".repeat(64), store.values["host-credential"])
        assertTrue(listener.failure == null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun transportHostIdentityMustMatchStoredPairedHostMetadata() {
        val store = Store()
        val coordinator = approvedCoordinator(store)
        controller(coordinator, FakeTransport(endpoint = "wss://other.example/pair"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun transportDeviceIdentityMustMatchStoredPairedHostMetadata() {
        val store = Store()
        val coordinator = approvedCoordinator(store)
        controller(coordinator, FakeTransport(deviceId = "different-device"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun storedScopesMustMatchTheSignedTransportRequest() {
        val store = Store()
        val coordinator = approvedCoordinator(store)
        controller(coordinator, FakeTransport(setOf("sessions.list")))
    }
}
