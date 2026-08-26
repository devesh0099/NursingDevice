package com.example.nursingdevice

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CloudPatientData(
    @SerializedName("patientId") val patientId: String,
    @SerializedName("name") val name: String,
    @SerializedName("age") val age: Int?,
    @SerializedName("gender") val gender: String?,
    @SerializedName("bloodType") val bloodType: String?,
    @SerializedName("sugar") val sugar: String?,
    @SerializedName("height") val height: String?,
    @SerializedName("weight") val weight: String?
)

data class CloudPatientApiResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("patient") val patient: CloudPatientData?
)

data class CloudRecordData(
    @SerializedName("patientId") val patientId: String,
    @SerializedName("nurseId") val nurseId: String?,
    @SerializedName("date") val date: String?,
    @SerializedName("time") val time: String?,
    @SerializedName("bp") val bp: String?,
    @SerializedName("hr") val hr: Int?,
    @SerializedName("rr") val rr: Int?,
    @SerializedName("temp") val temp: Float?,
    @SerializedName("obs") val obs: String?,
    @SerializedName("med") val med: String?
)

interface CloudPatientApiService {
    @GET("api/patients/{patientId}")
    suspend fun getPatient(@Path("patientId") patientId: String): CloudPatientApiResponse
}

class CloudHistoryRepository {
    private val primaryBaseUrl = "https://nfc-backend-ostp.onrender.com"
    private val secondaryBaseUrl = "https://nursing-backend-vp5o.onrender.com"
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
        )
        .build()

    private val primaryApi = createApi(primaryBaseUrl)
    private val secondaryApi = createApi(secondaryBaseUrl)

    private fun createApi(baseUrl: String): CloudPatientApiService =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CloudPatientApiService::class.java)

    suspend fun fetchEntireHistory(context: Context, patientId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val patient = fetchPatient(patientId) ?: CloudPatientData(
                    patientId = patientId,
                    name = "Unknown Patient",
                    age = null,
                    gender = null,
                    bloodType = null,
                    sugar = null,
                    height = null,
                    weight = null
                )
                val records = fetchPatientRecords(patientId)

                if (records.isEmpty()) {
                    return@withContext Result.failure(Exception("No records found for patient $patientId"))
                }

                val groupedByDate = records.groupBy { it.date ?: "undated" }
                val manager = NursePatientManager(context)
                val contentChunks = mutableListOf<String>()

                groupedByDate.forEach { (date, dateRecords) ->
                    val content = dateRecords
                        .sortedBy { it.time ?: "" }
                        .joinToString(separator = "\n\n=================================\n\n") { record ->
                            buildRecordBlock(patient, record)
                        }

                    val fileName = "${patient.name.replace(" ", "_").ifBlank { patient.patientId }}_$date.txt"
                    manager.saveCloudHistory(content, fileName, patient.patientId)
                    contentChunks.add("[$date]\n$content")
                }

                val combined = contentChunks.joinToString("\n\n#################################\n\n")
                Result.success(combined)
            } catch (e: Exception) {
                Log.e("CloudHistoryRepository", "fetchEntireHistory", e)
                Result.failure(e)
            }
        }

    private suspend fun fetchPatient(patientId: String): CloudPatientData? = withContext(Dispatchers.IO) {
        val candidateUrls = listOf(
            "$primaryBaseUrl/api/patients/$patientId",
            "$secondaryBaseUrl/api/patients/$patientId"
        )

        for (url in candidateUrls) {
            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val parsed = parsePatient(body)
                        if (parsed != null) return@withContext parsed
                    }
                }
            } catch (e: Exception) {
                Log.w("CloudHistoryRepository", "fetchPatient failed for $url: ${e.message}")
            }
        }
        null
    }

    private suspend fun fetchPatientRecords(patientId: String): List<CloudRecordData> = withContext(Dispatchers.IO) {
        val candidateUrls = listOf(
            "$primaryBaseUrl/api/records/patient/$patientId",
            "$primaryBaseUrl/api/patients/$patientId/records",
            "$primaryBaseUrl/api/records/$patientId",
            "$secondaryBaseUrl/api/records/patient/$patientId",
            "$secondaryBaseUrl/api/patients/$patientId/records",
            "$secondaryBaseUrl/api/records/$patientId"
        )

        for (url in candidateUrls) {
            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val parsed = parseRecords(body)
                        if (parsed != null) return@withContext parsed.filter { it.patientId == patientId }
                    }
                }
            } catch (e: Exception) {
                Log.w("CloudHistoryRepository", "fetchRecords failed for $url: ${e.message}")
            }
        }

        emptyList()
    }

    private fun parsePatient(body: String): CloudPatientData? {
        if (body.isBlank()) return null
        return try {
            val root = JsonParser().parse(body)
            if (!root.isJsonObject) return null
            val jsonObject = root.asJsonObject
            when {
                jsonObject.has("patient") && jsonObject.get("patient").isJsonObject ->
                    gson.fromJson(jsonObject.get("patient").toString(), CloudPatientData::class.java)

                jsonObject.has("data") && jsonObject.get("data").isJsonObject ->
                    gson.fromJson(jsonObject.get("data").toString(), CloudPatientData::class.java)

                jsonObject.has("patientId") && jsonObject.has("name") ->
                    gson.fromJson(jsonObject.toString(), CloudPatientData::class.java)

                else -> null
            }
        } catch (e: Exception) {
            Log.e("CloudHistoryRepository", "parsePatient", e)
            null
        }
    }

    private fun parseRecords(body: String): List<CloudRecordData>? {
        if (body.isBlank()) return emptyList()
        return try {
            val root = JsonParser().parse(body)
            when {
                root.isJsonArray -> gson.fromJson(body, Array<CloudRecordData>::class.java).toList()
                root.isJsonObject -> extractRecordsFromObject(root.asJsonObject)
                else -> null
            }
        } catch (e: Exception) {
            Log.e("CloudHistoryRepository", "parseRecords", e)
            null
        }
    }

    private fun extractRecordsFromObject(jsonObject: JsonObject): List<CloudRecordData>? {
        val arrayElement = listOf("records", "data", "details")
            .mapNotNull { key ->
                val element = jsonObject.get(key)
                if (element != null && element.isJsonArray) element else null
            }
            .firstOrNull()

        if (arrayElement != null) {
            return gson.fromJson(arrayElement.toString(), Array<CloudRecordData>::class.java).toList()
        }

        if (jsonObject.has("patientId") && jsonObject.has("date")) {
            return listOf(gson.fromJson(jsonObject.toString(), CloudRecordData::class.java))
        }

        return emptyList()
    }

    private fun buildRecordBlock(patient: CloudPatientData, record: CloudRecordData): String {
        val updatedOn = buildUpdatedOn(record.date, record.time)
        return buildString {
            appendLine("Patient ID: ${patient.patientId}")
            appendLine("Patient Name: ${patient.name}")
            appendLine("Age: ${patient.age ?: 0}")
            appendLine("Gender: ${patient.gender.orEmpty()}")
            appendLine("Blood Type: ${patient.bloodType.orEmpty()}")
            appendLine("Blood Sugar: ${patient.sugar.orEmpty()}")
            appendLine("Height: ${patient.height.orEmpty()}")
            appendLine("Weight: ${patient.weight.orEmpty()}")
            appendLine("Nurse ID: ${record.nurseId.orEmpty()}")
            appendLine("Blood Pressure: ${record.bp.orEmpty()}")
            appendLine("Heart Rate: ${record.hr?.toString() ?: ""} bpm")
            appendLine("Respiratory Rate: ${record.rr?.toString() ?: ""} breaths/min")
            appendLine("Body Temperature: ${formatTemperature(record.temp)}F")
            appendLine("Medication: ${record.med.orEmpty()}")
            appendLine("Description: ${record.obs.orEmpty()}")
            append("Updated on: $updatedOn")
        }
    }

    private fun formatTemperature(temp: Float?): String {
        if (temp == null) return ""
        return if (temp % 1f == 0f) temp.toInt().toString() else temp.toString()
    }

    private fun buildUpdatedOn(date: String?, time: String?): String {
        if (date.isNullOrBlank()) {
            return SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        }

        val hourMinute = time?.takeIf { it.isNotBlank() } ?: "00:00"
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .parse("$date $hourMinute")
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(parsed ?: Date())
        } catch (e: Exception) {
            "$date $hourMinute"
        }
    }
}
