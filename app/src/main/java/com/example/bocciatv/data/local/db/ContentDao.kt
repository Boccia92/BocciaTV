package com.example.bocciatv.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ContentDao {

    @Query("SELECT * FROM categories WHERE type = :type")
    suspend fun getCategories(type: String): List<CategoryEntity>

    @Query("SELECT * FROM streams WHERE type = :type AND categoryId = :categoryId")
    suspend fun getStreamsByCategory(type: String, categoryId: String): List<StreamEntity>

    @Query("SELECT * FROM streams WHERE type = :type AND name LIKE '%' || :query || '%' LIMIT 500")
    suspend fun searchStreams(type: String, query: String): List<StreamEntity>

    @Query("SELECT * FROM streams WHERE type = :type AND (streamId IN (:ids) OR seriesId IN (:ids))")
    suspend fun getStreamsByIds(type: String, ids: List<String>): List<StreamEntity>

    @Query("SELECT * FROM streams WHERE type = :type")
    suspend fun getAllStreamsForType(type: String): List<StreamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStreams(streams: List<StreamEntity>)

    @Query("DELETE FROM categories WHERE type = :type")
    suspend fun clearCategories(type: String)

    @Query("DELETE FROM streams WHERE type = :type")
    suspend fun clearStreams(type: String)

    @Transaction
    suspend fun replaceContent(type: String, categories: List<CategoryEntity>, streams: List<StreamEntity>) {
        clearCategories(type)
        clearStreams(type)
        insertCategories(categories)
        insertStreams(streams)
    }
}
