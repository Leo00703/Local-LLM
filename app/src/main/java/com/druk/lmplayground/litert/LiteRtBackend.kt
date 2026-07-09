package com.druk.lmplayground.litert

import com.druk.llamacpp.GenerationBackend
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

    override fun destroy() { /* engine owns the persistent conversation lifecycle */ }

    override suspend fun generateAll(callback: LlamaGenerationCallback): Int {
        val sb = StringBuilder()
        try {
            engine.sendMessage(pendingUserMessage).collect { delta ->
                sb.append(delta)
                callback.onFullResponse(sb.toString())
            }
        } catch (e: CancellationException) {
            // Cooperative cancellation (user stopped generation): rethrow so the
            // ViewModel's stop path unwinds, mirroring RemoteOpenAiBackend.
            throw e
        } catch (e: Exception) {
            val shown = sb.toString()
            val note = (if (shown.isNotEmpty()) shown + "\n\n" else "") +
                "⚠️ " + (e.message ?: "generation failed")
            callback.onFullResponse(note)
        }
        return 0
    }
}
