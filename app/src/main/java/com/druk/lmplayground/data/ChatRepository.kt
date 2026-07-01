package com.druk.lmplayground.data

import androidx.lifecycle.LiveData

class ChatRepository(private val dao: ChatDao) {

    fun getAllSessions(): LiveData<List<ChatSessionEntity>> = dao.getAllSessions()

    suspend fun getMessages(sessionId: String): List<ChatMessageEntity> =
        dao.getMessages(sessionId)

    suspend fun getAllAttachmentsJson(): List<String> =
        dao.getAllAttachmentsJson()

    suspend fun insertSession(session: ChatSessionEntity) =
        dao.insertSession(session)

    suspend fun insertMessage(message: ChatMessageEntity) =
        dao.insertMessage(message)

    suspend fun updateSessionTimestamp(sessionId: String, updatedAt: Long) =
        dao.updateSessionTimestamp(sessionId, updatedAt)

    suspend fun updateSessionTitle(sessionId: String, title: String) =
        dao.updateSessionTitle(sessionId, title)

    suspend fun updateSessionModel(sessionId: String, filename: String, name: String) =
        dao.updateSessionModel(sessionId, filename, name)

    suspend fun updateSessionPinned(sessionId: String, pinned: Boolean) =
        dao.updateSessionPinned(sessionId, pinned)

    suspend fun updateSessionParams(
        sessionId: String,
        contextSize: Int, temperature: Float, topP: Float,
        repetitionPenalty: Float, topK: Int, minP: Float, seed: Int,
        thinkingBudget: Int
    ) = dao.updateSessionParams(sessionId, contextSize, temperature, topP, repetitionPenalty, topK, minP, seed, thinkingBudget)

    suspend fun updateSessionSystemPrompt(sessionId: String, systemPrompt: String) =
        dao.updateSessionSystemPrompt(sessionId, systemPrompt)

    suspend fun updateSessionMetadata(sessionId: String, metadata: String) =
        dao.updateSessionMetadata(sessionId, metadata)

    suspend fun getSession(sessionId: String): ChatSessionEntity? =
        dao.getSession(sessionId)

    suspend fun deleteSession(sessionId: String) =
        dao.deleteSession(sessionId)

    suspend fun replaceSessionMessages(sessionId: String, messages: List<ChatMessageEntity>) =
        dao.replaceSessionMessages(sessionId, messages)

    // --- Folders ---

    fun getAllFolders(): LiveData<List<FolderEntity>> = dao.getAllFolders()

    suspend fun insertFolder(folder: FolderEntity) =
        dao.insertFolder(folder)

    suspend fun updateFolderName(folderId: String, name: String) =
        dao.updateFolderName(folderId, name)

    suspend fun updateSessionFolder(sessionId: String, folderId: String?) =
        dao.updateSessionFolder(sessionId, folderId)

    suspend fun deleteFolderAndChats(folderId: String) =
        dao.deleteFolderAndChats(folderId)
}
