package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A note the model saved via the "memory" tool, or one the user added by hand
 * in the Memory settings screen. Persisted across chats, local-only.
 *
 * [updatedAt] tracks the last edit so the management list can show the most
 * recently touched notes first (0 for legacy rows created before v1.9.59).
 * [category] is an optional light tag the model may attach (e.g. "preferences",
 * "projects"); null when untagged.
 */
@Entity(tableName = "memory_notes")
data class MemoryNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long = 0L,
    val category: String? = null,
)
