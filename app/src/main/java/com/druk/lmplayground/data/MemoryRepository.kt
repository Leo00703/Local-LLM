package com.druk.lmplayground.data

import androidx.lifecycle.LiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin async wrapper over [MemoryDao] for the Memory management screen. The DAO
 * methods are blocking, so every call hops to [Dispatchers.IO]; the reactive
 * list is a Room LiveData that Room already delivers off the main thread.
 */
class MemoryRepository(private val dao: MemoryDao) {

    /** Reactive list for the management screen (most recently touched first). */
    fun getAllLive(): LiveData<List<MemoryNoteEntity>> = dao.getAllLive()

    suspend fun add(content: String, category: String? = null): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dao.insert(
                MemoryNoteEntity(
                    content = content,
                    createdAt = now,
                    updatedAt = now,
                    category = category,
                )
            )
        }

    suspend fun getById(id: Long): MemoryNoteEntity? =
        withContext(Dispatchers.IO) { dao.getById(id) }

    suspend fun update(note: MemoryNoteEntity) =
        withContext(Dispatchers.IO) { dao.update(note) }

    suspend fun delete(id: Long): Int =
        withContext(Dispatchers.IO) { dao.deleteById(id) }

    suspend fun deleteAll(): Int =
        withContext(Dispatchers.IO) { dao.deleteAll() }
}
