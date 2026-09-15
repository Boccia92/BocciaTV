package com.example.bocciatv.ui.player

import android.app.AlertDialog
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.example.bocciatv.data.model.ReminderItem
import com.example.bocciatv.utils.ReminderScheduler
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.bocciatv.R
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.model.EpgProgram
import com.example.bocciatv.data.model.EpgResponse
import com.example.bocciatv.ui.adapter.GenericAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bocciatv.data.network.NetworkModule
import com.example.bocciatv.utils.DisplayUtils
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.*

@UnstableApi
class PlayerActivity : FragmentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var prefs: PrefsManager
    private var ids: List<String> = emptyList()
    private var currentMediaId: String? = null

    // Audio normalization (Night Mode) & Voice Boost
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var isNightModeActive = false
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var isVoiceBoostActive = false

    private var nextProgramTitle: String? = null
    private var nextProgramStartTime: Long = 0L
    private var nextProgramEventId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            saveCurrentPosition()
            checkIntroAndBingeWatching()
            handler.postDelayed(this, 1000)
        }
    }

    private var introStartTime: Long = 5000L
    private var introEndTime: Long = 90000L
    private var hasSkippedIntro = false
    private var isBingeActive = false

    private fun saveCurrentPosition() {
        player?.let {
            if (it.isPlaying) {
                val pos = it.currentPosition
                val duration = it.duration
                currentMediaId?.let { id ->
                    if (id != "unknown") {
                        prefs.savePosition(id, pos)
                        if (duration > 0 && (duration - pos) < 120000) {
                            prefs.setWatched(id)
                        } else if (pos > 10000) {
                            prefs.setWatched(id, false)
                        }
                    }
                }

                if (it.hasNextMediaItem() && duration > 0 && (duration - pos) < 10000) {
                    it.seekToNext()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val locale = Locale.ITALY
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        prefs = PrefsManager(this)
        isVoiceBoostActive = prefs.isVoiceBoost

        playerView = findViewById(R.id.player_view)

        // Setup custom action button listeners inside the player control overlay
        val btnZoom = playerView.findViewById<View>(R.id.btn_custom_zoom)
        btnZoom?.setOnClickListener { showZoomDialog() }

        val btnSubtitles = playerView.findViewById<View>(R.id.btn_custom_subtitles)
        btnSubtitles?.setOnClickListener { showSubtitleDialog() }

        val btnSettings = playerView.findViewById<View>(R.id.btn_custom_settings)
        btnSettings?.setOnClickListener { showSettingsMenu() }

        val btnPlayPause = playerView.findViewById<ImageButton>(R.id.btn_play_pause)
        btnPlayPause?.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                } else {
                    p.play()
                }
            }
        }

        val btnEpgGuide = playerView.findViewById<Button>(R.id.btn_epg_guide)
        btnEpgGuide?.setOnClickListener {
            showFullEpgDialog()
        }

        val btnReminder = playerView.findViewById<Button>(R.id.btn_reminder)
        btnReminder?.setOnClickListener {
            val title = nextProgramTitle
            val startTime = nextProgramStartTime
            val eventId = nextProgramEventId

            if (title.isNullOrEmpty() || startTime <= System.currentTimeMillis()) {
                Toast.makeText(this, "Nessun programma futuro disponibile per il promemoria", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val channelName = intent.getStringExtra("name") ?: "Canale TV"
            val streamUrl = intent.getStringExtra("url") ?: ""
            val currentId = currentMediaId ?: "unknown"

            if (prefs.isReminderSet(eventId ?: "")) {
                ReminderScheduler.cancelReminder(this, eventId!!)
                btnReminder.text = "🔔 Ricorda"
                Toast.makeText(this, "Promemoria rimosso", Toast.LENGTH_SHORT).show()
            } else {
                val item = ReminderItem(
                    eventId = eventId ?: "$currentId-$startTime",
                    programTitle = title,
                    channelId = currentId,
                    channelName = channelName,
                    streamUrl = streamUrl,
                    startTimeMillis = startTime
                )
                ReminderScheduler.scheduleReminder(this, item)
                btnReminder.text = "🔔 Annulla"
                Toast.makeText(this, "Promemoria impostato per: $title", Toast.LENGTH_SHORT).show()
            }
        }

        val btnSkipIntro = findViewById<Button>(R.id.btn_skip_intro)
        btnSkipIntro?.setOnClickListener {
            val player = player ?: return@setOnClickListener
            val targetPos = if (introEndTime > player.currentPosition) introEndTime else player.currentPosition + 85000L
            player.seekTo(targetPos)
            hasSkippedIntro = true
            btnSkipIntro.visibility = View.GONE
            Toast.makeText(this, "Sigla saltata", Toast.LENGTH_SHORT).show()
        }

        val btnPlayNext = findViewById<Button>(R.id.btn_play_next_episode)
        btnPlayNext?.setOnClickListener {
            playNextEpisodeOrFinish()
        }

        val urlsArray = intent.getStringArrayExtra("urls")
        val idsArray = intent.getStringArrayExtra("ids")
        val startIndex = intent.getIntExtra("index", 0)
        val channelName = intent.getStringExtra("name") ?: "Canale TV"
        val posterUrl = intent.getStringExtra("poster")

        val tvTitle = playerView.findViewById<TextView?>(R.id.tv_epg_title)
        val tvInfo = playerView.findViewById<TextView?>(R.id.tv_epg_info)
        val ivPoster = playerView.findViewById<ImageView?>(R.id.iv_epg_poster)

        tvTitle?.text = channelName
        tvInfo?.text = "In onda: Caricamento..."

        if (!posterUrl.isNullOrEmpty() && ivPoster != null) {
            Glide.with(this)
                .asBitmap()
                .load(posterUrl)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.movie)
                .error(R.drawable.movie)
                .into(ivPoster)
        } else {
            ivPoster?.setImageResource(R.drawable.movie)
        }

        if (urlsArray == null || idsArray == null) {
            val singleUrl = intent.getStringExtra("url") ?: return
            val singleId = intent.getStringExtra("id") ?: "unknown"
            ids = listOf(singleId)
            initializePlayer(listOf(singleUrl), 0)
            loadEpgInfo(singleId)
        } else {
            ids = idsArray.toList()
            initializePlayer(urlsArray.toList(), startIndex)
            if (ids.isNotEmpty()) loadEpgInfo(ids[0])
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerView.isControllerFullyVisible) {
                    hideEpgOverlay()
                } else {
                    hideEpgOverlay()
                    playerView.player = null
                    player?.stop()
                    player?.release()
                    player = null
                    finish()
                }
            }
        })
    }

    private fun initializePlayer(urls: List<String>, startIndex: Int) {
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this, renderersFactory).build().also {
            playerView.player = it

            val mediaItems = urls.map { url -> MediaItem.fromUri(url) }
            it.setMediaItems(mediaItems, startIndex, 0L)

            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        val sessionId = it.audioSessionId
                        if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                            if (isVoiceBoostActive && loudnessEnhancer == null) {
                                applyVoiceBoost(true)
                            }
                            if (isNightModeActive && dynamicsProcessing == null) {
                                applyNightMode(true)
                            }
                        }
                        val index = it.currentMediaItemIndex
                        if (index < ids.size) {
                            val newId = ids[index]
                            if (currentMediaId != newId) {
                                currentMediaId = newId
                                val savedPos = prefs.getPosition(currentMediaId!!)
                                if (savedPos > 0 && !prefs.isWatched(currentMediaId!!)) {
                                    it.seekTo(savedPos)
                                }
                                loadEpgInfo(currentMediaId!!)
                            }
                        }
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val index = it.currentMediaItemIndex
                    if (index < ids.size) {
                        currentMediaId = ids[index]
                        val savedPos = prefs.getPosition(currentMediaId!!)
                        if (savedPos > 0 && !prefs.isWatched(currentMediaId!!)) {
                            it.seekTo(savedPos)
                            Toast.makeText(this@PlayerActivity, "Ripresa visione...", Toast.LENGTH_SHORT).show()
                        }
                        loadEpgInfo(currentMediaId!!)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("BocciaTV", "Playback Error: ${error.message}", error)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    runOnUiThread {
                        val btnPlayPause = playerView.findViewById<ImageButton>(R.id.btn_play_pause)
                        if (isPlaying) {
                            btnPlayPause?.setImageResource(android.R.drawable.ic_media_pause)
                            btnPlayPause?.contentDescription = "Pausa"
                        } else {
                            btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
                            btnPlayPause?.contentDescription = "Play"
                        }
                    }
                }
            })

            it.prepare()
            it.playWhenReady = true
        }
        handler.post(progressUpdater)
    }

    private fun loadEpgInfo(streamId: String) {
        val channelName = intent.getStringExtra("name") ?: "Canale TV"
        val posterUrl = intent.getStringExtra("poster") ?: intent.getStringExtra("icon")

        val ivPoster = playerView.findViewById<ImageView?>(R.id.iv_epg_poster)
        val tvTitle = playerView.findViewById<TextView?>(R.id.tv_epg_title)
        val tvInfo = playerView.findViewById<TextView?>(R.id.tv_epg_info)

        tvTitle?.text = channelName
        if (!posterUrl.isNullOrEmpty() && ivPoster != null) {
            Glide.with(this)
                .asBitmap()
                .load(posterUrl)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.movie)
                .error(R.drawable.movie)
                .into(ivPoster)
        }

        if (streamId == "unknown" || streamId.isEmpty()) return

        NetworkModule.api.getShortEpg(prefs.user, prefs.pass, streamId = streamId).enqueue(object : Callback<EpgResponse> {
            override fun onResponse(call: Call<EpgResponse>, response: Response<EpgResponse>) {
                val listings = response.body()?.epgListings ?: emptyList()
                if (listings.isNotEmpty()) {
                    val current = listings[0]
                    val next = listings.getOrNull(1)

                    if (next != null) {
                        nextProgramTitle = next.decodedTitle
                        val rawTs = next.startTimestamp ?: 0L
                        nextProgramStartTime = if (rawTs > 10000000000L) rawTs else rawTs * 1000
                        nextProgramEventId = next.id ?: "${streamId}_${next.decodedTitle}"

                        val isSet = prefs.isReminderSet(nextProgramEventId!!)
                        runOnUiThread {
                            val btnReminder = playerView.findViewById<Button>(R.id.btn_reminder)
                            btnReminder?.text = if (isSet) "🔔 Annulla" else "🔔 Ricorda"
                        }
                    }

                    runOnUiThread {
                        val currentText = "In onda: ${current.decodedTitle} (${current.start ?: "--:--"} - ${current.end ?: "--:--"})"
                        val nextText = if (next != null) " | A seguire: ${next.decodedTitle}" else ""
                        tvInfo?.text = "$currentText$nextText"
                        showEpgOverlay()
                    }
                }
            }

            override fun onFailure(call: Call<EpgResponse>, t: Throwable) {
                Log.e("BocciaTV", "EPG fetch error: ${t.message}")
            }
        })
    }

    private fun showFullEpgDialog() {
        val streamId = currentMediaId ?: return
        if (streamId == "unknown" || streamId.isEmpty()) {
            Toast.makeText(this, "EPG non disponibile per questo canale", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Caricamento Guida EPG...", Toast.LENGTH_SHORT).show()

        NetworkModule.api.getSimpleDataTable(prefs.user, prefs.pass, streamId = streamId).enqueue(object : Callback<EpgResponse> {
            override fun onResponse(call: Call<EpgResponse>, response: Response<EpgResponse>) {
                val listings = response.body()?.epgListings ?: emptyList()
                if (listings.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this@PlayerActivity, "Nessun programma EPG trovato", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                runOnUiThread {
                    showEpgDialogUi(listings)
                }
            }

            override fun onFailure(call: Call<EpgResponse>, t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this@PlayerActivity, "Errore caricamento EPG", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun showEpgDialogUi(listings: List<EpgProgram>) {
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            setPadding(24, 24, 24, 24)
            clipToPadding = false
        }

        val adapter = GenericAdapter<EpgProgram>(
            layoutId = R.layout.item_epg_program,
            bind = { v, item ->
                val tvTime = v.findViewById<TextView>(R.id.tv_epg_time)
                val tvTitle = v.findViewById<TextView>(R.id.tv_epg_prog_title)
                val tvDesc = v.findViewById<TextView>(R.id.tv_epg_prog_desc)
                val btnItemReminder = v.findViewById<Button>(R.id.btn_item_reminder)

                tvTime.text = "${item.start ?: "--:--"} - ${item.end ?: "--:--"}"
                tvTitle.text = item.decodedTitle
                tvDesc.text = item.decodedDescription

                val eventId = item.id ?: "${currentMediaId}_${item.decodedTitle}"
                val isSet = prefs.isReminderSet(eventId)
                btnItemReminder.text = if (isSet) "🔔 Annulla" else "🔔 Ricorda"

                btnItemReminder.setOnClickListener {
                    val rawTs = item.startTimestamp ?: 0L
                    val startTime = if (rawTs > 10000000000L) rawTs else rawTs * 1000
                    val channelName = intent.getStringExtra("name") ?: "Canale TV"
                    val streamUrl = intent.getStringExtra("url") ?: ""
                    val currentId = currentMediaId ?: "unknown"

                    if (prefs.isReminderSet(eventId)) {
                        ReminderScheduler.cancelReminder(this, eventId)
                        btnItemReminder.text = "🔔 Ricorda"
                        Toast.makeText(this, "Promemoria rimosso", Toast.LENGTH_SHORT).show()
                    } else {
                        val reminderItem = ReminderItem(
                            eventId = eventId,
                            programTitle = item.decodedTitle,
                            channelId = currentId,
                            channelName = channelName,
                            streamUrl = streamUrl,
                            startTimeMillis = if (startTime > 0) startTime else System.currentTimeMillis() + 3600000
                        )
                        ReminderScheduler.scheduleReminder(this, reminderItem)
                        btnItemReminder.text = "🔔 Annulla"
                        Toast.makeText(this, "Promemoria impostato per: ${item.decodedTitle}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onClick = { program ->
                Toast.makeText(this, program.decodedTitle, Toast.LENGTH_SHORT).show()
            },
            enableZoom = false
        )

        rv.adapter = adapter
        adapter.update(listings)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Guida EPG - ${intent.getStringExtra("name") ?: "Canale TV"}")
            .setView(rv)
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun showEpgOverlay() {
        if (isFinishing || isDestroyed) return
        playerView.showController()
        playerView.post {
            val btnPlayPause = playerView.findViewById<View>(R.id.btn_play_pause)
            btnPlayPause?.requestFocus()
        }
    }

    private fun hideEpgOverlay() {
        playerView.hideController()
    }

    private fun applyNightMode(active: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "Modalità Notte non supportata su questo dispositivo", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val sessionId = player?.audioSessionId ?: return
            if (sessionId == C.AUDIO_SESSION_ID_UNSET) return

            if (active) {
                if (dynamicsProcessing == null) {
                    val config = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2, // channels
                        true, 1, // preEq
                        true, 1, // mbc
                        true, 1, // postEq
                        true // limiter
                    ).build()

                    dynamicsProcessing = DynamicsProcessing(0, sessionId, config)

                    val limiter = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -20f, 0f)
                    dynamicsProcessing?.setLimiterByChannelIndex(0, limiter)
                    dynamicsProcessing?.setLimiterByChannelIndex(1, limiter)
                }
                dynamicsProcessing?.enabled = true
                isNightModeActive = true
                Toast.makeText(this, "Modalità Notte Attiva (Audio Bilanciato)", Toast.LENGTH_SHORT).show()
            } else {
                dynamicsProcessing?.enabled = false
                isNightModeActive = false
                Toast.makeText(this, "Modalità Notte Disattivata", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("BocciaTV", "Error applying Night Mode", e)
        }
    }

    private fun applyVoiceBoost(active: Boolean) {
        try {
            val sessionId = player?.audioSessionId ?: return
            if (sessionId == C.AUDIO_SESSION_ID_UNSET) return

            if (active) {
                if (loudnessEnhancer == null) {
                    loudnessEnhancer = LoudnessEnhancer(sessionId)
                }
                loudnessEnhancer?.setTargetGain(500) // +500mB (+5 dB) for voice boost
                loudnessEnhancer?.enabled = true
                isVoiceBoostActive = true
                prefs.isVoiceBoost = true
                Toast.makeText(this, "Voice Boost Attivo (+500mB)", Toast.LENGTH_SHORT).show()
            } else {
                loudnessEnhancer?.enabled = false
                isVoiceBoostActive = false
                prefs.isVoiceBoost = false
                Toast.makeText(this, "Voice Boost Disattivato", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("BocciaTV", "Error applying Voice Boost", e)
        }
    }

    private fun showSettingsMenu() {
        val options = arrayOf(
            "Sottotitoli",
            "Velocità Riproduzione",
            "Zoom Video",
            if (isNightModeActive) "Disattiva Modalità Notte" else "Attiva Modalità Notte",
            if (isVoiceBoostActive) "Disattiva Voice Boost" else "Attiva Voice Boost"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Impostazioni Video")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSubtitleDialog()
                    1 -> showSpeedDialog()
                    2 -> showZoomDialog()
                    3 -> applyNightMode(!isNightModeActive)
                    4 -> applyVoiceBoost(!isVoiceBoostActive)
                }
            }
            .show()
    }

    private fun showSubtitleDialog() {
        val tracks = player?.currentTracks ?: return
        val textTracks = mutableListOf<Tracks.Group>()
        val labels = mutableListOf<String>()

        labels.add("Disabilita Sottotitoli")

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                textTracks.add(group)
                val format = group.getTrackFormat(0)
                val label = format.language ?: "Traccia ${textTracks.size}"
                labels.add(label)
            }
        }

        if (textTracks.isEmpty()) {
            Toast.makeText(this, "Nessun sottotitolo disponibile", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Scegli Sottotitoli")
            .setItems(labels.toTypedArray()) { _, which ->
                val params = player?.trackSelectionParameters?.buildUpon() ?: return@setItems
                if (which == 0) {
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val selectedGroup = textTracks[which - 1]
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    params.addOverride(TrackSelectionOverride(selectedGroup.mediaTrackGroup, 0))
                }
                player?.trackSelectionParameters = params.build()
            }
            .show()
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x (Normale)", "1.25x", "1.5x", "2.0x")
        val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Velocità Riproduzione")
            .setItems(speeds) { _, which ->
                player?.setPlaybackSpeed(speedValues[which])
                Toast.makeText(this, "Velocità impostata a ${speeds[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showZoomDialog() {
        val modes = arrayOf("Adatta (Fit)", "Riempi (Fill)", "Zoom", "Allunga (Stretch)")
        val modeValues = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Zoom Video")
            .setItems(modes) { _, which ->
                playerView.resizeMode = modeValues[which]
                Toast.makeText(this, "Modalità: ${modes[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun findControllerView(idName: String): View? {
        val resId = resources.getIdentifier(idName, "id", packageName)
        return if (resId != 0) playerView.findViewById(resId) else null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        player?.let {
            if (!playerView.isControllerFullyVisible) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_INFO -> {
                        showEpgOverlay()
                        val playBtn = playerView.findViewById<View>(R.id.btn_play_pause)
                        playBtn?.requestFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        showSettingsMenu()
                        return true
                    }
                }
            } else {
                showEpgOverlay()
                val progressTimeBar = findControllerView("exo_progress")
                val playPauseBtn = playerView.findViewById<View>(R.id.btn_play_pause)

                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (progressTimeBar != null && progressTimeBar.hasFocus()) {
                        return super.onKeyDown(keyCode, event)
                    }
                    val focused = currentFocus
                    if (focused != null) {
                        focused.performClick()
                        return true
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (playPauseBtn != null && playPauseBtn.hasFocus()) {
                        progressTimeBar?.requestFocus()
                        return true
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (progressTimeBar != null && progressTimeBar.hasFocus()) {
                        playPauseBtn?.requestFocus()
                        return true
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (progressTimeBar != null && progressTimeBar.hasFocus()) {
                        player?.let { p ->
                            val currentPos = p.currentPosition
                            val duration = p.duration
                            val step = 15000L // 15 seconds per click
                            val newPos = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                                minOf(duration, currentPos + step)
                            } else {
                                maxOf(0L, currentPos - step)
                            }
                            p.seekTo(newPos)
                            Toast.makeText(this, formatTime(newPos), Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                } else if (keyCode == KeyEvent.KEYCODE_MENU) {
                    showSettingsMenu()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = ms / 1000
        val hours = totalSecs / 3600
        val minutes = (totalSecs % 3600) / 60
        val seconds = totalSecs % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun finish() {
        playerView.hideController()
        playerView.player = null
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        } catch (_: Exception) {}
        player?.stop()
        player?.release()
        player = null
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        saveCurrentPosition()
        dynamicsProcessing?.release()
        dynamicsProcessing = null
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        } catch (_: Exception) {}
        playerView.hideController()
        playerView.player = null
        player?.stop()
        player?.release()
        player = null
    }

    private fun checkIntroAndBingeWatching() {
        val player = player ?: return
        val pos = player.currentPosition
        val duration = player.duration

        // 1. Skip Intro Logic
        val btnSkipIntro = findViewById<Button>(R.id.btn_skip_intro)
        if (!hasSkippedIntro && pos >= introStartTime && pos <= introEndTime) {
            btnSkipIntro?.visibility = View.VISIBLE
        } else {
            btnSkipIntro?.visibility = View.GONE
        }

        // 2. Next Episode / Binge Watching Logic
        val llBinge = findViewById<View>(R.id.ll_next_episode_overlay)
        val tvCountdown = findViewById<TextView>(R.id.tv_next_episode_countdown)

        if (duration > 0 && (duration - pos) <= 40000L) {
            val secondsLeft = ((duration - pos) / 1000).coerceAtLeast(0)
            tvCountdown?.text = "Prossimo episodio in ${secondsLeft}s..."
            if (llBinge?.visibility != View.VISIBLE) {
                llBinge?.visibility = View.VISIBLE
                llBinge?.requestFocus()
            }

            if (secondsLeft <= 1 && !isBingeActive) {
                isBingeActive = true
                playNextEpisodeOrFinish()
            }
        } else {
            if (llBinge?.visibility == View.VISIBLE && !isBingeActive) {
                llBinge?.visibility = View.GONE
            }
        }
    }

    private fun playNextEpisodeOrFinish() {
        val player = player ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            hasSkippedIntro = false
            isBingeActive = false
            findViewById<View>(R.id.ll_next_episode_overlay)?.visibility = View.GONE
            Toast.makeText(this, "Riproduzione prossimo episodio...", Toast.LENGTH_SHORT).show()
        } else {
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
        player?.pause()
    }
}
