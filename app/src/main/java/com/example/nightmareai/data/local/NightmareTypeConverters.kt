package com.example.nightmareai.data.local

import androidx.room.TypeConverter
import com.example.nightmareai.data.local.entity.ChatType

/**
 * Room TypeConverters for strongly-typed enum persistence.
 */
class NightmareTypeConverters {

    @TypeConverter
    fun fromChatType(value: ChatType): String = value.name

    @TypeConverter
    fun toChatType(value: String): ChatType = ChatType.valueOf(value)
}
