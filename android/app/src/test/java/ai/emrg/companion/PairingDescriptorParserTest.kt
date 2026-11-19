package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingDescriptorParserTest {
    private val fingerprint = "AA:".repeat(31) + "AA"

    @Test fun acceptsValidVersionOneDescriptor() {
        val json = """{"version":1,"endpoint":"wss://host.example:56031","fingerprint_sha256":"$fingerprint","expires_at":1100}"""
        val result = PairingDescriptorParser.parse(json, 1000)
        assertTrue(result is PairingDescriptorResult.Valid)
        val descriptor = (result as PairingDescriptorResult.Valid).descriptor
        assertEquals("wss://host.example:56031", descriptor.endpoint)
        assertEquals(fingerprint, descriptor.fingerprintSha256)
        assertEquals(1100L, descriptor.expiresAtEpochSeconds)
    }

    @Test fun rejectsPlaintextEndpoint() {
        val json = """{"version":1,"endpoint":"ws://host.example","fingerprint_sha256":"$fingerprint"}"""
        assertEquals("INVALID_ENDPOINT", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsMissingExpiryAndLongLivedDescriptors() {
        val noExpiry = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint"}"""
        assertEquals("MISSING_EXPIRY", (PairingDescriptorParser.parse(noExpiry, 1000) as PairingDescriptorResult.Invalid).reason)
        val tooFar = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","expires_at":2000}"""
        assertEquals("EXPIRY_TOO_FAR", (PairingDescriptorParser.parse(tooFar, 1000) as PairingDescriptorResult.Invalid).reason)
        val fractional = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","expires_at":1001.5}"""
        assertEquals("INVALID_EXPIRY", (PairingDescriptorParser.parse(fractional, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsExpiredDescriptor() {
        val json = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","expires_at":999}"""
        assertEquals("EXPIRED", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsCredentialBearingPayload() {
        val json = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","token":"secret"}"""
        assertEquals("CREDENTIAL_IN_DESCRIPTOR", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsUnsupportedVersionAndOversizedPayload() {
        val version = """{"version":3,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint"}"""
        assertEquals("UNSUPPORTED_VERSION", (PairingDescriptorParser.parse(version, 1000) as PairingDescriptorResult.Invalid).reason)
        assertEquals("INVALID_LENGTH", (PairingDescriptorParser.parse("x".repeat(2049), 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsMalformedJsonAndFingerprint() {
        assertEquals("MALFORMED_JSON", (PairingDescriptorParser.parse("{", 1000) as PairingDescriptorResult.Invalid).reason)
        val json = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"nope"}"""
        assertEquals("INVALID_FINGERPRINT", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsStringVersion() {
        val json = """{"version":"1","endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint"}"""
        assertEquals("UNSUPPORTED_VERSION", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsCredentialFieldsCaseInsensitively() {
        val json = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","TOKEN":"secret"}"""
        assertEquals("CREDENTIAL_IN_DESCRIPTOR", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsUnknownFields() {
        val json = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","display_name":"Phone"}"""
        assertEquals("UNSUPPORTED_FIELD", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsExpiryWithWrongJsonType() {
        val json = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","expires_at":"2000"}"""
        assertEquals("INVALID_EXPIRY", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun acceptsV2DescriptorWithCanonicalOpaquePairingId() {
        val pairingId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val json = """{"version":2,"endpoint":"wss://host.example:56031","fingerprint_sha256":"$fingerprint","expires_at":1100,"pairing_id":"$pairingId"}"""
        val result = PairingDescriptorParser.parse(json, 1000)
        assertTrue(result is PairingDescriptorResult.Valid)
        val descriptor = (result as PairingDescriptorResult.Valid).descriptor
        assertEquals(2, descriptor.version)
        assertEquals(pairingId, descriptor.pairingId)
    }

    @Test fun rejectsV2DescriptorWithoutCanonicalPairingId() {
        val missing = """{"version":2,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","expires_at":1100}"""
        assertEquals("INVALID_PAIRING_ID", (PairingDescriptorParser.parse(missing, 1000) as PairingDescriptorResult.Invalid).reason)
        val paddedId = "A".repeat(42) + "="
        val padded = """{"version":2,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","expires_at":1100,"pairing_id":"$paddedId"}"""
        assertEquals("INVALID_PAIRING_ID", (PairingDescriptorParser.parse(padded, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun v1DescriptorRemainsCompatibleAndHasNoPairingId() {
        val json = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","expires_at":1100}"""
        val descriptor = (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Valid).descriptor
        assertEquals(1, descriptor.version)
        assertEquals(null, descriptor.pairingId)
    }

    @Test fun rejectsDuplicateTopLevelFields() {
        val json = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","expires_at":1100,"version":1}"""
        assertEquals("DUPLICATE_FIELD", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsNonStringEndpointAndFingerprint() {
        val endpoint = """{"version":1,"endpoint":7,"fingerprint_sha256":"$fingerprint","expires_at":1100}"""
        val fingerprintValue = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":7,"expires_at":1100}"""
        assertEquals("INVALID_FIELD_TYPE", (PairingDescriptorParser.parse(endpoint, 1000) as PairingDescriptorResult.Invalid).reason)
        assertEquals("INVALID_FIELD_TYPE", (PairingDescriptorParser.parse(fingerprintValue, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsCredentialFieldAfterJsonEscapeDecoding() {
        val json = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","\\u0074oken":"secret"}"""
        assertEquals("CREDENTIAL_IN_DESCRIPTOR", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsNonStringV2PairingId() {
        val json = """{"version":2,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","expires_at":1100,"pairing_id":123}"""
        assertEquals("INVALID_PAIRING_ID", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsDuplicateKeysAfterJsonEscapeDecoding() {
        val json = """{"version":1,"\u0076ersion":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","expires_at":1100}"""
        assertEquals("DUPLICATE_FIELD", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }
}
