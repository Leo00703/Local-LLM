package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A note the model saved via the "memory" tool, persisted across chats.
 * Local-only; the user can inspect/clear them (future) and the tool caps count.
 */
@Entity(tableName = "memory_notes")
data class MemoryNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long,
)
