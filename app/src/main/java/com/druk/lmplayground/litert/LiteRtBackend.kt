package com.druk.lmplayground.litert

import com.druk.llamacpp.GenerationBackend
import com.druk.llamacpp.GenerationStats
import com.druk.llamacpp.LlamaGenerationCallback
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * [GenerationBackend] backed by a persistent LiteRT-LM conversation held in
 * [LiteRtEngine]. Turns accumulate in the native conversation (multi-turn
 * memory), mirroring the in-process streaming adapter
 * [com.druk.lmplayground.remote.RemoteOpenAiBackend].
 *
 * The system prompt is set as the conversation's real `systemInstruction` (in
 * [LiteRtEngine.startConversation]), so it is not handled here.
 *
 * The [LlamaGenerationCallback] contract is ACCUMULATED text (onFullResponse
 * gets the full string every update). LiteRT emits DELTAS split into a thought
 * channel and an answer channel; both are accumulated and re-rendered into the
 * app's `<think>...</think>answer` form so the thinking card can parse them.
 */
class LiteRtBackend(
    private val engine: LiteRtEngine,
) : GenerationBackend {

    private var pendingUserMessage: String = ""
    private var pendingEnableThinking: Boolean = false

    // Tools (function calling). The VM passes the enabled set as OpenAI-format JSON
    // each turn; the conversation must be rebuilt with them (0.13.1 fixes tools at
    // creation time), so we only re-set the engine when the JSON actually changes,
    // to avoid dropping conversation history on every turn.
    private var lastToolsJson: String = "[]"
    private var pendingToolCalls: List<ToolCall> = emptyList()
    // A tool-result turn to send on the next generateAll (set by submitToolResults).
    private var pendingToolResults: Message? = null

    // Authoritative stats for the last generateAll (real token count from the
    // LiteRT conversation + measured decode timing). Consumed by the ViewModel
    // via lastStats(); the per-callback counter undercounts ~3.7x under MTP.
    @Volatile private var stats: GenerationStats? = null

    override fun addMessage(message: String, enableThinking: Boolean) {
        pendingUserMessage = message
        pendingEnableThinking = enableThinking
    }

    override fun replayHistory(userMessages: Array<String>, assistantMessages: Array<String>) {
        // TODO(step): deep replay — a fresh Conversation can't be seeded from a
        // non-suspend method; history is not re-injected in this slice.
    }

    override fun setTools(toolsJson: String) {
        val normalized = toolsJson.takeIf { it.isNotBlank() && it.trim() != "[]" } ?: "[]"
        if (normalized == lastToolsJson) return   // unchanged -> keep the conversation + history
        lastToolsJson = normalized
        val providers: List<ToolProvider> = if (normalized == "[]") emptyList() else {
            val arr = JSONArray(normalized)
            (0 until arr.length()).mapNotNull { i ->
                // OpenAI tools array: [{ "type":"function", "function":{name,description,parameters} }].
                // Hand the function schema to LiteRT as an OpenApiTool; execute() is
                // never called in manual mode (automaticToolCalling=false), the VM runs it.
                val fn = arr.optJSONObject(i)?.optJSONObject("function") ?: return@mapNotNull null
                tool(object : OpenApiTool {
                    override fun getToolDescriptionJsonString(): String = fn.toString()
                    override fun execute(paramsJsonString: String): String = ""
                })
            }
        }
        engine.setTools(providers)   // rebuilds the conversation with these tools
    }

    /** Pending calls from the last [generateAll] that returned 2, as `[{id,name,arguments}]`. */
    override fun getToolCallsJson(): String {
        val out = JSONArray()
        pendingToolCalls.forEachIndexed { i, c ->
            // The model gives no call id; synthesize one (results pair back by name/order).
            out.put(
                JSONObject()
                    .put("id", "call_$i")
                    .put("name", c.name)
                    .put("arguments", JSONObject(c.arguments).toString())
            )
        }
        return out.toString()
    }

    /**
     * Feed tool results (from ToolRegistry: `[{id,name,content}]`) back as a single
     * TOOL message so the next [generateAll] continues the turn with them in context.
     * Returns 0; the VM's loop re-invokes generateAll for the continuation.
     */
    override fun submitToolResults(resultsJson: String, enableThinking: Boolean): Int {
        pendingEnableThinking = enableThinking
        val results = try { JSONArray(resultsJson) } catch (_: Exception) { JSONArray() }
        val contents: List<Content> = (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            Content.ToolResponse(r.optString("name"), r.optString("content"))
        }
        // Always non-null once results are submitted, so generateAll routes to the
        // tool-continuation turn (never falls back to re-sending the original question).
        pendingToolResults = Message.tool(Contents.of(contents))
        pendingToolCalls = emptyList()
        return 0
    }

    override fun setPreambleCachePath(path: String, fingerprint: String) { /* no-op */ }

    override fun printReport() { /* no-op */ }

    override fun getReport(): String = ""

    override fun lastStats(): GenerationStats? = stats

    override fun destroy() { /* engine owns the persistent conversation lifecycle */ }

    /** Combine streamed reasoning + answer into the app's `<think>…</think>answer` form. */
    private fun render(reasoning: StringBuilder, answer: StringBuilder): String =
        if (reasoning.isNotEmpty()) {
            "<think>$reasoning" + (if (answer.isNotEmpty()) "</think>" else "") + answer
        } else {
            answer.toString()
        }

    override suspend fun generateAll(callback: LlamaGenerationCallback): Int {
        val tStart = System.currentTimeMillis()
        var firstMs = 0L
        var lastMs = tStart
        var emissions = 0
        val thinkSb = StringBuilder()
        val answerSb = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        // Baseline token total BEFORE decode starts. getTokenCount() acquires the
        // native execution lock, so calling it DURING decode (as we did on the first
        // delta) blocked THIS collector until the entire decode finished -> every
        // token after the first arrived in one burst (no visible streaming). Read it
        // only before (decode not running) and after (decode done), NEVER inside the
        // hot collect loop.
        val tokBefore = engine.tokenCount()
        // Continue with tool results if we just ran tools; otherwise send the user turn.
        val toolResults = pendingToolResults
        pendingToolResults = null
        val flow = if (toolResults != null)
            engine.sendToolResults(toolResults, pendingEnableThinking)
        else
            engine.sendMessage(pendingUserMessage, pendingEnableThinking)
        try {
            flow.collect { chunk ->
                val now = System.currentTimeMillis()
                if (firstMs == 0L) firstMs = now
                lastMs = now
                emissions++
                when {
                    chunk.toolCalls.isNotEmpty() -> toolCalls.addAll(chunk.toolCalls)
                    chunk.isThought -> thinkSb.append(chunk.text)
                    else -> answerSb.append(chunk.text)
                }
                callback.onFullResponse(render(thinkSb, answerSb))
            }
        } catch (e: CancellationException) {
            // Cooperative cancellation (user stopped generation): rethrow so the
            // ViewModel's stop path unwinds, mirroring RemoteOpenAiBackend.
            stats = null
            throw e
        } catch (e: Exception) {
            val shown = render(thinkSb, answerSb)
            val note = (if (shown.isNotEmpty()) shown + "\n\n" else "") +
                "⚠️ " + (e.message ?: "generation failed")
            callback.onFullResponse(note)
            stats = null
            return 0
        }
        // Decode is done now, so getTokenCount() no longer contends with it.
        // tokEnd - tokBefore = this turn's (prompt + completion); subtract an estimate
        // of the prompt (~chars/4) to approximate completion. The delta itself is real
        // (MTP-accurate), which beats counting emissions (they undercount ~3.7x on MTP).
        val tokEnd = engine.tokenCount()
        val promptEst = (pendingUserMessage.length + 3) / 4
        val completion = (tokEnd - tokBefore - promptEst).coerceAtLeast(emissions).coerceAtLeast(1)
        val ttft = if (firstMs > 0L) (firstMs - tStart).toInt().coerceAtLeast(0) else 0
        val decodeMs = if (firstMs > 0L) (lastMs - firstMs).toInt().coerceAtLeast(0) else 0
        stats = GenerationStats(
            completionTokens = completion,
            ttftMs = ttft,
            decodeMs = decodeMs,
        )
        // Model requested tool calls: hand them to the VM's tool loop (it runs each,
        // calls submitToolResults, then re-invokes generateAll for the continuation).
        if (toolCalls.isNotEmpty()) {
            pendingToolCalls = toolCalls
            return 2
        }
        return 0
    }
}
