package com.druk.lmplayground.remote

import android.util.Base64
import com.druk.llamacpp.GenerationBackend
import com.druk.llamacpp.GenerationStats
import com.druk.llamacpp.LlamaGenerationCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * [GenerationBackend] backed by an OpenAI-compatible HTTP server. The whole
 * conversation is kept client-side (there is no server KV cache); each
 * [generateAll] re-sends the running message list with `stream=true` and
 * forwards the accumulated assistant text through the same
 * [LlamaGenerationCallback.onFullResponse] contract the local engine uses.
 *
 * Reasoning handling: servers expose thinking either inline as `<think>...`
 * inside `delta.content` (Qwen templates) or in a separate
 * `delta.reasoning_content` / `delta.reasoning` field (DeepSeek-R1, many
 * LM Studio reasoning models). Both are normalized to a single
 * `<think>…</think>answer` stream so the app's thinking card renders it.
 *
 * Tool calling: when [setTools] receives enabled tools they are forwarded as
 * `tools`; streamed `tool_calls` are accumulated and surfaced through the
 * [getToolCallsJson] / [submitToolResults] contract — [generateAll] returns 2 so
 * the ViewModel runs the same tool loop it uses for the local engine.
 * `stream_options.include_usage` is requested so the server's exact token counts
 * drive the stats via [lastStats].
 */
class RemoteOpenAiBackend(
    private val client: RemoteOpenAiClient,
    private val model: String,
    private val systemPrompt: String,
    private val temperature: Float,
    private val topP: Float,
    private val topK: Int,
    private val minP: Float,
    private val repeatPenalty: Float,
    private val seed: Int,
    private val maxContext: Int,
) : GenerationBackend {

    // role: system/user/assistant/tool. For an assistant turn that issued tool
    // calls, [toolCalls] holds the OpenAI-format array and [content] may be null;
    // for a tool-result turn, [toolCallId] links it to the call it answers.
    // [imageDataUrls] carries `data:image/jpeg;base64,…` parts for a vision user
    // turn; when present the message content is emitted as an OpenAI content-part
    // array (text + image_url) instead of a plain string.
    private data class Msg(
        val role: String,
        val content: String?,
        val toolCalls: JSONArray? = null,
        val toolCallId: String? = null,
        val imageDataUrls: List<String>? = null,
    )

    private data class PendingCall(val id: String, val name: String, val arguments: String)

    /** Accumulates one streamed tool call across SSE delta fragments (by index). */
    private class ToolCallAcc {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private val history = mutableListOf<Msg>()
    private val pendingToolCalls = mutableListOf<PendingCall>()
    // Data URLs staged via [setImageData], consumed by the next [addMessage] user
    // turn (one vision turn). Kept separate so a turn without an image is unchanged.
    private val pendingImages = mutableListOf<String>()

    @Volatile private var currentCall: Call? = null
    @Volatile private var lastEnableThinking = true
    @Volatile private var stats: GenerationStats? = null
    @Volatile private var contextUsed = 0
    // OpenAI-format tools array (from ToolRegistry.toOpenAIToolsJson), or null when
    // the user has no tools enabled. Forwarded as `tools` on each request.
    @Volatile private var toolsJson: String? = null

    override fun addMessage(message: String, enableThinking: Boolean) {
        lastEnableThinking = enableThinking
        val images = pendingImages.toList().takeIf { it.isNotEmpty() }
        pendingImages.clear()
        history.add(Msg("user", message, imageDataUrls = images))
    }

    /**
     * Stage an image for the next user turn. The bytes are the transcoded JPEG
     * the vision UI already produced; base64-encode them into an OpenAI
     * `image_url` data URL. Vision-capable servers (e.g. Ollama llava / an
     * LM Studio VLM) read it; a non-vision server rejects the request, which the
     * error path below surfaces as a normal failure.
     */
    override fun setImageData(data: ByteArray) {
        if (data.isEmpty()) return
        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
        pendingImages.add("data:image/jpeg;base64,$b64")
    }

    override fun replayHistory(userMessages: Array<String>, assistantMessages: Array<String>) {
        history.clear()
        for (i in userMessages.indices) {
            history.add(Msg("user", userMessages[i]))
            history.add(Msg("assistant", assistantMessages[i]))
        }
    }

    override fun setTools(toolsJson: String) {
        this.toolsJson = toolsJson.takeIf { it.isNotBlank() && it.trim() != "[]" }
    }

    /** Pending calls from the last [generateAll] that returned 2, as `[{id,name,arguments}]`. */
    override fun getToolCallsJson(): String {
        val arr = JSONArray()
        pendingToolCalls.forEach { c ->
            arr.put(JSONObject().put("id", c.id).put("name", c.name).put("arguments", c.arguments))
        }
        return arr.toString()
    }

    /**
     * Feed tool results (from ToolRegistry: `[{id,name,content}]`) back as `tool`
     * messages so the next [generateAll] continues the turn. Returns 0; the VM's
     * loop re-invokes generateAll for the continuation.
     */
    override fun submitToolResults(resultsJson: String, enableThinking: Boolean): Int {
        lastEnableThinking = enableThinking
        try {
            val results = JSONArray(resultsJson)
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                history.add(
                    Msg(
                        role = "tool",
                        content = r.optString("content"),
                        toolCallId = r.optString("id"),
                    )
                )
            }
        } catch (_: Exception) { /* malformed results: skip, model continues without them */ }
        pendingToolCalls.clear()
        return 0
    }

    override fun setPreambleCachePath(path: String, fingerprint: String) { /* no KV cache */ }

    override fun printReport() { /* no-op */ }

    /**
     * Mimics the native report's "Context:" line so the ViewModel's existing
     * parser can drive the context ring from the server's real token usage.
     */
    override fun getReport(): String =
        if (contextUsed > 0) "Context: $contextUsed / $maxContext tokens" else ""

    override fun lastStats(): GenerationStats? = stats

    override fun destroy() {
        currentCall?.cancel()
    }

    /** Combine streamed reasoning + answer into the app's `<think>…</think>answer` form. */
    private fun render(reasoning: StringBuilder, content: StringBuilder): String =
        if (reasoning.isNotEmpty()) {
            "<think>$reasoning" + (if (content.isNotEmpty()) "</think>" else "") + content
        } else {
            content.toString()
        }

    override suspend fun generateAll(callback: LlamaGenerationCallback): Int {
        // Message text is sent verbatim and never mutated per-turn: an earlier
        // version appended "/no_think" to the last user message, which changed
        // the prompt prefix between turns and defeated the server's KV-cache
        // reuse (each turn re-processed the whole prompt, so speed fell off as
        // the chat grew). Thinking is disabled purely via request parameters
        // below, which don't alter the cached token prefix.
        val messages = buildList {
            if (systemPrompt.isNotBlank()) add(Msg("system", systemPrompt))
            addAll(history)
        }
        val modelId = model.lowercase()
        val disableThinking = !lastEnableThinking
        val bodyJson = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            put("temperature", temperature.toDouble())
            put("top_p", topP.toDouble())
            // Vendor extensions honored by LM Studio (ignored elsewhere).
            if (topK > 0) put("top_k", topK)
            if (minP > 0f) put("min_p", minP.toDouble())
            if (repeatPenalty != 1.0f) put("repeat_penalty", repeatPenalty.toDouble())
            if (seed >= 0) put("seed", seed)
            // Turning thinking OFF: different families read different switches, so
            // send each one its server will honor (others ignore unknown fields).
            // Only emitted when OFF, to leave the default path untouched.
            //   enable_thinking=false   → Qwen3.x + most HF templates (LM Studio
            //                             forwards chat_template_kwargs to Jinja).
            //   thinking:{type:disabled} → GLM-4.5/4.6/4.7 native top-level switch.
            // gpt-oss / DeepSeek-R1 / *-thinking-* are reasoning-only and cannot
            // be turned off by any flag — that is the model, not the client.
            if (disableThinking) {
                put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                if (modelId.contains("glm")) {
                    put("thinking", JSONObject().put("type", "disabled"))
                }
            }
            // Tools: forward the enabled set so the model can call them. The VM's
            // loop runs each call locally and feeds results back via
            // submitToolResults, then re-invokes generateAll for the continuation.
            toolsJson?.let {
                put("tools", JSONArray(it))
                put("tool_choice", "auto")
            }
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    val o = JSONObject().put("role", m.role)
                    when {
                        m.toolCallId != null -> {
                            o.put("tool_call_id", m.toolCallId)
                            o.put("content", m.content ?: "")
                        }
                        m.toolCalls != null -> {
                            o.put("content", if (m.content.isNullOrEmpty()) JSONObject.NULL else m.content)
                            o.put("tool_calls", m.toolCalls)
                        }
                        else -> {
                            val imgs = m.imageDataUrls
                            if (imgs != null && imgs.isNotEmpty()) {
                                // Vision turn: OpenAI content-part array (text first,
                                // then each image as an image_url data URL).
                                val parts = JSONArray()
                                if (!m.content.isNullOrEmpty()) {
                                    parts.put(JSONObject().put("type", "text").put("text", m.content))
                                }
                                for (url in imgs) {
                                    parts.put(
                                        JSONObject()
                                            .put("type", "image_url")
                                            .put("image_url", JSONObject().put("url", url))
                                    )
                                }
                                o.put("content", parts)
                            } else {
                                o.put("content", m.content ?: "")
                            }
                        }
                    }
                    put(o)
                }
            })
        }.toString()

        val reasoning = StringBuilder()
        val content = StringBuilder()
        val toolAccs = linkedMapOf<Int, ToolCallAcc>()
        val tStart = System.currentTimeMillis()
        var firstTokenMs = 0L
        var promptTokens = 0
        var completionTokens = 0
        try {
            withContext(Dispatchers.IO) {
                val call = client.chatCompletionCall(bodyJson)
                currentCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val source = response.body?.source() ?: throw IOException("empty response body")
                    while (!source.exhausted()) {
                        ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload == "[DONE]") break
                        // Skip a malformed / non-JSON data frame (some gateways emit
                        // error or keep-alive frames) instead of aborting the whole
                        // stream — which would drop any tool_calls already accumulated.
                        val obj = try {
                            JSONObject(payload)
                        } catch (e: org.json.JSONException) {
                            continue
                        }
                        obj.optJSONObject("usage")?.let { u ->
                            promptTokens = u.optInt("prompt_tokens", promptTokens)
                            completionTokens = u.optInt("completion_tokens", completionTokens)
                        }
                        val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                        val reason = delta?.optString("reasoning_content").orEmpty()
                            .ifEmpty { delta?.optString("reasoning").orEmpty() }
                        val text = delta?.optString("content").orEmpty()
                        if (reason.isNotEmpty() || text.isNotEmpty()) {
                            if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis()
                            if (reason.isNotEmpty()) reasoning.append(reason)
                            if (text.isNotEmpty()) content.append(text)
                            callback.onFullResponse(render(reasoning, content))
                        }
                        // Tool calls stream in fragments keyed by `index`: the first
                        // fragment carries id + function.name, later ones append
                        // function.arguments. Accumulate per index.
                        delta?.optJSONArray("tool_calls")?.let { tcs ->
                            for (j in 0 until tcs.length()) {
                                val tc = tcs.optJSONObject(j)
                                if (tc != null) {
                                    val idx = tc.optInt("index", j)
                                    val acc = toolAccs.getOrPut(idx) { ToolCallAcc() }
                                    tc.optString("id").takeIf { it.isNotEmpty() }?.let { acc.id = it }
                                    tc.optJSONObject("function")?.let { fn ->
                                        fn.optString("name").takeIf { it.isNotEmpty() }?.let { acc.name = it }
                                        acc.args.append(fn.optString("arguments"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val endMs = System.currentTimeMillis()
            stats = if (completionTokens > 0) {
                GenerationStats(
                    completionTokens = completionTokens,
                    ttftMs = if (firstTokenMs > 0L) (firstTokenMs - tStart).toInt().coerceAtLeast(0) else 0,
                    decodeMs = if (firstTokenMs > 0L) (endMs - firstTokenMs).toInt().coerceAtLeast(0) else 0,
                )
            } else null
            if (promptTokens + completionTokens > 0) contextUsed = promptTokens + completionTokens

            // Did the model ask to call tools? Record an assistant turn carrying the
            // tool_calls (so the next request shows the server its own call) and hand
            // back 2 for the VM's tool loop, which executes them and re-invokes us.
            val calls = toolAccs.entries.sortedBy { it.key }
                .map { (idx, acc) ->
                    PendingCall(
                        id = acc.id.ifEmpty { "call_$idx" },
                        name = acc.name,
                        arguments = acc.args.toString().ifEmpty { "{}" },
                    )
                }
                .filter { it.name.isNotEmpty() }
            if (calls.isNotEmpty()) {
                val tcArray = JSONArray()
                calls.forEach { c ->
                    tcArray.put(
                        JSONObject()
                            .put("id", c.id)
                            .put("type", "function")
                            .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))
                    )
                }
                history.add(Msg("assistant", content.toString().ifEmpty { null }, toolCalls = tcArray))
                pendingToolCalls.clear()
                pendingToolCalls.addAll(calls)
                return 2
            }

            // Normal turn: history keeps the answer only (no reasoning) so re-sent
            // turns stay clean.
            if (content.isNotEmpty()) history.add(Msg("assistant", content.toString()))
            return 0
        } catch (e: CancellationException) {
            currentCall?.cancel()
            if (content.isNotEmpty()) history.add(Msg("assistant", content.toString()))
            throw e
        } catch (e: Exception) {
            val shown = render(reasoning, content)
            val note = (if (shown.isNotEmpty()) shown + "\n\n" else "") +
                "⚠️ " + (e.message ?: "request failed")
            callback.onFullResponse(note)
            if (content.isNotEmpty()) history.add(Msg("assistant", content.toString()))
            return 0
        } finally {
            currentCall = null
        }
    }
}
