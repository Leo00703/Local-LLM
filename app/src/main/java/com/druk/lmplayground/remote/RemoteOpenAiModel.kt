package com.druk.lmplayground.remote

import com.druk.llamacpp.GenerationBackend
import com.druk.llamacpp.GenerationModel

/**
 * [GenerationModel] for an OpenAI-compatible remote server. No GGUF is
 * loaded; capability/size info is synthesized. createSession returns a
 * [RemoteOpenAiBackend] bound to [modelId] (no network call at creation).
 */
class RemoteOpenAiModel(
    private val baseUrl: String,
    private val modelId: String,
) : GenerationModel {

    private val client = RemoteOpenAiClient(baseUrl)

    override fun getModelSize(): Long = 0L

    override fun getModelReport(): String = "Remote OpenAI-compatible server: $baseUrl ($modelId)"

    override fun getContextTrainSize(): Int = DEFAULT_CONTEXT

    override fun supportsThinking(): Boolean = false

    override fun supportsToolCalling(): Boolean = false

    override fun unloadModel() { /* nothing to release */ }

    override fun createSession(
        contextSize: Int,
        temperature: Float,
        topP: Float,
        repetitionPenalty: Float,
        topK: Int,
        minP: Float,
        seed: Int,
        thinkingBudget: Int,
        systemPrompt: String,
    ): GenerationBackend {
        return RemoteOpenAiBackend(
            client = client,
            model = modelId,
            systemPrompt = systemPrompt,
            temperature = temperature,
            topP = topP,
            seed = seed,
        )
    }

    companion object {
        /** Fallback context size shown on the ring (the server may differ). */
        const val DEFAULT_CONTEXT = 8192
    }
}
