package com.example.nightmareai.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents an attached local artifact (.zip, .apk, or code folder) and its extracted offline facts.
 * Partitioned strictly by [googleId] and scoped to [threadId].
 */
@Entity(
    tableName = "chat_sources",
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
        Index(value = ["googleId", "threadId", "isDeleted", "createdAt"]),
        Index(value = ["googleId", "isDeleted", "createdAt"])
    ]
)
data class ChatSourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val uuid: String = UUID.randomUUID().toString(),
    val threadId: Long,
    val googleId: String,
    val sourceType: String,
    val displayName: String,
    val localUriString: String,
    val extractedFactsJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
) {
    companion object {
        const val TYPE_ZIP = "ZIP"
        const val TYPE_APK = "APK"
        const val TYPE_FOLDER = "FOLDER"
    }
}
