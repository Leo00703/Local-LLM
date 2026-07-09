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

    override fun setTools(toolsJson: String) { /* no-op: LiteRT has no tool calling */ }

    override fun getToolCallsJson(): String = "[]"

    override fun submitToolResults(resultsJson: String, enableThinking: Boolean): Int = 0

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
        // Baseline token total BEFORE decode starts. getTokenCount() acquires the
        // native execution lock, so calling it DURING decode (as we did on the first
        // delta) blocked THIS collector until the entire decode finished -> every
        // token after the first arrived in one burst (no visible streaming). Read it
        // only before (decode not running) and after (decode done), NEVER inside the
        // hot collect loop.
        val tokBefore = engine.tokenCount()
        try {
            engine.sendMessage(pendingUserMessage, pendingEnableThinking).collect { chunk ->
                val now = System.currentTimeMillis()
                if (firstMs == 0L) firstMs = now
                lastMs = now
                emissions++
                if (chunk.isThought) thinkSb.append(chunk.text) else answerSb.append(chunk.text)
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
        return 0
    }
}
