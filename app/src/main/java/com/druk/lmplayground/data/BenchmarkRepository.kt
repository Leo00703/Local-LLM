package com.druk.lmplayground.data

import androidx.lifecycle.LiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Async wrapper over [BenchmarkDao]. Reads are Room LiveData (already off-main);
 * writes hop to [Dispatchers.IO] since the DAO calls are blocking.
 */
class BenchmarkRepository(private val dao: BenchmarkDao) {

    fun getAllLive(): LiveData<List<BenchmarkResultEntity>> = dao.getAllLive()

    fun getForModelLive(filename: String): LiveData<List<BenchmarkResultEntity>> =
        dao.getForModelLive(filename)

    suspend fun insert(result: BenchmarkResultEntity): Long =
        withContext(Dispatchers.IO) { dao.insert(result) }

    suspend fun delete(id: Long): Int =
        withContext(Dispatchers.IO) { dao.deleteById(id) }

    suspend fun deleteAll(): Int =
        withContext(Dispatchers.IO) { dao.deleteAll() }
}
