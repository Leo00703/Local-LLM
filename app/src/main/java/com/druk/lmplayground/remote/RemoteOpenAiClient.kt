package com.druk.lmplayground.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin OkHttp wrapper for an OpenAI-compatible server (LM Studio / Ollama).
 * No auth (local servers don't require a key). The read timeout is disabled
 * because chat completions are long-lived SSE streams.
 */
class RemoteOpenAiClient(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    /** GET /v1/models → list of model ids. Empty on any failure. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$base/v1/models").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val data = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until data.length()) {
                        data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Build (but don't execute) a streaming POST /v1/chat/completions call. */
    fun chatCompletionCall(bodyJson: String): Call {
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$base/v1/chat/completions").post(body).build()
        return http.newCall(request)
    }
}
