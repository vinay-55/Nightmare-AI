package com.example.nightmareai.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a single message or structured two-section truth verification report inside a thread.
 * Every query MUST filter by [googleId] (and [threadId] for non-global chats).
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["threadId"]),
        Index(value = ["googleId", "threadId", "isDeleted", "timestamp"]),
        Index(value = ["googleId", "isDeleted", "timestamp"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val uuid: String = UUID.randomUUID().toString(),
    val threadId: Long,
    val googleId: String,
    val sender: String,
    val content: String,
    val twoSectionReportJson: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
) {
    companion object {
        const val SENDER_USER = "USER"
        const val SENDER_ASSISTANT = "ASSISTANT"
        const val SENDER_SYSTEM = "SYSTEM"
    }
}
