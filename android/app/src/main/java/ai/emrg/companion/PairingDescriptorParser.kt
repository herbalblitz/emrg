package ai.emrg.companion

import org.json.JSONObject

/** Versioned QR descriptor parser. It carries host identity metadata only, never credentials. */
internal data class PairingDescriptor(
    val version: Int,
    val endpoint: String,
    val fingerprintSha256: String,
    val expiresAtEpochSeconds: Long?,
)

internal sealed interface PairingDescriptorResult {
    data class Valid(val descriptor: PairingDescriptor) : PairingDescriptorResult
    data class Invalid(val reason: String) : PairingDescriptorResult
}

internal object PairingDescriptorParser {
    private const val MAX_PAYLOAD_CHARS = 2048

    fun parse(payload: String, nowEpochSeconds: Long): PairingDescriptorResult {
        if (payload.length !in 1..MAX_PAYLOAD_CHARS) return PairingDescriptorResult.Invalid("INVALID_LENGTH")
        return try {
            val json = JSONObject(payload)
            if (json.optInt("version", -1) != 1) return PairingDescriptorResult.Invalid("UNSUPPORTED_VERSION")
            val endpoint = json.optString("endpoint", "").trim()
            val fingerprint = json.optString("fingerprint_sha256", "").trim()
            if (!isSecurePairingEndpoint(endpoint)) return PairingDescriptorResult.Invalid("INVALID_ENDPOINT")
            if (!isSha256Fingerprint(fingerprint)) return PairingDescriptorResult.Invalid("INVALID_FINGERPRINT")
            val expiry = if (json.has("expires_at")) {
                val raw = json.optLong("expires_at", -1L)
                if (raw <= nowEpochSeconds) return PairingDescriptorResult.Invalid("EXPIRED")
                raw
            } else null
            // Credential-like fields are expressly forbidden in QR payloads.
            if (json.has("credential") || json.has("token") || json.has("secret")) {
                return PairingDescriptorResult.Invalid("CREDENTIAL_IN_DESCRIPTOR")
            }
            PairingDescriptorResult.Valid(
                PairingDescriptor(1, endpoint, fingerprint.uppercase(), expiry)
            )
        } catch (_: Exception) {
            PairingDescriptorResult.Invalid("MALFORMED_JSON")
        }
    }
}
