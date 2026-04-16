package com.example.nursingdevice

import android.util.Log
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

data class NurseRequest(
    @SerializedName("nurseId") val nurseId: String,
    @SerializedName("name") val name: String,
    @SerializedName("age") val age: Int?,
    @SerializedName("gender") val gender: String?,
    @SerializedName("pointOfCare") val pointOfCare: String,
    @SerializedName("contactNo") val contactNo: String?
)

data class NurseApiResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("nurse") val nurse: NurseData?
)

data class NurseData(
    @SerializedName("nurseId") val nurseId: String,
    @SerializedName("name") val name: String,
    @SerializedName("age") val age: Int?,
    @SerializedName("gender") val gender: String?,
    @SerializedName("pointOfCare") val pointOfCare: String,
    @SerializedName("contactNo") val contactNo: String?
)

interface NurseApiService {
    @POST("api/nurses/register")
    suspend fun registerNurse(@Body request: NurseRequest): NurseApiResponse

    @GET("api/nurses/{nurseId}")
    suspend fun getNurse(@Path("nurseId") nurseId: String): NurseApiResponse
}

class NurseRepository {
    private val BASE_URL = "https://nfc-backend-ostp.onrender.com/"
    private val api: NurseApiService

    init {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
        api = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NurseApiService::class.java)
    }

    suspend fun register(
        nurseId: String, name: String, age: Int?,
        gender: String?, pointOfCare: String, contactNo: String?
    ): Result<NurseData> = withContext(Dispatchers.IO) {
        try {
            val resp = api.registerNurse(NurseRequest(nurseId, name, age, gender, pointOfCare, contactNo))
            if (resp.success && resp.nurse != null) Result.success(resp.nurse)
            else Result.failure(Exception(resp.message ?: "Registration failed"))
        } catch (e: Exception) {
            Log.e("NurseRepository", "register", e)
            Result.failure(e)
        }
    }

    suspend fun login(nurseId: String): Result<NurseData> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getNurse(nurseId)
            if (resp.success && resp.nurse != null) Result.success(resp.nurse)
            else Result.failure(Exception("Nurse ID not found. Please register first."))
        } catch (e: Exception) {
            Log.e("NurseRepository", "login", e)
            Result.failure(e)
        }
    }
}
