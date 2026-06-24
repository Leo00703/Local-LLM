package com.druk.llamacpp

/**
 * A loaded model handle that creates [GenerationBackend] sessions. Abstracts
 * over the local llama.cpp model ([LlamaModel]) and remote backends (which
 * synthesize capability/size info from configuration rather than a GGUF).
 */
interface GenerationModel {
    fun getModelSize(): Long
    fun getModelReport(): String
    fun getContextTrainSize(): Int
    fun supportsThinking(): Boolean
    fun supportsToolCalling(): Boolean
    fun unloadModel()
    fun createSession(
        contextSize: Int,
        temperature: Float,
        topP: Float,
        repetitionPenalty: Float,
        topK: Int,
        minP: Float,
        seed: Int,
        thinkingBudget: Int,
        systemPrompt: String,
    ): GenerationBackend?
}
