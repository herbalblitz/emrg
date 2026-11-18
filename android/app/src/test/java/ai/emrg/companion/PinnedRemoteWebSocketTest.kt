package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PinnedRemoteWebSocketTest {
    private class EncodedCertificate(private val bytes: ByteArray) : Certificate("test") {
        override fun getEncoded(): ByteArray = bytes
        override fun verify(key: java.security.PublicKey) = Unit
        override fun verify(key: java.security.PublicKey, sigProvider: String) = Unit
        override fun toString(): String = "test certificate"
        override fun getPublicKey(): java.security.PublicKey? = null
    }

    @Test fun authSuccessRequiresProtocolAndApprovedWorkspace() {
        val host = PairedHost(
            endpoint = "wss://host.example",
            hostFingerprintSha256 = "AA:".repeat(31) + "AA",
            deviceId = "phone",
            credentialAlias = "cred",
            scopes = setOf(PairingScope.SEND_CHAT),
            approvedWorkspaceId = "workspace-expected",
            approvedCwd = "/approved"
        )
        val matching = ProtocolFrame.parse("""{"type":"auth_ok","protocol":"1.0","workspace_id":"workspace-expected"}""")
        val wrongWorkspace = ProtocolFrame.parse("""{"type":"auth_ok","protocol":"1.0","workspace_id":"other"}""")
        val missingProtocol = ProtocolFrame.parse("""{"type":"auth_ok","workspace_id":"workspace-expected"}""")
        assertEquals(true, PinnedRemoteWebSocket.isValidAuthSuccess(matching, host))
        assertEquals(false, PinnedRemoteWebSocket.isValidAuthSuccess(wrongWorkspace, host))
        assertEquals(false, PinnedRemoteWebSocket.isValidAuthSuccess(missingProtocol, host))
    }

    @Test fun chatFrameUsesCompanionAllowlistAndHostApprovedWorkspace() {
        val command = TaskCommand("request-9", "/approved/path", "hi", "session-2", listOf("shell"), "now")
        val frame = PinnedRemoteWebSocket.companionChatFrame(command, "workspace-3")
        assertEquals("chat.send", frame.getString("type"))
        assertEquals("request-9", frame.getString("request_id"))
        assertEquals("workspace-3", frame.getString("workspace_id"))
        assertEquals("hi", frame.getString("prompt"))
        assertEquals("session-2", frame.getString("session_id"))
        assertEquals(false, frame.has("cwd"))
        assertEquals(false, frame.has("tools"))
        assertEquals(false, frame.has("timestamp"))
    }

    @Test fun chatFrameRequiresApprovedWorkspace() {
        val command = TaskCommand("r", "/approved/path", "hi")
        try {
            PinnedRemoteWebSocket.companionChatFrame(command, "")
            throw AssertionError("empty workspace should be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun chatFrameRejectsOversizedOrBlankIdentifiers() {
        val badId = TaskCommand("r".repeat(129), "/w", "prompt")
        val badSession = TaskCommand("r", "/w", "prompt", " ")
        for (command in listOf(badId, badSession)) {
            try {
                PinnedRemoteWebSocket.companionChatFrame(command, "workspace")
                throw AssertionError("invalid frame should be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun authFrameIncludesRequiredProtocolAndFreshNonce() {
        val first = PinnedRemoteWebSocket.createAuthFrame("device-7", "credential-test")
        val second = PinnedRemoteWebSocket.createAuthFrame("device-7", "credential-test")
        assertEquals("auth", first.getString("type"))
        assertEquals("1.0", first.getString("protocol"))
        assertEquals("device-7", first.getString("device_id"))
        assertEquals("credential-test", first.getString("credential"))
        assertEquals(true, first.getString("client_nonce").isNotBlank())
        assertEquals(false, first.getString("client_nonce") == second.getString("client_nonce"))
    }

    @Test fun certificateFingerprintUsesSha256ColonHexFormat() {
        val fingerprint = PinnedRemoteWebSocket.certificateFingerprint(EncodedCertificate(byteArrayOf(1, 2, 3)))
        assertEquals("03:90:58:C6:F2:C0:CB:49:2C:53:3B:0A:4D:14:EF:77:CC:0F:78:AB:CC:CE:D5:28:7D:84:A1:A2:01:1C:FB:81", fingerprint)
    }

    private fun testCertificate(): X509Certificate {
        val stream = PinnedRemoteWebSocketTest::class.java.classLoader!!.getResourceAsStream("companion-test-cert.pem")
            ?: throw AssertionError("test certificate fixture missing")
        return stream.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    @Test fun exactPinTrustManagerAcceptsOnlyMatchingLeafCertificate() {
        val certificate = testCertificate()
        val expected = PinnedRemoteWebSocket.certificateFingerprint(certificate)
        val manager = ExactPinTrustManager(expected)
        manager.checkServerTrusted(arrayOf(certificate), "ECDSA")
        assertTrue(manager.acceptedIssuers.isEmpty())
    }

    @Test fun exactPinTrustManagerRejectsWrongFingerprint() {
        val certificate = testCertificate()
        val wrongPin = "00".repeat(32)
        val manager = ExactPinTrustManager(wrongPin)
        try {
            manager.checkServerTrusted(arrayOf(certificate), "ECDSA")
            fail("pin mismatch must fail TLS trust")
        } catch (_: java.security.cert.CertificateException) { }
    }

    @Test fun exactPinTrustManagerRejectsMissingCertificate() {
        val manager = ExactPinTrustManager("11".repeat(32))
        try {
            manager.checkServerTrusted(emptyArray(), "ECDSA")
            fail("missing cert must fail")
        } catch (_: java.security.cert.CertificateException) { }
    }

    @Test fun pinManagerRejectsMalformedFingerprint() {
        try {
            ExactPinTrustManager("not-a-pin")
            fail("invalid pin must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
