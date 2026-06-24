package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A project folder that groups chat sessions. A [ChatSessionEntity] references
 * its folder via the nullable `folderId` column (null = unfiled). Deleting a
 * folder also deletes the chats it contains (see ChatDao.deleteFolderAndChats).
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    /** Manual ordering in the drawer; lower comes first, ties broken by createdAt. */
    val position: Int = 0
)
