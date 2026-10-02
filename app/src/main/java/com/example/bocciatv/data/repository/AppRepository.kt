package com.example.bocciatv.data.repository

import android.content.Context
import android.util.Log
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.local.db.AppDatabase
import com.example.bocciatv.data.local.db.CategoryEntity
import com.example.bocciatv.data.local.db.StreamEntity
import com.example.bocciatv.data.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.contentDao()
    private val prefs = PrefsManager(context)

    suspend fun syncContentIfNeeded(force: Boolean = false) = withContext(Dispatchers.IO) {
        val lastSync = prefs.lastSyncTime
        val now = System.currentTimeMillis()
        val twelveHoursMs = 12 * 3600 * 1000L

        if (force || (now - lastSync) > twelveHoursMs || lastSync == 0L) {
            try {
                Log.d("AppRepository", "Avvio sincronizzazione completa nel DB Room...")
                syncType("get_live_categories", "get_live_streams")
                syncType("get_vod_categories", "get_vod_streams")
                syncType("get_series_categories", "get_series")
                prefs.lastSyncTime = now
                Log.d("AppRepository", "Sincronizzazione Room completata con successo!")
            } catch (e: Exception) {
                Log.e("AppRepository", "Errore durante sincronizzazione Room: ${e.message}")
            }
        }
    }

    private suspend fun syncType(catAction: String, streamAction: String) = withContext(Dispatchers.IO) {
        val user = prefs.user
        val pass = prefs.pass
        if (user.isEmpty() || pass.isEmpty()) return@withContext

        val catResponse = NetworkModule.api.getCategories(user, pass, catAction).execute()
        val categories = catResponse.body() ?: emptyList()
        val catEntities = categories.map {
            CategoryEntity(
                dbId = "${catAction}_${it.id}",
                id = it.id,
                name = it.name,
                type = catAction
            )
        }

        val streamResponse = NetworkModule.api.getStreams(user, pass, streamAction, null).execute()
        val streams = streamResponse.body() ?: emptyList()
        val streamEntities = streams.map {
            val streamIdStr = it.streamId?.toString() ?: it.seriesId?.toString() ?: it.name ?: ""
            StreamEntity(
                id = "${catAction}_$streamIdStr",
                streamId = it.streamId?.toString(),
                seriesId = it.seriesId?.toString(),
                categoryId = it.categoryId,
                name = it.name,
                icon = it.icon,
                cover = it.cover,
                extension = it.extension,
                type = catAction
            )
        }

        dao.replaceContent(catAction, catEntities, streamEntities)
    }

    suspend fun getCategories(type: String) = withContext(Dispatchers.IO) {
        dao.getCategories(type)
    }

    suspend fun getStreamsByCategory(type: String, categoryId: String) = withContext(Dispatchers.IO) {
        dao.getStreamsByCategory(type, categoryId)
    }

    suspend fun searchStreams(type: String, query: String) = withContext(Dispatchers.IO) {
        dao.searchStreams(type, query)
    }

    suspend fun getStreamsByIds(type: String, ids: List<String>) = withContext(Dispatchers.IO) {
        dao.getStreamsByIds(type, ids)
    }

    suspend fun getAllStreamsForType(type: String) = withContext(Dispatchers.IO) {
        dao.getAllStreamsForType(type)
    }
}
