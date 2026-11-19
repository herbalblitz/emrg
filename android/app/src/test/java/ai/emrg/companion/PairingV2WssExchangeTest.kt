package ai.emrg.companion

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingV2WssExchangeTest {
    private val pairingId = "A".repeat(43)

    @Test
    fun acceptsOnlyMatchingChallengeEnvelopeForRequest() {
        val request = JSONObject()
            .put("type", "pairing.request")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
        val response = JSONObject()
            .put("type", "pairing.challenge")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
            .put("challenge_id", "B".repeat(22))
            .put("challenge", "C".repeat(100))
        assertTrue(PairingV2WssExchange.isExpectedResponse(request, response))
        assertFalse(PairingV2WssExchange.isExpectedResponse(
            request, response.put("unexpected", true)))
    }

    @Test
    fun rejectsMismatchedTransactionAndUnexpectedResponseTypes() {
        val request = JSONObject()
            .put("type", "pairing.request")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
        val wrongTransaction = JSONObject()
            .put("type", "pairing.challenge")
            .put("protocol", "2.0")
            .put("pairing_id", "D".repeat(43))
            .put("challenge_id", "B".repeat(22))
            .put("challenge", "C".repeat(100))
        assertFalse(PairingV2WssExchange.isExpectedResponse(request, wrongTransaction))
        assertFalse(PairingV2WssExchange.isExpectedResponse(
            request, JSONObject().put("type", "auth.ok").put("protocol", "2.0").put("pairing_id", pairingId)))
    }

    @Test
    fun redeemResponseMustBeForTheSameDeviceAndTransaction() {
        val request = JSONObject()
            .put("type", "pairing.redeem")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
            .put("device_id", "android-device")
        val response = JSONObject()
            .put("type", "pairing.credential")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
            .put("device_id", "android-device")
            .put("credential", "a".repeat(64))
        assertTrue(PairingV2WssExchange.isExpectedResponse(request, response))
        response.put("device_id", "other-device")
        assertFalse(PairingV2WssExchange.isExpectedResponse(request, response))
    }
}
