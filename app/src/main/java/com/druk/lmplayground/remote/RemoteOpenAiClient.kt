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
    // Ollama/llama.cpp: parameter count (e.g. "7.6B") and capabilities the
    // server reports (e.g. "tools", "vision", "thinking"). Empty/null for LM Studio.
    val parameterSize: String? = null,
    val capabilities: List<String> = emptyList(),
)

/**
 * Thin OkHttp wrapper for an OpenAI-compatible server (LM Studio / Ollama /
 * llama.cpp). An optional [apiKey] is sent as `Authorization: Bearer <key>` on
 * every request — required when llama.cpp is started with `--api-key`, and
 * harmlessly ignored by LM Studio and Ollama. The read timeout is disabled
 * because chat completions are long-lived SSE streams.
 */
class RemoteOpenAiClient(
    baseUrl: String,
    private val apiKey: String? = null,
) {

    private val base = baseUrl.trim().trimEnd('/')

    /** Adds the `Authorization: Bearer <key>` header when an API key is set. */
    private fun Request.Builder.withAuth(): Request.Builder =
        if (apiKey != null) header("Authorization", "Bearer $apiKey") else this

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

    // Warm-up POST can legitimately take a while (cold model load) but must not hang
    // forever if the server accepts then wedges: a generous but FINITE call timeout.
    private val warmupHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /** GET /v1/models → list of model ids. Empty on any failure. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$base/v1/models").withAuth().get().build()
            // Short control call: use the bounded client so a half-open socket can't
            // hang the remote-models spinner forever (http has no read timeout).
            controlHttp.newCall(request).execute().use { response ->
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
            val request = Request.Builder().url("$base/v1/chat/completions").withAuth().post(body).build()
            warmupHttp.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Best-effort: ask the server to evict [model] from memory. llama.cpp
     * (router mode) uses POST /models/unload — single-model servers keep the
     * model resident for the server's lifetime, so the call just 404s. Then
     * LM Studio's native unload is tried; a 2xx means this IS LM Studio and
     * the model was evicted, so the Ollama path is skipped — otherwise that
     * second call would hit LM Studio's unknown-endpoint handler and log a
     * spurious error. Ollama servers 404 the LM Studio path, so they fall
     * through to the keep_alive=0 generate call. Blocking — call from a
     * background thread.
     */
    fun offloadBlocking(model: String) {
        if (isLlamaCpp()) {
            tryControlPost(
                "$base/models/unload",
                JSONObject().put("model", model).toString()
            )
            return
        }
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
        controlHttp.newCall(Request.Builder().url(url).withAuth().post(body).build())
            .execute().use { it.isSuccessful }
    } catch (_: Exception) {
        // best-effort — server may not support this mechanism
        false
    }

    /** True when the server identifies as llama.cpp (`owned_by: "llamacpp"` on /v1/models). */
    private fun isLlamaCpp(): Boolean =
        (controlGet("$base/v1/models") ?: "").contains("\"llamacpp\"")

    /**
     * Fetch native per-model metadata (quantization, arch, max context, ...).
     * Tries LM Studio's GET /api/v0/models, then Ollama's POST /api/show, then
     * llama.cpp's /v1/models + /props (see [fetchLlamaCppDetails]). Null when
     * none applies. Best-effort, bounded timeouts.
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
        fetchLlamaCppDetails(modelId)
    }

    /**
     * llama.cpp branch of [fetchModelDetails]. Identified by `owned_by:
     * "llamacpp"` on the standard /v1/models endpoint, whose per-model `meta`
     * already carries the quantization (`ftype`), runtime context (`n_ctx`)
     * and parameter count (`n_params`) — no vendor endpoint needed. GET /props
     * (best-effort) adds the vision and tool-call capabilities for feature
     * gating. Null when the server is not llama.cpp or the lookup fails.
     */
    private fun fetchLlamaCppDetails(modelId: String): ServerModelDetails? {
        val listBody = controlGet("$base/v1/models") ?: return null
        val data = runCatching { JSONObject(listBody).optJSONArray("data") }.getOrNull() ?: return null
        var entry: JSONObject? = null
        var firstLlamaCpp: JSONObject? = null
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            if (m.optString("owned_by") != "llamacpp") continue
            if (firstLlamaCpp == null) firstLlamaCpp = m
            if (m.optString("id") == modelId) {
                entry = m
                break
            }
        }
        // The request `model` field is advisory on llama.cpp — if the stored id
        // no longer matches (server restarted with another GGUF), use the entry
        // the server actually reports rather than failing the whole lookup.
        val model = entry ?: firstLlamaCpp ?: return null
        val meta = model.optJSONObject("meta")
        val capabilities = controlGet("$base/props")?.let { body ->
            runCatching {
                val props = JSONObject(body)
                buildList {
                    if (props.optJSONObject("modalities")?.optBoolean("vision") == true) add("vision")
                    if (props.optJSONObject("chat_template_caps")?.optBoolean("supports_tool_calls") == true) add("tools")
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
        return ServerModelDetails(
            quantization = meta?.optString("ftype")?.ifBlank { null },
            architecture = null,
            type = null,
            format = null,
            publisher = "llama.cpp",
            maxContext = meta?.optInt("n_ctx", 0) ?: 0,
            loadedContext = 0,
            parameterSize = formatParamCount(meta?.optLong("n_params", 0L) ?: 0L),
            capabilities = capabilities,
        )
    }

    /** Raw parameter count → "7.6B" style (the format Ollama's parameter_size uses). */
    private fun formatParamCount(n: Long): String? {
        if (n <= 0) return null
        var v = n.toDouble()
        var unit = "K"
        when {
            v >= 1e12 -> { v /= 1e12; unit = "T" }
            v >= 1e9 -> { v /= 1e9; unit = "B" }
            v >= 1e6 -> { v /= 1e6; unit = "M" }
        }
        return String.format("%.1f%s", v, unit)
    }

    private fun controlGet(url: String): String? = try {
        controlHttp.newCall(Request.Builder().url(url).withAuth().get().build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    private fun controlPostForBody(url: String, json: String): String? = try {
        val body = json.toRequestBody("application/json".toMediaType())
        controlHttp.newCall(Request.Builder().url(url).withAuth().post(body).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    /** Build (but don't execute) a streaming POST /v1/chat/completions call. */
    fun chatCompletionCall(bodyJson: String): Call {
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$base/v1/chat/completions").withAuth().post(body).build()
        return http.newCall(request)
    }
}
