package com.druk.lmplayground.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * DAO for saved benchmark results. The reactive lists are Room [LiveData]
 * (delivered off the main thread); the blocking insert is called off-main by
 * [BenchmarkRepository].
 */
@Dao
interface BenchmarkDao {
    @Insert
    fun insert(result: BenchmarkResultEntity): Long

    /** All results, newest first (for the comparison view). */
    @Query("SELECT * FROM benchmark_results ORDER BY createdAt DESC")
    fun getAllLive(): LiveData<List<BenchmarkResultEntity>>

    /** Results for a single model, newest first. */
    @Query("SELECT * FROM benchmark_results WHERE modelFilename = :filename ORDER BY createdAt DESC")
    fun getForModelLive(filename: String): LiveData<List<BenchmarkResultEntity>>

    @Query("DELETE FROM benchmark_results WHERE id = :id")
    fun deleteById(id: Long): Int

    @Query("DELETE FROM benchmark_results")
    fun deleteAll(): Int
}
