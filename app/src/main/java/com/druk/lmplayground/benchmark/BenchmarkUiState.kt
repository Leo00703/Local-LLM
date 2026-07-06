package com.druk.lmplayground.benchmark

import com.druk.lmplayground.data.BenchmarkResultEntity

/** Which accelerator(s) a benchmark suite should measure. */
enum class BenchmarkHardware {
    CPU, GPU, BOTH;

    /** The GPU flags to run, in order (false = CPU, true = GPU). */
    fun gpuFlags(): List<Boolean> = when (this) {
        CPU -> listOf(false)
        GPU -> listOf(true)
        BOTH -> listOf(false, true)
    }
}

/** State of a benchmark suite, surfaced by the conversation VM to the screen. */
sealed class BenchmarkUiState {
    object Idle : BenchmarkUiState()

    /**
     * A run is in progress. [status] is a human label (e.g. "Bonsai-1.7B · GPU ·
     * 2/3"), [fraction] is overall progress across all hardware+runs in [0,1].
     */
    data class Running(val status: String, val fraction: Float) : BenchmarkUiState()

    /** The finished suite: one saved result per hardware benchmarked. */
    data class Done(val results: List<BenchmarkResultEntity>) : BenchmarkUiState()

    data class Error(val message: String) : BenchmarkUiState()
}
