package dev.vitals.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the device token with a non-exportable AES-256-GCM key held by
 * Android Keystore. SharedPreferences only sees `v1:<iv>:<ciphertext>`.
 *
 * The key has no user-authentication requirement: the watchdog must read the
 * token with the screen off. The app sandbox plus Keystore is the boundary; a
 * rooted device can still extract it while the app is installed.
 */
class TokenVault(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val blob = "v1:${base64(cipher.iv)}:${base64(encrypted)}"
        if (!prefs.edit().putString(KEY_TOKEN, blob).commit()) {
            throw IllegalStateException("cannot persist the encrypted token")
        }
    }

    /** Returns null when there is no token or the Keystore key is gone. */
    fun load(): String? {
        val blob = prefs.getString(KEY_TOKEN, null) ?: return null
        val key = existingKey() ?: run {
            Log.w(TAG, "encrypted token found but the Keystore key is missing")
            return null
        }
        return runCatching {
            val parts = blob.split(':')
            require(parts.size == 3 && parts[0] == "v1") { "unsupported token envelope" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, unbase64(parts[1])),
            )
            String(cipher.doFinal(unbase64(parts[2])), Charsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "cannot decrypt the token (${it.javaClass.simpleName}); re-pair required")
        }.getOrNull()
    }

    fun hasEncryptedToken(): Boolean = prefs.contains(KEY_TOKEN)

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private fun key(): SecretKey =
        existingKey() ?: generateKey()

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun base64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unbase64(text: String): ByteArray =
        Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val PREFS = "vitals.session"
        const val KEY_TOKEN = "token_enc"
        const val ALIAS = "vitals.token.v1"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG = "VitalsVault"
    }
}
