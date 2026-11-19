package ai.emrg.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PairingV2FlowTest {
    private class FakeExchange : PairingV2FrameExchange {
        var calls = 0
        var lastRequest: JSONObject? = null
        var lastListener: PairingV2ExchangeListener? = null

        override fun exchange(
            endpoint: String,
            fingerprintSha256: String,
            requestFrame: JSONObject,
            listener: PairingV2ExchangeListener,
        ): PairingV2ExchangeHandle {
            calls += 1
            lastRequest = JSONObject(requestFrame.toString())
            lastListener = listener
            return PairingV2ExchangeHandle { }
        }

        fun respond(response: JSONObject) {
            lastListener?.onResponse(response) ?: error("No exchange listener")
        }
    }

    private class FakeKeys : CompanionDeviceKeyManager {
        private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        override fun createOrLoad(alias: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)

        override fun sign(alias: String, challenge: ByteArray): String {
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(pair.private)
            signer.update(challenge)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
        }

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

    private val pairingId = "A".repeat(43)
    private val fingerprint = "AA:".repeat(31) + "AA"

    private fun client(keys: CompanionDeviceKeyManager = FakeKeys()) = PairingV2Client(
        keys = keys,
        deviceId = "android-device",
        keyAlias = "emrg-device",
        expectedHostIdentity = fingerprint,
        nowEpochSeconds = { 1_000L },
    )

    private fun flow(exchange: FakeExchange, client: PairingV2Client = client()) = PairingV2Flow(
        endpoint = "wss://host.example/pair",
        fingerprintSha256 = fingerprint,
        pairingId = pairingId,
        client = client,
        exchange = exchange,
    )

    private fun deliverChallenge(exchange: FakeExchange, listener: RecordingListener) {
        val request = exchange.lastRequest ?: error("Request was not sent")
        val publicKey = Base64.getUrlDecoder().decode(request.getString("public_key"))
        val challengeId = "B".repeat(22)
        val challenge = JSONObject()
            .put("client_nonce", request.getString("client_nonce"))
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
            .put("challenge_id", challengeId)
            .put("device_id", "android-device")
            .put("public_key_sha256", MessageDigest.getInstance("SHA-256").digest(publicKey)
                .joinToString("") { "%02x".format(it) })
            .put("host_identity", fingerprint)
            .put("workspace_id", "workspace-1")
            .put("expires_at", 1_100)
            .put("nonce", "A".repeat(43))
            .put("scopes", JSONArray().put("status"))
        val encodedChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(challenge.toString().toByteArray(Charsets.UTF_8))
        exchange.respond(
            JSONObject()
                .put("type", "pairing.challenge")
                .put("protocol", "2.0")
                .put("pairing_id", pairingId)
                .put("challenge_id", challengeId)
                .put("challenge", encodedChallenge),
        )
        assertNotNull(listener.challenge)
    }

    @Test
    fun redeemRequiresExplicitConfirmationForMatchingHostApproval() {
        val exchange = FakeExchange()
        val listener = RecordingListener()
        val flow = flow(exchange)
        flow.requestChallenge(listener)
        deliverChallenge(exchange, listener)
        val challenge = listener.challenge!!

        flow.redeemAfterHostApproval(challenge, listener)

        assertEquals("HOST_APPROVAL_NOT_CONFIRMED", listener.failure)
        assertEquals(1, exchange.calls)
        assertTrue(listener.credential == null)
    }

    @Test
    fun hostApprovalMustReferToChallengeFromThisFlow() {
        val exchange = FakeExchange()
        val listener = RecordingListener()
        val flow = flow(exchange)
        flow.requestChallenge(listener)
        deliverChallenge(exchange, listener)
        val challenge = listener.challenge!!

        assertFalse(flow.confirmHostApproval(challenge.copy(pairingId = "D".repeat(43))))
        assertTrue(flow.confirmHostApproval(challenge))

        val otherListener = RecordingListener()
        flow(FakeExchange()).redeemAfterHostApproval(challenge, otherListener)
        assertEquals("PAIRING_TRANSACTION_MISMATCH", otherListener.failure)
    }

    @Test
    fun retriesReuseTheSameSignedRedeemFrame() {
        val exchange = FakeExchange()
        val listener = RecordingListener()
        val flow = flow(exchange)
        flow.requestChallenge(listener)
        deliverChallenge(exchange, listener)
        val challenge = listener.challenge!!
        assertTrue(flow.confirmHostApproval(challenge))

        flow.redeemAfterHostApproval(challenge, listener)
        val first = exchange.lastRequest!!.toString()
        flow.redeemAfterHostApproval(challenge, listener)
        val second = exchange.lastRequest!!.toString()

        assertEquals(first, second)
        assertEquals(3, exchange.calls)
    }

    @Test
    fun invalidRequestDoesNotOpenTransport() {
        val exchange = FakeExchange()
        val listener = RecordingListener()
        val invalidFlow = PairingV2Flow(
            endpoint = "wss://host.example/pair",
            fingerprintSha256 = fingerprint,
            pairingId = "not-a-canonical-pairing-id",
            client = client(),
            exchange = exchange,
        )

        invalidFlow.requestChallenge(listener)

        assertEquals("PAIRING_REQUEST_INVALID", listener.failure)
        assertEquals(0, exchange.calls)
    }
}
