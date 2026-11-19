package ai.emrg.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores host-scoped credentials encrypted with a non-exportable Android Keystore key.
 *
 * This storage layer does not perform pairing, host verification, or network transport.
 * Callers must only write credentials after the pairing state machine reaches approval.
 */
internal interface CredentialStore {
    fun put(alias: String, credential: String)
    fun get(alias: String): String?
    fun delete(alias: String)
}

internal class AndroidKeystoreCredentialStore(context: Context) : CredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun put(alias: String, credential: String) {
        requireValidAlias(alias)
        require(isValidCredential(credential)) { "Credential is invalid" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        cipher.updateAAD(alias.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(credential.toByteArray(StandardCharsets.UTF_8))
        val encoded = CredentialEnvelope.encode(cipher.iv, ciphertext)

        check(preferences.edit().putString(storageKey(alias), encoded).commit()) {
            "Credential storage write failed"
        }
    }

    @Synchronized
    override fun get(alias: String): String? {
        requireValidAlias(alias)
        val stored = preferences.getString(storageKey(alias), null) ?: return null
        val envelope = CredentialEnvelope.decode(stored)
        val iv = envelope.iv
        val ciphertext = envelope.ciphertext

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getKey(alias), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(alias.toByteArray(StandardCharsets.UTF_8))
        val plaintext = cipher.doFinal(ciphertext)
        val credential = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IllegalStateException("Stored credential text is invalid")
        }
        check(isValidCredential(credential)) { "Stored credential is invalid" }
        return credential
    }

    @Synchronized
    override fun delete(alias: String) {
        requireValidAlias(alias)
        check(preferences.edit().remove(storageKey(alias)).commit()) {
            "Credential storage deletion failed"
        }
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(keyAlias(alias))) keyStore.deleteEntry(keyAlias(alias))
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = loadKeyStore()
        val keystoreAlias = keyAlias(alias)
        if (keyStore.containsAlias(keystoreAlias)) {
            return (keyStore.getKey(keystoreAlias, null) as? SecretKey)
                ?: throw IllegalStateException("Keystore entry is not an AES key")
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keystoreAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getKey(alias: String): SecretKey {
        val key = loadKeyStore().getKey(keyAlias(alias), null)
        return key as? SecretKey ?: throw IllegalStateException("Credential key is missing")
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun storageKey(alias: String) = STORAGE_PREFIX + alias
    private fun keyAlias(alias: String) = KEY_PREFIX + alias

    companion object {
        private const val PREFERENCES_NAME = "emrg_secure_credentials"
        private const val STORAGE_PREFIX = "credential:"
        private const val KEY_PREFIX = "emrg_credential_"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128

        internal fun isValidCredentialAlias(alias: String): Boolean =
            alias.matches(Regex("[A-Za-z0-9._-]{1,64}"))

        internal fun isValidCredential(credential: String): Boolean {
            if (credential.isBlank()) return false
            return try {
                val encoded: ByteBuffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(credential))
                encoded.remaining() in 1..MAX_CREDENTIAL_BYTES
            } catch (_: CharacterCodingException) {
                false
            }
        }

        private const val MAX_CREDENTIAL_BYTES = 512

        private fun requireValidAlias(alias: String) {
            require(isValidCredentialAlias(alias)) { "Credential alias is invalid" }
        }
    }
}
