package ai.emrg.companion

import java.util.Base64

/** Strict, platform-independent codec for the encrypted credential envelope. */
internal data class CredentialEnvelopeBytes(val iv: ByteArray, val ciphertext: ByteArray)

internal object CredentialEnvelope {
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        require(iv.size == IV_BYTES) { "Credential IV must be 12 bytes" }
        require(ciphertext.size >= TAG_BYTES) { "Credential ciphertext is too short" }
        return encoder.encodeToString(iv) + "." + encoder.encodeToString(ciphertext)
    }

    fun decode(value: String): CredentialEnvelopeBytes {
        val separator = value.indexOf('.')
        require(separator > 0 && separator == value.lastIndexOf('.') && separator < value.lastIndex) {
            "Stored credential format is invalid"
        }
        val ivText = value.substring(0, separator)
        val ciphertextText = value.substring(separator + 1)
        val iv = decodeCanonical(ivText)
        val ciphertext = decodeCanonical(ciphertextText)
        require(iv.size == IV_BYTES) { "Stored credential IV is invalid" }
        require(ciphertext.size >= TAG_BYTES) { "Stored credential ciphertext is invalid" }
        return CredentialEnvelopeBytes(iv, ciphertext)
    }

    private fun decodeCanonical(value: String): ByteArray {
        val decoded = try {
            decoder.decode(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Stored credential encoding is invalid")
        }
        require(encoder.encodeToString(decoded) == value) {
            "Stored credential encoding is not canonical"
        }
        return decoded
    }
}
