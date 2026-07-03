package com.druk.llamacpp

/**
 * Authoritative per-generation stats a backend can report after a turn,
 * bypassing the UI's one-call-per-token assumption. Used by remote backends
 * (which stream multi-token SSE chunks) to surface the server's exact token
 * count + decode timing. Local engine returns null and relies on the
 * decode-window measurement instead.
 *
 * @param completionTokens generated tokens (excludes the prompt)
 * @param ttftMs time-to-first-token in ms
 * @param decodeMs decode window (first token → last token) in ms
 */
data class GenerationStats(
    val completionTokens: Int,
    val ttftMs: Int,
    val decodeMs: Int,
)

/**
 * A live generation session: send a user turn, stream the response, and run
 * the tool-call loop. Abstracts over the local llama.cpp engine
 * ([LlamaGenerationSession], which talks to the `:llama` service over AIDL)
 * and remote backends (e.g. an OpenAI-compatible HTTP server).
 *
 * The streaming contract is [generateAll] + [LlamaGenerationCallback]:
 * `onFullResponse` receives the full accumulated text on every update.
 */
interface GenerationBackend {
    fun addMessage(message: String, enableThinking: Boolean)
    fun replayHistory(userMessages: Array<String>, assistantMessages: Array<String>)
    fun setTools(toolsJson: String)
    fun getToolCallsJson(): String
    fun submitToolResults(resultsJson: String, enableThinking: Boolean): Int
    fun setPreambleCachePath(path: String, fingerprint: String)
    fun printReport()
    fun getReport(): String
    fun destroy()
    suspend fun generateAll(callback: LlamaGenerationCallback): Int

    /**
     * Authoritative stats for the most recent [generateAll], or null when the
     * backend doesn't report them (the local engine, which is measured via the
     * decode window instead). Read once after generation completes.
     */
    fun lastStats(): GenerationStats? = null

    /**
     * Stage an encoded image (jpg/png/… bytes) for the next [addMessage] turn,
     * for vision-capable local models. Default no-op so backends without
     * on-device vision (e.g. remote) inherit it unchanged.
     */
    fun setImageData(data: ByteArray) {}

    /**
     * Attach the model's already-loaded vision projector to this LIVE session
     * (the lazy vision flow loads the projector only at the first image send,
     * after sessions exist). Returns true when a vision-capable projector is
     * attached. Default false so non-vision backends inherit it unchanged.
     */
    fun attachProjector(): Boolean = false

    /**
     * Token count of the last image evaluated on this session (0 if none / not
     * a vision backend). Lets the UI show how much context an image consumed.
     */
    fun getLastImageTokens(): Int = 0
}
