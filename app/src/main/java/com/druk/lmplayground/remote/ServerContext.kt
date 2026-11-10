package com.druk.lmplayground.remote

import org.json.JSONArray
import org.json.JSONObject

/** How far a server model's context window can be trusted. */
enum class ContextSource {
    /** The model is loaded and the server reports the size it was loaded with. */
    LOADED,

    /** Read from the server's launch arguments (llama-server `--ctx-size`). */
    LAUNCH_ARGS,

    /** The server reports the size it runs with (llama.cpp / Strata `/props`). */
    SERVER,

    /** The model's training maximum: an upper bound, the server may run with much less. */
    MODEL_MAX,

    /** Nothing usable was advertised. */
    UNKNOWN;

    /** True when the server will really use that many tokens. */
    val exact: Boolean get() = this == LOADED || this == LAUNCH_ARGS || this == SERVER
}

/** A context window and where it came from. [tokens] is 0 when unknown. */
data class ServerContext(val tokens: Int, val source: ContextSource) {
    val exact: Boolean get() = tokens > 0 && source.exact

    companion object {
        val UNKNOWN = ServerContext(0, ContextSource.UNKNOWN)
    }
}

/**
 * Where each kind of server keeps the context it really runs with. The OpenAI `/v1/models` list
 * carries none of it, and the number that looks like it (LM Studio's `max_context_length`,
 * llama.cpp's training `context_length`) is the model's maximum, often 10 to 100 times what the
 * server was started with: trusting it makes the context ring and the compaction wrong.
 *
 * Pure functions over JSON so they can be unit-tested without a server (see ServerContextTest).
 */
object ServerContexts {

    /**
     * LM Studio, from `GET /api/v0/models`: a loaded model reports `loaded_context_length` (what it
     * was loaded with, it changes on every reload). A model that is not loaded has no context yet
     * (LM Studio loads it with its own default on first use, which the API does not reveal), so the
     * training maximum is only returned as an upper bound.
     */
    fun lmStudio(state: String?, loaded: Int, max: Int): ServerContext = when {
        loaded > 0 && !state.equals("not-loaded", ignoreCase = true) -> ServerContext(loaded, ContextSource.LOADED)
        max > 0 -> ServerContext(max, ContextSource.MODEL_MAX)
        else -> ServerContext.UNKNOWN
    }

    /**
     * Ollama: `GET /api/ps` lists the running models with the `context_length` they run with.
     * Falls back to the model's maximum from `/api/show` as an upper bound.
     */
    fun ollama(psBody: String?, modelId: String, max: Int): ServerContext {
        val running = runCatching {
            val models = JSONObject(psBody ?: return@runCatching 0).optJSONArray("models") ?: return@runCatching 0
            (0 until models.length())
                .mapNotNull { models.optJSONObject(it) }
                .firstOrNull { it.optString("name") == modelId || it.optString("model") == modelId }
                ?.optInt("context_length", 0) ?: 0
        }.getOrDefault(0)
        return when {
            running > 0 -> ServerContext(running, ContextSource.LOADED)
            max > 0 -> ServerContext(max, ContextSource.MODEL_MAX)
            else -> ServerContext.UNKNOWN
        }
    }

    /** True for a llama.cpp-style `/props` answer (llama-server, Strata, ...). */
    fun isLlamaProps(props: JSONObject?): Boolean =
        props != null && !props.has("error") &&
            (props.has("default_generation_settings") || props.has("build_info") || props.has("role"))

    /** Strata speaks llama.cpp's dialect and announces itself in `/props.build_info`. */
    fun isStrata(props: JSONObject?): Boolean =
        props != null && props.optString("build_info").startsWith("Strata", ignoreCase = true)

    /**
     * The per-slot context a single-model server runs with: `/props` →
     * `default_generation_settings.n_ctx`. A router (new llama-server, the Llama-macOS app) answers
     * `role: "router"` with n_ctx 0 because the context belongs to each model, not to the router.
     */
    fun propsContext(props: JSONObject?): Int {
        if (props == null || props.optString("role") == "router") return 0
        val dgs = props.optJSONObject("default_generation_settings")
        return dgs?.optInt("n_ctx", 0)?.takeIf { it > 0 } ?: props.optInt("n_ctx", 0)
    }

    /**
     * The `--ctx-size` / `-c` a llama-server model is launched with, from `status.args` (and the
     * `status.preset` text as a fallback). 0 when unset or `0` (= "take it from the model").
     * With several slots (`-np N`) and a split KV cache each slot only gets ctx / N; a unified KV
     * cache (`--kv-unified`) lets one request use it all.
     */
    fun llamaLaunchContext(args: JSONArray?, preset: String?): Int {
        val tokens = buildList {
            if (args != null) for (i in 0 until args.length()) add(args.optString(i))
        }
        val values = presetValues(preset)

        fun flagValue(names: List<String>): Int {
            for ((i, t) in tokens.withIndex()) {
                for (n in names) {
                    if (t == n) return tokens.getOrNull(i + 1)?.toIntOrNull() ?: 0
                    if (t.startsWith("$n=")) return t.substringAfter('=').toIntOrNull() ?: 0
                }
            }
            return 0
        }

        val ctx = flagValue(listOf("--ctx-size", "--ctx_size", "-c")).takeIf { it > 0 }
            ?: (values["ctx-size"] ?: values["ctx_size"] ?: values["c"])?.toIntOrNull()?.takeIf { it > 0 }
            ?: return 0
        val parallel = flagValue(listOf("--parallel", "--n-parallel", "-np")).takeIf { it > 0 }
            ?: (values["parallel"] ?: values["n-parallel"] ?: values["np"])?.toIntOrNull()?.takeIf { it > 0 }
            ?: 0
        val unified = "--kv-unified" in tokens || "-kvu" in tokens ||
            (values["kv-unified"] ?: values["kvu"]).let { it == "1" || it.equals("true", true) || it.equals("on", true) }
        return if (parallel > 1 && !unified) ctx / parallel else ctx
    }

    /**
     * One model of a llama.cpp-compatible `/v1/models` list, with the server's `/props`.
     * Order of trust: what a single-model server runs with (`/props`), what a loaded model reports
     * (`meta.n_ctx`), the launch arguments of a router model, and last the training context as a bound.
     */
    fun llamaModel(entry: JSONObject, props: JSONObject?): ServerContext {
        propsContext(props).takeIf { it > 0 }?.let { return ServerContext(it, ContextSource.SERVER) }
        val meta = entry.optJSONObject("meta")
        meta?.optInt("n_ctx", 0)?.takeIf { it > 0 }?.let { return ServerContext(it, ContextSource.LOADED) }
        val status = entry.optJSONObject("status")
        llamaLaunchContext(status?.optJSONArray("args"), status?.optString("preset")).takeIf { it > 0 }
            ?.let { return ServerContext(it, ContextSource.LAUNCH_ARGS) }
        val train = meta?.optInt("n_ctx_train", 0)?.takeIf { it > 0 }
            ?: entry.optInt("context_length", 0).takeIf { it > 0 }
        return if (train != null) ServerContext(train, ContextSource.MODEL_MAX) else ServerContext.UNKNOWN
    }

    /** `key = value` lines of a llama-server preset block, keys without leading dashes. */
    private fun presetValues(preset: String?): Map<String, String> {
        if (preset.isNullOrBlank()) return emptyMap()
        val out = HashMap<String, String>()
        val line = Regex("^\\s*([A-Za-z0-9_-]+)\\s*=\\s*(.*?)\\s*$")
        for (raw in preset.lineSequence()) {
            val m = line.find(raw) ?: continue
            out[m.groupValues[1].trimStart('-').lowercase()] = m.groupValues[2]
        }
        return out
    }
}
