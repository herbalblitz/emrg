package com.emrg.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device tokens at rest.
 *
 * The token is a bearer credential for a host that can run shell commands, so
 * it does not belong in plain `SharedPreferences`. It is encrypted with an
 * AES-256-GCM key that lives in Android Keystore and is never exportable; the
 * preference file holds only `iv:ciphertext` in Base64. A backup of the app's
 * data is disabled in the manifest, and a key that cannot be unwrapped (a
 * restored device, a cleared keystore) reads as "no token", which sends the
 * user to pairing instead of failing with a crash.
 */
class SecureTokenStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("emrg_secure_tokens", Context.MODE_PRIVATE)

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun put(hostId: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(hostId, value).commit()
    }

    fun get(hostId: String): String? {
        val value = prefs.getString(hostId, null) ?: return null
        val parts = value.split(":", limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val body = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(hostId: String) {
        prefs.edit().remove(hostId).apply()
    }

    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Not user-authentication-bound: a chat notification or a
                // reconnect after a reboot must not require a fingerprint
                // prompt in the middle of a running turn. The key is still
                // non-exportable and app-private.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "emrg.device.token.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
