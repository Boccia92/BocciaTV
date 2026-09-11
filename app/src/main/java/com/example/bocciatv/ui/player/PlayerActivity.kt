package com.example.bocciatv.ui.player

import android.app.AlertDialog
import android.media.audiofx.DynamicsProcessing
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import com.example.bocciatv.R
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.model.EpgResponse
import com.example.bocciatv.data.network.NetworkModule
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

    // EPG Overlay Views
    private lateinit var llEpgOverlay: LinearLayout
    private lateinit var tvEpgChannelName: TextView
    private lateinit var tvEpgCurrentTitle: TextView
    private lateinit var tvEpgTime: TextView
    private lateinit var tvEpgDescription: TextView
    private lateinit var tvEpgNextTitle: TextView

    // Audio normalization (Night Mode)
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var isNightModeActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            saveCurrentPosition()
            handler.postDelayed(this, 5000)
        }
    }

    private val hideEpgRunnable = Runnable {
        llEpgOverlay.visibility = View.GONE
    }

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

        playerView = findViewById(R.id.player_view)

        // EPG Views
        llEpgOverlay = findViewById(R.id.ll_epg_overlay)
        tvEpgChannelName = findViewById(R.id.tv_epg_channel_name)
        tvEpgCurrentTitle = findViewById(R.id.tv_epg_current_title)
        tvEpgTime = findViewById(R.id.tv_epg_time)
        tvEpgDescription = findViewById(R.id.tv_epg_description)
        tvEpgNextTitle = findViewById(R.id.tv_epg_next_title)

        playerView.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                if (visibility == View.VISIBLE) {
                    val settingsBtn = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
                    settingsBtn?.setOnClickListener { showSettingsMenu() }
                }
            }
        })

        val urlsArray = intent.getStringArrayExtra("urls")
        val idsArray = intent.getStringArrayExtra("ids")
        val startIndex = intent.getIntExtra("index", 0)
        val channelName = intent.getStringExtra("name") ?: "Canale TV"

        tvEpgChannelName.text = channelName

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
            })

            it.prepare()
            it.playWhenReady = true
        }
        handler.post(progressUpdater)
    }

    private fun loadEpgInfo(streamId: String) {
        if (streamId == "unknown" || streamId.isEmpty()) return

        NetworkModule.api.getShortEpg(prefs.user, prefs.pass, streamId = streamId).enqueue(object : Callback<EpgResponse> {
            override fun onResponse(call: Call<EpgResponse>, response: Response<EpgResponse>) {
                val listings = response.body()?.epgListings ?: emptyList()
                if (listings.isNotEmpty()) {
                    val current = listings[0]
                    val next = listings.getOrNull(1)

                    runOnUiThread {
                        tvEpgCurrentTitle.text = "In onda: ${current.decodedTitle}"
                        tvEpgTime.text = "${current.start ?: "--:--"} - ${current.end ?: "--:--"}"
                        tvEpgDescription.text = current.decodedDescription

                        if (next != null) {
                            tvEpgNextTitle.text = "A seguire: ${next.decodedTitle} (${next.start ?: ""})"
                            tvEpgNextTitle.visibility = View.VISIBLE
                        } else {
                            tvEpgNextTitle.visibility = View.GONE
                        }

                        showEpgOverlay()
                    }
                }
            }

            override fun onFailure(call: Call<EpgResponse>, t: Throwable) {
                Log.e("BocciaTV", "EPG fetch error: ${t.message}")
            }
        })
    }

    private fun showEpgOverlay() {
        llEpgOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideEpgRunnable)
        handler.postDelayed(hideEpgRunnable, 6000)
    }

    private fun applyNightMode(active: Boolean) {
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

    private fun showSettingsMenu() {
        val options = arrayOf(
            "Sottotitoli",
            "Velocità Riproduzione",
            "Zoom Video",
            if (isNightModeActive) "Disattiva Modalità Notte" else "Attiva Modalità Notte"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Impostazioni Video")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSubtitleDialog()
                    1 -> showSpeedDialog()
                    2 -> showZoomDialog()
                    3 -> applyNightMode(!isNightModeActive)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        player?.let {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                    showEpgOverlay()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    it.seekTo(it.currentPosition + 10000)
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    it.seekTo(it.currentPosition - 10000)
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (it.isPlaying) it.pause() else it.play()
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU -> {
                    showSettingsMenu()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPosition()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(hideEpgRunnable)
        dynamicsProcessing?.release()
        player?.release()
        player = null
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
        player?.pause()
    }
}
