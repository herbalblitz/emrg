package ai.emrg.companion

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * Builds the device-key-bound Pairing v2 request and redeem frames.
 *
 * This is a transport-neutral protocol helper: it does not open sockets, approve
 * requests, or issue credentials. The caller must send frames only over the
 * separately pinned WSS channel and must wait for trusted host approval.
 */
internal class PairingV2Client(
    private val keys: CompanionDeviceKeyManager,
    private val deviceId: String,
    private val keyAlias: String,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    private var publicKeyBytes: ByteArray? = null

    @Synchronized
    fun createRequestFrame(pairingId: String): JSONObject {
        requireCanonicalBase64Url(pairingId, 32, "pairing id")
        require(deviceId.matches(DEVICE_ID_PATTERN)) { "Device ID is invalid" }
        val encodedPublicKey = keys.createOrLoad(keyAlias)
        val rawPublicKey = decodeCanonicalBase64Url(encodedPublicKey, MIN_PUBLIC_KEY_BYTES, MAX_PUBLIC_KEY_BYTES)
        val keyDigest = sha256Hex(rawPublicKey)
        publicKeyBytes = rawPublicKey
        return JSONObject()
            .put("type", "pairing.request")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
            .put("device_id", deviceId)
            .put("key_algorithm", KEY_ALGORITHM)
            .put("public_key", encodedPublicKey)
    }

    @Synchronized
    fun createRedeemFrame(pairingId: String, challengeId: String, encodedChallenge: String): JSONObject {
        requireCanonicalBase64Url(pairingId, 32, "pairing id")
        requireCanonicalBase64Url(challengeId, 16, "challenge id")
        val rawChallenge = decodeCanonicalBase64Url(encodedChallenge, 1, MAX_CHALLENGE_BYTES)
        val publicKey = publicKeyBytes
            ?: throw IllegalStateException("Pairing request must be created before redeem")
        val challenge = try {
            JSONObject(rawChallenge.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw IllegalArgumentException("Pairing challenge is invalid")
        }
        require(challenge.opt("protocol") is String && challenge.getString("protocol") == "2.0") {
            "Pairing challenge protocol mismatch"
        }
        require(challenge.opt("pairing_id") is String && challenge.getString("pairing_id") == pairingId) {
            "Pairing challenge transaction mismatch"
        }
        require(challenge.opt("challenge_id") is String && challenge.getString("challenge_id") == challengeId) {
            "Pairing challenge ID mismatch"
        }
        require(challenge.opt("device_id") is String && challenge.getString("device_id") == deviceId) {
            "Pairing challenge device mismatch"
        }
        require(challenge.opt("public_key_sha256") is String &&
            challenge.getString("public_key_sha256") == sha256Hex(publicKey)) {
            "Pairing challenge key mismatch"
        }
        require(challenge.opt("host_identity") is String && challenge.getString("host_identity").isNotBlank()) {
            "Pairing host identity is missing"
        }
        require(challenge.opt("workspace_id") is String && challenge.getString("workspace_id").isNotBlank()) {
            "Pairing workspace is missing"
        }
        val expiryValue = challenge.opt("expires_at")
        require(expiryValue is Number && expiryValue.toDouble().isFinite() &&
            expiryValue.toDouble() == expiryValue.toLong().toDouble()) {
            "Pairing challenge expiry is invalid"
        }
        val expiresAt = expiryValue.toLong()
        require(expiresAt > nowEpochSeconds()) { "Pairing challenge expired" }
        val nonceValue = challenge.opt("nonce")
        require(nonceValue is String) { "Pairing challenge nonce is invalid" }
        val nonce = nonceValue
        requireCanonicalBase64Url(nonce, 32, "challenge nonce")
        val scopes = challenge.optJSONArray("scopes") ?: throw IllegalArgumentException("Pairing scopes are missing")
        require(scopes.length() in 1..3) { "Pairing scopes are invalid" }
        val allowedScopes = setOf("status", "sessions.list", "chat.send")
        val seenScopes = HashSet<String>()
        for (index in 0 until scopes.length()) {
            val scopeValue = scopes.opt(index)
            require(scopeValue is String) { "Pairing scopes are invalid" }
            val scope = scopeValue
            require(scope in allowedScopes && seenScopes.add(scope)) { "Pairing scopes are invalid" }
        }

        // Sign the exact UTF-8 bytes returned by the host, not a reserialized JSON object.
        val signature = keys.sign(keyAlias, rawChallenge)
        decodeCanonicalBase64Url(signature, MIN_SIGNATURE_BYTES, MAX_SIGNATURE_BYTES)
        return JSONObject()
            .put("type", "pairing.redeem")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
            .put("device_id", deviceId)
            .put("challenge_id", challengeId)
            .put("signature", signature)
    }

    private fun requireCanonicalBase64Url(value: String, expectedBytes: Int, label: String) {
        val decoded = decodeCanonicalBase64Url(value, expectedBytes, expectedBytes)
        require(decoded.size == expectedBytes) { "$label is invalid" }
    }

    private fun decodeCanonicalBase64Url(value: String, minBytes: Int, maxBytes: Int): ByteArray {
        require(value.isNotEmpty() && value.length <= (maxBytes * 8 + 5) / 6) {
            "Base64URL value is invalid"
        }
        require(value.matches(BASE64URL_PATTERN)) { "Base64URL value is invalid" }
        val decoded = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Base64URL value is invalid")
        }
        require(decoded.size in minBytes..maxBytes) { "Base64URL value length is invalid" }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value) {
            "Base64URL value is not canonical"
        }
        return decoded
    }

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_ALGORITHM = "P-256"
        const val MIN_PUBLIC_KEY_BYTES = 80
        const val MAX_PUBLIC_KEY_BYTES = 256
        const val MIN_SIGNATURE_BYTES = 64
        const val MAX_SIGNATURE_BYTES = 80
        const val MAX_CHALLENGE_BYTES = 8192
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        val BASE64URL_PATTERN = Regex("[A-Za-z0-9_-]+")
    }
}
