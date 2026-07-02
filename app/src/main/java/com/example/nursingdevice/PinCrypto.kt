package com.example.nursingdevice

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Derives the SQLCipher passphrase for the encrypted credential store from the
 * nurse's PIN + their nurseId, using PBKDF2WithHmacSHA256 (256-bit key).
 *
 * The salt is random per install and stored in plain SharedPreferences — a salt
 * is not secret; it only prevents precomputation. The PIN itself is never stored.
 *
 * Design ref: CREDENTIALS_AND_STORAGE_PLAN.md §6.
 */
object PinCrypto {
    private const val PREFS = "cad_secure_prefs"
    private const val KEY_SALT = "pbkdf2_salt"
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256

    /** Random 16-byte salt, created once and reused across unlocks. */
    fun getOrCreateSalt(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SALT, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).apply()
        return salt
    }

    /** PBKDF2(PIN + ":" + ownerId, salt) -> 256-bit key bytes (SQLCipher passphrase). */
    fun deriveKey(pin: String, ownerId: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec("$pin:$ownerId".toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
