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
    /** The model's training maximum: an upper bound, NOT what the server runs with. */
    val maxContext: Int = 0,
    val loadedContext: Int = 0,
    /**
     * The context the server really runs this model with (tokens), or 0 when it does not say.
     * Never the training maximum: see [ServerContexts].
     */
    val contextWindow: Int = 0,
    val contextSource: ContextSource = ContextSource.UNKNOWN,
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

    private val http: OkHttpClient = sharedHttp.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    // Bounded-timeout client for control calls (unload) so a hung server can't
    // block the caller indefinitely the way the no-read-timeout chat client can.
    private val controlHttp: OkHttpClient = sharedHttp.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // Warm-up POST can legitimately take a while (cold model load) but must not hang
    // forever if the server accepts then wedges: a generous but FINITE call timeout.
    private val warmupHttp: OkHttpClient = sharedHttp.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    // Very short timeouts for the "is it online?" check that runs when the model picker
    // opens: an unreachable address must not keep the picker waiting.
    private val probeHttp: OkHttpClient = sharedHttp.newBuilder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    /**
     * GET /v1/models with a short timeout: the model ids when the server answers, null
     * when it is offline, unreachable, rejects the request or is not OpenAI-compatible.
     * Unlike [listModels], "no models" (empty list) and "not online" (null) differ.
     */
    suspend fun probeModels(): List<String>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$base/v1/models").withAuth().get().build()
            probeHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val data = JSONObject(body).optJSONArray("data") ?: return@withContext null
                buildList {
                    for (i in 0 until data.length()) {
                        data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

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
                            val maxCtx = m.optInt("max_context_length", 0)
                            val loadedCtx = m.optInt("loaded_context_length", 0)
                            val ctx = ServerContexts.lmStudio(m.optString("state"), loadedCtx, maxCtx)
                            return@withContext ServerModelDetails(
                                quantization = m.optString("quantization").ifBlank { null },
                                architecture = m.optString("arch").ifBlank { null },
                                type = m.optString("type").ifBlank { null },
                                format = m.optString("compatibility_type").ifBlank { null },
                                publisher = m.optString("publisher").ifBlank { null },
                                maxContext = maxCtx,
                                loadedContext = loadedCtx,
                                contextWindow = if (ctx.exact) ctx.tokens else 0,
                                contextSource = ctx.source,
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
                // /api/show only has the model's maximum; /api/ps has the context a running model uses.
                val running = ServerContexts.ollama(controlGet("$base/api/ps"), modelId, ctx)
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
                    contextWindow = if (running.exact) running.tokens else 0,
                    contextSource = running.source,
                    parameterSize = det?.optString("parameter_size")?.ifBlank { null },
                    capabilities = caps,
                )
            }
        }
        fetchLlamaCppDetails(modelId)
    }

    /**
     * llama.cpp branch of [fetchModelDetails], also used for Strata (same dialect). A server counts
     * as llama.cpp-compatible when `/props` has llama-server's shape, or the model list says
     * `owned_by: "llamacpp"`. The context comes from [ServerContexts.llamaModel]: the per-slot
     * `/props` n_ctx of a single-model server, `meta.n_ctx` of a loaded model, or the `--ctx-size`
     * a router will launch the model with. `/v1/models` `context_length` is the training context
     * and is never taken for what the server runs. GET /props also adds the vision and tool-call
     * capabilities for feature gating. Null when the server is not llama.cpp-compatible.
     */
    private fun fetchLlamaCppDetails(modelId: String): ServerModelDetails? {
        val listBody = controlGet("$base/v1/models") ?: return null
        val data = runCatching { JSONObject(listBody).optJSONArray("data") }.getOrNull() ?: return null
        val props = controlGet("$base/props")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val llamaLike = ServerContexts.isLlamaProps(props)
        var entry: JSONObject? = null
        var firstCandidate: JSONObject? = null
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            if (!llamaLike && m.optString("owned_by") != "llamacpp") continue
            if (firstCandidate == null) firstCandidate = m
            if (m.optString("id") == modelId) {
                entry = m
                break
            }
        }
        // The request `model` field is advisory on llama.cpp — if the stored id
        // no longer matches (server restarted with another GGUF), use the entry
        // the server actually reports rather than failing the whole lookup.
        val model = entry ?: firstCandidate ?: return null
        val meta = model.optJSONObject("meta")
        val capabilities = props?.let { p ->
            buildList {
                if (p.optJSONObject("modalities")?.optBoolean("vision") == true) add("vision")
                if (p.optJSONObject("chat_template_caps")?.optBoolean("supports_tool_calls") == true) add("tools")
            }
        } ?: emptyList()
        val ctx = ServerContexts.llamaModel(model, props)
        val training = meta?.optInt("n_ctx_train", 0)?.takeIf { it > 0 } ?: model.optInt("context_length", 0)
        return ServerModelDetails(
            quantization = meta?.optString("ftype")?.ifBlank { null },
            architecture = null,
            type = null,
            format = null,
            publisher = if (ServerContexts.isStrata(props)) "Strata" else "llama.cpp",
            maxContext = training,
            loadedContext = 0,
            contextWindow = if (ctx.exact) ctx.tokens else 0,
            contextSource = ctx.source,
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

    private companion object {
        /**
         * One connection pool and dispatcher for every instance. A client is created per call
         * (each time the picker opens, on every load), and four separately built OkHttp clients
         * each used to bring their own pool and idle threads. The per-purpose timeouts above
         * are applied on top with newBuilder(), which shares them.
         */
        val sharedHttp: OkHttpClient by lazy { OkHttpClient() }
    }

    /** Build (but don't execute) a streaming POST /v1/chat/completions call. */
    fun chatCompletionCall(bodyJson: String): Call {
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$base/v1/chat/completions").withAuth().post(body).build()
        return http.newCall(request)
    }
}
