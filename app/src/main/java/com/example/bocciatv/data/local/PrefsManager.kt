package com.example.bocciatv.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("bocciatv_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    var user: String
        get() = prefs.getString("user", "") ?: ""
        set(v) = prefs.edit().putString("user", v).apply()

    var pass: String
        get() = prefs.getString("pass", "") ?: ""
        set(v) = prefs.edit().putString("pass", v).apply()

    var expDate: String
        get() = prefs.getString("exp_date", "") ?: ""
        set(v) = prefs.edit().putString("exp_date", v).apply()

    var lastDismissedVersion: Int
        get() = prefs.getInt("last_dismissed_version", 0)
        set(v) = prefs.edit().putInt("last_dismissed_version", v).apply()

    fun savePosition(id: String, position: Long) {
        if (id == "unknown") return
        prefs.edit().putLong("pos_$id", position).apply()
        addToRecent(id)
    }

    fun getPosition(id: String): Long {
        return prefs.getLong("pos_$id", 0L)
    }

    fun setWatched(id: String, watched: Boolean = true) {
        if (id == "unknown") return
        prefs.edit().putBoolean("watched_$id", watched).apply()
        if (watched) removeFromRecent(id)
    }

    fun isWatched(id: String): Boolean {
        return prefs.getBoolean("watched_$id", false)
    }

    private fun addToRecent(id: String) {
        val recent = getRecentList().toMutableList()
        if (recent.contains(id)) recent.remove(id)
        recent.add(0, id)
        if (recent.size > 50) recent.removeAt(recent.size - 1)
        saveRecentList(recent)
    }

    fun removeFromRecent(id: String) {
        val recent = getRecentList().toMutableList()
        recent.remove(id)
        saveRecentList(recent)
        // Also clear saved position when removed from recents
        prefs.edit().remove("pos_$id").apply()
        prefs.edit().remove("watched_$id").apply()
    }

    fun getRecentList(): List<String> {
        val json = prefs.getString("recent_list", "[]")
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun saveRecentList(list: List<String>) {
        prefs.edit().putString("recent_list", gson.toJson(list)).apply()
    }

    fun getFavorites(type: String): Set<String> {
        return prefs.getStringSet("fav_$type", emptySet()) ?: emptySet()
    }

    fun toggleFavorite(type: String, id: String) {
        val favs = getFavorites(type).toMutableSet()
        if (favs.contains(id)) favs.remove(id) else favs.add(id)
        prefs.edit().putStringSet("fav_$type", favs).apply()
    }

    fun isFavorite(type: String, id: String): Boolean {
        return getFavorites(type).contains(id)
    }

    fun clear() = prefs.edit().clear().apply()
}
