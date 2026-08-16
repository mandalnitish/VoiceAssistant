package com.example.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AcApiClient {

    private const val BASE_URL = BuildConfig.AC_API_BASE_URL
    private const val API_KEY = BuildConfig.BRIDGE_API_KEY

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private suspend fun request(
        method: String,
        endpoint: String
    ): Result<String> = withContext(Dispatchers.IO) {

        try {
            val requestBuilder = Request.Builder()
                .url(BASE_URL + endpoint)
                .addHeader("X-Api-Key", API_KEY)

            when (method) {
                "GET" -> {
                    requestBuilder.get()
                }

                "POST" -> {
                    requestBuilder.post(
                        "".toRequestBody(
                            "application/json".toMediaType()
                        )
                    )
                }

                else -> {
                    return@withContext Result.failure(
                        IllegalArgumentException("Unsupported HTTP method")
                    )
                }
            }

            val response = client.newCall(
                requestBuilder.build()
            ).execute()

            val body = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                Result.success(body)
            } else {
                Result.failure(
                    Exception(
                        "HTTP ${response.code}: $body"
                    )
                )
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun health(): Result<String> {
        return request("GET", "health")
    }

    suspend fun getStatus(): Result<String> {
        return request("GET", "ac/status")
    }

    suspend fun turnOn(): Result<String> {
        return request("POST", "ac/on")
    }

    suspend fun turnOff(): Result<String> {
        return request("POST", "ac/off")
    }

    suspend fun setTemperature(
        temperature: Int
    ): Result<String> {
        return request(
            "POST",
            "ac/temperature/$temperature"
        )
    }

    suspend fun setMode(
        mode: String
    ): Result<String> {
        return request(
            "POST",
            "ac/mode/$mode"
        )
    }

    suspend fun setFanSpeed(
        speed: String
    ): Result<String> {
        return request(
            "POST",
            "ac/fan/$speed"
        )
    }

    suspend fun setSwing(
        mode: String
    ): Result<String> {
        return request(
            "POST",
            "ac/swing/$mode"
        )
    }

    suspend fun setSwingHorizontal(
        mode: String
    ): Result<String> {
        return request(
            "POST",
            "ac/swing/horizontal/$mode"
        )
    }

    /** mode should be one of: none, eco, boost, clean */
    suspend fun setPreset(
        mode: String
    ): Result<String> {
        return request(
            "POST",
            "ac/preset/$mode"
        )
    }

    /** mode should be one of: on, off */
    suspend fun setDisplay(
        mode: String
    ): Result<String> {
        return request(
            "POST",
            "ac/display/$mode"
        )
    }

    /** mode should be one of: off, on, hc, 90, 80, 70, 55, 40 */
    suspend fun setConverti(
        mode: String
    ): Result<String> {
        return request(
            "POST",
            "ac/converti/$mode"
        )
    }
}