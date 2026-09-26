package com.example.bocciatv.ui.content

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
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
import com.example.bocciatv.data.model.EpgResponse
import com.example.bocciatv.data.model.StreamItem
import com.example.bocciatv.data.network.NetworkModule
import com.example.bocciatv.ui.adapter.GenericAdapter
import com.example.bocciatv.ui.player.PlayerActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*

@UnstableApi
class ContentActivity : FragmentActivity() {
    companion object {
        const val EXTRA_TYPE = "type"
        const val TYPE_LIVE = "get_live_categories"
        const val TYPE_VOD = "get_vod_categories"
        const val TYPE_SERIES = "get_series_categories"
        private const val CAT_FAVORITES = "PREFERITI_ID"
        private const val CAT_RECENT = "RECENT_ID"
        var shouldRefresh = false

        private val streamsCache = mutableMapOf<String, List<StreamItem>>()
        private val categoryMapCache = mutableMapOf<String, MutableMap<String, List<StreamItem>>>()

        // Reusable thread-safe date formatters to prevent GC thrashing
        private val sdfDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY)
        private val sdfTimeOnly = SimpleDateFormat("HH:mm", Locale.ITALY)
    }

    private lateinit var type: String
    private lateinit var prefs: PrefsManager
    private lateinit var catAdapter: GenericAdapter<Category>
    private lateinit var streamAdapter: GenericAdapter<StreamItem>
    
    private var masterList = emptyList<StreamItem>()
    private var categoryStreamsMap = mutableMapOf<String, List<StreamItem>>()
    private var currentCatId: String? = CAT_FAVORITES
    private var currentSearch: String = ""
    private var isAlphabeticalSort = false

    private val epgHandler = Handler(Looper.getMainLooper())
    private var epgRunnable: Runnable? = null
    private var currentEpgCall: Call<EpgResponse>? = null

    private var filterJob: Job? = null

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
    }

    override fun onResume() {
        super.onResume()
        if (shouldRefresh) {
            Log.d("SYNC_DEBUG", "Database locale azzerato")
            streamsCache.clear()
            categoryMapCache.clear()
            masterList = emptyList()
            catAdapter.update(emptyList())
            streamAdapter.update(emptyList())
            loadCategories()
            shouldRefresh = false
        } else {
            currentCatId?.let { loadStreamsForCategory(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        filterJob?.cancel()
        epgHandler.removeCallbacksAndMessages(null)
        currentEpgCall?.cancel()
        Glide.with(this).resumeRequests()
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
        
        filterJob?.cancel()
        filterJob = lifecycleScope.launch(Dispatchers.Default) {
            val pbLoading = findViewById<ProgressBar>(R.id.pb_loading)
            withContext(Dispatchers.Main) {
                pbLoading?.visibility = View.VISIBLE
            }

            val filtered = if (search.isNotEmpty()) {
                masterList.filter { it.name?.contains(search, ignoreCase = true) == true }.take(500)
            } else if (catId == CAT_FAVORITES) {
                val favIds = prefs.getFavorites(type)
                masterList.filter { 
                    val id = it.streamId?.toString() ?: it.seriesId?.toString() ?: ""
                    favIds.contains(id)
                }
            } else if (catId == CAT_RECENT) {
                val recentIds = prefs.getRecentList()
                val masterMap = masterList.associateBy { it.streamId?.toString() ?: it.seriesId?.toString() ?: "" }
                recentIds.mapNotNull { id -> masterMap[id] }
            } else if (catId != null) {
                categoryStreamsMap[catId] ?: emptyList()
            } else {
                masterList
            }

            if (!isActive) return@launch

            val sortedAndFinal = if (isAlphabeticalSort) {
                filtered.sortedBy { it.name?.lowercase() ?: "" }
            } else {
                filtered
            }

            val limited = if (sortedAndFinal.size > 500) sortedAndFinal.take(500) else sortedAndFinal

            withContext(Dispatchers.Main) {
                pbLoading?.visibility = View.GONE
                streamAdapter.update(limited)
                if (focusStreams) {
                    val rvStreams = findViewById<RecyclerView>(R.id.rv_streams)
                    rvStreams?.post { rvStreams.requestFocus() }
                }
            }
        }
    }

    private fun loadStreamsForCategory(catId: String, focusStreams: Boolean = false) {
        currentCatId = catId
        val pbLoading = findViewById<ProgressBar>(R.id.pb_loading)

        if (catId == CAT_FAVORITES) {
            val favIds = prefs.getFavorites(type)
            val filtered = masterList.filter { 
                val id = it.streamId?.toString() ?: it.seriesId?.toString() ?: ""
                favIds.contains(id)
            }
            displayStreams(filtered, focusStreams)
            return
        }

        if (catId == CAT_RECENT) {
            val recentIds = prefs.getRecentList()
            val masterMap = masterList.associateBy { it.streamId?.toString() ?: it.seriesId?.toString() ?: "" }
            val filtered = recentIds.mapNotNull { id -> masterMap[id] }
            displayStreams(filtered, focusStreams)
            return
        }

        if (categoryStreamsMap.containsKey(catId) && !categoryStreamsMap[catId].isNullOrEmpty()) {
            displayStreams(categoryStreamsMap[catId]!!, focusStreams)
            return
        }

        pbLoading?.visibility = View.VISIBLE
        val streamAction = when(type) {
            TYPE_LIVE -> "get_live_streams"
            TYPE_VOD -> "get_vod_streams"
            else -> "get_series"
        }

        NetworkModule.api.getStreams(prefs.user, prefs.pass, streamAction, catId).enqueue(object : Callback<List<StreamItem>> {
            override fun onResponse(call: Call<List<StreamItem>>, response: Response<List<StreamItem>>) {
                val body = response.body() ?: emptyList()
                categoryStreamsMap[catId] = body
                categoryMapCache[type] = categoryStreamsMap
                runOnUiThread {
                    pbLoading?.visibility = View.GONE
                    displayStreams(body, focusStreams)
                }
            }
            override fun onFailure(call: Call<List<StreamItem>>, t: Throwable) {
                runOnUiThread {
                    pbLoading?.visibility = View.GONE
                    Toast.makeText(this@ContentActivity, "Errore caricamento categoria", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun displayStreams(streams: List<StreamItem>, focusStreams: Boolean) {
        val rvStreams = findViewById<RecyclerView>(R.id.rv_streams)
        lifecycleScope.launch(Dispatchers.Default) {
            val sortedAndFinal = if (isAlphabeticalSort) {
                streams.sortedBy { it.name?.lowercase() ?: "" }
            } else {
                streams
            }

            val limited = if (sortedAndFinal.size > 500) sortedAndFinal.take(500) else sortedAndFinal

            withContext(Dispatchers.Main) {
                streamAdapter.update(limited)
                if (focusStreams) {
                    rvStreams?.post { rvStreams.requestFocus() }
                }
            }
        }
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
                loadStreamsForCategory(item.id ?: "", focusStreams = true)
                catAdapter.notifyDataSetChanged()
            },
            enableZoom = false,
            onFocusChange = { v, hasFocus, item ->
                val tv = v.findViewById<TextView>(R.id.tv_name)
                val isSelected = item.id == currentCatId
                when {
                    hasFocus && isSelected -> {
                        v.setBackgroundResource(R.drawable.category_focused_active_bg)
                        tv.setTextColor(Color.BLACK)
                    }
                    hasFocus -> {
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
            }
        )
        rvCats.layoutManager = LinearLayoutManager(this)
        rvCats.adapter = catAdapter

        val rvStreams = findViewById<RecyclerView>(R.id.rv_streams)
        rvStreams.setHasFixedSize(true)
        rvStreams.setItemViewCacheSize(6) // Conservativo per evitare GC overhead su Android TV / Fire Stick
        rvStreams.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_SETTLING || newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    Glide.with(this@ContentActivity).pauseRequests()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    Glide.with(this@ContentActivity).resumeRequests()
                }
            }
        })

        val density = resources.displayMetrics.density
        val targetW = (105 * density).toInt()
        val targetH = (95 * density).toInt()

        streamAdapter = GenericAdapter(R.layout.item_grid, { holder: GenericAdapter.ViewHolder, item: StreamItem ->
            holder.findViewById<TextView>(R.id.tv_name).text = item.name
            val img = holder.findViewById<ImageView>(R.id.iv_thumb)

            Glide.with(this).clear(img)

            val iconUrl = item.icon ?: item.cover
            if (!iconUrl.isNullOrEmpty()) {
                Glide.with(this)
                    .load(iconUrl)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .override(targetW, targetH)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.movie)
                    .error(R.drawable.movie)
                    .into(img)
            } else {
                img.setImageResource(R.drawable.movie)
            }
            
            val id = item.streamId?.toString() ?: item.seriesId?.toString() ?: ""
            if (prefs.isWatched(id)) {
                holder.itemView.alpha = 0.5f
            } else if (prefs.getPosition(id) > 0) {
                holder.itemView.alpha = 0.8f
            } else {
                holder.itemView.alpha = 1.0f
            }

            val isFav = prefs.isFavorite(type, id)
            val lastFav = holder.itemView.getTag(R.id.tv_name) as? Boolean
            if (lastFav != isFav) {
                holder.itemView.setTag(R.id.tv_name, isFav)
                if (isFav) {
                    holder.itemView.setBackgroundResource(R.drawable.card_background_fav)
                } else {
                    holder.itemView.setBackgroundResource(R.drawable.card_background)
                }
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
        }, onFocus = { item ->
            if (type == TYPE_LIVE) {
                val llPreview = findViewById<View>(R.id.ll_epg_preview)
                val tvTitle = findViewById<TextView>(R.id.tv_preview_title)
                val tvCurrent = findViewById<TextView>(R.id.tv_preview_current)
                val tvNext = findViewById<TextView>(R.id.tv_preview_next)
                val tvNext2 = findViewById<TextView>(R.id.tv_preview_next2)
                val tvNext3 = findViewById<TextView>(R.id.tv_preview_next3)
                val tvNext4 = findViewById<TextView>(R.id.tv_preview_next4)
                val tvNext5 = findViewById<TextView>(R.id.tv_preview_next5)
                val ivLogo = findViewById<ImageView>(R.id.iv_preview_logo)

                llPreview?.visibility = View.VISIBLE
                tvTitle?.text = item.name
                tvCurrent?.text = "IN ONDA: Caricamento..."
                tvNext?.text = "A SEGUIRE: Caricamento..."
                tvNext2?.text = "POI: Caricamento..."
                tvNext3?.text = "PIÙ TARDI: Caricamento..."
                tvNext4?.text = "Caricamento..."
                tvNext5?.text = "Caricamento..."

                val iconUrl = item.icon ?: item.cover
                if (!iconUrl.isNullOrEmpty() && ivLogo != null) {
                    Glide.with(this).load(iconUrl).format(DecodeFormat.PREFER_RGB_565).placeholder(R.drawable.movie).error(R.drawable.movie).into(ivLogo)
                } else {
                    ivLogo?.setImageResource(R.drawable.movie)
                }

                epgRunnable?.let { epgHandler.removeCallbacks(it) }
                currentEpgCall?.cancel()

                epgRunnable = Runnable {
                    val streamId = item.streamId?.toString() ?: ""
                    if (streamId.isNotEmpty()) {
                        currentEpgCall = NetworkModule.api.getShortEpg(prefs.user, prefs.pass, streamId = streamId, limit = 12)
                        currentEpgCall?.enqueue(object : Callback<EpgResponse> {
                            override fun onResponse(call: Call<EpgResponse>, response: Response<EpgResponse>) {
                                val listings = response.body()?.epgListings ?: emptyList()
                                val currentTime = System.currentTimeMillis()
                                val validListings = listings.filter { program ->
                                    val endTime = parseEpgTime(program.end, program.stopTimestamp)
                                    endTime == 0L || endTime > (currentTime - 600000L)
                                }

                                runOnUiThread {
                                    if (validListings.isNotEmpty()) {
                                        val current = validListings[0]
                                        val next1 = validListings.getOrNull(1)
                                        val next2 = validListings.getOrNull(2)
                                        val next3 = validListings.getOrNull(3)
                                        val next4 = validListings.getOrNull(4)
                                        val next5 = validListings.getOrNull(5)

                                        val curStart = extractTime(current.start, current.startTimestamp)
                                        val curEnd = extractTime(current.end, current.stopTimestamp)
                                        tvCurrent?.text = "IN ONDA: ${current.decodedTitle} ($curStart - $curEnd)"

                                        val nextViews = arrayOf(tvNext, tvNext2, tvNext3, tvNext4, tvNext5)
                                        val nextItems = arrayOf(next1, next2, next3, next4, next5)
                                        val labels = arrayOf("A SEGUIRE: ", "POI: ", "PIÙ TARDI: ", "", "")

                                        for (i in nextViews.indices) {
                                            val view = nextViews[i]
                                            val itemProg = nextItems[i]
                                            if (view != null) {
                                                if (itemProg != null) {
                                                    val start = extractTime(itemProg.start, itemProg.startTimestamp)
                                                    val end = extractTime(itemProg.end, itemProg.stopTimestamp)
                                                    val prefix = labels.getOrElse(i) { "" }
                                                    view.text = "$prefix${itemProg.decodedTitle} ($start - $end)"
                                                    view.visibility = View.VISIBLE
                                                } else {
                                                    view.visibility = View.GONE
                                                }
                                            }
                                        }
                                    } else {
                                        tvCurrent?.text = "IN ONDA: Nessun dato EPG disponibile"
                                        tvNext?.visibility = View.GONE
                                        tvNext2?.visibility = View.GONE
                                        tvNext3?.visibility = View.GONE
                                        tvNext4?.visibility = View.GONE
                                        tvNext5?.visibility = View.GONE
                                    }
                                }
                            }
                            override fun onFailure(call: Call<EpgResponse>, t: Throwable) {
                                if (call.isCanceled) return
                                runOnUiThread {
                                    tvCurrent?.text = "IN ONDA: Nessun dato EPG disponibile"
                                    tvNext?.visibility = View.GONE
                                    tvNext2?.visibility = View.GONE
                                    tvNext3?.visibility = View.GONE
                                    tvNext4?.visibility = View.GONE
                                    tvNext5?.visibility = View.GONE
                                }
                            }
                        })
                    } else {
                        tvCurrent?.text = "IN ONDA: Nessun dato EPG disponibile"
                        tvNext?.visibility = View.GONE
                        tvNext2?.visibility = View.GONE
                        tvNext3?.visibility = View.GONE
                        tvNext4?.visibility = View.GONE
                        tvNext5?.visibility = View.GONE
                    }
                }
                epgHandler.postDelayed(epgRunnable!!, 350)
            } else {
                findViewById<View>(R.id.ll_epg_preview)?.visibility = View.GONE
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
                if (currentCatId == CAT_FAVORITES) {
                    val currentList = streamAdapter.items.toMutableList()
                    val index = currentList.indexOf(item)
                    if (index != -1) {
                        currentList.removeAt(index)
                        streamAdapter.update(currentList)
                    }
                } else {
                    val index = streamAdapter.items.indexOf(item)
                    if (index != -1) {
                        streamAdapter.notifyItemChanged(index)
                    }
                }
            }
        })
        rvStreams.layoutManager = GridLayoutManager(this, if (type == TYPE_LIVE) 5 else 6)
        rvStreams.adapter = streamAdapter
    }

    private fun buildStreamUrl(item: StreamItem): String {
        val baseUrl = prefs.serverUrl.ifEmpty { "http://latteax.securitysc.shop" }
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
                    if (finalCats.isNotEmpty()) {
                        loadStreamsForCategory(finalCats[0].id ?: "")
                    }
                }
                loadAllContentSilent()
            }
            override fun onFailure(call: Call<List<Category>>, t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this@ContentActivity, "Errore connessione server IPTV", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun loadAllContentSilent() {
        if (streamsCache.containsKey(type) && !streamsCache[type].isNullOrEmpty()) {
            masterList = streamsCache[type]!!
            return
        }
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
                    streamsCache[type] = masterList
                    CoroutineScope(Dispatchers.Default).launch {
                        categoryMapCache[type] = masterList.groupBy { it.categoryId ?: "" }.toMutableMap()
                    }
                }
            }
            override fun onFailure(call: Call<List<StreamItem>>, t: Throwable) {}
        })
    }

    private fun parseEpgTime(rawTime: String?, rawTimestamp: Long?): Long {
        if (rawTimestamp != null && rawTimestamp > 0) {
            return if (rawTimestamp > 10000000000L) rawTimestamp else rawTimestamp * 1000
        }
        if (!rawTime.isNullOrEmpty()) {
            try {
                synchronized(sdfDateTime) {
                    val date = sdfDateTime.parse(rawTime)
                    if (date != null) return date.time
                }
            } catch (_: Exception) {}
        }
        return 0L
    }

    private fun extractTime(rawTime: String?, rawTimestamp: Long?): String {
        if (rawTimestamp != null && rawTimestamp > 0) {
            val ms = if (rawTimestamp > 10000000000L) rawTimestamp else rawTimestamp * 1000
            synchronized(sdfTimeOnly) {
                return sdfTimeOnly.format(Date(ms))
            }
        }
        if (!rawTime.isNullOrEmpty()) {
            if (rawTime.length >= 16) {
                return rawTime.substring(11, 16)
            }
            try {
                synchronized(sdfDateTime) {
                    val date = sdfDateTime.parse(rawTime)
                    if (date != null) {
                        synchronized(sdfTimeOnly) {
                            return sdfTimeOnly.format(date)
                        }
                    }
                }
            } catch (_: Exception) {}
            return rawTime
        }
        return "--:--"
    }
}
