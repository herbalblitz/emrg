package ai.emrg.companion

import org.json.JSONObject

/** Versioned QR descriptor parser. It carries host identity metadata only, never credentials. */
internal data class PairingDescriptor(
    val version: Int,
    val endpoint: String,
    val fingerprintSha256: String,
    val expiresAtEpochSeconds: Long?,
    val pairingId: String? = null,
)

internal sealed interface PairingDescriptorResult {
    data class Valid(val descriptor: PairingDescriptor) : PairingDescriptorResult
    data class Invalid(val reason: String) : PairingDescriptorResult
}

internal object PairingDescriptorParser {
    private const val MAX_PAYLOAD_CHARS = 2048
    private const val MAX_DESCRIPTOR_LIFETIME_SECONDS = 180
    private val v1Fields = setOf("version", "endpoint", "fingerprint_sha256", "expires_at")
    private val v2Fields = v1Fields + "pairing_id"
    private val forbiddenCredentialFields = setOf("credential", "token", "secret", "password", "private_key", "authorization")

    /** Reject duplicate top-level names before JSONObject silently overwrites them. */
    private fun hasDuplicateTopLevelKeys(payload: String): Boolean {
        var index = 0
        var depth = 0
        val keys = HashSet<String>()
        while (index < payload.length) {
            when (payload[index].code) {
                123 -> { depth++; if (depth == 1) keys.clear(); index++ }
                125 -> { depth--; index++ }
                34 -> {
                    val start = index
                    index++
                    var escaped = false
                    while (index < payload.length) {
                        val ch = payload[index++]
                        if (escaped) escaped = false
                        else if (ch.code == 92) escaped = true
                        else if (ch.code == 34) break
                    }
                    if (depth == 1) {
                        var next = index
                        while (next < payload.length && payload[next].isWhitespace()) next++
                        if (next < payload.length && payload[next] == ':') {
                            val key = try {
                                JSONObject("{" + payload.substring(start, index) + ":null}").keys().next()
                            } catch (_: Exception) { return true }
                            if (!keys.add(key)) return true
                        }
                    }
                }
                else -> index++
            }
        }
        return false
    }

    private fun isCanonicalPairingId(value: String): Boolean {
        if (!value.matches(Regex("[A-Za-z0-9_-]{43}"))) return false
        return try {
            val decoded = java.util.Base64.getUrlDecoder().decode(value)
            decoded.size == 32 &&
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun parse(payload: String, nowEpochSeconds: Long): PairingDescriptorResult {
        if (payload.length !in 1..MAX_PAYLOAD_CHARS) return PairingDescriptorResult.Invalid("INVALID_LENGTH")
        return try {
            if (hasDuplicateTopLevelKeys(payload)) return PairingDescriptorResult.Invalid("DUPLICATE_FIELD")
            val json = JSONObject(payload)
            val versionValue = json.opt("version")
            if (versionValue !is Number || !versionValue.toDouble().isFinite() ||
                versionValue.toDouble() !in setOf(1.0, 2.0)
            ) return PairingDescriptorResult.Invalid("UNSUPPORTED_VERSION")
            val version = versionValue.toInt()
            val allowedFields = if (version == 1) v1Fields else v2Fields
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.lowercase() in forbiddenCredentialFields) return PairingDescriptorResult.Invalid("CREDENTIAL_IN_DESCRIPTOR")
                if (key !in allowedFields) return PairingDescriptorResult.Invalid("UNSUPPORTED_FIELD")
            }
            val endpointValue = json.opt("endpoint")
            val fingerprintValue = json.opt("fingerprint_sha256")
            if (endpointValue !is String || fingerprintValue !is String) {
                return PairingDescriptorResult.Invalid("INVALID_FIELD_TYPE")
            }
            val endpoint = endpointValue.trim()
            val fingerprint = fingerprintValue.trim()
            if (!isSecurePairingEndpoint(endpoint)) return PairingDescriptorResult.Invalid("INVALID_ENDPOINT")
            if (!isSha256Fingerprint(fingerprint)) return PairingDescriptorResult.Invalid("INVALID_FINGERPRINT")
            if (!json.has("expires_at")) return PairingDescriptorResult.Invalid("MISSING_EXPIRY")
            val value = json.get("expires_at")
            if (value !is Number) return PairingDescriptorResult.Invalid("INVALID_EXPIRY")
            val numeric = value.toDouble()
            if (!numeric.isFinite() || numeric != numeric.toLong().toDouble()) {
                return PairingDescriptorResult.Invalid("INVALID_EXPIRY")
            }
            val expiry = numeric.toLong()
            if (expiry <= nowEpochSeconds) return PairingDescriptorResult.Invalid("EXPIRED")
            if (numeric - nowEpochSeconds.toDouble() > MAX_DESCRIPTOR_LIFETIME_SECONDS.toDouble()) {
                return PairingDescriptorResult.Invalid("EXPIRY_TOO_FAR")
            }
            val pairingId = if (version == 2) {
                val value = json.optString("pairing_id", "")
                if (!isCanonicalPairingId(value)) return PairingDescriptorResult.Invalid("INVALID_PAIRING_ID")
                value
            } else null
            PairingDescriptorResult.Valid(
                PairingDescriptor(version, endpoint, fingerprint.uppercase(), expiry, pairingId)
            )
        } catch (_: Exception) {
            PairingDescriptorResult.Invalid("MALFORMED_JSON")
        }
    }
}
