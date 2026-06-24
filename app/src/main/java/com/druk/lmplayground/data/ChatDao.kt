package com.druk.lmplayground.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_sessions ORDER BY pinned DESC, updatedAt DESC")
    fun getAllSessions(): LiveData<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessages(sessionId: String): List<ChatMessageEntity>

    @Insert
    suspend fun insertSession(session: ChatSessionEntity)

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateSessionTimestamp(sessionId: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: String, title: String)

    @Query("UPDATE chat_sessions SET modelFilename = :filename, modelName = :name WHERE id = :sessionId")
    suspend fun updateSessionModel(sessionId: String, filename: String, name: String)

    @Query("UPDATE chat_sessions SET pinned = :pinned WHERE id = :sessionId")
    suspend fun updateSessionPinned(sessionId: String, pinned: Boolean)

    @Query("""UPDATE chat_sessions SET
        contextSize = :contextSize, temperature = :temperature, topP = :topP,
        repetitionPenalty = :repetitionPenalty, topK = :topK, minP = :minP, seed = :seed,
        thinkingBudget = :thinkingBudget
        WHERE id = :sessionId""")
    suspend fun updateSessionParams(
        sessionId: String,
        contextSize: Int, temperature: Float, topP: Float,
        repetitionPenalty: Float, topK: Int, minP: Float, seed: Int,
        thinkingBudget: Int
    )

    @Query("UPDATE chat_sessions SET systemPrompt = :systemPrompt WHERE id = :sessionId")
    suspend fun updateSessionSystemPrompt(sessionId: String, systemPrompt: String)

    @Query("UPDATE chat_sessions SET metadata = :metadata WHERE id = :sessionId")
    suspend fun updateSessionMetadata(sessionId: String, metadata: String)

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): ChatSessionEntity?

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    /**
     * Replace all persisted messages for a session with [messages] in one
     * transaction. Used by edit/regenerate, which truncate the conversation
     * to a point and then re-send: the caller passes the surviving prefix,
     * and the resent user turn + new assistant reply are persisted afterwards
     * by the normal generation path.
     */
    @Transaction
    suspend fun replaceSessionMessages(sessionId: String, messages: List<ChatMessageEntity>) {
        deleteMessagesForSession(sessionId)
        messages.forEach { insertMessage(it) }
    }

    // --- Folders ---

    @Query("SELECT * FROM folders ORDER BY position ASC, createdAt ASC")
    fun getAllFolders(): LiveData<List<FolderEntity>>

    @Insert
    suspend fun insertFolder(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name WHERE id = :folderId")
    suspend fun updateFolderName(folderId: String, name: String)

    @Query("UPDATE chat_sessions SET folderId = :folderId WHERE id = :sessionId")
    suspend fun updateSessionFolder(sessionId: String, folderId: String?)

    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: String)

    @Query("DELETE FROM chat_sessions WHERE folderId = :folderId")
    suspend fun deleteSessionsInFolder(folderId: String)

    /**
     * Delete a folder together with every chat it contains, in one
     * transaction. The chats' messages are removed via the chat_messages
     * foreign-key cascade.
     */
    @Transaction
    suspend fun deleteFolderAndChats(folderId: String) {
        deleteSessionsInFolder(folderId)
        deleteFolder(folderId)
    }
}
