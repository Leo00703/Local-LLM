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
        var emissions = 0
        val sb = StringBuilder()
        // Baseline token total BEFORE decode starts. getTokenCount() acquires the
        // native execution lock, so calling it DURING decode (as we did on the first
        // delta) blocked THIS collector until the entire decode finished -> every
        // token after the first arrived in one burst (no visible streaming). Read it
        // only before (decode not running) and after (decode done), NEVER inside the
        // hot collect loop.
        val tokBefore = engine.tokenCount()
        try {
            engine.sendMessage(pendingUserMessage).collect { delta ->
                val now = System.currentTimeMillis()
                if (firstMs == 0L) firstMs = now
                lastMs = now
                emissions++
                sb.append(delta)
                // TEMP diagnostic at the COLLECTOR: compare these timestamps with the
                // LiteRtOnMsg (source) ones to confirm streaming is no longer starved.
                android.util.Log.i(
                    "LiteRtStream",
                    "delta #$emissions @${now - tStart}ms thread=${Thread.currentThread().name} len=${delta.length} total=${sb.length}"
                )
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
        return 0
    }
}
