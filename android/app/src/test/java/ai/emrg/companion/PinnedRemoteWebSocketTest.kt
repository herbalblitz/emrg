package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.cert.Certificate

class PinnedRemoteWebSocketTest {
    private class EncodedCertificate(private val bytes: ByteArray) : Certificate("test") {
        override fun getEncoded(): ByteArray = bytes
        override fun verify(key: java.security.PublicKey) = Unit
        override fun verify(key: java.security.PublicKey, sigProvider: String) = Unit
        override fun toString(): String = "test certificate"
        override fun getPublicKey(): java.security.PublicKey? = null
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
}
