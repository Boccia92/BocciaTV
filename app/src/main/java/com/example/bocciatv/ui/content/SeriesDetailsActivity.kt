package com.example.bocciatv.ui.content

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bocciatv.R
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.model.Episode
import com.example.bocciatv.data.model.SeriesInfoResponse
import com.example.bocciatv.data.network.NetworkModule
import com.example.bocciatv.ui.adapter.GenericAdapter
import com.example.bocciatv.ui.player.PlayerActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

class SeriesDetailsActivity : FragmentActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var seasonAdapter: GenericAdapter<String>
    private lateinit var episodeAdapter: GenericAdapter<Episode>
    private var allEpisodes: Map<String, List<Episode>> = emptyMap()
    private var currentSeasonKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val locale = Locale.ITALY
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series_details)
        prefs = PrefsManager(this)

        val seriesId = intent.getStringExtra("series_id") ?: return
        val seriesName = intent.getStringExtra("name") ?: "Serie TV"

        findViewById<TextView>(R.id.tv_series_name).text = seriesName

        setupLists()
        loadSeriesInfo(seriesId)
    }

    override fun onResume() {
        super.onResume()
        episodeAdapter.notifyDataSetChanged()
    }

    private fun setupLists() {
        val rvSeasons = findViewById<RecyclerView>(R.id.rv_seasons)
        seasonAdapter = GenericAdapter(R.layout.item_simple, { v, item ->
            v.findViewById<TextView>(R.id.tv_name).text = "Stagione $item"
        }, { seasonKey ->
            showEpisodes(seasonKey)
        })
        rvSeasons.layoutManager = LinearLayoutManager(this)
        rvSeasons.adapter = seasonAdapter

        val rvEpisodes = findViewById<RecyclerView>(R.id.rv_episodes)
        episodeAdapter = GenericAdapter(R.layout.item_simple, { v, item ->
            val tv = v.findViewById<TextView>(R.id.tv_name)
            tv.text = item.title ?: "Episodio"
            
            if (prefs.isWatched(item.id ?: "unknown")) {
                tv.alpha = 0.5f
                tv.text = "✔️ ${item.title}"
            } else if (prefs.getPosition(item.id ?: "unknown") > 0) {
                tv.alpha = 0.8f
                tv.text = "🕒 ${item.title}"
            } else {
                tv.alpha = 1.0f
            }
            
        }, { episode ->
            val seasonEpisodes = allEpisodes[currentSeasonKey] ?: emptyList()
            val urls = seasonEpisodes.map { "http://latteax.securitysc.shop/series/${prefs.user}/${prefs.pass}/${it.id}.${it.extension ?: "mp4"}" }
            val ids = seasonEpisodes.map { it.id ?: "" }
            val startIndex = seasonEpisodes.indexOf(episode)

            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("urls", urls.toTypedArray())
                putExtra("ids", ids.toTypedArray())
                putExtra("index", startIndex)
            }
            startActivity(intent)
        })
        rvEpisodes.layoutManager = LinearLayoutManager(this)
        rvEpisodes.adapter = episodeAdapter
    }

    private fun loadSeriesInfo(seriesId: String) {
        NetworkModule.api.getSeriesInfo(prefs.user, prefs.pass, seriesId = seriesId).enqueue(object : Callback<SeriesInfoResponse> {
            override fun onResponse(call: Call<SeriesInfoResponse>, response: Response<SeriesInfoResponse>) {
                val body = response.body()
                if (response.isSuccessful && body?.episodes != null) {
                    allEpisodes = body.episodes
                    val seasons = allEpisodes.keys.toList().sortedBy { it.toIntOrNull() ?: 0 }
                    runOnUiThread {
                        seasonAdapter.update(seasons)
                        if (seasons.isNotEmpty()) showEpisodes(seasons[0])
                    }
                }
            }
            override fun onFailure(call: Call<SeriesInfoResponse>, t: Throwable) {
                Toast.makeText(this@SeriesDetailsActivity, "Errore caricamento stagioni", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showEpisodes(seasonKey: String) {
        currentSeasonKey = seasonKey
        val episodes = (allEpisodes[seasonKey] ?: emptyList()).sortedBy { it.title?.lowercase() ?: "" }
        episodeAdapter.update(episodes)
    }
}
