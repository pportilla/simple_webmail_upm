package io.github.pportilla.upmmail.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passwords encrypted with an AES-256/GCM key that never leaves the Android Keystore. The IV and
 * ciphertext are kept Base64-encoded in a private SharedPreferences file (excluded from backups
 * by the manifest, and useless without the device-bound key anyway).
 *
 * Any decryption failure (for example a key invalidated after the lock screen was removed)
 * yields null, so the UI asks for the password again instead of crashing.
 */
class KeystorePasswordStore(context: Context) : PasswordStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun get(accountId: Long): String? = synchronized(lock) {
        val stored = prefs.getString(key(accountId), null) ?: return null
        try {
            val iv = Base64.getDecoder().decode(stored.substringBefore(SEPARATOR))
            val ciphertext = Base64.getDecoder().decode(stored.substringAfter(SEPARATOR))
            val key = existingKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Stored password for account $accountId cannot be decrypted", e)
            null
        }
    }

    override fun set(accountId: Long, password: String) = synchronized(lock) {
        val cipher = try {
            encryptCipher(orCreateKey())
        } catch (e: Exception) {
            // The key may have been invalidated (e.g. security settings changed): start over.
            Log.w(TAG, "Recreating the password key", e)
            deleteKey()
            encryptCipher(orCreateKey())
        }
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val encoded = Base64.getEncoder().encodeToString(cipher.iv) + SEPARATOR + Base64.getEncoder().encodeToString(ciphertext)
        check(prefs.edit().putString(key(accountId), encoded).commit()) { "Could not store the password" }
    }

    override fun remove(accountId: Long) = synchronized(lock) {
        prefs.edit().remove(key(accountId)).apply()
    }

    private fun encryptCipher(key: SecretKey): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun orCreateKey(): SecretKey = existingKey() ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        generator.generateKey()
    }

    private fun deleteKey() {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun key(accountId: Long) = "password_$accountId"

    private companion object {
        const val TAG = "KeystorePasswordStore"
        const val PREFS_NAME = "upmmail_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "upmmail_password_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val SEPARATOR = ':'
    }
}
