package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One saved benchmark run-batch for a model. Aggregates (avg) are denormalized
 * into columns for cheap list rendering + comparison; the full per-metric
 * min/max/avg/median series is kept in [seriesJson] for the detail/chart view.
 *
 * Keyed loosely by [modelFilename] so a model's history can be queried; results
 * are only comparable at equal [accelerator] (GPU forces F16 KV, so the numbers
 * differ from CPU).
 */
@Entity(tableName = "benchmark_results")
data class BenchmarkResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelFilename: String,
    val modelName: String,
    /** The native "Compute:" line, e.g. "CPU" or "GPU (OpenCL): Adreno 830". */
    val accelerator: String,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val runs: Int,
    val ttftMsAvg: Float,
    val prefillTokPerSecAvg: Float,
    val decodeTokPerSecAvg: Float,
    /** Cold load time (first run), milliseconds. */
    val loadTimeMs: Int,
    /** Best-effort peak app memory during the batch, MB. Null if unavailable. */
    val peakMemoryMb: Float?,
    val contextUsed: Int,
    /** KV cache type used: 0 = F16, 1 = Q8_0, 2 = Q4_0. */
    val kvCacheType: Int,
    val appVersion: String,
    val createdAt: Long,
    /** JSON: per-metric {min,max,avg,median} across the runs, for charts. */
    val seriesJson: String,
)
