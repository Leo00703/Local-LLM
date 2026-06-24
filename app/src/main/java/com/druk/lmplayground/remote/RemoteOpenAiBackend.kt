package com.druk.lmplayground.remote

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
 * Tool calling is not forwarded in v1. Reasoning is forwarded best-effort: when
 * the user disables thinking, `chat_template_kwargs.enable_thinking=false` is
 * sent (honored by LM Studio / Qwen templates; ignored by servers that don't
 * support it). `stream_options.include_usage` is requested so the server's
 * exact token counts drive the stats via [lastStats].
 */
class RemoteOpenAiBackend(
    private val client: RemoteOpenAiClient,
    private val model: String,
    private val systemPrompt: String,
    private val temperature: Float,
    private val topP: Float,
    private val seed: Int,
) : GenerationBackend {

    private data class Msg(val role: String, val content: String)

    private val history = mutableListOf<Msg>()

    @Volatile private var currentCall: Call? = null
    @Volatile private var lastEnableThinking = true
    @Volatile private var stats: GenerationStats? = null
    @Volatile private var contextUsed = 0

    override fun addMessage(message: String, enableThinking: Boolean) {
        lastEnableThinking = enableThinking
        history.add(Msg("user", message))
    }

    override fun replayHistory(userMessages: Array<String>, assistantMessages: Array<String>) {
        history.clear()
        for (i in userMessages.indices) {
            history.add(Msg("user", userMessages[i]))
            history.add(Msg("assistant", assistantMessages[i]))
        }
    }

    override fun setTools(toolsJson: String) { /* tools not forwarded in v1 */ }

    override fun getToolCallsJson(): String = "[]"

    override fun submitToolResults(resultsJson: String, enableThinking: Boolean): Int = 0

    override fun setPreambleCachePath(path: String, fingerprint: String) { /* no KV cache */ }

    override fun printReport() { /* no-op */ }

    /**
     * Mimics the native report's "Context:" line so the ViewModel's existing
     * parser can drive the context ring from the server's real token usage.
     */
    override fun getReport(): String =
        if (contextUsed > 0) "Context: $contextUsed / ${RemoteOpenAiModel.DEFAULT_CONTEXT} tokens" else ""

    override fun lastStats(): GenerationStats? = stats

    override fun destroy() {
        currentCall?.cancel()
    }

    override suspend fun generateAll(callback: LlamaGenerationCallback): Int {
        val messages = buildList {
            if (systemPrompt.isNotBlank()) add(Msg("system", systemPrompt))
            addAll(history)
        }
        val bodyJson = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            put("temperature", temperature.toDouble())
            put("top_p", topP.toDouble())
            if (seed >= 0) put("seed", seed)
            // Only sent when the user turned thinking OFF, to avoid tripping
            // servers that reject unknown template kwargs on the default path.
            if (!lastEnableThinking) {
                put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            }
            put("messages", JSONArray().apply {
                messages.forEach {
                    put(JSONObject().put("role", it.role).put("content", it.content))
                }
            })
        }.toString()

        val sb = StringBuilder()
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
                        val obj = JSONObject(payload)
                        obj.optJSONObject("usage")?.let { u ->
                            promptTokens = u.optInt("prompt_tokens", promptTokens)
                            completionTokens = u.optInt("completion_tokens", completionTokens)
                        }
                        val delta = obj.optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta")?.optString("content").orEmpty()
                        if (delta.isNotEmpty()) {
                            if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis()
                            sb.append(delta)
                            callback.onFullResponse(sb.toString())
                        }
                    }
                }
            }
            val endMs = System.currentTimeMillis()
            if (sb.isNotEmpty()) history.add(Msg("assistant", sb.toString()))
            // Only trust server-reported counts; otherwise leave stats null so
            // the ViewModel keeps its (approximate) per-chunk fallback count.
            stats = if (completionTokens > 0) {
                GenerationStats(
                    completionTokens = completionTokens,
                    ttftMs = if (firstTokenMs > 0L) (firstTokenMs - tStart).toInt().coerceAtLeast(0) else 0,
                    decodeMs = if (firstTokenMs > 0L) (endMs - firstTokenMs).toInt().coerceAtLeast(0) else 0,
                )
            } else null
            if (promptTokens + completionTokens > 0) contextUsed = promptTokens + completionTokens
            return 0
        } catch (e: CancellationException) {
            currentCall?.cancel()
            if (sb.isNotEmpty()) history.add(Msg("assistant", sb.toString()))
            throw e
        } catch (e: Exception) {
            val note = (if (sb.isNotEmpty()) sb.toString() + "\n\n" else "") +
                "⚠️ " + (e.message ?: "request failed")
            callback.onFullResponse(note)
            if (sb.isNotEmpty()) history.add(Msg("assistant", sb.toString()))
            return 0
        } finally {
            currentCall = null
        }
    }
}
