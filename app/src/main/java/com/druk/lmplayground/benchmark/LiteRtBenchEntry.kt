package com.druk.lmplayground.benchmark

/**
 * One decode-throughput datapoint for the dev "Test LiteRT" chart: a single
 * (model, hardware, MTP on/off) config. [decodeTps] is real decode tokens/sec,
 * which for the fixed counting prompt equals chars/sec (each digit/newline is a
 * 1-char token). [parityOk] is true when this config's greedy output is
 * byte-identical to the model's base config (a correctness check for MTP).
 */
data class LiteRtBenchEntry(
    val model: String,      // "E2B" / "E4B"
    val config: String,     // "CPU base" / "CPU MTP" / "GPU base" / "GPU MTP"
    val decodeTps: Float,
    val parityOk: Boolean,
    val failed: Boolean = false,
)
