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

@Entity(tableName = "nurses")
data class NurseEntity(
    @PrimaryKey val nurseId: String,
    val name: String,
    val age: Int? = null,
    val gender: String? = null,
    val pointOfCare: String? = null,
    val contactNo: String? = null,
    val isCurrent: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "patient_context")
data class PatientContextEntity(
    @PrimaryKey val singletonId: Int = 1,
    val patientId: String,
    val name: String,
    val age: String,
    val gender: String,
    val bloodType: String,
    val rawJson: String,
    val scannedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_records")
data class LocalRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordType: String,
    val patientId: String? = null,
    val nurseId: String? = null,
    val fileName: String? = null,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface NurseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(nurse: NurseEntity)

    @Query("UPDATE nurses SET isCurrent = 0")
    fun clearCurrent()

    @Query("UPDATE nurses SET isCurrent = 1 WHERE nurseId = :nurseId")
    fun markCurrent(nurseId: String)

    @Query("SELECT * FROM nurses WHERE isCurrent = 1 LIMIT 1")
    fun getCurrentNurse(): NurseEntity?
}

@Dao
interface PatientContextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(patient: PatientContextEntity)

    @Query("SELECT * FROM patient_context WHERE singletonId = 1 LIMIT 1")
    fun getCurrentPatient(): PatientContextEntity?

    @Query("DELETE FROM patient_context")
    fun clear()
}

@Dao
interface LocalRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: LocalRecordEntity): Long

    @Query("SELECT * FROM local_records WHERE recordType = :recordType ORDER BY createdAt DESC")
    fun getRecordsByType(recordType: String): List<LocalRecordEntity>

    @Query("SELECT * FROM local_records WHERE recordType = :recordType AND patientId = :patientId ORDER BY createdAt DESC")
    fun getRecordsByTypeAndPatient(recordType: String, patientId: String): List<LocalRecordEntity>

    @Query("SELECT * FROM local_records WHERE recordType = :recordType ORDER BY createdAt DESC LIMIT 1")
    fun getLatestByType(recordType: String): LocalRecordEntity?

    @Query("SELECT * FROM local_records WHERE recordType = :recordType AND fileName = :fileName LIMIT 1")
    fun getByTypeAndFileName(recordType: String, fileName: String): LocalRecordEntity?
}

@Database(
    entities = [NurseEntity::class, PatientContextEntity::class, LocalRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NursingDeviceDatabase : RoomDatabase() {
    abstract fun nurseDao(): NurseDao
    abstract fun patientContextDao(): PatientContextDao
    abstract fun localRecordDao(): LocalRecordDao

    companion object {
        @Volatile
        private var INSTANCE: NursingDeviceDatabase? = null

        fun getInstance(context: Context): NursingDeviceDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NursingDeviceDatabase::class.java,
                    "nursing_device_room.db"
                )
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
