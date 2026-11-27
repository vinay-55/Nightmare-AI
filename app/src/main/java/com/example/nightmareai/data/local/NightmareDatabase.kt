package com.example.nightmareai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.nightmareai.data.local.dao.ChatMessageDao
import com.example.nightmareai.data.local.dao.ChatSourceDao
import com.example.nightmareai.data.local.dao.ChatThreadDao
import com.example.nightmareai.data.local.entity.ChatMessageEntity
import com.example.nightmareai.data.local.entity.ChatSourceEntity
import com.example.nightmareai.data.local.entity.ChatThreadEntity

/**
 * Local Room database for Nightmare AI with Write-Ahead Logging (WAL) enabled
 * for high-concurrency background source writes and UI reads.
 */
@Database(
    entities = [
        ChatThreadEntity::class,
        ChatMessageEntity::class,
        ChatSourceEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(NightmareTypeConverters::class)
abstract class NightmareDatabase : RoomDatabase() {

    abstract fun chatThreadDao(): ChatThreadDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatSourceDao(): ChatSourceDao

    companion object {
        private const val DATABASE_NAME = "nightmare_ai.db"

        @Volatile
        private var instance: NightmareDatabase? = null

        fun getInstance(context: Context): NightmareDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NightmareDatabase::class.java,
                    DATABASE_NAME
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
