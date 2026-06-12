package com.example.nursingdevice

import org.json.JSONObject
import android.util.Log

object SessionCache {
    var currentPatientRawData: String? = null
    var fetchedRecordData: String = "No record fetched yet."
    // Parsed Fields
    var currentPatientName: String = "None"
    var currentPatientAge: String = ""
    var currentPatientGender: String = ""
    var currentPatientBloodType: String = ""
    var currentPatientId: String = ""

    // Stores all the reports generated during this app session
    val sessionHistory = mutableListOf<String>()

    fun processScannedData(data: String) {
        currentPatientRawData = data

        try {
            // Parse the exact JSON structure sent by the Aggregator
            val jsonObject = JSONObject(data)

            currentPatientName = jsonObject.optString("name", "Unknown Patient")
            currentPatientAge = jsonObject.optString("age", "N/A")
            currentPatientGender = jsonObject.optString("gender", "N/A")
            currentPatientBloodType = jsonObject.optString("bloodType", "N/A")
            currentPatientId = jsonObject.optString("patientId", "N/A")

            Log.d("SessionCache", "Successfully parsed JSON for patient: $currentPatientName")

        } catch (e: Exception) {
            Log.e("SessionCache", "Data is not JSON, applying fallback.", e)
            // Safety fallback for unexpected plain-text formatting
            currentPatientName = extractNameFallback(data)
        }
    }

    private fun extractNameFallback(data: String): String {
        val lines = data.lines()
        lines.find { it.contains("Patient Name:", ignoreCase = true) }?.let {
            return it.substringAfter("Patient Name:").trim().removeSurrounding("\"").removeSuffix(",")
        }
        lines.find { it.contains("Name:", ignoreCase = true) }?.let {
            return it.substringAfter("Name:").trim().removeSurrounding("\"").removeSuffix(",")
        }
        return "Unknown Patient"
    }

    fun addUpdatedRecord(report: String) {
        sessionHistory.add(report)
    }

    fun loadPatient(patient: Patient?) {
        if (patient == null) {
            currentPatientRawData = null
            currentPatientName = "None"
            currentPatientAge = ""
            currentPatientGender = ""
            currentPatientBloodType = ""
            currentPatientId = ""
            return
        }

        currentPatientName = patient.name
        currentPatientAge = patient.age.toString()
        currentPatientGender = patient.gender
        currentPatientBloodType = patient.bloodType
        currentPatientId = patient.patientId
    }

    fun setFetchedRecord(data: String) {
        fetchedRecordData = data
    }

    fun clearSession() {
        currentPatientRawData = null
        currentPatientName = "None"
        currentPatientAge = ""
        currentPatientGender = ""
        currentPatientBloodType = ""
        currentPatientId = ""
        sessionHistory.clear()
    }
}
