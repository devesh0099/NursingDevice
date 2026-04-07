package com.example.nursingdevice

import android.content.Context
import android.content.SharedPreferences
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
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nurse_patient_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_NURSE_NAME = "nurse_name"
        private const val KEY_NURSE_ID   = "nurse_id"
        private const val KEY_PATIENT_JSON = "patient_json"
    }

    fun saveNurse(nurse: Nurse) {
        prefs.edit()
            .putString(KEY_NURSE_NAME, nurse.name)
            .putString(KEY_NURSE_ID, nurse.id)
            .apply()
    }

    fun getNurse(): Nurse {
        val name = prefs.getString(KEY_NURSE_NAME, "") ?: ""
        val id   = prefs.getString(KEY_NURSE_ID, "") ?: ""
        return Nurse(name = name, id = id)
    }

    fun savePatient(patientJson: String) {
        prefs.edit().putString(KEY_PATIENT_JSON, patientJson).apply()
    }

    fun getPatient(): Patient? {
        val jsonStr = prefs.getString(KEY_PATIENT_JSON, null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            Patient(
                name      = json.getString("name"),
                age       = json.getInt("age"),
                gender    = json.getString("gender"),
                bloodType = json.getString("bloodType"),
                patientId = json.getString("patientId")
            )
        } catch (e: Exception) { null }
    }

    fun clearPatient() {
        prefs.edit().remove(KEY_PATIENT_JSON).apply()
    }
}
