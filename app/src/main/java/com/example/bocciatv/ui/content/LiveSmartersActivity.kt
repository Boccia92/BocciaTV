package com.example.bocciatv.ui.content

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.bocciatv.R
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.model.Category
import com.example.bocciatv.data.model.EpgProgram
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
import androidx.media3.common.util.UnstableApi
import android.graphics.Color
import com.example.bocciatv.data.model.ReminderItem
import com.example.bocciatv.utils.ReminderScheduler
import java.util.Calendar

@UnstableApi
class LiveSmartersActivity : FragmentActivity() {

    companion object {
        private const val CAT_FAVORITES = "PREFERITI_ID"
    }

    enum class SmartersState { BROWSE, PLAYER }
    private var currentState = SmartersState.BROWSE

    private lateinit var prefs: PrefsManager
    private lateinit var catAdapter: GenericAdapter<Category>
    private lateinit var channelAdapter: GenericAdapter<StreamItem>
    private lateinit var zappingAdapter: GenericAdapter<StreamItem>
    private lateinit var epgAdapter: GenericAdapter<EpgProgram>
    
    private var allChannels = emptyList<StreamItem>()
    private var currentCatId: String? = null
    private var currentCatName: String? = null
    private var activeZappingChannelId: String? = null
    private var currentPlayingStreamId: String? = null
    private var currentPlayingItem: StreamItem? = null

    private lateinit var llBrowseLeft: LinearLayout
    private lateinit var llZappingLeft: LinearLayout
    private lateinit var tvZappingCategory: TextView
    private lateinit var rvZappingChannels: RecyclerView

    private lateinit var flGridContainer: FrameLayout
    private lateinit var llPlayerContainer: LinearLayout
    private lateinit var pbLoading: ProgressBar
    private lateinit var playerView: PlayerView
    private lateinit var tvPlayingChannel: TextView
    private lateinit var rvChannels: RecyclerView
    
    private var player: ExoPlayer? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val epgHandler = Handler(Looper.getMainLooper())
    private var epgRunnable: Runnable? = null
    private lateinit var tvClock: TextView
    private var currentEpgCall: Call<EpgResponse>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_smarters)
        prefs = PrefsManager(this)

        llBrowseLeft = findViewById(R.id.ll_browse_left)
        llZappingLeft = findViewById(R.id.ll_zapping_left)
        tvZappingCategory = findViewById(R.id.tv_zapping_category)
        rvZappingChannels = findViewById(R.id.rv_zapping_channels)
        
        flGridContainer = findViewById(R.id.fl_grid_container)
        llPlayerContainer = findViewById(R.id.ll_player_container)
        pbLoading = findViewById(R.id.pb_loading)
        playerView = findViewById(R.id.player_view_smarters)
        tvPlayingChannel = findViewById(R.id.tv_playing_channel)
        rvChannels = findViewById(R.id.rv_channels)
        tvClock = findViewById(R.id.tv_clock)

        setupClock()
        setupLists()
        loadCategoriesAndChannels()

        // Click sul riquadro video
        val miniPlayerWrap = findViewById<FrameLayout>(R.id.fl_mini_player_wrap)
        miniPlayerWrap.setOnClickListener {
            currentPlayingItem?.let { item -> launchFullScreenPlayer(item) }
        }
        playerView.setOnClickListener {
            currentPlayingItem?.let { item -> launchFullScreenPlayer(item) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentState == SmartersState.PLAYER) {
                    switchToBrowseState()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (currentState == SmartersState.PLAYER && currentPlayingItem != null) {
            playChannel(currentPlayingItem!!)
        }
    }

    private fun setupClock() {
        val dateFormat = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.ITALY)
        clockHandler.post(object : Runnable {
            override fun run() {
                tvClock.text = dateFormat.format(Date())
                clockHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun setupLists() {
        // --- STATE 1: BROWSE ---
        
        // Categorie (Sinistra)
        val rvCats = findViewById<RecyclerView>(R.id.rv_categories)
        catAdapter = GenericAdapter(R.layout.item_category_smarters, { v, item ->
            val tv = v.findViewById<TextView>(R.id.tv_cat_name)
            tv.text = item.name
            val isSelected = (item.id == currentCatId)
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
        }, { item ->
            val prevCatId = currentCatId
            currentCatId = item.id
            currentCatName = item.name

            val prevIndex = catAdapter.items.indexOfFirst { it.id == prevCatId }
            val newIndex = catAdapter.items.indexOfFirst { it.id == currentCatId }
            if (prevIndex != -1) catAdapter.notifyItemChanged(prevIndex)
            if (newIndex != -1) catAdapter.notifyItemChanged(newIndex)

            filterChannelsGrid()
        }, enableZoom = false, onFocusChange = { v, hasFocus, item ->
            val tv = v.findViewById<TextView>(R.id.tv_cat_name)
            val isSelected = (item.id == currentCatId)

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
        })
        rvCats.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvCats.layoutManager = LinearLayoutManager(this)
        rvCats.adapter = catAdapter

        // Canali (Griglia Destra)
        rvChannels.setHasFixedSize(true)
        rvChannels.setItemViewCacheSize(30)
        channelAdapter = GenericAdapter(R.layout.item_channel_smarters, { v, item ->
            v.findViewById<TextView>(R.id.tv_channel_name).text = item.name
            val img = v.findViewById<ImageView>(R.id.iv_logo)
            val iconUrl = item.icon ?: item.cover
            if (!iconUrl.isNullOrEmpty()) {
                Glide.with(this).load(iconUrl).override(200, 200).diskCacheStrategy(DiskCacheStrategy.ALL).placeholder(R.drawable.movie).error(R.drawable.movie).into(img)
            } else {
                img.setImageResource(R.drawable.movie)
            }
        }, { item ->
            switchToPlayerState(item)
        }, onLongClick = { item ->
            val id = item.streamId?.toString() ?: ""
            if (id.isNotEmpty()) {
                prefs.toggleFavorite("get_live_categories", id)
                Toast.makeText(this, "Preferiti aggiornati", Toast.LENGTH_SHORT).show()
                filterChannelsGrid()
            }
        }, enableZoom = true)
        rvChannels.layoutManager = GridLayoutManager(this, 5)
        rvChannels.adapter = channelAdapter


        // --- STATE 2: PLAYER / ZAPPING ---
        
        // Canali Verticali (Sinistra Zapping)
        zappingAdapter = GenericAdapter(R.layout.item_zapping_channel, { v, item ->
            val tv = v.findViewById<TextView>(R.id.tv_zapping_name)
            tv.text = item.name
            val targetId = (item.streamId ?: item.seriesId ?: "").toString()
            v.isActivated = (targetId == currentPlayingStreamId)
        }, { item ->
            val targetId = (item.streamId ?: item.seriesId ?: "").toString()
            if (targetId == currentPlayingStreamId) {
                launchFullScreenPlayer(item)
            } else {
                val previousPlayingId = currentPlayingStreamId
                currentPlayingStreamId = targetId
                playChannel(item)
                
                // Notifica solo i singoli item senza azzerare la RecyclerView:
                val prevIndex = zappingAdapter.items.indexOfFirst { (it.streamId ?: it.seriesId ?: "").toString() == previousPlayingId }
                val newIndex = zappingAdapter.items.indexOfFirst { (it.streamId ?: it.seriesId ?: "").toString() == targetId }
                
                if (prevIndex != -1) zappingAdapter.notifyItemChanged(prevIndex)
                if (newIndex != -1) zappingAdapter.notifyItemChanged(newIndex)
            }
        }, onLongClick = { item ->
            val id = item.streamId?.toString() ?: ""
            if (id.isNotEmpty()) {
                prefs.toggleFavorite("get_live_categories", id)
                Toast.makeText(this, "Preferiti aggiornati", Toast.LENGTH_SHORT).show()
                val channels = getCurrentCategoryChannels()
                zappingAdapter.update(channels)
            }
        }, enableZoom = false, onFocusChange = { _, hasFocus, item ->
            if (hasFocus) {
                tvPlayingChannel.text = item.name
                debounceLoadEpg(item)
            }
        })
        rvZappingChannels.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvZappingChannels.itemAnimator = null // Rimuove animazioni che disorientano il focus
        rvZappingChannels.layoutManager = LinearLayoutManager(this)
        rvZappingChannels.adapter = zappingAdapter

        // EPG (Sotto al mini player)
        val rvEpg = findViewById<RecyclerView>(R.id.rv_epg)
        epgAdapter = GenericAdapter(R.layout.item_epg_smarters, { v, prog ->
            v.isFocusable = true
            val dateTimeStr = formatEpgDateTime(prog.start, prog.startTimestamp, prog.end, prog.stopTimestamp)
            v.findViewById<TextView>(R.id.tv_epg_time).text = dateTimeStr
            v.findViewById<TextView>(R.id.tv_epg_title).text = prog.decodedTitle
            
            val tvReminder = v.findViewById<TextView>(R.id.tv_epg_reminder) ?: return@GenericAdapter
            val startTime = parseEpgTime(prog.start, prog.startTimestamp)
            
            if (startTime > System.currentTimeMillis() && !prog.decodedTitle.contains("Nessun dato") && !prog.decodedTitle.contains("Caricamento")) {
                tvReminder.visibility = View.VISIBLE
                val eventId = prog.id ?: "${activeZappingChannelId}_${prog.decodedTitle}_${prog.start}"
                if (prefs.isReminderSet(eventId)) {
                    tvReminder.text = "🔔 Attivo"
                    tvReminder.setTextColor(Color.GREEN)
                } else {
                    tvReminder.text = "🔔"
                    tvReminder.setTextColor(Color.WHITE)
                }
            } else {
                tvReminder.visibility = View.GONE
            }

            v.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val position = rvEpg.getChildAdapterPosition(v)
                    val totalCount = epgAdapter.items.size

                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == totalCount - 1) {
                        return@setOnKeyListener true
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }, { prog ->
            val startTime = parseEpgTime(prog.start, prog.startTimestamp)
            if (startTime > System.currentTimeMillis() && !prog.decodedTitle.contains("Nessun dato") && !prog.decodedTitle.contains("Caricamento")) {
                val eventId = prog.id ?: "${activeZappingChannelId}_${prog.decodedTitle}_${prog.start}"
                val channelName = tvPlayingChannel.text.toString()
                val streamUrl = "http://latteax.securitysc.shop/live/${prefs.user}/${prefs.pass}/$activeZappingChannelId.ts"
                
                if (prefs.isReminderSet(eventId)) {
                    ReminderScheduler.cancelReminder(this, eventId)
                    Toast.makeText(this, "Promemoria rimosso: ${prog.decodedTitle}", Toast.LENGTH_SHORT).show()
                } else {
                    val reminderItem = ReminderItem(
                        eventId = eventId,
                        programTitle = prog.decodedTitle,
                        channelId = activeZappingChannelId ?: "",
                        channelName = channelName,
                        streamUrl = streamUrl,
                        startTimeMillis = startTime
                    )
                    ReminderScheduler.scheduleReminder(this, reminderItem)
                    Toast.makeText(this, "Promemoria impostato: ${prog.decodedTitle}", Toast.LENGTH_SHORT).show()
                }
                epgAdapter.notifyDataSetChanged()
            } else if (prog.decodedTitle.contains("Nessun dato") || prog.decodedTitle.contains("Caricamento")) {
                // Ignore clicks on placeholder text
            } else {
                Toast.makeText(this, "Il programma è già iniziato o terminato", Toast.LENGTH_SHORT).show()
            }
        }, enableZoom = false)
        rvEpg.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        rvEpg.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvEpg.adapter = epgAdapter
    }

    private fun loadCategoriesAndChannels() {
        pbLoading.visibility = View.VISIBLE
        NetworkModule.api.getCategories(prefs.user, prefs.pass, "get_live_categories").enqueue(object : Callback<List<Category>> {
            override fun onResponse(call: Call<List<Category>>, response: Response<List<Category>>) {
                val cats = response.body() ?: emptyList()
                
                NetworkModule.api.getStreams(prefs.user, prefs.pass, "get_live_streams", null).enqueue(object : Callback<List<StreamItem>> {
                    override fun onResponse(call: Call<List<StreamItem>>, response: Response<List<StreamItem>>) {
                        pbLoading.visibility = View.GONE
                        allChannels = response.body() ?: emptyList()
                        
                        runOnUiThread {
                            val finalCats = mutableListOf<Category>()
                            finalCats.add(Category(CAT_FAVORITES, "⭐ PREFERITI"))
                            finalCats.addAll(cats)
                            catAdapter.update(finalCats)
                            if (finalCats.isNotEmpty()) {
                                currentCatId = finalCats[0].id
                                currentCatName = finalCats[0].name
                                filterChannelsGrid()
                            }
                        }
                    }
                    override fun onFailure(call: Call<List<StreamItem>>, t: Throwable) {
                        pbLoading.visibility = View.GONE
                        Toast.makeText(this@LiveSmartersActivity, "Errore canali", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            override fun onFailure(call: Call<List<Category>>, t: Throwable) {
                pbLoading.visibility = View.GONE
                Toast.makeText(this@LiveSmartersActivity, "Errore categorie", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun getCurrentCategoryChannels(): List<StreamItem> {
        return if (currentCatId == CAT_FAVORITES) {
            val favIds = prefs.getFavorites("get_live_categories")
            allChannels.filter { favIds.contains(it.streamId?.toString() ?: "") }
        } else {
            allChannels.filter { it.categoryId == currentCatId }
        }
    }

    private fun filterChannelsGrid() {
        if (currentCatId == null) return
        val filtered = getCurrentCategoryChannels()
        channelAdapter.update(filtered)
        rvChannels.scrollToPosition(0)
    }

    // Passaggio allo Stato 2
    private fun switchToPlayerState(item: StreamItem) {
        currentState = SmartersState.PLAYER
        
        // Hide Browse UI, Show Player UI
        llBrowseLeft.visibility = View.GONE
        flGridContainer.visibility = View.GONE
        llZappingLeft.visibility = View.VISIBLE
        llPlayerContainer.visibility = View.VISIBLE
        
        // Header Category
        tvZappingCategory.text = currentCatName ?: "Categoria"

        // Carica la lista di canali Zapping
        val channels = getCurrentCategoryChannels()
        zappingAdapter.update(channels)
        
        currentPlayingStreamId = item.streamId?.toString()
        playChannel(item)
        
        val pos = channels.indexOf(item)
        if (pos >= 0) {
            rvZappingChannels.scrollToPosition(pos)
            rvZappingChannels.postDelayed({
                rvZappingChannels.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
            }, 150)
        }
    }

    // Passaggio allo Stato 1
    private fun switchToBrowseState() {
        currentState = SmartersState.BROWSE
        stopPlayer()
        
        // Hide Player UI, Show Browse UI
        llZappingLeft.visibility = View.GONE
        llPlayerContainer.visibility = View.GONE
        llBrowseLeft.visibility = View.VISIBLE
        flGridContainer.visibility = View.VISIBLE
        
        rvChannels.requestFocus()
    }

    private fun playChannel(item: StreamItem) {
        currentPlayingItem = item
        stopPlayer()
        
        val targetId = (item.streamId ?: item.seriesId ?: "").toString().trim()
        player = ExoPlayer.Builder(this).build().apply {
            playerView.player = this
            val url = "http://latteax.securitysc.shop/live/${prefs.user}/${prefs.pass}/$targetId.ts"
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    private fun launchFullScreenPlayer(item: StreamItem) {
        player?.pause() // Ferma il mini-player locale per evitare conflitti audio
        
        val targetId = (item.streamId ?: item.seriesId ?: "").toString().trim()
        val streamUrl = "http://latteax.securitysc.shop/live/${prefs.user}/${prefs.pass}/$targetId.ts"
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("url", streamUrl)
            putExtra("id", targetId)
            putExtra("name", item.name ?: "")
            putExtra("poster", item.icon ?: item.cover ?: "")
        }
        startActivity(intent)
    }

    private fun debounceLoadEpg(item: StreamItem) {
        epgRunnable?.let { epgHandler.removeCallbacks(it) }
        epgRunnable = Runnable {
            loadEpgForChannel(item)
        }
        epgHandler.postDelayed(epgRunnable!!, 300) // Ritardo ridotto per la preview visiva
    }

    private fun loadEpgForChannel(item: StreamItem) {
        currentEpgCall?.cancel()
        
        val streamId = item.streamId?.toString() ?: return
        currentEpgCall = NetworkModule.api.getSimpleDataTable(prefs.user, prefs.pass, streamId = streamId)
        currentEpgCall?.enqueue(object : Callback<EpgResponse> {
            override fun onResponse(call: Call<EpgResponse>, response: Response<EpgResponse>) {
                if (call.isCanceled) return
                val listings = response.body()?.epgListings ?: emptyList()
                val currentTime = System.currentTimeMillis()
                val validListings = listings.filter { program ->
                    val endTime = parseEpgTime(program.end, program.stopTimestamp)
                    endTime == 0L || endTime > (currentTime - 600000L)
                }
                runOnUiThread { 
                    if (validListings.isEmpty()) {
                        loadShortEpgFallback(streamId)
                    } else {
                        epgAdapter.update(validListings) 
                    }
                }
            }
            override fun onFailure(call: Call<EpgResponse>, t: Throwable) {
                if (call.isCanceled) return
                loadShortEpgFallback(streamId)
            }
        })
    }

    private fun loadShortEpgFallback(streamId: String) {
        NetworkModule.api.getShortEpg(prefs.user, prefs.pass, streamId = streamId, limit = 100).enqueue(object : Callback<EpgResponse> {
            override fun onResponse(call: Call<EpgResponse>, response: Response<EpgResponse>) {
                val listings = response.body()?.epgListings ?: emptyList()
                val currentTime = System.currentTimeMillis()
                val validListings = listings.filter { program ->
                    val endTime = parseEpgTime(program.end, program.stopTimestamp)
                    endTime == 0L || endTime > (currentTime - 600000L)
                }
                runOnUiThread {
                    if (validListings.isEmpty()) {
                        val noDataB64 = Base64.encodeToString("Nessun dato EPG disponibile".toByteArray(), Base64.NO_WRAP)
                        epgAdapter.update(listOf(EpgProgram(null, null, noDataB64, null, "", "", null, null, null, null)))
                    } else {
                        epgAdapter.update(validListings)
                    }
                }
            }
            override fun onFailure(call: Call<EpgResponse>, t: Throwable) {
                runOnUiThread {
                    val errB64 = Base64.encodeToString("Errore caricamento guida".toByteArray(), Base64.NO_WRAP)
                    epgAdapter.update(listOf(EpgProgram(null, null, errB64, null, "", "", null, null, null, null)))
                }
            }
        })
    }

    private fun stopPlayer() {
        playerView.player = null
        player?.stop()
        player?.release()
        player = null
    }

    private fun parseEpgTime(rawTime: String?, rawTimestamp: Long?): Long {
        if (rawTimestamp != null && rawTimestamp > 0) return if (rawTimestamp > 10000000000L) rawTimestamp else rawTimestamp * 1000
        if (!rawTime.isNullOrEmpty()) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY)
                return sdf.parse(rawTime)?.time ?: 0L
            } catch (_: Exception) {}
        }
        return 0L
    }

    private fun extractTime(rawTime: String?, rawTimestamp: Long?): String {
        if (rawTimestamp != null && rawTimestamp > 0) {
            val ms = if (rawTimestamp > 10000000000L) rawTimestamp else rawTimestamp * 1000
            return SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(ms))
        }
        if (!rawTime.isNullOrEmpty()) {
            if (rawTime.length >= 16) return rawTime.substring(11, 16)
        }
        return "--:--"
    }

    private fun formatEpgDateTime(startRaw: String?, startTs: Long?, endRaw: String?, endTs: Long?): String {
        val startMillis = parseEpgTime(startRaw, startTs)
        val endMillis = parseEpgTime(endRaw, endTs)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALY)
        val startTimeStr = if (startMillis > 0L) timeFormat.format(Date(startMillis)) else extractTime(startRaw, startTs)
        val endTimeStr = if (endMillis > 0L) timeFormat.format(Date(endMillis)) else extractTime(endRaw, endTs)

        if (startMillis <= 0L) return "$startTimeStr - $endTimeStr"

        val startDate = Date(startMillis)
        val calStart = Calendar.getInstance().apply { time = startDate }
        val calNow = Calendar.getInstance()

        val isToday = calStart.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
                      calStart.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)

        calNow.add(Calendar.DAY_OF_YEAR, 1)
        val isTomorrow = calStart.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
                         calStart.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)

        return when {
            isToday -> "$startTimeStr - $endTimeStr"
            isTomorrow -> "Domani, $startTimeStr - $endTimeStr"
            else -> {
                val formatter = SimpleDateFormat("EEE dd/MM", Locale.ITALY)
                val dateStr = formatter.format(startDate).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALY) else it.toString() }
                "$dateStr, $startTimeStr - $endTimeStr"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacksAndMessages(null)
        epgHandler.removeCallbacksAndMessages(null)
        stopPlayer()
    }
}
