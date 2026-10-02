package com.example.bocciatv.data.repository

import android.content.Context
import android.util.Log
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.local.db.AppDatabase
import com.example.bocciatv.data.local.db.CategoryEntity
import com.example.bocciatv.data.local.db.StreamEntity
import com.example.bocciatv.data.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class AppRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.contentDao()
    private val prefs = PrefsManager(context)

    companion object {
        private val syncMutex = Mutex()
    }

    suspend fun syncContentIfNeeded(force: Boolean = false) = withContext(Dispatchers.IO) {
        val lastSync = prefs.lastSyncTime
        val now = System.currentTimeMillis()
        val twelveHoursMs = 12 * 3600 * 1000L

        if (force || (now - lastSync) > twelveHoursMs || lastSync == 0L) {
            if (syncMutex.isLocked) {
                Log.d("AppRepository", "Sincronizzazione già in corso, salto...")
                return@withContext
            }

            syncMutex.withLock {
                try {
                    Log.d("AppRepository", "Avvio sincronizzazione completa nel DB Room...")
                    syncType("get_live_categories", "get_live_streams")
                    syncType("get_vod_categories", "get_vod_streams")
                    syncType("get_series_categories", "get_series")
                    
                    // Update lastSyncTime ONLY if all 3 completed successfully
                    prefs.lastSyncTime = System.currentTimeMillis()
                    Log.d("AppRepository", "Sincronizzazione Room completata con successo!")
                } catch (e: Exception) {
                    Log.e("AppRepository", "Errore durante sincronizzazione Room: ${e.message}")
                }
            }
        }
    }

    private suspend fun syncType(catAction: String, streamAction: String) = withContext(Dispatchers.IO) {
        val user = prefs.user
        val pass = prefs.pass
        if (user.isEmpty() || pass.isEmpty()) {
            throw IllegalStateException("Credenziali utente non trovate")
        }

        val catResponse = NetworkModule.api.getCategories(user, pass, catAction).execute()
        if (!catResponse.isSuccessful || catResponse.body() == null) {
            throw IOException("Errore scaricamento categorie per $catAction: ${catResponse.code()}")
        }
        val categories = catResponse.body()!!
        val catEntities = categories.map {
            CategoryEntity(
                dbId = "${catAction}_${it.id}",
                id = it.id ?: "",
                name = it.name,
                type = catAction
            )
        }

        val streamResponse = NetworkModule.api.getStreams(user, pass, streamAction, null).execute()
        if (!streamResponse.isSuccessful || streamResponse.body() == null) {
            throw IOException("Errore scaricamento flussi per $streamAction: ${streamResponse.code()}")
        }
        val streams = streamResponse.body()!!
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
        if (ids.isEmpty()) return@withContext emptyList<StreamEntity>()
        val result = mutableListOf<StreamEntity>()
        for (chunk in ids.chunked(900)) {
            result.addAll(dao.getStreamsByIds(type, chunk))
        }
        result
    }

    suspend fun getAllStreamsForType(type: String) = withContext(Dispatchers.IO) {
        dao.getAllStreamsForType(type)
    }
}
