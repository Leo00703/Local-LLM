package com.druk.llamacpp.jni

class NativeLlamaModel {

    private var nativeHandle: Long = 0

    external fun createSession(
        contextSize: Int,
        temperature: Float,
        topP: Float,
        repetitionPenalty: Float,
        topK: Int,
        minP: Float,
        seed: Int,
        thinkingBudget: Int,
        systemPrompt: String,
        kvCacheType: Int = 0
    ): NativeLlamaSession?

    external fun getContextTrainSize(): Int

    external fun getRecommendedContextSize(deviceRamBytes: Long, kvBytesPerElemX16: Int): Int

    external fun getModelSize(): Long

    external fun getModelReport(): String

    external fun supportsThinking(): Boolean

    external fun supportsToolCalling(): Boolean

    /** True once a vision-capable mmproj has been attached via [loadMmproj]. */
    external fun supportsVision(): Boolean

    /**
     * Attach a multimodal projector (mmproj) to this loaded text model, enabling
     * image input. CPU-only. Returns true if the projector initialised and
     * supports vision. [path] is a real filesystem path or an "fd:N" sentinel.
     */
    external fun loadMmproj(path: String): Boolean

    /** Human-readable reason the last [loadMmproj] failed (empty on success). */
    external fun getMmprojError(): String

    /**
     * Preferred max tokens an image may use (0 = model default). Higher = more
     * image resolution/detail. Read at the next [loadMmproj]; changing it after
     * the projector is loaded requires a reload to take effect.
     */
    external fun setImageMaxTokens(n: Int)

    external fun unloadModel()
}
