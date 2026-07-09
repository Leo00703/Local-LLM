package com.druk.lmplayground.litert

import com.druk.llamacpp.GenerationBackend
import com.druk.llamacpp.GenerationStats
import com.druk.llamacpp.LlamaGenerationCallback
import kotlinx.coroutines.CancellationException

/**
 * [GenerationBackend] backed by a persistent LiteRT-LM conversation held in
 * [LiteRtEngine]. Turns accumulate in the native conversation (multi-turn
 * memory), mirroring the in-process streaming adapter
 * [com.druk.lmplayground.remote.RemoteOpenAiBackend].
 *
 * The LiteRT `Conversation` config can't take a String system instruction (its
 * `systemInstruction` param is a `Content`, not a String), so the system prompt
 * is prepended once to the first user turn instead.
 *
 * The [LlamaGenerationCallback] contract is ACCUMULATED text (onFullResponse
 * gets the full string every update), but LiteRT emits DELTAS, so each delta is
 * appended into a [StringBuilder] and the running total is forwarded.
 */
class LiteRtBackend(
    private val engine: LiteRtEngine,
    private val systemPrompt: String,
) : GenerationBackend {

    private var pendingUserMessage: String = ""
    private var firstTurn = true

    // Authoritative stats for the last generateAll (real token count from the
    // LiteRT conversation + measured decode timing). Consumed by the ViewModel
    // via lastStats(); the per-callback counter undercounts ~3.7x under MTP.
    @Volatile private var stats: GenerationStats? = null

    override fun addMessage(message: String, enableThinking: Boolean) {
        pendingUserMessage = if (firstTurn && systemPrompt.isNotBlank()) {
            "$systemPrompt\n\n$message"
        } else {
            message
        }
        firstTurn = false
    }

    override fun replayHistory(userMessages: Array<String>, assistantMessages: Array<String>) {
        // TODO(step): deep replay — a fresh Conversation can't be seeded from a
        // non-suspend method; history is not re-injected in this slice.
    }

    override fun setTools(toolsJson: String) { /* no-op: LiteRT has no tool calling */ }

    override fun getToolCallsJson(): String = "[]"

    override fun submitToolResults(resultsJson: String, enableThinking: Boolean): Int = 0

    override fun setPreambleCachePath(path: String, fingerprint: String) { /* no-op */ }

    override fun printReport() { /* no-op */ }

    override fun getReport(): String = ""

    override fun lastStats(): GenerationStats? = stats

    override fun destroy() { /* engine owns the persistent conversation lifecycle */ }

    override suspend fun generateAll(callback: LlamaGenerationCallback): Int {
        val tStart = System.currentTimeMillis()
        var firstMs = 0L
        var lastMs = tStart
        var tokAtFirst = -1
        var emissions = 0
        val sb = StringBuilder()
        try {
            engine.sendMessage(pendingUserMessage).collect { delta ->
                val now = System.currentTimeMillis()
                if (firstMs == 0L) {
                    firstMs = now
                    // Token total just as the first chunk lands; the completion
                    // window is measured from here so the prompt + first chunk
                    // don't inflate the tok/s.
                    tokAtFirst = engine.tokenCount()
                }
                lastMs = now
                emissions++
                sb.append(delta)
                callback.onFullResponse(sb.toString())
            }
        } catch (e: CancellationException) {
            // Cooperative cancellation (user stopped generation): rethrow so the
            // ViewModel's stop path unwinds, mirroring RemoteOpenAiBackend.
            stats = null
            throw e
        } catch (e: Exception) {
            val shown = sb.toString()
            val note = (if (shown.isNotEmpty()) shown + "\n\n" else "") +
                "⚠️ " + (e.message ?: "generation failed")
            callback.onFullResponse(note)
            stats = null
            return 0
        }
        val tokEnd = engine.tokenCount()
        // Real completion tokens over the decode window. getTokenCount is the
        // true running total (prompt + generated), so the count from the first
        // chunk to the end is the generated span. Robust fallbacks if the live
        // count didn't move.
        val windowed = if (tokAtFirst >= 0) (tokEnd - tokAtFirst) else 0
        val completion = when {
            windowed > 0 -> windowed
            // Count didn't update live: fall back to the total (still real
            // tokens, better than the emission count) or, last resort, emissions.
            tokEnd > 0 && emissions > 0 -> tokEnd.coerceAtLeast(emissions)
            else -> emissions
        }
        val ttft = if (firstMs > 0L) (firstMs - tStart).toInt().coerceAtLeast(0) else 0
        val decodeMs = if (firstMs > 0L) (lastMs - firstMs).toInt().coerceAtLeast(0) else 0
        stats = GenerationStats(
            completionTokens = completion,
            ttftMs = ttft,
            decodeMs = decodeMs,
        )
        return 0
    }
}
