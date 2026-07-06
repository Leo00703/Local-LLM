package com.druk.lmplayground.benchmark

import com.druk.lmplayground.data.BenchmarkResultEntity

/** State of a benchmark run, surfaced by the conversation VM to the run UI. */
sealed class BenchmarkUiState {
    object Idle : BenchmarkUiState()
    /** [current] of [total] runs completed (1-based). */
    data class Running(val current: Int, val total: Int) : BenchmarkUiState()
    /** The just-finished, saved result (its history row appears via LiveData). */
    data class Done(val result: BenchmarkResultEntity) : BenchmarkUiState()
    data class Error(val message: String) : BenchmarkUiState()
}
