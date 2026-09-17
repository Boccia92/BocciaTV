package com.example.bocciatv.ui.dashboard

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.bocciatv.R
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.model.Category
import com.example.bocciatv.data.model.StreamItem
import com.example.bocciatv.data.network.NetworkModule
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class PrototypeDashboardActivity : FragmentActivity() {

    private lateinit var heroBackdrop: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroPlot: TextView
    private lateinit var rvContent: RecyclerView
    private lateinit var prefs: PrefsManager

    private val sharedPool = RecyclerView.RecycledViewPool()
    private lateinit var mainAdapter: CategoryRowAdapter
    private var activeRailId: Int = R.id.rail_movies

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prototype_dashboard)
        
        prefs = PrefsManager(this)

        heroBackdrop = findViewById(R.id.hero_backdrop)
        heroTitle = findViewById(R.id.hero_title)
        heroMeta = findViewById(R.id.hero_meta)
        heroPlot = findViewById(R.id.hero_plot)

        rvContent = findViewById(R.id.rv_prototype_content)
        // La lista principale ora scrolla verticalmente (le categorie)
        rvContent.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        mainAdapter = CategoryRowAdapter(sharedPool) { item ->
            updateHeroInfo(item)
        }
        rvContent.adapter = mainAdapter

        setupFloatingRail()
        loadData("get_vod_categories", "get_vod_streams")
    }

    private fun setupFloatingRail() {
        val views = listOf(
            findViewById<LinearLayout>(R.id.rail_search),
            findViewById<LinearLayout>(R.id.rail_live),
            findViewById<LinearLayout>(R.id.rail_movies),
            findViewById<LinearLayout>(R.id.rail_series),
            findViewById<LinearLayout>(R.id.rail_favs),
            findViewById<LinearLayout>(R.id.rail_settings)
        )

        for (v in views) {
            v.setOnFocusChangeListener { view, hasFocus ->
                val icon = (view as? ViewGroup)?.getChildAt(0) as? ImageView
                val text = (view as? ViewGroup)?.getChildAt(1) as? TextView

                if (hasFocus) {
                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start()
                    icon?.setColorFilter(Color.WHITE)
                    text?.setTextColor(Color.WHITE)
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                    if (view.id != activeRailId) {
                        icon?.setColorFilter(Color.parseColor("#80FFFFFF"))
                        text?.setTextColor(Color.parseColor("#80FFFFFF"))
                    }
                }
            }

            v.setOnClickListener { view ->
                val prevActive = activeRailId
                activeRailId = view.id
                updateRailState(views)
                
                if (prevActive != activeRailId) {
                    when (activeRailId) {
                        R.id.rail_movies -> loadData("get_vod_categories", "get_vod_streams")
                        R.id.rail_series -> loadData("get_series_categories", "get_series")
                        R.id.rail_live -> loadData("get_live_categories", "get_live_streams")
                    }
                }
            }
        }
        
        updateRailState(views)
    }

    private fun updateRailState(views: List<LinearLayout>) {
        for (v in views) {
            val icon = v.getChildAt(0) as? ImageView
            val text = v.getChildAt(1) as? TextView

            if (v.id == activeRailId) {
                v.setBackgroundResource(R.drawable.rail_pill_bg)
                icon?.setColorFilter(Color.WHITE)
                text?.setTextColor(Color.WHITE)
            } else {
                v.setBackgroundResource(android.R.color.transparent)
                if (!v.hasFocus()) {
                    icon?.setColorFilter(Color.parseColor("#80FFFFFF"))
                    text?.setTextColor(Color.parseColor("#80FFFFFF"))
                }
            }
        }
    }

    private fun loadData(catAction: String, streamAction: String) {
        mainAdapter.submitList(emptyList()) // Svuota l'elenco e mostra la schermata pulita per il caricamento
        
        NetworkModule.api.getCategories(prefs.user, prefs.pass, catAction).enqueue(object : Callback<List<Category>> {
            override fun onResponse(call: Call<List<Category>>, catResponse: Response<List<Category>>) {
                val categories = catResponse.body() ?: emptyList()
                
                NetworkModule.api.getStreams(prefs.user, prefs.pass, streamAction, null).enqueue(object : Callback<List<StreamItem>> {
                    override fun onResponse(call: Call<List<StreamItem>>, response: Response<List<StreamItem>>) {
                        val streams = response.body() ?: emptyList()
                        
                        Thread {
                            // Raggruppa i flussi per categoria e crea un oggetto Riga
                            val rows = categories.mapNotNull { cat ->
                                val catStreams = streams.filter { it.categoryId == cat.id }
                                if (catStreams.isNotEmpty()) CategoryRow(cat.id ?: "", cat.name ?: "", catStreams) else null
                            }
                            
                            runOnUiThread {
                                mainAdapter.submitList(rows)
                                if (rows.isNotEmpty() && rows[0].items.isNotEmpty()) {
                                    updateHeroInfo(rows[0].items[0])
                                }
                            }
                        }.start()
                    }
                    override fun onFailure(call: Call<List<StreamItem>>, t: Throwable) {
                        runOnUiThread { Toast.makeText(this@PrototypeDashboardActivity, "Errore caricamento contenuti", Toast.LENGTH_SHORT).show() }
                    }
                })
            }
            override fun onFailure(call: Call<List<Category>>, t: Throwable) {
                runOnUiThread { Toast.makeText(this@PrototypeDashboardActivity, "Errore caricamento categorie", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun updateHeroInfo(item: StreamItem) {
        heroTitle.text = item.name ?: "Titolo Sconosciuto"
        heroMeta.text = "2023 • Film/Live • 120 min"
        heroPlot.text = "Guarda ${item.name} ora in streaming. Sperimenta l'emozione, l'avventura e molto altro direttamente a casa tua."

        val imageUrl = item.cover ?: item.icon
        if (!imageUrl.isNullOrEmpty()) {
            Glide.with(this).load(imageUrl).into(heroBackdrop)
        } else {
            heroBackdrop.setImageResource(R.drawable.movie)
        }
    }
}

// Struttura Dati per una Riga Categoria
data class CategoryRow(val id: String, val name: String, val items: List<StreamItem>)

// Adapter Principale (Verticale) che gestisce le singole Categorie
class CategoryRowAdapter(
    private val pool: RecyclerView.RecycledViewPool,
    private val onFocus: (StreamItem) -> Unit
) : RecyclerView.Adapter<CategoryRowAdapter.RowViewHolder>() {

    private var rows = emptyList<CategoryRow>()

    fun submitList(newRows: List<CategoryRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prototype_category_row, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount() = rows.size

    inner class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_category_name)
        private val rvItems: RecyclerView = itemView.findViewById(R.id.rv_row_items)
        private val adapter = DashboardAdapter()

        init {
            rvItems.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            rvItems.setRecycledViewPool(pool)
            adapter.onItemFocused = onFocus
            rvItems.adapter = adapter
        }

        fun bind(row: CategoryRow) {
            tvName.text = row.name
            adapter.submitList(row.items)
        }
    }
}

// Sub-Adapter (Orizzontale) che gestisce le singole Card dentro una Categoria
class DashboardAdapter : RecyclerView.Adapter<DashboardAdapter.ViewHolder>() {

    private var items = emptyList<StreamItem>()
    var onItemFocused: ((StreamItem) -> Unit)? = null

    fun submitList(newItems: List<StreamItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prototype_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.card_image)
        private val tvTitle: TextView = itemView.findViewById(R.id.card_title)
        private val gradient: View = itemView.findViewById(R.id.card_gradient)

        fun bind(item: StreamItem) {
            tvTitle.text = item.name ?: ""

            val imageUrl = item.cover ?: item.icon
            if (!imageUrl.isNullOrEmpty()) {
                Glide.with(itemView.context).load(imageUrl).into(ivCover)
            } else {
                ivCover.setImageResource(R.drawable.movie)
            }

            if (!itemView.hasFocus()) {
                tvTitle.alpha = 0f
                gradient.alpha = 0f
            }

            itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start()
                    tvTitle.animate().alpha(1f).setDuration(200).start()
                    gradient.animate().alpha(1f).setDuration(200).start()
                    onItemFocused?.invoke(item)
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                    tvTitle.animate().alpha(0f).setDuration(200).start()
                    gradient.animate().alpha(0f).setDuration(200).start()
                }
            }
        }
    }
}