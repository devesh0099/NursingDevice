package com.example.nursingdevice

import android.content.Context
import android.util.Log
import com.google.gson.annotations.SerializedName

/**
 * The nurse's credential as returned by the backend register/rotate response.
 * (Matches the server's `credentials` object; base64-DER keys + PEM cert.)
 */
data class Credentials(
    @SerializedName("privateKey")    val privateKey: String?,
    @SerializedName("publicKey")     val publicKey: String?,
    @SerializedName("certificate")   val certificate: String?,
    @SerializedName("caCertificate") val caCertificate: String?
)

/** In-memory copy of the unlocked credential, used by CryptoUtils during NFC. */
object CredentialHolder {
    @Volatile var current: CredentialEntity? = null
    val isUnlocked get() = current != null
    fun clear() { current = null }
}

/** Outcome of an unlock attempt, with an exact reason on failure. */
sealed class UnlockResult {
    object Success : UnlockResult()
    /** PIN opened the store, but no credential is stored yet (register / rotate needed). */
    object NoCredential : UnlockResult()
    /** The store could not be opened — [reason] explains why (usually a wrong PIN). */
    data class Failed(val reason: String) : UnlockResult()
}

/**
 * Facade over the encrypted credential DB: derive the passphrase from PIN+id,
 * open the DB, and save / load the credential into CredentialHolder.
 *
 * Returns false when the PIN is wrong (SQLCipher fails to open) or no credential
 * is stored yet.
 */
object CredentialStore {

    /**
     * Unlock with a PIN and load the stored credential into memory.
     * Returns a specific [UnlockResult] so the UI can show the exact problem.
     */
    fun unlock(context: Context, pin: String, nurseId: String): UnlockResult {
        return try {
            val db = open(context, pin, nurseId)
            val cred = db.credentialDao().getCredential()   // first query — fails here if PIN is wrong
            CredentialHolder.current = cred
            if (cred != null) UnlockResult.Success else UnlockResult.NoCredential
        } catch (e: Exception) {
            Log.e("CredentialStore", "unlock failed", e)
            NursingDeviceDatabase.reset()
            val m = e.message.orEmpty()
            val reason = if (m.contains("not a database", true) || m.contains("encrypted", true) ||
                m.contains("file is not", true) || m.contains("SQLITE_NOTADB", true)) {
                "incorrect PIN (could not decrypt the credential store)"
            } else {
                "could not open the credential store: $m"
            }
            UnlockResult.Failed(reason)
        }
    }

    /**
     * Save credentials received from the server at registration, encrypting them
     * under a freshly set PIN. Also loads them into memory for immediate use.
     */
    fun saveFromServer(
        context: Context,
        pin: String,
        nurseId: String,
        role: String,
        creds: Credentials
    ): Boolean {
        val priv = creds.privateKey
        val pub = creds.publicKey
        val cert = creds.certificate
        val ca = creds.caCertificate
        if (priv.isNullOrBlank() || pub.isNullOrBlank() || cert.isNullOrBlank() || ca.isNullOrBlank()) {
            Log.w("CredentialStore", "Incomplete credentials from server; not saving.")
            return false
        }
        return try {
            val db = open(context, pin, nurseId)
            val entity = CredentialEntity(
                ownerId = nurseId,
                role = role,
                privateKeyB64 = priv,
                publicKeyB64 = pub,
                certPem = cert,
                caCertPem = ca
            )
            db.credentialDao().upsert(entity)
            CredentialHolder.current = entity
            true
        } catch (e: Exception) {
            Log.e("CredentialStore", "saveFromServer failed", e)
            false
        }
    }

    fun lock() {
        CredentialHolder.clear()
        NursingDeviceDatabase.reset()
    }

    private fun open(context: Context, pin: String, nurseId: String): NursingDeviceDatabase {
        // Always drop any cached handle first. getInstance() reuses an already-open
        // connection and IGNORES the passphrase, so SQLCipher only ever validates the
        // PIN on a genuine open. Without this reset:
        //   - login would accept ANY pin once the DB had been opened (wrong-PIN bug), and
        //   - a save could persist the credential under a stale passphrase from a prior
        //     failed unlock (register-after-failed-login bug).
        // Reopening is cheap and NFC reads keys from the in-memory CredentialHolder, not
        // this handle, so closing it between operations is safe.
        NursingDeviceDatabase.reset()
        val salt = PinCrypto.getOrCreateSalt(context)
        val passphrase = PinCrypto.deriveKey(pin, nurseId, salt)
        return NursingDeviceDatabase.getInstance(context, passphrase)
    }
}
