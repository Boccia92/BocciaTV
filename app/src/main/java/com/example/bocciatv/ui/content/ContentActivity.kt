package com.example.bocciatv.ui.content

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.bocciatv.R
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.model.Category
import com.example.bocciatv.data.model.StreamItem
import com.example.bocciatv.data.network.NetworkModule
import com.example.bocciatv.ui.adapter.GenericAdapter
import com.example.bocciatv.ui.player.PlayerActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

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
        loadCategories()
        loadAllContent()
    }

    override fun onResume() {
        super.onResume()
        applyFilters()
        catAdapter.notifyDataSetChanged()
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

    private fun applyFilters() {
        val filtered = if (currentSearch.isNotEmpty()) {
            masterList.filter { it.name?.contains(currentSearch, ignoreCase = true) == true }
        } else if (currentCatId == CAT_FAVORITES) {
            val favIds = prefs.getFavorites(type)
            masterList.filter { 
                val id = it.streamId?.toString() ?: it.seriesId?.toString() ?: ""
                favIds.contains(id)
            }
        } else if (currentCatId == CAT_RECENT) {
            val recentIds = prefs.getRecentList()
            recentIds.mapNotNull { id ->
                masterList.find { (it.streamId?.toString() ?: it.seriesId?.toString() ?: "") == id }
            }
        } else if (currentCatId != null) {
            masterList.filter { it.categoryId == currentCatId }
        } else {
            masterList
        }
        streamAdapter.update(filtered)
    }

    private fun setupLists() {
        val rvCats = findViewById<RecyclerView>(R.id.rv_cats)
        catAdapter = GenericAdapter(R.layout.item_simple, { v, item ->
            val tv = v.findViewById<TextView>(R.id.tv_name)
            tv.text = item.name
            if (item.id == currentCatId) {
                v.setBackgroundResource(R.drawable.category_active_bg)
            } else {
                v.setBackgroundResource(android.R.color.transparent)
            }
        }, { item ->
            currentCatId = item.id
            findViewById<EditText>(R.id.et_search).text.clear()
            catAdapter.notifyDataSetChanged()
            applyFilters()
        })
        rvCats.layoutManager = LinearLayoutManager(this)
        rvCats.adapter = catAdapter

        val rvStreams = findViewById<RecyclerView>(R.id.rv_streams)
        streamAdapter = GenericAdapter(R.layout.item_grid, { v, item ->
            v.findViewById<TextView>(R.id.tv_name).text = item.name
            val img = v.findViewById<ImageView>(R.id.iv_thumb)
            Glide.with(this).load(item.icon ?: item.cover).placeholder(R.drawable.movie).into(img)
            
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
                }
                startActivity(intent)
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
        rvStreams.layoutManager = GridLayoutManager(this, if (type == TYPE_LIVE) 4 else 5)
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
                runOnUiThread { catAdapter.update(finalCats) }
            }
            override fun onFailure(call: Call<List<Category>>, t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this@ContentActivity, "Errore connessione server. Se la VPN è attiva, prova un altro Paese (es. Italia 🇮🇹)", Toast.LENGTH_LONG).show()
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
                    masterList = body.sortedBy { it.name?.lowercase() ?: "" }
                    runOnUiThread { applyFilters() }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@ContentActivity, "Server IPTV irraggiungibile con questa VPN. Seleziona Italia 🇮🇹 o disattiva la VPN.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            override fun onFailure(call: Call<List<StreamItem>>, t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this@ContentActivity, "Errore caricamento lista. Se hai la VPN attiva, seleziona Italia 🇮🇹 o cambia Paese.", Toast.LENGTH_LONG).show()
                }
            }
        })
    }
}
