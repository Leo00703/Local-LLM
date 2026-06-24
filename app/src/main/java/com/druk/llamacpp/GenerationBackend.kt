package com.druk.llamacpp

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
}
