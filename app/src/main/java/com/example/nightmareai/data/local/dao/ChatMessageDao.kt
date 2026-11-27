package com.example.nightmareai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.nightmareai.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [ChatMessageEntity].
 * Strictly enforces [googleId] isolation on every query and [threadId] isolation on thread-scoped chats.
 */
@Dao
interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE googleId = :googleId AND threadId = :threadId AND isDeleted = 0
        ORDER BY timestamp ASC, id ASC
        """
    )
    fun observeMessagesForThread(googleId: String, threadId: Long): Flow<List<ChatMessageEntity>>

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE googleId = :googleId AND threadId = :threadId AND isDeleted = 0
        ORDER BY timestamp ASC, id ASC
        """
    )
    suspend fun getMessagesForThreadSync(googleId: String, threadId: Long): List<ChatMessageEntity>

    /**
     * Global cross-thread read strictly for the single GLOBAL_ABOUT_YOU workspace of [googleId].
     */
    @Query(
        """
        SELECT m.* FROM chat_messages m
        INNER JOIN chat_threads t ON m.threadId = t.id
        WHERE m.googleId = :googleId
          AND t.googleId = :googleId
          AND t.chatType IN ('MASTER', 'NORMAL')
          AND m.isDeleted = 0
          AND t.isDeleted = 0
        ORDER BY m.timestamp ASC, m.id ASC
        """
    )
    suspend fun getAllMessagesForGlobalAboutYouSync(googleId: String): List<ChatMessageEntity>

    @Query(
        """
        UPDATE chat_messages
        SET isDeleted = 1
        WHERE threadId = :threadId AND googleId = :googleId
        """
    )
    suspend fun softDeleteMessagesForThread(googleId: String, threadId: Long)
}
