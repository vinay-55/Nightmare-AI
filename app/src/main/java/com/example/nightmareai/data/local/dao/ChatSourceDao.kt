package com.example.nightmareai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.nightmareai.data.local.entity.ChatSourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [ChatSourceEntity].
 * Strictly enforces [googleId] isolation on every query and [threadId] isolation on thread-scoped chats.
 */
@Dao
interface ChatSourceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: ChatSourceEntity): Long

    @Query(
        """
        SELECT * FROM chat_sources
        WHERE googleId = :googleId AND threadId = :threadId AND isDeleted = 0
        ORDER BY createdAt DESC, id DESC
        """
    )
    fun observeSourcesForThread(googleId: String, threadId: Long): Flow<List<ChatSourceEntity>>

    @Query(
        """
        SELECT * FROM chat_sources
        WHERE googleId = :googleId AND threadId = :threadId AND isDeleted = 0
        ORDER BY createdAt DESC, id DESC
        """
    )
    suspend fun getSourcesForThreadSync(googleId: String, threadId: Long): List<ChatSourceEntity>

    @Query(
        """
        SELECT * FROM chat_sources
        WHERE googleId = :googleId AND isDeleted = 0
        ORDER BY createdAt DESC, id DESC
        """
    )
    fun observeAllSourcesForAccount(googleId: String): Flow<List<ChatSourceEntity>>

    /**
     * Global cross-thread read strictly for the single GLOBAL_ABOUT_YOU workspace of [googleId].
     */
    @Query(
        """
        SELECT s.* FROM chat_sources s
        INNER JOIN chat_threads t ON s.threadId = t.id
        WHERE s.googleId = :googleId
          AND t.googleId = :googleId
          AND t.chatType IN ('MASTER', 'NORMAL')
          AND s.isDeleted = 0
          AND t.isDeleted = 0
        ORDER BY s.createdAt DESC, s.id DESC
        """
    )
    suspend fun getAllSourcesForGlobalAboutYouSync(googleId: String): List<ChatSourceEntity>

    @Query(
        """
        UPDATE chat_sources
        SET isDeleted = 1
        WHERE id = :sourceId AND googleId = :googleId
        """
    )
    suspend fun softDeleteSource(googleId: String, sourceId: Long)
}
