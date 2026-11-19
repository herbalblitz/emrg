package ai.emrg.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureCredentialStoreTest {
    @Test
    fun acceptsSimpleScopedAliases() {
        assertTrue(AndroidKeystoreCredentialStore.isValidCredentialAlias("host-01"))
        assertTrue(AndroidKeystoreCredentialStore.isValidCredentialAlias("device.alpha_2"))
        assertTrue(AndroidKeystoreCredentialStore.isValidCredentialAlias("A"))
    }

    @Test
    fun rejectsEmptyLongOrUnsafeAliases() {
        assertFalse(AndroidKeystoreCredentialStore.isValidCredentialAlias(""))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredentialAlias("a".repeat(65)))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredentialAlias("../host"))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredentialAlias("host:token"))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredentialAlias("host with spaces"))
    }
    @Test
    fun acceptsOnlyNonBlankCredentialsWithinHostProtocolLimit() {
        assertTrue(AndroidKeystoreCredentialStore.isValidCredential("token"))
        assertTrue(AndroidKeystoreCredentialStore.isValidCredential("x".repeat(512)))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredential(""))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredential("   "))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredential("x".repeat(513)))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredential("é".repeat(257)))
    }

    @Test
    fun credentialEnvelopeRoundTripsCanonicalBase64() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(32) { (it + 1).toByte() }
        val encoded = CredentialEnvelope.encode(iv, ciphertext)
        val decoded = CredentialEnvelope.decode(encoded)
        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun credentialEnvelopeRejectsMalformedSeparatorsAndWhitespace() {
        val valid = CredentialEnvelope.encode(ByteArray(12), ByteArray(16))
        val malformed = listOf("", "abc", "abc.def.ghi", valid + " ", valid.replace(".", ".."))
        malformed.forEach { value ->
            try {
                CredentialEnvelope.decode(value)
                throw AssertionError("Expected malformed envelope to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun credentialEnvelopeRejectsInvalidIvAndCiphertextLengths() {
        val encoder = java.util.Base64.getEncoder()
        val shortIv = encoder.encodeToString(ByteArray(11))
        val validIv = encoder.encodeToString(ByteArray(12))
        val shortCiphertext = encoder.encodeToString(ByteArray(15))
        val validCiphertext = encoder.encodeToString(ByteArray(16))
        listOf("$shortIv.$validCiphertext", "$validIv.$shortCiphertext").forEach { value ->
            try {
                CredentialEnvelope.decode(value)
                throw AssertionError("Expected invalid envelope to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun credentialEnvelopeRejectsNonCanonicalBase64() {
        val encoder = java.util.Base64.getEncoder()
        val iv = encoder.encodeToString(ByteArray(12))
        val ciphertext = encoder.encodeToString(ByteArray(16)).trimEnd('=')
        try {
            CredentialEnvelope.decode("$iv.$ciphertext")
            throw AssertionError("Expected non-canonical Base64 to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

}
