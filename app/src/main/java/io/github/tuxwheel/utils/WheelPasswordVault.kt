package io.github.tuxwheel.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted storage for KingSong wheel PINs, keyed by wheel address.
 *
 * The PIN is sealed with an AES-256-GCM key that never leaves the Android Keystore; the prefs
 * file only ever holds per-wheel Base64(iv):Base64(ciphertext) entries. Losing the key (rare,
 * e.g. a Keystore wipe) only invalidates the stored PINs: decryption failure removes the
 * entry and the app falls back to asking the user again. Storing a PIN is what lets the
 * black box and the saved-wheel auto-connect authenticate on their own; nothing is required,
 * and a saved PIN can always be forgotten from the password dialog.
 */
class WheelPasswordVault(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(address: String, password: String): Boolean {
        if (address.isBlank() || password.length != PIN_LENGTH) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val sealed = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val entry = encode(cipher.iv) + ENTRY_SEPARATOR + encode(sealed)
            prefs.edit().putString(normalize(address), entry).commit()
        } catch (e: Exception) {
            false
        }
    }

    fun load(address: String): String? {
        if (address.isBlank()) return null
        val entry = prefs.getString(normalize(address), null) ?: return null
        return try {
            val parts = entry.split(ENTRY_SEPARATOR, limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, decode(parts[0])),
            )
            String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
        } catch (e: Exception) {
            remove(address)
            null
        }
    }

    fun remove(address: String) {
        prefs.edit().remove(normalize(address)).commit()
    }

    fun has(address: String): Boolean =
        address.isNotBlank() && prefs.contains(normalize(address))

    private fun secretKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(BLOCK_MODE)
                .setEncryptionPaddings(PADDING)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private fun normalize(address: String) = address.trim().uppercase()

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        private const val PREFS_NAME = "wheel_password_vault"
        private const val ENTRY_SEPARATOR = ":"
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "tuxwheel_wheel_pin"
        private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
        private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val TAG_LENGTH_BITS = 128
        private const val PIN_LENGTH = 4
    }
}
