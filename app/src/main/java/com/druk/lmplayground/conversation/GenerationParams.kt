package com.druk.lmplayground.conversation

data class GenerationParams(
    val contextSize: Int = 4096,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val repetitionPenalty: Float = 1.0f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val seed: Int = -1,
    val thinkingBudget: Int = contextSize / 4,
    // Vision only: max tokens an attached image may use (higher = more image
    // resolution/detail, more context). Applied to the projector via
    // mtmd_context_params.image_max_tokens. Ignored by text-only models.
    val imageMaxTokens: Int = 256,
    // KV-cache quantization (local models only): 0 = F16, 1 = Q8_0, 2 = Q4_0.
    // Default Q8_0 — ~half the KV memory at near-zero quality loss. Applied at
    // context creation; the native side auto-falls back to F16 if the device
    // can't do Flash Attention + quantized KV. Ignored by remote backends.
    val kvCacheType: Int = 1
) {
    fun toMap(): Map<String, Float> = mapOf(
        "contextSize" to contextSize.toFloat(),
        "temperature" to temperature,
        "topP" to topP,
        "repetitionPenalty" to repetitionPenalty,
        "topK" to topK.toFloat(),
        "minP" to minP,
        "seed" to seed.toFloat(),
        "thinkingBudget" to thinkingBudget.toFloat(),
        "imageMaxTokens" to imageMaxTokens.toFloat(),
        "kvCacheType" to kvCacheType.toFloat()
    )

    companion object {
        fun fromMap(map: Map<String, Float>): GenerationParams {
            val contextSize = map["contextSize"]?.toInt() ?: 4096
            return GenerationParams(
                contextSize = contextSize,
                temperature = map["temperature"] ?: 0.8f,
                topP = map["topP"] ?: 0.95f,
                repetitionPenalty = map["repetitionPenalty"] ?: 1.0f,
                topK = map["topK"]?.toInt() ?: 40,
                minP = map["minP"] ?: 0.05f,
                seed = map["seed"]?.toInt() ?: -1,
                thinkingBudget = map["thinkingBudget"]?.toInt() ?: (contextSize / 4),
                imageMaxTokens = map["imageMaxTokens"]?.toInt() ?: 256,
                kvCacheType = map["kvCacheType"]?.toInt() ?: 1
            )
        }
    }
}
