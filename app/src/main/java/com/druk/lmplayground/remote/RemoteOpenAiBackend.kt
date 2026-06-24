package com.druk.lmplayground.remote

import com.druk.llamacpp.GenerationBackend
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
 * v1 scope: plain chat. Tool calling and reasoning budget are not forwarded
 * (the model decides on its own); setTools/getToolCallsJson are inert so the
 * ViewModel's tool loop never engages.
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

    @Volatile
    private var currentCall: Call? = null

    override fun addMessage(message: String, enableThinking: Boolean) {
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

    override fun getReport(): String = ""

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
            put("temperature", temperature.toDouble())
            put("top_p", topP.toDouble())
            if (seed >= 0) put("seed", seed)
            put("messages", JSONArray().apply {
                messages.forEach {
                    put(JSONObject().put("role", it.role).put("content", it.content))
                }
            })
        }.toString()

        val sb = StringBuilder()
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
                        val delta = JSONObject(payload)
                            .optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta")?.optString("content").orEmpty()
                        if (delta.isNotEmpty()) {
                            sb.append(delta)
                            callback.onFullResponse(sb.toString())
                        }
                    }
                }
            }
            if (sb.isNotEmpty()) history.add(Msg("assistant", sb.toString()))
            return 0
        } catch (e: CancellationException) {
            currentCall?.cancel()
            if (sb.isNotEmpty()) history.add(Msg("assistant", sb.toString()))
            throw e
        } catch (e: Exception) {
            // Surface the failure in the chat bubble instead of crashing the
            // generation coroutine; keep the partial (if any) in history.
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
