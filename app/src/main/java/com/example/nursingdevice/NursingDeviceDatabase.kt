package com.example.nursingdevice

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * CAD encrypted store — CREDENTIALS ONLY.
 *
 * Per the design decision (CREDENTIALS_AND_STORAGE_PLAN.md §4): on the CAD, the
 * ONLY thing persisted to disk is the nurse's credential, encrypted at rest with
 * SQLCipher. Everything else (scanned patient context, vitals, fetched records)
 * lives in cache / in-memory (see NursePatientManager) and never touches disk in
 * plaintext.
 *
 * The database is opened with a PIN-derived passphrase (see PinCrypto); a wrong
 * PIN makes SQLCipher fail to open, which surfaces as "incorrect PIN".
 */
@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val ownerId: String,   // nurseId
    val role: String,                  // "nurse"
    val privateKeyB64: String,         // Kpri, PKCS#8 DER base64
    val publicKeyB64: String,          // Kpub, SPKI DER base64
    val certPem: String,               // CA-signed cert (PEM)
    val caCertPem: String,             // trust anchor for verifying peers
    val issuedAt: Long = 0,
    val expiresAt: Long = 0
)

@Dao
interface CredentialDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(credential: CredentialEntity)

    @Query("SELECT * FROM credentials LIMIT 1")
    fun getCredential(): CredentialEntity?

    @Query("DELETE FROM credentials")
    fun clear()
}

@Database(entities = [CredentialEntity::class], version = 1, exportSchema = false)
abstract class NursingDeviceDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao

    companion object {
        @Volatile
        private var INSTANCE: NursingDeviceDatabase? = null

        /**
         * Open (once) the encrypted credential DB with a PIN-derived passphrase.
         * SupportFactory zeroes the passphrase array it is given, so we hand it a copy.
         */
        fun getInstance(context: Context, passphrase: ByteArray): NursingDeviceDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context, passphrase).also { INSTANCE = it }
            }

        private fun build(context: Context, passphrase: ByteArray): NursingDeviceDatabase {
            SQLiteDatabase.loadLibs(context)
            val factory = SupportFactory(passphrase.copyOf())
            return Room.databaseBuilder(
                context.applicationContext,
                NursingDeviceDatabase::class.java,
                "nursing_device_creds.db"
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        }

        /** Drop the cached handle (e.g. on logout) so a different PIN can re-open. */
        fun reset() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
