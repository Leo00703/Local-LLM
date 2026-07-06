package com.druk.lmplayground.benchmark

/**
 * User-tunable benchmark parameters. Defaults mirror Google AI Edge Gallery
 * (256 prefill / 256 decode / 3 runs). [kvCacheType] follows the model's KV
 * setting: 0 = F16, 1 = Q8_0, 2 = Q4_0 (GPU forces F16 regardless).
 */
data class BenchmarkConfig(
    val prefillTokens: Int = 256,
    val decodeTokens: Int = 256,
    val runs: Int = 3,
    val kvCacheType: Int = 1,
    /** Experimental: build the self-MTP draft context (Qwen3.5-class models). */
    val speculative: Boolean = false,
) {
    /** Max draft tokens per MTP verify step when [speculative] is on. */
    val specNDraft: Int get() = if (speculative) 4 else 0

    companion object {
        const val MIN_TOKENS = 32
        const val MAX_TOKENS = 2048
        const val MIN_RUNS = 1
        const val MAX_RUNS = 10
    }
}
