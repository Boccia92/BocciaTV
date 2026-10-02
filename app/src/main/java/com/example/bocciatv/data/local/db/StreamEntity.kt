package com.example.bocciatv.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.bocciatv.data.model.StreamItem

@Entity(
    tableName = "streams",
    indices = [Index(value = ["type", "categoryId"])]
)
data class StreamEntity(
    @PrimaryKey val id: String,
    val streamId: String?,
    val seriesId: String?,
    val categoryId: String?,
    val name: String?,
    val icon: String?,
    val cover: String?,
    val extension: String?,
    val type: String
) {
    fun toStreamItem(): StreamItem {
        return StreamItem(
            streamId = streamId,
            seriesId = seriesId,
            categoryId = categoryId,
            name = name,
            icon = icon,
            cover = cover,
            extension = extension
        )
    }
}
