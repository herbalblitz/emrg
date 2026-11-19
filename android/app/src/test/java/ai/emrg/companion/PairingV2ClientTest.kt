package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
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
    fun refusesRedeemBeforeCreatingDeviceRequest() {
        val client = PairingV2Client(FakeDeviceKeys(), "android-device-1", "emrg-host-1")
        assertThrows(IllegalStateException::class.java) {
            client.createRedeemFrame(b64(32), b64(16), b64(64))
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

}
