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
    /**
     * True once a vision-capable projector is attached. Default false so
     * non-visual backends (e.g. remote) don't need to implement it.
     */
    fun supportsVision(): Boolean = false
    /**
     * Attach a multimodal projector so this model can accept images. Default
     * no-op returning false for backends without on-device vision (remote).
     */
    fun loadMmproj(path: String): Boolean = false
    /** Reason the last [loadMmproj] failed (empty on success / not supported). */
    fun getMmprojError(): String = ""
    /** Preferred max image tokens (0 = model default). No-op for non-vision backends. */
    fun setImageMaxTokens(n: Int) {}
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
        /** KV-cache quantization: 0 = F16, 1 = Q8_0, 2 = Q4_0. Local-only; remote ignores it. */
        kvCacheType: Int = 0,
    ): GenerationBackend?
}
