package com.emrg.app.net

import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** What the host's certificate says, before the user trusts it. */
data class CertificateProbe(
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val notAfter: String,
)

/**
 * Certificate pinning for the gateway's self-signed listener.
 *
 * The host is identified by the SHA-256 of its DER certificate, the same string
 * `emrg-gateway pair` prints and the same one the user compares on the phone.
 * There is no CA and no DNS name in this trust decision: a phone that pairs on
 * a LAN address keeps working when DHCP moves the host, and a certificate
 * rotated on the host stops matching until every phone pairs again — which is
 * the intended, visible consequence of `emrg-gateway rotate-cert`.
 *
 * The pin is checked in [PinnedTrustManager.checkServerTrusted], which OkHttp
 * calls for the leaf. Hostname verification is deliberately reduced to `true`
 * *only* because the pin already names the exact certificate; there is no name
 * left for a hostname check to add.
 */
object Tls {

    /** `AA:BB:…` with no separators, so a user's paste always compares equal. */
    fun normalizeFingerprint(raw: String): String =
        raw.filter { it.isLetterOrDigit() }.uppercase()

    fun fingerprintOf(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Read the certificate the host presents, without trusting it yet.
     *
     * This is the first half of pairing: the app shows this fingerprint and the
     * user compares it with the host's terminal before the one-shot code is
     * sent. Sending the code first would hand it to whoever answered the port.
     */
    fun probe(host: String, port: Int, timeoutMs: Int = 8_000): CertificateProbe {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(TrustAll), SecureRandom())
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.use { ssl ->
            ssl.soTimeout = timeoutMs
            ssl.connect(InetSocketAddress(host, port), timeoutMs)
            ssl.startHandshake()
            val cert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw CertificateException("the host presented no certificate")
            return CertificateProbe(
                fingerprint = fingerprintOf(cert),
                subject = cert.subjectDN?.name.orEmpty(),
                issuer = cert.issuerDN?.name.orEmpty(),
                notAfter = cert.notAfter?.toString().orEmpty(),
            )
        }
    }

    fun pinnedSocketFactory(pin: String): SSLSocketFactory {
        val expected = normalizeFingerprint(pin)
        require(expected.length == 64) { "a certificate fingerprint is 64 hex digits" }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(PinnedTrustManager(expected)), SecureRandom())
        return context.socketFactory
    }

    fun pinningTrustManager(pin: String): X509TrustManager =
        PinnedTrustManager(normalizeFingerprint(pin))

    /** The pin is the identity, so any name that reached this certificate is fine. */
    val pinnedHostnameVerifier = HostnameVerifier { _, _ -> true }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class PinnedTrustManager(private val expected: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("the host presented no certificate")
            val actual = normalizeFingerprint(fingerprintOf(leaf))
            if (actual != expected) {
                throw CertificateException(
                    "certificate mismatch: the host presented $actual, the app pinned $expected"
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
