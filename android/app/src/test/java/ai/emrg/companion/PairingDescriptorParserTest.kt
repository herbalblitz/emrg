package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingDescriptorParserTest {
    private val fingerprint = "AA:".repeat(31) + "AA"

    @Test fun acceptsValidVersionOneDescriptor() {
        val json = """{"version":1,"endpoint":"wss://host.example:56031","fingerprint_sha256":"$fingerprint","expires_at":2000}"""
        val result = PairingDescriptorParser.parse(json, 1000)
        assertTrue(result is PairingDescriptorResult.Valid)
        val descriptor = (result as PairingDescriptorResult.Valid).descriptor
        assertEquals("wss://host.example:56031", descriptor.endpoint)
        assertEquals(fingerprint, descriptor.fingerprintSha256)
        assertEquals(2000L, descriptor.expiresAtEpochSeconds)
    }

    @Test fun rejectsPlaintextEndpoint() {
        val json = """{"version":1,"endpoint":"ws://host.example","fingerprint_sha256":"$fingerprint"}"""
        assertEquals("INVALID_ENDPOINT", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
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
        val version = """{"version":2,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint"}"""
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

    @Test fun rejectsUnknownFields() {
        val json = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","display_name":"Phone"}"""
        assertEquals("UNSUPPORTED_FIELD", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }

    @Test fun rejectsExpiryWithWrongJsonType() {
        val json = """{"version":1,"endpoint":"wss://host.example","fingerprint_sha256":"$fingerprint","expires_at":"2000"}"""
        assertEquals("INVALID_EXPIRY", (PairingDescriptorParser.parse(json, 1000) as PairingDescriptorResult.Invalid).reason)
    }
}
