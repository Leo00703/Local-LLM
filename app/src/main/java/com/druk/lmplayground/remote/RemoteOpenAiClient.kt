package com.druk.lmplayground.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Per-model metadata read from a server's native API (not the OpenAI
 * /v1/models, which only returns the id). All fields are best-effort.
 */
data class ServerModelDetails(
    val quantization: String? = null,
    val architecture: String? = null,
    val type: String? = null,
    val format: String? = null,
    val publisher: String? = null,
    val maxContext: Int = 0,
    val loadedContext: Int = 0,
    // Ollama-only: parameter count (e.g. "7.6B") and capabilities the server
    // reports (e.g. "tools", "vision", "thinking"). Empty/null for LM Studio.
    val parameterSize: String? = null,
    val capabilities: List<String> = emptyList(),
)

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

    // Bounded-timeout client for control calls (unload) so a hung server can't
    // block the caller indefinitely the way the no-read-timeout chat client can.
    private val controlHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
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

    /**
     * Fire a tiny non-streaming request so the server loads the model into
     * memory ahead of the first real turn. Returns true on a 2xx response.
     */
    suspend fun warmUp(model: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 1)
                put("stream", false)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", ".")))
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$base/v1/chat/completions").post(body).build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Best-effort: ask the server to evict [model] from memory. LM Studio's
     * native unload is tried first; a 2xx means this IS LM Studio and the model
     * was evicted, so the Ollama path is skipped — otherwise that second call
     * would hit LM Studio's unknown-endpoint handler and log a spurious error.
     * Ollama servers 404 the LM Studio path, so they fall through to the
     * keep_alive=0 generate call. Blocking — call from a background thread.
     */
    fun offloadBlocking(model: String) {
        val unloaded = tryControlPost(
            "$base/api/v1/models/unload",
            JSONObject().put("instance_id", model).toString()
        )
        if (unloaded) return
        tryControlPost(
            "$base/api/generate",
            JSONObject().put("model", model).put("keep_alive", 0).toString()
        )
    }

    /** POST [json] to [url]; returns true on a 2xx response. Never throws. */
    private fun tryControlPost(url: String, json: String): Boolean = try {
        val body = json.toRequestBody("application/json".toMediaType())
        controlHttp.newCall(Request.Builder().url(url).post(body).build())
            .execute().use { it.isSuccessful }
    } catch (_: Exception) {
        // best-effort — server may not support this mechanism
        false
    }

    /**
     * Fetch native per-model metadata (quantization, arch, max context, ...).
     * Tries LM Studio's GET /api/v0/models then Ollama's POST /api/show.
     * Null when neither applies. Best-effort, bounded timeouts.
     */
    suspend fun fetchModelDetails(modelId: String): ServerModelDetails? = withContext(Dispatchers.IO) {
        // LM Studio native model list
        controlGet("$base/api/v0/models")?.let { body ->
            runCatching {
                val arr = JSONObject(body).optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        if (m.optString("id") == modelId) {
                            return@withContext ServerModelDetails(
                                quantization = m.optString("quantization").ifBlank { null },
                                architecture = m.optString("arch").ifBlank { null },
                                type = m.optString("type").ifBlank { null },
                                format = m.optString("compatibility_type").ifBlank { null },
                                publisher = m.optString("publisher").ifBlank { null },
                                maxContext = m.optInt("max_context_length", 0),
                                loadedContext = m.optInt("loaded_context_length", 0),
                            )
                        }
                    }
                }
            }
        }
        // Ollama fallback
        controlPostForBody("$base/api/show", JSONObject().put("name", modelId).toString())?.let { body ->
            runCatching {
                val o = JSONObject(body)
                val det = o.optJSONObject("details")
                val mi = o.optJSONObject("model_info")
                val ctx = mi?.let { info ->
                    info.keys().asSequence().firstOrNull { it.endsWith(".context_length") }
                        ?.let { info.optInt(it, 0) }
                } ?: 0
                val caps = o.optJSONArray("capabilities")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
                } ?: emptyList()
                return@withContext ServerModelDetails(
                    quantization = det?.optString("quantization_level")?.ifBlank { null },
                    architecture = det?.optString("family")?.ifBlank { null },
                    type = null,
                    format = det?.optString("format")?.ifBlank { null },
                    publisher = null,
                    maxContext = ctx,
                    parameterSize = det?.optString("parameter_size")?.ifBlank { null },
                    capabilities = caps,
                )
            }
        }
        null
    }

    private fun controlGet(url: String): String? = try {
        controlHttp.newCall(Request.Builder().url(url).get().build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    private fun controlPostForBody(url: String, json: String): String? = try {
        val body = json.toRequestBody("application/json".toMediaType())
        controlHttp.newCall(Request.Builder().url(url).post(body).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    /** Build (but don't execute) a streaming POST /v1/chat/completions call. */
    fun chatCompletionCall(bodyJson: String): Call {
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$base/v1/chat/completions").post(body).build()
        return http.newCall(request)
    }
}
