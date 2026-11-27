package com.example.nightmareai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.nightmareai.data.local.entity.ChatThreadEntity
import com.example.nightmareai.data.local.entity.ChatType
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [ChatThreadEntity]. Every query strictly filters by [googleId] and [isDeleted] = 0
 * to guarantee zero-merge isolation between accounts.
 */
@Dao
interface ChatThreadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThread(thread: ChatThreadEntity): Long

    @Update
    suspend fun updateThread(thread: ChatThreadEntity)

    @Query(
        """
        SELECT * FROM chat_threads
        WHERE id = :threadId AND googleId = :googleId AND isDeleted = 0
        LIMIT 1
        """
    )
    suspend fun getThreadById(googleId: String, threadId: Long): ChatThreadEntity?

    @Query(
        """
        SELECT * FROM chat_threads
        WHERE googleId = :googleId AND chatType = :chatType AND isDeleted = 0
        ORDER BY updatedAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getSingleThreadByType(googleId: String, chatType: ChatType): ChatThreadEntity?

    @Query(
        """
        SELECT * FROM chat_threads
        WHERE googleId = :googleId AND chatType = 'MASTER' AND isPrimaryLargeCard = 1 AND isDeleted = 0
        ORDER BY createdAt ASC, id ASC
        LIMIT 1
        """
    )
    fun observePrimaryMasterThread(googleId: String): Flow<ChatThreadEntity?>

    @Query(
        """
        SELECT * FROM chat_threads
        WHERE googleId = :googleId AND chatType = 'MASTER' AND isPrimaryLargeCard = 1 AND isDeleted = 0
        ORDER BY createdAt ASC, id ASC
        LIMIT 1
        """
    )
    suspend fun getPrimaryMasterThreadSync(googleId: String): ChatThreadEntity?

    @Query(
        """
        SELECT * FROM chat_threads
        WHERE googleId = :googleId AND chatType = 'MASTER' AND isPrimaryLargeCard = 0 AND isDeleted = 0
        ORDER BY updatedAt DESC, id DESC
        """
    )
    fun observeSecondaryMasterThreads(googleId: String): Flow<List<ChatThreadEntity>>

    @Query(
        """
        SELECT * FROM chat_threads
        WHERE googleId = :googleId AND chatType = 'NORMAL' AND isDeleted = 0
        ORDER BY updatedAt DESC, id DESC
        """
    )
    fun observeNormalThreads(googleId: String): Flow<List<ChatThreadEntity>>

    /**
     * Global cross-thread read strictly for the single GLOBAL_ABOUT_YOU workspace of [googleId].
     */
    @Query(
        """
        SELECT * FROM chat_threads
        WHERE googleId = :googleId AND chatType IN ('MASTER', 'NORMAL') AND isDeleted = 0
        ORDER BY updatedAt DESC, id DESC
        """
    )
    suspend fun getAllAuditThreadsForGlobalAboutYouSync(googleId: String): List<ChatThreadEntity>

    @Query(
        """
        UPDATE chat_threads
        SET updatedAt = :updatedAt
        WHERE id = :threadId AND googleId = :googleId AND isDeleted = 0
        """
    )
    suspend fun touchThreadUpdatedAt(googleId: String, threadId: Long, updatedAt: Long)

    @Query(
        """
        UPDATE chat_threads
        SET title = :title, updatedAt = :updatedAt
        WHERE id = :threadId AND googleId = :googleId AND isDeleted = 0
        """
    )
    suspend fun renameThread(googleId: String, threadId: Long, title: String, updatedAt: Long)

    @Query(
        """
        UPDATE chat_threads
        SET isDeleted = 1, updatedAt = :updatedAt
        WHERE id = :threadId AND googleId = :googleId
        """
    )
    suspend fun softDeleteThread(googleId: String, threadId: Long, updatedAt: Long)
}
