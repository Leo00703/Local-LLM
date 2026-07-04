package com.druk.lmplayground.remote

import com.druk.llamacpp.GenerationBackend
import com.druk.llamacpp.GenerationModel

/**
 * [GenerationModel] for an OpenAI-compatible remote server. No GGUF is
 * loaded; capability/size info is synthesized. [maxContext] is the server
 * model's real context window (from its native API) so the context ring is
 * accurate; falls back to [DEFAULT_CONTEXT] when unknown.
 */
class RemoteOpenAiModel(
    private val baseUrl: String,
    private val modelId: String,
    private val maxContext: Int = DEFAULT_CONTEXT,
) : GenerationModel {

    private val client = RemoteOpenAiClient(baseUrl)

    override fun getModelSize(): Long = 0L

    override fun getModelReport(): String = "Remote OpenAI-compatible server: $baseUrl ($modelId)"

    override fun getContextTrainSize(): Int = if (maxContext > 0) maxContext else DEFAULT_CONTEXT

    override fun supportsThinking(): Boolean = false

    override fun supportsToolCalling(): Boolean = false

    override fun unloadModel() {
        // Ask the server to evict the model from memory. Best-effort and
        // blocking — the ViewModel calls this on a background dispatcher.
        client.offloadBlocking(modelId)
    }

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
        kvCacheType: Int,   // ignored: a remote server owns its own KV cache
    ): GenerationBackend {
        return RemoteOpenAiBackend(
            client = client,
            model = modelId,
            systemPrompt = systemPrompt,
            temperature = temperature,
            topP = topP,
            topK = topK,
            minP = minP,
            repeatPenalty = repetitionPenalty,
            seed = seed,
            maxContext = getContextTrainSize(),
        )
    }

    companion object {
        /** Fallback context size when the server doesn't report one. */
        const val DEFAULT_CONTEXT = 8192
    }
}
