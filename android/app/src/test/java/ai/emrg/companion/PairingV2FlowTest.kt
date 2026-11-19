package ai.emrg.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingV2FlowTest {
    private class FakeExchange : PairingV2FrameExchange {
        var calls = 0
        override fun exchange(
            endpoint: String,
            fingerprintSha256: String,
            requestFrame: JSONObject,
            listener: PairingV2ExchangeListener,
        ): PairingV2ExchangeHandle {
            calls += 1
            return PairingV2ExchangeHandle { }
        }
    }

    private class FakeKeys : CompanionDeviceKeyManager {
        override fun createOrLoad(alias: String): String = "invalid-public-key"
        override fun sign(alias: String, challenge: ByteArray): String = "signature"
        override fun delete(alias: String) = Unit
    }

    private class RecordingListener : PairingV2FlowListener {
        var challenge: PairingV2Challenge? = null
        var credential: String? = null
        var failure: String? = null
        override fun onChallenge(challenge: PairingV2Challenge) { this.challenge = challenge }
        override fun onCredential(credential: String) { this.credential = credential }
        override fun onFailure(safeCode: String) { failure = safeCode }
    }

    private fun client() = PairingV2Client(
        keys = FakeKeys(),
        deviceId = "android-device",
        keyAlias = "emrg-device",
        expectedHostIdentity = "AA:".repeat(31) + "AA",
    )

    @Test
    fun redeemRejectsChallengeFromAnotherPairingBeforeOpeningTransport() {
        val exchange = FakeExchange()
        val listener = RecordingListener()
        val flow = PairingV2Flow(
            endpoint = "wss://host.example/pair",
            fingerprintSha256 = "AA:".repeat(31) + "AA",
            pairingId = "A".repeat(43),
            client = client(),
            exchange = exchange,
        )

        flow.redeemAfterHostApproval(
            PairingV2Challenge("B".repeat(43), "C".repeat(22), "D".repeat(100)),
            listener,
        )

        assertEquals("PAIRING_TRANSACTION_MISMATCH", listener.failure)
        assertEquals(0, exchange.calls)
        assertTrue(listener.credential == null)
    }

    @Test
    fun invalidRequestDoesNotOpenTransport() {
        val exchange = FakeExchange()
        val listener = RecordingListener()
        val flow = PairingV2Flow(
            endpoint = "wss://host.example/pair",
            fingerprintSha256 = "AA:".repeat(31) + "AA",
            pairingId = "not-a-canonical-pairing-id",
            client = client(),
            exchange = exchange,
        )

        flow.requestChallenge(listener)

        assertEquals("PAIRING_REQUEST_INVALID", listener.failure)
        assertEquals(0, exchange.calls)
    }
}
