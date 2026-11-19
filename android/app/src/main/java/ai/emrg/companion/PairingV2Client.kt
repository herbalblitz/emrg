package ai.emrg.companion

import org.json.JSONObject
import java.security.KeyFactory
import java.security.AlgorithmParameters
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.security.SecureRandom

/** A validated challenge envelope returned by the dedicated host WSS endpoint. */
internal data class PairingV2Challenge(
    val pairingId: String,
    val challengeId: String,
    val encodedChallenge: String,
)

/**
 * Builds the device-key-bound Pairing v2 request and redeem frames.
 *
 * This is a transport-neutral protocol helper: it does not open sockets, approve
 * requests, or issue credentials. The caller must send frames only over the
 * separately pinned WSS channel and must wait for trusted host approval.
 */
internal class PairingV2Client(
    private val keys: CompanionDeviceKeyManager,
    internal val deviceId: String,
    private val keyAlias: String,
    requestedScopes: Set<String> = setOf("status"),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    /** SHA-256 identity from the QR descriptor, compared with the signed host challenge. */
    private val expectedHostIdentity: String,
) {
    internal val requestedScopes: Set<String> = requestedScopes.toSet()
    private var publicKeyBytes: ByteArray? = null
    private var activePairingId: String? = null
    private var activeClientNonce: String? = null

    @Synchronized
    fun createRequestFrame(pairingId: String): JSONObject {
        publicKeyBytes = null
        activePairingId = null
        activeClientNonce = null
        requireCanonicalBase64Url(pairingId, 32, "pairing id")
        require(deviceId.matches(DEVICE_ID_PATTERN)) { "Device ID is invalid" }
        val encodedPublicKey = keys.createOrLoad(keyAlias)
        val rawPublicKey = decodeCanonicalBase64Url(encodedPublicKey, MIN_PUBLIC_KEY_BYTES, MAX_PUBLIC_KEY_BYTES)
        val parsedPublicKey = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(rawPublicKey)) as? ECPublicKey
        } catch (_: Exception) {
            null
        }
        require(parsedPublicKey != null && isP256(parsedPublicKey.params)) {
            "Device public key is not P-256"
        }
        require(requestedScopes.isNotEmpty() && requestedScopes.size <= 3 &&
            requestedScopes.all { it in ALLOWED_SCOPES }) { "Requested scopes are invalid" }
        val nonceBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val clientNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        publicKeyBytes = rawPublicKey
        activePairingId = pairingId
        activeClientNonce = clientNonce
        return JSONObject()
            .put("type", "pairing.request")
            .put("protocol", "2.0")
            .put("pairing_id", pairingId)
            .put("device_id", deviceId)
            .put("key_algorithm", KEY_ALGORITHM)
            .put("public_key", encodedPublicKey)
            .put("requested_scopes", org.json.JSONArray(requestedScopes.sorted()))
            .put("client_nonce", clientNonce)
    }

    /** Validate the host's challenge envelope before interpreting its signed payload. */
    @Synchronized
    fun parseChallengeResponse(pairingId: String, response: JSONObject): PairingV2Challenge {
        require(activePairingId == pairingId && publicKeyBytes != null) {
            "Pairing request does not match this transaction"
        }
        require(response.length() == 5 &&
            response.opt("type") == "pairing.challenge" &&
            response.opt("protocol") == "2.0" &&
            response.opt("pairing_id") == pairingId &&
            response.opt("challenge_id") is String &&
            response.opt("challenge") is String
        ) { "Pairing challenge response is invalid" }
        val challengeId = response.getString("challenge_id")
        requireCanonicalBase64Url(challengeId, 16, "challenge id")
        val encodedChallenge = response.getString("challenge")
        decodeCanonicalBase64Url(encodedChallenge, 1, MAX_CHALLENGE_BYTES)
        return PairingV2Challenge(pairingId, challengeId, encodedChallenge)
    }

    /** Accept a credential only when the response is bound to this device and transaction. */
    @Synchronized
    fun parseCredentialResponse(pairingId: String, response: JSONObject): String {
        require(activePairingId == pairingId) {
            "Credential response does not match this transaction"
        }
        require(response.length() == 5 &&
            response.opt("type") == "pairing.credential" &&
            response.opt("protocol") == "2.0" &&
            response.opt("pairing_id") == pairingId &&
            response.opt("device_id") == deviceId &&
            response.opt("credential") is String
        ) { "Pairing credential response is invalid" }
        val credential = response.getString("credential")
        require(AndroidKeystoreCredentialStore.isValidCredential(credential)) {
            "Pairing credential is invalid"
        }
        return credential
    }

    @Synchronized
    fun createRedeemFrame(pairingId: String, challengeId: String, encodedChallenge: String): JSONObject {
        val publicKey = publicKeyBytes
            ?: throw IllegalStateException("Pairing request must be created before redeem")
        requireCanonicalBase64Url(pairingId, 32, "pairing id")
        requireCanonicalBase64Url(challengeId, 16, "challenge id")
        require(activePairingId == pairingId) { "Pairing request does not match this transaction" }
        val rawChallenge = decodeCanonicalBase64Url(encodedChallenge, 1, MAX_CHALLENGE_BYTES)
        val challenge = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val json = decoder.decode(ByteBuffer.wrap(rawChallenge)).toString()
            JSONObject(json)
        } catch (_: Exception) {
            throw IllegalArgumentException("Pairing challenge is invalid")
        }
        require(challenge.opt("client_nonce") is String &&
            challenge.getString("client_nonce") == activeClientNonce) {
            "Pairing challenge client nonce mismatch"
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
        val challengeHostIdentity = challenge.opt("host_identity")
        require(challengeHostIdentity is String &&
            normalizeHostIdentity(challengeHostIdentity) == normalizeHostIdentity(expectedHostIdentity)) {
            "Pairing challenge host identity does not match the QR-pinned host"
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
        val now = nowEpochSeconds()
        require(expiresAt > now) { "Pairing challenge expired" }
        require(expiresAt <= now + MAX_CHALLENGE_LIFETIME_SECONDS) {
            "Pairing challenge expiry exceeds the allowed lifetime"
        }
        val nonceValue = challenge.opt("nonce")
        require(nonceValue is String) { "Pairing challenge nonce is invalid" }
        val nonce = nonceValue
        requireCanonicalBase64Url(nonce, 32, "challenge nonce")
        val scopes = challenge.optJSONArray("scopes") ?: throw IllegalArgumentException("Pairing scopes are missing")
        require(scopes.length() in 1..3) { "Pairing scopes are invalid" }
        val allowedScopes = ALLOWED_SCOPES
        val seenScopes = HashSet<String>()
        for (index in 0 until scopes.length()) {
            val scopeValue = scopes.opt(index)
            require(scopeValue is String) { "Pairing scopes are invalid" }
            val scope = scopeValue
            require(scope in allowedScopes && seenScopes.add(scope)) { "Pairing scopes are invalid" }
        }

        require(seenScopes == requestedScopes) { "Pairing host changed requested scopes" }

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

    internal fun isP256(actual: ECParameterSpec): Boolean {
        val expected = try {
            AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
        } catch (_: Exception) {
            return false
        }
        return actual.curve == expected.curve &&
            actual.generator == expected.generator &&
            actual.order == expected.order &&
            actual.cofactor == expected.cofactor
    }

    private fun normalizeHostIdentity(value: String): String =
        value.replace(":", "").replace(" ", "").trim().uppercase()
            .also { require(it.matches(Regex("[0-9A-F]{64}"))) { "Host identity fingerprint is invalid" } }

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
        const val MAX_CHALLENGE_LIFETIME_SECONDS = 180
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        val BASE64URL_PATTERN = Regex("[A-Za-z0-9_-]+")
        val ALLOWED_SCOPES = setOf("status", "sessions.list", "chat.send")
    }
}
