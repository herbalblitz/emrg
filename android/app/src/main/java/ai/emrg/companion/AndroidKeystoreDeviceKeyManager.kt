package ai.emrg.companion

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.AlgorithmParameters
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * Owns a non-exportable P-256 signing identity for one Companion host.
 *
 * The private key is created and retained by Android Keystore. Only the DER
 * SubjectPublicKeyInfo public key and DER ECDSA signatures cross the wire.
 * This class deliberately has no software-key fallback.
 */
internal interface CompanionDeviceKeyManager {
    fun createOrLoad(alias: String): String
    fun sign(alias: String, challenge: ByteArray): String
    fun delete(alias: String)
}

internal class AndroidKeystoreDeviceKeyManager : CompanionDeviceKeyManager {
    @Synchronized
    override fun createOrLoad(alias: String): String {
        requireValidAlias(alias)
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE_PROVIDER,
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generator.generateKeyPair()
        }
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Device identity is unavailable")
        check(entry.privateKey.encoded == null) {
            "Device private key is exportable"
        }
        val publicKey = entry.certificate.publicKey as? ECPublicKey
            ?: throw IllegalStateException("Device public key is invalid")
        check(isP256(publicKey.params)) {
            "Device key is not P-256"
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.encoded)
    }

    @Synchronized
    override fun sign(alias: String, challenge: ByteArray): String {
        requireValidAlias(alias)
        require(challenge.isNotEmpty()) { "Challenge is empty" }
        val keyStore = loadKeyStore()
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Device identity is unavailable")
        check(entry.privateKey.encoded == null) {
            "Device private key is exportable"
        }
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(entry.privateKey)
        signature.update(challenge)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())
    }

    @Synchronized
    override fun delete(alias: String) {
        requireValidAlias(alias)
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun isP256(actual: ECParameterSpec): Boolean {
        val expected = try {
            AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec(P256_CURVE))
            }.getParameterSpec(ECParameterSpec::class.java)
        } catch (_: Exception) {
            return false
        }
        return actual.curve == expected.curve &&
            actual.generator == expected.generator &&
            actual.order == expected.order &&
            actual.cofactor == expected.cofactor
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

    private fun requireValidAlias(alias: String) {
        require(alias.matches(ALIAS_PATTERN)) { "Device key alias is invalid" }
    }

    private companion object {
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val P256_CURVE = "secp256r1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        val ALIAS_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
