package com.example.bocciatv.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.bocciatv.data.model.Category

@Entity(
    tableName = "categories",
    indices = [Index(value = ["type", "id"])]
)
data class CategoryEntity(
    @PrimaryKey val dbId: String,
    val id: String,
    val name: String?,
    val type: String
) {
    fun toCategory(): Category {
        return Category(
            id = id,
            name = name
        )
    }
}
