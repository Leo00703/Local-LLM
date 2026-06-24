package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val author: String,
    val content: String,
    val thinkingDurationSeconds: Int = 0,
    val thinkingTokens: Int = 0,
    val responseTokens: Int = 0,
    val responseDurationSeconds: Float = 0f,
    /** Decode window (first token → end) in seconds; the honest tok/s denominator. */
    val responseDecodeSeconds: Float = 0f,
    val timestamp: Long
)
