package com.example.nursingdevice

import android.content.Context
import org.json.JSONObject
import java.io.File

data class Nurse(
    val name: String = "",
    val id: String = ""
)

data class Patient(
    val name: String,
    val age: Int,
    val gender: String,
    val bloodType: String,
    val sugar: String = "",
    val height: String = "",
    val weight: String = "",
    val patientId: String
)

/**
 * CAD non-credential storage.
 *
 * Per the design decision (CREDENTIALS_AND_STORAGE_PLAN.md §4), the CAD's
 * encrypted Room holds ONLY the credential. Everything here lives OFF the
 * encrypted DB:
 *   - nurse profile        -> SharedPreferences (identity, not patient data)
 *   - scanned patient       -> in-memory SessionCache (never persisted to disk)
 *   - fetched / session      -> app cacheDir files (OS-clearable cache)
 *
 * Public method signatures are unchanged from the previous Room-backed version,
 * so existing activities keep compiling.
 */
class NursePatientManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("cad_nurse_profile", Context.MODE_PRIVATE)
    private val cloudDir: File by lazy { File(context.cacheDir, "cloud_history").apply { mkdirs() } }
    private val fetchedFile: File by lazy { File(context.cacheDir, "fetched_record.txt") }

    // --- Nurse profile (SharedPreferences) ---

    fun saveNurse(nurse: Nurse) {
        prefs.edit()
            .putString("nurseId", nurse.id)
            .putString("name", nurse.name)
            .apply()
    }

    fun saveNurseData(data: NurseData) {
        prefs.edit()
            .putString("nurseId", data.nurseId)
            .putString("name", data.name)
            .putString("age", data.age?.toString())
            .putString("gender", data.gender)
            .putString("pointOfCare", data.pointOfCare)
            .putString("contactNo", data.contactNo)
            .apply()
    }

    fun getNurse(): Nurse =
        Nurse(
            name = prefs.getString("name", "").orEmpty(),
            id = prefs.getString("nurseId", "").orEmpty()
        )

    // --- Scanned patient context (in-memory only) ---

    fun savePatient(patientJson: String) {
        // Parse + hold in memory; nothing patient-related is written to disk.
        SessionCache.processScannedData(patientJson)
    }

    fun getPatient(): Patient? {
        val id = SessionCache.currentPatientId
        if (id.isBlank() || id == "N/A") return null
        return Patient(
            name = SessionCache.currentPatientName,
            age = SessionCache.currentPatientAge.toIntOrNull() ?: 0,
            gender = SessionCache.currentPatientGender,
            bloodType = SessionCache.currentPatientBloodType,
            sugar = SessionCache.currentPatientSugar,
            height = SessionCache.currentPatientHeight,
            weight = SessionCache.currentPatientWeight,
            patientId = id
        )
    }

    fun clearPatient() {
        SessionCache.loadPatient(null)
    }

    // --- Fetched record (single cache file) ---

    fun saveFetchedRecord(content: String) {
        runCatching { fetchedFile.writeText(content) }
    }

    fun getLatestFetchedRecord(): String =
        runCatching { fetchedFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "No record fetched yet."

    // --- Cloud history (cache files, one per fetched file, scoped by patientId) ---

    private fun cloudFile(patientId: String, fileName: String) =
        File(cloudDir, "${patientId}__${fileName}")

    // Strip the "<patientId>__" prefix back to the original fileName.
    private fun originalName(f: File, patientId: String) =
        f.name.removePrefix("${patientId}__")

    fun saveCloudHistory(content: String, fileName: String, patientId: String) {
        runCatching { cloudFile(patientId, fileName).writeText(content) }
    }

    fun getLatestCloudHistory(): String =
        cloudDir.listFiles()?.maxByOrNull { it.lastModified() }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: "No cloud history fetched yet."

    fun getCloudHistoryDates(patientId: String): List<String> =
        cloudDir.listFiles()
            ?.filter { it.name.startsWith("${patientId}__") }
            ?.mapNotNull { file ->
                originalName(file, patientId)
                    .removeSuffix(".txt")
                    .substringAfterLast('_', "")
                    .takeIf { it.isNotBlank() }
            }
            ?.distinct()
            ?: emptyList()

    fun getCloudHistoryBlocks(patientId: String, date: String): List<CloudHistoryBlock> {
        val file = cloudDir.listFiles()
            ?.filter { it.name.startsWith("${patientId}__") }
            ?.firstOrNull { originalName(it, patientId).removeSuffix(".txt").endsWith("_$date") }
            ?: return emptyList()

        val content = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return content
            .split(Regex("\\n\\n=+\\n\\n"))
            .mapIndexedNotNull { index, block ->
                val trimmed = block.trim()
                if (trimmed.isBlank()) null
                else CloudHistoryBlock(
                    title = "Record ${index + 1}",
                    content = trimmed,
                    updatedAt = file.lastModified()
                )
            }
    }

    // --- Session reports generated this visit (in-memory) ---

    fun addSessionRecord(content: String, fileName: String?) {
        SessionCache.addUpdatedRecord(content)
    }

    fun getSessionRecords(): List<String> =
        SessionCache.sessionHistory.toList().reversed()
}

data class CloudHistoryBlock(
    val title: String,
    val content: String,
    val updatedAt: Long
)
