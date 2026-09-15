package com.example.bocciatv.ui.content

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.bocciatv.R
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.model.Category
import com.example.bocciatv.data.model.StreamItem
import com.example.bocciatv.data.network.NetworkModule
import com.example.bocciatv.ui.adapter.GenericAdapter
import com.example.bocciatv.ui.player.PlayerActivity
import com.example.bocciatv.utils.DisplayUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

@UnstableApi
class ContentActivity : FragmentActivity() {
    companion object {
        const val EXTRA_TYPE = "type"
        const val TYPE_LIVE = "get_live_categories"
        const val TYPE_VOD = "get_vod_categories"
        const val TYPE_SERIES = "get_series_categories"
        private const val CAT_FAVORITES = "PREFERITI_ID"
        private const val CAT_RECENT = "RECENT_ID"
    }

    private lateinit var type: String
    private lateinit var prefs: PrefsManager
    private lateinit var catAdapter: GenericAdapter<Category>
    private lateinit var streamAdapter: GenericAdapter<StreamItem>
    
    private var masterList = emptyList<StreamItem>()
    private var currentCatId: String? = CAT_FAVORITES
    private var currentSearch: String = ""
    private var isAlphabeticalSort = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val locale = Locale.ITALY
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content)
        prefs = PrefsManager(this)
        type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_LIVE

        findViewById<TextView>(R.id.tv_type_title).text = when(type) {
            TYPE_LIVE -> "LIVE TV"
            TYPE_VOD -> "FILM"
            else -> "SERIE TV"
        }

        setupLists()
        setupSearch()
        
        val btnSort = findViewById<Button>(R.id.btn_sort)
        btnSort.setOnClickListener {
            isAlphabeticalSort = !isAlphabeticalSort
            btnSort.text = if (isAlphabeticalSort) "AZ (On)" else "A-Z"
            applyFilters()
        }

        loadCategories()
        loadAllContent()
    }

    override fun onResume() {
        super.onResume()
        applyFilters()
    }

    private fun setupSearch() {
        val etSearch = findViewById<EditText>(R.id.et_search)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearch = s.toString()
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun applyFilters(focusStreams: Boolean = false) {
        val search = currentSearch
        val catId = currentCatId
        val currentType = type

        Thread {
            var filtered = if (search.isNotEmpty()) {
                masterList.filter { it.name?.contains(search, ignoreCase = true) == true }
            } else if (catId == CAT_FAVORITES) {
                val favIds = prefs.getFavorites(currentType)
                masterList.filter { 
                    val id = it.streamId?.toString() ?: it.seriesId?.toString() ?: ""
                    favIds.contains(id)
                }
            } else if (catId == CAT_RECENT) {
                val recentIds = prefs.getRecentList()
                recentIds.mapNotNull { id ->
                    masterList.find { (it.streamId?.toString() ?: it.seriesId?.toString() ?: "") == id }
                }
            } else if (catId != null) {
                masterList.filter { it.categoryId == catId }
            } else {
                masterList
            }

            if (isAlphabeticalSort) {
                filtered = filtered.sortedBy { it.name?.lowercase() ?: "" }
            }

            runOnUiThread {
                streamAdapter.update(filtered)
                if (focusStreams) {
                    val rvStreams = findViewById<RecyclerView>(R.id.rv_streams)
                    rvStreams.post { 
                        rvStreams.requestFocus() 
                    }
                }
            }
        }.start()
    }

    private fun setupLists() {
        val rvCats = findViewById<RecyclerView>(R.id.rv_cats)
        catAdapter = GenericAdapter(
            layoutId = R.layout.item_simple,
            bind = { v, item ->
                val tv = v.findViewById<TextView>(R.id.tv_name)
                tv.text = item.name

                val isSelected = item.id == currentCatId
                val isFocused = v.hasFocus()

                when {
                    isFocused && isSelected -> {
                        v.setBackgroundResource(R.drawable.category_focused_active_bg)
                        tv.setTextColor(Color.BLACK)
                    }
                    isFocused -> {
                        v.setBackgroundResource(R.drawable.category_focused_bg)
                        tv.setTextColor(Color.BLACK)
                    }
                    isSelected -> {
                        v.setBackgroundResource(R.drawable.category_active_bg)
                        tv.setTextColor(Color.WHITE)
                    }
                    else -> {
                        v.setBackgroundResource(android.R.color.transparent)
                        tv.setTextColor(Color.WHITE)
                    }
                }
            },
            onClick = { item ->
                currentCatId = item.id
                findViewById<EditText>(R.id.et_search).text.clear()
                applyFilters(focusStreams = true)
            },
            onFocus = { item ->
                if (currentCatId != item.id) {
                    currentCatId = item.id
                    applyFilters()
                }
            },
            enableZoom = false
        )
        rvCats.layoutManager = LinearLayoutManager(this)
        rvCats.adapter = catAdapter

        val rvStreams = findViewById<RecyclerView>(R.id.rv_streams)
        rvStreams.setHasFixedSize(true)
        rvStreams.setItemViewCacheSize(20)

        streamAdapter = GenericAdapter(R.layout.item_grid, { v, item ->
            v.findViewById<TextView>(R.id.tv_name).text = item.name
            val img = v.findViewById<ImageView>(R.id.iv_thumb)
            
            val iconUrl = item.icon ?: item.cover
            if (!iconUrl.isNullOrEmpty()) {
                Glide.with(this)
                    .asBitmap()
                    .load(iconUrl)
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.movie)
                    .error(R.drawable.movie)
                    .into(img)
            } else {
                img.setImageResource(R.drawable.movie)
            }
            
            val id = item.streamId?.toString() ?: item.seriesId?.toString() ?: ""
            if (prefs.isWatched(id)) {
                v.alpha = 0.5f
            } else if (prefs.getPosition(id) > 0) {
                v.alpha = 0.8f
            } else {
                v.alpha = 1.0f
            }

            if (prefs.isFavorite(type, id)) {
                v.setBackgroundResource(R.drawable.card_background_fav)
            } else {
                v.setBackgroundResource(R.drawable.card_background)
            }
        }, { item ->
            if (type == TYPE_SERIES) {
                val intent = Intent(this, SeriesDetailsActivity::class.java).apply {
                    putExtra("series_id", item.seriesId?.toString())
                    putExtra("name", item.name)
                }
                startActivity(intent)
            } else {
                val streamUrl = buildStreamUrl(item)
                val id = item.streamId?.toString() ?: ""
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("url", streamUrl)
                    putExtra("id", id)
                    putExtra("name", item.name)
                    putExtra("poster", item.icon ?: item.cover)
                }
                startActivity(intent)
                overridePendingTransition(0, 0)
            }
        }, onLongClick = { item ->
            val id = item.streamId?.toString() ?: item.seriesId?.toString() ?: ""
            
            if (currentCatId == CAT_RECENT) {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Rimuovi contenuto")
                    .setMessage("Vuoi rimuovere questo elemento da 'Continua a Guardare'?")
                    .setPositiveButton("Rimuovi") { _, _ ->
                        prefs.removeFromRecent(id)
                        applyFilters()
                        Toast.makeText(this, "Rimosso", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            } else {
                prefs.toggleFavorite(type, id)
                applyFilters()
            }
        })
        rvStreams.layoutManager = GridLayoutManager(this, if (type == TYPE_LIVE) 5 else 6)
        rvStreams.adapter = streamAdapter
    }

    private fun buildStreamUrl(item: StreamItem): String {
        val baseUrl = "http://latteax.securitysc.shop"
        val user = prefs.user
        val pass = prefs.pass
        val id = item.streamId?.toString() ?: ""
        
        return when(type) {
            TYPE_LIVE -> "$baseUrl/live/$user/$pass/$id.ts"
            else -> {
                val ext = item.extension ?: "mp4"
                "$baseUrl/movie/$user/$pass/$id.$ext"
            }
        }
    }

    private fun loadCategories() {
        NetworkModule.api.getCategories(prefs.user, prefs.pass, type).enqueue(object : Callback<List<Category>> {
            override fun onResponse(call: Call<List<Category>>, response: Response<List<Category>>) {
                val cats = response.body() ?: emptyList()
                val finalCats = mutableListOf<Category>()
                finalCats.add(Category(CAT_FAVORITES, "⭐ PREFERITI"))
                finalCats.add(Category(CAT_RECENT, "🕒 CONTINUA A GUARDARE"))
                finalCats.addAll(cats)
                runOnUiThread {
                    catAdapter.update(finalCats)
                    if (type == TYPE_LIVE && currentCatId == CAT_FAVORITES && prefs.getFavorites(type).isEmpty() && cats.isNotEmpty()) {
                        currentCatId = cats[0].id
                        applyFilters()
                    }
                }
            }
            override fun onFailure(call: Call<List<Category>>, t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this@ContentActivity, "Errore connessione server IPTV", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun loadAllContent() {
        val streamAction = when(type) {
            TYPE_LIVE -> "get_live_streams"
            TYPE_VOD -> "get_vod_streams"
            else -> "get_series"
        }
        
        NetworkModule.api.getStreams(prefs.user, prefs.pass, streamAction, null).enqueue(object : Callback<List<StreamItem>> {
            override fun onResponse(call: Call<List<StreamItem>>, response: Response<List<StreamItem>>) {
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    masterList = body
                    runOnUiThread { applyFilters() }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@ContentActivity, "Server IPTV non disponibile", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            override fun onFailure(call: Call<List<StreamItem>>, t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this@ContentActivity, "Errore caricamento lista contenuti", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
}
