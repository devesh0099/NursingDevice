package com.example.nursingdevice

import android.content.Context
import org.json.JSONObject

data class Nurse(
    val name: String = "",
    val id: String = ""
)

data class Patient(
    val name: String,
    val age: Int,
    val gender: String,
    val bloodType: String,
    val patientId: String
)

class NursePatientManager(private val context: Context) {
    private val db = NursingDeviceDatabase.getInstance(context)

    fun saveNurse(nurse: Nurse) {
        db.nurseDao().clearCurrent()
        db.nurseDao().upsert(
            NurseEntity(
                nurseId = nurse.id,
                name = nurse.name,
                isCurrent = true
            )
        )
        db.nurseDao().markCurrent(nurse.id)
    }

    fun saveNurseData(data: NurseData) {
        db.nurseDao().clearCurrent()
        db.nurseDao().upsert(
            NurseEntity(
                nurseId = data.nurseId,
                name = data.name,
                age = data.age,
                gender = data.gender,
                pointOfCare = data.pointOfCare,
                contactNo = data.contactNo,
                isCurrent = true
            )
        )
        db.nurseDao().markCurrent(data.nurseId)
    }

    fun getNurse(): Nurse {
        val nurse = db.nurseDao().getCurrentNurse()
        return Nurse(name = nurse?.name.orEmpty(), id = nurse?.nurseId.orEmpty())
    }

    fun savePatient(patientJson: String) {
        val json = JSONObject(patientJson)
        db.patientContextDao().upsert(
            PatientContextEntity(
                patientId = json.optString("patientId", "N/A"),
                name = json.optString("name", "Unknown Patient"),
                age = json.opt("age")?.toString() ?: "N/A",
                gender = json.optString("gender", "N/A"),
                bloodType = json.optString("bloodType", "N/A"),
                rawJson = patientJson
            )
        )
    }

    fun getPatient(): Patient? {
        val patient = db.patientContextDao().getCurrentPatient() ?: return null
        return try {
            Patient(
                name = patient.name,
                age = patient.age.toIntOrNull() ?: 0,
                gender = patient.gender,
                bloodType = patient.bloodType,
                patientId = patient.patientId
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clearPatient() {
        db.patientContextDao().clear()
    }

    fun saveFetchedRecord(content: String) {
        db.localRecordDao().insert(
            LocalRecordEntity(
                recordType = "FETCHED_RECORD",
                patientId = getPatient()?.patientId,
                nurseId = getNurse().id.ifEmpty { null },
                fileName = "fetched_record.txt",
                content = content
            )
        )
    }

    fun getLatestFetchedRecord(): String =
        db.localRecordDao().getLatestByType("FETCHED_RECORD")?.content ?: "No record fetched yet."

    fun addSessionRecord(content: String, fileName: String?) {
        db.localRecordDao().insert(
            LocalRecordEntity(
                recordType = "SESSION_REPORT",
                patientId = getPatient()?.patientId,
                nurseId = getNurse().id.ifEmpty { null },
                fileName = fileName,
                content = content
            )
        )
    }

    fun getSessionRecords(): List<String> =
        db.localRecordDao().getRecordsByType("SESSION_REPORT").map { it.content }.reversed()
}
