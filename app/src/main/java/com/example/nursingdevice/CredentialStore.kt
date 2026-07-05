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

/** Outcome of a credential save, with an exact reason on failure (for the UI). */
sealed class SaveResult {
    object Success : SaveResult()
    data class Failed(val reason: String) : SaveResult()
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
     * Returns a [SaveResult] carrying the exact reason on failure (for the UI).
     */
    fun saveFromServer(
        context: Context,
        pin: String,
        nurseId: String,
        role: String,
        creds: Credentials
    ): SaveResult {
        val priv = creds.privateKey
        val pub = creds.publicKey
        val cert = creds.certificate
        val ca = creds.caCertificate
        if (priv.isNullOrBlank() || pub.isNullOrBlank() || cert.isNullOrBlank() || ca.isNullOrBlank()) {
            Log.w("CredentialStore", "Incomplete credentials from server; not saving.")
            return SaveResult.Failed("server did not return a complete credential")
        }
        val entity = CredentialEntity(
            ownerId = nurseId,
            role = role,
            privateKeyB64 = priv,
            publicKeyB64 = pub,
            certPem = cert,
            caCertPem = ca
        )
        return try {
            writeCredential(context, pin, nurseId, entity)
            CredentialHolder.current = entity
            SaveResult.Success
        } catch (e: Exception) {
            Log.e("CredentialStore", "saveFromServer failed", e)
            SaveResult.Failed(describeSaveError(e))
        }
    }

    /**
     * Write the credential, healing a stale store. If the creds DB was created under
     * a DIFFERENT PIN/nurseId (e.g. an earlier registration attempt), SQLCipher can't
     * reopen it with this key and throws "file is not a database". The CAD store holds
     * ONLY server-issued credentials — which we're re-saving right now and can always
     * re-fetch — so it is safe to discard the old file and recreate it under this PIN.
     */
    private fun writeCredential(context: Context, pin: String, nurseId: String, entity: CredentialEntity) {
        try {
            open(context, pin, nurseId).credentialDao().upsert(entity)
        } catch (e: Exception) {
            if (!isKeyMismatch(e)) throw e
            Log.w("CredentialStore", "creds DB unreadable with this PIN — recreating it", e)
            NursingDeviceDatabase.reset()                     // close the broken handle first
            context.deleteDatabase(NursingDeviceDatabase.DB_NAME)  // drop file + journal/wal/shm
            open(context, pin, nurseId).credentialDao().upsert(entity)
        }
    }

    /** True if [e] (or any cause) is SQLCipher's "wrong key / not a database" signal. */
    private fun isKeyMismatch(e: Throwable?): Boolean {
        var c: Throwable? = e
        while (c != null) {
            val m = c.message?.lowercase().orEmpty()
            if (m.contains("not a database") || m.contains("file is not") ||
                m.contains("encrypted") || m.contains("sqlite_notadb")) return true
            c = c.cause
        }
        return false
    }

    private fun describeSaveError(e: Throwable): String =
        if (isKeyMismatch(e)) "the local secure store was locked under a different PIN"
        else e.message ?: e.javaClass.simpleName

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
