package com.example.nightmareai.data.repository

import com.example.nightmareai.data.local.NightmareDatabase
import com.example.nightmareai.data.local.entity.ChatMessageEntity
import com.example.nightmareai.data.local.entity.ChatSourceEntity
import com.example.nightmareai.data.local.entity.ChatThreadEntity
import com.example.nightmareai.data.local.entity.ChatType
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

data class WorkspaceBootstrap(
    val aboutYouThread: ChatThreadEntity,
    val primaryMasterThread: ChatThreadEntity
)

data class GlobalAuditSnapshot(
    val threads: List<ChatThreadEntity>,
    val messagesByThread: Map<Long, List<ChatMessageEntity>>,
    val sourcesByThread: Map<Long, List<ChatSourceEntity>>
)

/**
 * Single source of truth coordinating Room persistence with strict [googleId]
 * and [threadId] data isolation boundaries.
 */
class NightmareRepository(private val database: NightmareDatabase) {

    private val threadDao = database.chatThreadDao()
    private val messageDao = database.chatMessageDao()
    private val sourceDao = database.chatSourceDao()

    /**
     * Ensures the active [googleId] workspace has its single GLOBAL_ABOUT_YOU thread
     * and its pinned Primary Master Chat large card. Never touches any other account's rows.
     */
    suspend fun ensureWorkspaceInitialized(googleId: String): WorkspaceBootstrap {
        val now = System.currentTimeMillis()
        val aboutYou = threadDao.getSingleThreadByType(googleId, ChatType.GLOBAL_ABOUT_YOU)
            ?: run {
                val created = ChatThreadEntity(
                    googleId = googleId,
                    chatType = ChatType.GLOBAL_ABOUT_YOU,
                    title = "About You",
                    isPrimaryLargeCard = false,
                    metadataJson = JSONObject().put("scope", "GLOBAL_WORKSPACE").toString(),
                    createdAt = now,
                    updatedAt = now
                )
                val id = threadDao.insertThread(created)
                created.copy(id = id)
            }

        val primaryMaster = threadDao.getPrimaryMasterThreadSync(googleId)
            ?: run {
                val created = ChatThreadEntity(
                    googleId = googleId,
                    chatType = ChatType.MASTER,
                    title = "Primary Master Audit",
                    isPrimaryLargeCard = true,
                    metadataJson = JSONObject().put("slot", "primary_master").toString(),
                    createdAt = now,
                    updatedAt = now
                )
                val id = threadDao.insertThread(created)
                created.copy(id = id)
            }

        return WorkspaceBootstrap(
            aboutYouThread = aboutYou,
            primaryMasterThread = primaryMaster
        )
    }

    fun observePrimaryMasterThread(googleId: String): Flow<ChatThreadEntity?> =
        threadDao.observePrimaryMasterThread(googleId)

    fun observeSecondaryMasterThreads(googleId: String): Flow<List<ChatThreadEntity>> =
        threadDao.observeSecondaryMasterThreads(googleId)

    fun observeNormalThreads(googleId: String): Flow<List<ChatThreadEntity>> =
        threadDao.observeNormalThreads(googleId)

    fun observeMessagesForThread(googleId: String, threadId: Long): Flow<List<ChatMessageEntity>> =
        messageDao.observeMessagesForThread(googleId, threadId)

    fun observeSourcesForThread(googleId: String, threadId: Long): Flow<List<ChatSourceEntity>> =
        sourceDao.observeSourcesForThread(googleId, threadId)

    fun observeAllSourcesForAccount(googleId: String): Flow<List<ChatSourceEntity>> =
        sourceDao.observeAllSourcesForAccount(googleId)

    suspend fun getThreadById(googleId: String, threadId: Long): ChatThreadEntity? =
        threadDao.getThreadById(googleId, threadId)

    suspend fun getMessagesForThreadSync(googleId: String, threadId: Long): List<ChatMessageEntity> =
        messageDao.getMessagesForThreadSync(googleId, threadId)

    suspend fun getSourcesForThreadSync(googleId: String, threadId: Long): List<ChatSourceEntity> =
        sourceDao.getSourcesForThreadSync(googleId, threadId)

    suspend fun createNormalChat(googleId: String, title: String = "New Audit Chat"): ChatThreadEntity {
        val now = System.currentTimeMillis()
        val entity = ChatThreadEntity(
            googleId = googleId,
            chatType = ChatType.NORMAL,
            title = title,
            isPrimaryLargeCard = false,
            createdAt = now,
            updatedAt = now
        )
        val id = threadDao.insertThread(entity)
        return entity.copy(id = id)
    }

    suspend fun createSecondaryMasterChat(googleId: String, title: String? = null): ChatThreadEntity {
        val now = System.currentTimeMillis()
        val resolvedTitle = title ?: "Master Audit #${(now % 10000)}"
        val entity = ChatThreadEntity(
            googleId = googleId,
            chatType = ChatType.MASTER,
            title = resolvedTitle,
            isPrimaryLargeCard = false,
            createdAt = now,
            updatedAt = now
        )
        val id = threadDao.insertThread(entity)
        return entity.copy(id = id)
    }

    suspend fun renameThread(googleId: String, threadId: Long, newTitle: String) {
        threadDao.renameThread(googleId, threadId, newTitle.trim(), System.currentTimeMillis())
    }

    suspend fun appendMessage(
        googleId: String,
        threadId: Long,
        sender: String,
        content: String,
        twoSectionReportJson: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): ChatMessageEntity {
        val thread = threadDao.getThreadById(googleId, threadId)
            ?: throw SecurityException("Thread $threadId does not belong to googleId=$googleId")
        val msg = ChatMessageEntity(
            threadId = thread.id,
            googleId = googleId,
            sender = sender,
            content = content,
            twoSectionReportJson = twoSectionReportJson,
            timestamp = timestamp
        )
        val id = messageDao.insertMessage(msg)
        threadDao.touchThreadUpdatedAt(googleId, thread.id, timestamp)
        return msg.copy(id = id)
    }

    suspend fun attachSourceToThread(
        googleId: String,
        threadId: Long,
        sourceType: String,
        displayName: String,
        localUriString: String,
        extractedFactsJson: String
    ): ChatSourceEntity {
        val thread = threadDao.getThreadById(googleId, threadId)
            ?: throw SecurityException("Thread $threadId does not belong to googleId=$googleId")
        val now = System.currentTimeMillis()
        val source = ChatSourceEntity(
            threadId = thread.id,
            googleId = googleId,
            sourceType = sourceType,
            displayName = displayName,
            localUriString = localUriString,
            extractedFactsJson = extractedFactsJson,
            createdAt = now
        )
        val id = sourceDao.insertSource(source)
        threadDao.touchThreadUpdatedAt(googleId, thread.id, now)
        return source.copy(id = id)
    }

    /**
     * Strictly restricted to GLOBAL_ABOUT_YOU threads.
     * Throws [SecurityException] if called for a MASTER or NORMAL thread so cross-chat
     * leakage is impossible by construction.
     */
    suspend fun getGlobalAuditSnapshotForAboutYou(
        googleId: String,
        requestingThreadId: Long
    ): GlobalAuditSnapshot {
        val thread = threadDao.getThreadById(googleId, requestingThreadId)
            ?: throw SecurityException("Thread $requestingThreadId not found for googleId=$googleId")
        if (thread.chatType != ChatType.GLOBAL_ABOUT_YOU) {
            throw SecurityException(
                "Cross-chat query denied for chatType=${thread.chatType}. Only GLOBAL_ABOUT_YOU may query across threads."
            )
        }
        val threads = threadDao.getAllAuditThreadsForGlobalAboutYouSync(googleId)
        val messages = messageDao.getAllMessagesForGlobalAboutYouSync(googleId)
        val sources = sourceDao.getAllSourcesForGlobalAboutYouSync(googleId)
        return GlobalAuditSnapshot(
            threads = threads,
            messagesByThread = messages.groupBy { it.threadId },
            sourcesByThread = sources.groupBy { it.threadId }
        )
    }
}
