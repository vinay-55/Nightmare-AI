package com.example.nightmareai.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a conversation thread partitioned strictly by [googleId].
 * Includes [uuid] and [isDeleted] for zero-migration future cloud sync/backup readiness.
 */
@Entity(
    tableName = "chat_threads",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["googleId", "isDeleted", "chatType", "updatedAt"]),
        Index(value = ["googleId", "chatType", "isPrimaryLargeCard", "isDeleted"])
    ]
)
data class ChatThreadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val uuid: String = UUID.randomUUID().toString(),
    val googleId: String,
    val chatType: ChatType,
    val title: String,
    val isPrimaryLargeCard: Boolean = false,
    val metadataJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)
