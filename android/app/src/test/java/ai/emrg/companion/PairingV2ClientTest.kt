package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.AlgorithmParameters
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.math.BigInteger
import java.util.Base64

class PairingV2ClientTest {
    private class FakeDeviceKeys : CompanionDeviceKeyManager {
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

        fun verify(challenge: ByteArray, signature: String): Boolean {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pair.public)
            verifier.update(challenge)
            return verifier.verify(Base64.getUrlDecoder().decode(signature))
        }

        fun publicKeyDigest(): String = MessageDigest.getInstance("SHA-256")
            .digest(pair.public.encoded).joinToString("") { "%02x".format(it) }
    }

    private fun b64(size: Int, seed: Int = 1): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size) { (it + seed).toByte() })

    private fun challenge(
        pairingId: String,
        challengeId: String,
        deviceId: String,
        publicKeyDigest: String,
        expiresAt: Long,
    ): ByteArray = ("{\"challenge_id\":\"" + challengeId +
        "\",\"device_id\":\"" + deviceId +
        "\",\"expires_at\":" + expiresAt +
        ",\"host_identity\":\"host-identity\",\"nonce\":\"" + b64(32) +
        "\",\"pairing_id\":\"" + pairingId +
        "\",\"protocol\":\"2.0\",\"public_key_sha256\":\"" + publicKeyDigest +
        "\",\"scopes\":[\"status\",\"sessions.list\"],\"workspace_id\":\"workspace-1\"}")
        .toByteArray(Charsets.UTF_8)

    @Test
    fun buildsRequestAndSignsExactHostChallengeBytes() {
        val keys = FakeDeviceKeys()
        val client = PairingV2Client(keys, "android-device-1", "emrg-host-1") { 1_700_000_000L }
        val pairingId = b64(32)
        val challengeId = b64(16)
        val request = client.createRequestFrame(pairingId)
        assertEquals("pairing.request", request.getString("type"))
        assertEquals("2.0", request.getString("protocol"))
        assertEquals("P-256", request.getString("key_algorithm"))
        assertEquals(pairingId, request.getString("pairing_id"))
        assertEquals(122, request.getString("public_key").length)

        val rawChallenge = challenge(pairingId, challengeId, "android-device-1", keys.publicKeyDigest(), 1_700_000_100L)
        val redeem = client.createRedeemFrame(
            pairingId, challengeId,
            Base64.getUrlEncoder().withoutPadding().encodeToString(rawChallenge),
        )
        assertEquals("pairing.redeem", redeem.getString("type"))
        assertEquals(pairingId, redeem.getString("pairing_id"))
        assertEquals(challengeId, redeem.getString("challenge_id"))
        assertEquals("android-device-1", redeem.getString("device_id"))
        assertEquals(true, keys.verify(rawChallenge, redeem.getString("signature")))
    }

    @Test
    fun refusesChallengeBoundToAnotherDeviceOrPublicKey() {
        val keys = FakeDeviceKeys()
        val client = PairingV2Client(keys, "android-device-1", "emrg-host-1") { 1_700_000_000L }
        val pairingId = b64(32)
        val challengeId = b64(16)
        client.createRequestFrame(pairingId)
        val raw = challenge(pairingId, challengeId, "other-device", keys.publicKeyDigest(), 1_700_000_100L)
        assertThrows(IllegalArgumentException::class.java) {
            client.createRedeemFrame(pairingId, challengeId, Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
        }
    }

    @Test
    fun refusesExpiredChallenge() {
        val keys = FakeDeviceKeys()
        val client = PairingV2Client(keys, "android-device-1", "emrg-host-1") { 1_700_000_000L }
        val pairingId = b64(32)
        val challengeId = b64(16)
        client.createRequestFrame(pairingId)
        val raw = challenge(pairingId, challengeId, "android-device-1", keys.publicKeyDigest(), 1_699_999_999L)
        assertThrows(IllegalArgumentException::class.java) {
            client.createRedeemFrame(pairingId, challengeId, Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
        }
    }

    @Test
    fun refusesChallengeWithExpiryBeyondProtocolLifetime() {
        val keys = FakeDeviceKeys()
        val client = PairingV2Client(keys, "android-device-1", "emrg-host-1") { 1_700_000_000L }
        val pairingId = b64(32)
        val challengeId = b64(16)
        client.createRequestFrame(pairingId)
        val raw = challenge(pairingId, challengeId, "android-device-1", keys.publicKeyDigest(), 1_700_000_181L)
        assertThrows(IllegalArgumentException::class.java) {
            client.createRedeemFrame(pairingId, challengeId, Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
        }
    }

    @Test
    fun refusesRedeemForDifferentPairingTransaction() {
        val keys = FakeDeviceKeys()
        val client = PairingV2Client(keys, "android-device-1", "emrg-host-1") { 1_700_000_000L }
        val activePairingId = b64(32)
        val otherPairingId = b64(32, seed = 3)
        val challengeId = b64(16)
        client.createRequestFrame(activePairingId)
        val raw = challenge(otherPairingId, challengeId, "android-device-1", keys.publicKeyDigest(), 1_700_000_100L)
        assertThrows(IllegalArgumentException::class.java) {
            client.createRedeemFrame(otherPairingId, challengeId, Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
        }
    }

    @Test
    fun startingNewRequestInvalidatesPreviousPairingTransaction() {
        val keys = FakeDeviceKeys()
        val client = PairingV2Client(keys, "android-device-1", "emrg-host-1") { 1_700_000_000L }
        val oldPairingId = b64(32)
        val newPairingId = b64(32, seed = 4)
        val challengeId = b64(16)
        client.createRequestFrame(oldPairingId)
        client.createRequestFrame(newPairingId)
        val raw = challenge(
            oldPairingId, challengeId, "android-device-1",
            keys.publicKeyDigest(), 1_700_000_100L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            client.createRedeemFrame(
                oldPairingId, challengeId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(raw),
            )
        }
    }

    @Test
    fun refusesRedeemBeforeCreatingDeviceRequest() {
        val client = PairingV2Client(FakeDeviceKeys(), "android-device-1", "emrg-host-1")
        assertThrows(IllegalStateException::class.java) {
            client.createRedeemFrame(b64(32), b64(16), b64(64))
        }
    }

    @Test
    fun refusesMalformedUtf8InChallenge() {
        val keys = FakeDeviceKeys()
        val client = PairingV2Client(keys, "android-device-1", "emrg-host-1") { 1_700_000_000L }
        val pairingId = b64(32)
        val challengeId = b64(16)
        client.createRequestFrame(pairingId)
        val raw = challenge(pairingId, challengeId, "android-device-1", keys.publicKeyDigest(), 1_700_000_100L)
        val marker = "host-identity".toByteArray(Charsets.UTF_8)
        val offset = raw.indices.first { index ->
            index + marker.size <= raw.size && raw.copyOfRange(index, index + marker.size).contentEquals(marker)
        }
        raw[offset + 5] = 0xC3.toByte()
        assertThrows(IllegalArgumentException::class.java) {
            client.createRedeemFrame(pairingId, challengeId, Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
        }
    }

    @Test
    fun refusesChallengeWithWrongJsonFieldTypes() {
        val keys = FakeDeviceKeys()
        val client = PairingV2Client(keys, "android-device-1", "emrg-host-1") { 1_700_000_000L }
        val pairingId = b64(32)
        val challengeId = b64(16)
        client.createRequestFrame(pairingId)
        val raw = challenge(pairingId, challengeId, "android-device-1", keys.publicKeyDigest(), 1_700_000_100L)
            .toString(Charsets.UTF_8)
            .replace("\"protocol\":\"2.0\"", "\"protocol\":2.0")
            .toByteArray(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            client.createRedeemFrame(pairingId, challengeId, Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
        }
    }

    @Test
    fun refusesChallengeWithUnexpectedScopeOrChallengeId() {
        val keys = FakeDeviceKeys()
        val client = PairingV2Client(keys, "android-device-1", "emrg-host-1") { 1_700_000_000L }
        val pairingId = b64(32)
        val challengeId = b64(16)
        client.createRequestFrame(pairingId)
        val wrongId = challenge(pairingId, b64(16, seed = 2), "android-device-1", keys.publicKeyDigest(), 1_700_000_100L)
        assertThrows(IllegalArgumentException::class.java) {
            client.createRedeemFrame(pairingId, challengeId, Base64.getUrlEncoder().withoutPadding().encodeToString(wrongId))
        }
        val raw = challenge(pairingId, challengeId, "android-device-1", keys.publicKeyDigest(), 1_700_000_100L)
            .toString(Charsets.UTF_8)
            .replace("\"sessions.list\"", "\"untrusted.scope\"")
            .toByteArray(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            client.createRedeemFrame(pairingId, challengeId, Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
        }
    }


    @Test
    fun refusesModified256BitEllipticCurveParameters() {
        val p256 = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val altered = ECParameterSpec(
            p256.curve,
            p256.generator,
            p256.order.add(BigInteger.ONE),
            p256.cofactor,
        )
        val client = PairingV2Client(FakeDeviceKeys(), "android-device-1", "emrg-host-1")
        assertEquals(false, client.isP256(altered))
        assertEquals(true, client.isP256(p256))
    }

    @Test
    fun refusesNonDerOrNonP256DevicePublicKey() {
        val invalidKeys = object : CompanionDeviceKeyManager {
            override fun createOrLoad(alias: String): String = b64(91)
            override fun sign(alias: String, challenge: ByteArray): String = b64(70)
            override fun delete(alias: String) = Unit
        }
        val client = PairingV2Client(invalidKeys, "android-device-1", "emrg-host-1")
        assertThrows(IllegalArgumentException::class.java) {
            client.createRequestFrame(b64(32))
        }
    }

    @Test
    fun validatesChallengeAndCredentialResponseTransactionBindings() {
        val keys = FakeDeviceKeys()
        val client = PairingV2Client(keys, "android-device-1", "emrg-host-1") { 1_700_000_000L }
        val pairingId = b64(32)
        val challengeId = b64(16)
        client.createRequestFrame(pairingId)
        val challengeBytes = challenge(
            pairingId, challengeId, "android-device-1",
            keys.publicKeyDigest(), 1_700_000_100L,
        )
        val response = org.json.JSONObject()
            .put("type", "pairing.challenge")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
            .put("challenge_id", challengeId)
            .put("challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes))
        val parsed = client.parseChallengeResponse(pairingId, response)
        assertEquals(pairingId, parsed.pairingId)
        assertEquals(challengeId, parsed.challengeId)

        val credentialResponse = org.json.JSONObject()
            .put("type", "pairing.credential")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
            .put("device_id", "android-device-1")
            .put("credential", "host-scoped-credential")
        assertEquals("host-scoped-credential", client.parseCredentialResponse(pairingId, credentialResponse))
    }

    @Test
    fun rejectsChallengeAndCredentialResponsesWithWrongBindingOrExtraFields() {
        val client = PairingV2Client(FakeDeviceKeys(), "android-device-1", "emrg-host-1")
        val pairingId = b64(32)
        client.createRequestFrame(pairingId)
        val challengeResponse = org.json.JSONObject()
            .put("type", "pairing.challenge")
            .put("protocol", "2.0")
            .put("pairing_id", b64(32, seed = 8))
            .put("challenge_id", b64(16))
            .put("challenge", b64(96))
        assertThrows(IllegalArgumentException::class.java) {
            client.parseChallengeResponse(pairingId, challengeResponse)
        }

        val credentialResponse = org.json.JSONObject()
            .put("type", "pairing.credential")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
            .put("device_id", "another-device")
            .put("credential", "credential")
        assertThrows(IllegalArgumentException::class.java) {
            client.parseCredentialResponse(pairingId, credentialResponse)
        }
        credentialResponse.put("device_id", "android-device-1").put("unexpected", true)
        assertThrows(IllegalArgumentException::class.java) {
            client.parseCredentialResponse(pairingId, credentialResponse)
        }
    }

    @Test
    fun rejectsMalformedCredentialInCredentialResponse() {
        val client = PairingV2Client(FakeDeviceKeys(), "android-device-1", "emrg-host-1")
        val pairingId = b64(32)
        client.createRequestFrame(pairingId)
        val response = org.json.JSONObject()
            .put("type", "pairing.credential")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
            .put("device_id", "android-device-1")
            .put("credential", " ")
        assertThrows(IllegalArgumentException::class.java) {
            client.parseCredentialResponse(pairingId, response)
        }
    }

}
