package com.druk.lmplayground.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Synchronous DAO for the memory-tool notes. The tool runs off the main thread
 * (the generation/tool loop), so blocking queries are safe here.
 */
@Dao
interface MemoryDao {
    @Insert
    fun insert(note: MemoryNoteEntity): Long

    @Query("SELECT * FROM memory_notes ORDER BY createdAt DESC")
    fun getAll(): List<MemoryNoteEntity>

    @Query("DELETE FROM memory_notes WHERE id = :id")
    fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM memory_notes")
    fun count(): Int
}
