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

    @Test fun certificateFingerprintUsesSha256ColonHexFormat() {
        val fingerprint = PinnedRemoteWebSocket.certificateFingerprint(EncodedCertificate(byteArrayOf(1, 2, 3)))
        assertEquals("03:90:58:C6:F2:C0:CB:49:2C:53:3B:0A:4D:14:EF:77:CC:0F:78:AB:CC:CE:D5:28:7D:84:A1:A2:01:1C:FB:81", fingerprint)
    }
}
