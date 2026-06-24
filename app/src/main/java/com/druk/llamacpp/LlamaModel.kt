package com.druk.llamacpp

/**
 * AIDL-proxy view of a loaded model. Holds an opaque positive [modelId]
 * issued by the service; the native pointer never leaves the service
 * process.
 */
class LlamaModel internal constructor(
    private val client: InferenceClient,
    internal val modelId: Int,
) : GenerationModel {
    override fun getModelSize(): Long = client.withService { it.getModelSize(modelId) }

    override fun getModelReport(): String = client.withService { it.getModelReport(modelId) }

    override fun getContextTrainSize(): Int = client.withService { it.getContextTrainSize(modelId) }

    override fun supportsThinking(): Boolean = client.withService { it.supportsThinking(modelId) }

    override fun supportsToolCalling(): Boolean = client.requireConnected().supportsToolCalling(modelId)

    override fun unloadModel() {
        try {
            client.withService { it.unloadModel(modelId) }
        } catch (_: InferenceUnavailableException) {
            // Service is gone anyway — the model handle is implicitly
            // released. Don't bubble this up; callers just want the
            // unload to be cleaned up.
        }
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
    ): LlamaGenerationSession? {
        InferenceLimits.requireWithinBudget(systemPrompt, "system prompt")
        val params = SamplerParams(
            contextSize = contextSize,
            temperature = temperature,
            topP = topP,
            repetitionPenalty = repetitionPenalty,
            topK = topK,
            minP = minP,
            seed = seed,
            thinkingBudget = thinkingBudget,
            systemPrompt = systemPrompt,
        )
        val sessionId = client.withService { it.createSession(modelId, params) }
        if (sessionId == 0) return null
        return LlamaGenerationSession(client, sessionId)
    }
}
