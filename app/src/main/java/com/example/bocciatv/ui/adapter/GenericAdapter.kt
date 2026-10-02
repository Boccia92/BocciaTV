package com.example.bocciatv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.bocciatv.R
import com.example.bocciatv.data.model.Category
import com.example.bocciatv.data.model.StreamItem

fun interface ViewHolderBinder<T> {
    fun bind(holder: GenericAdapter.ViewHolder, item: T)
}

class GenericAdapter<T> : RecyclerView.Adapter<GenericAdapter.ViewHolder> {

    private val layoutId: Int
    private val bindHolder: ViewHolderBinder<T>?
    private val bindView: ((View, T) -> Unit)?
    private val onClick: (T) -> Unit
    private val onFocus: ((T) -> Unit)?
    private val onLongClick: ((T) -> Unit)?
    private val enableZoom: Boolean
    private val onFocusChange: ((View, Boolean, T) -> Unit)?

    var items = emptyList<T>()

    constructor(
        layoutId: Int,
        bind: ViewHolderBinder<T>,
        onClick: (T) -> Unit,
        onFocus: ((T) -> Unit)? = null,
        onLongClick: ((T) -> Unit)? = null,
        enableZoom: Boolean = true,
        onFocusChange: ((View, Boolean, T) -> Unit)? = null,
    ) {
        this.layoutId = layoutId
        this.bindHolder = bind
        this.bindView = null
        this.onClick = onClick
        this.onFocus = onFocus
        this.onLongClick = onLongClick
        this.enableZoom = enableZoom
        this.onFocusChange = onFocusChange
    }

    constructor(
        layoutId: Int,
        bind: (View, T) -> Unit,
        onClick: (T) -> Unit,
        onFocus: ((T) -> Unit)? = null,
        onLongClick: ((T) -> Unit)? = null,
        enableZoom: Boolean = true,
        onFocusChange: ((View, Boolean, T) -> Unit)? = null,
    ) {
        this.layoutId = layoutId
        this.bindHolder = null
        this.bindView = bind
        this.onClick = onClick
        this.onFocus = onFocus
        this.onLongClick = onLongClick
        this.enableZoom = enableZoom
        this.onFocusChange = onFocusChange
    }

    private fun getItemId(item: Any?): Any {
        when (item) {
            is StreamItem -> return item.streamId ?: item.seriesId ?: item.name ?: item
            is Category -> return item.id ?: item.name ?: item
        }
        return item ?: 0
    }

    fun replaceAll(newItems: List<T>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun update(newItems: List<T>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = items[oldItemPosition]
                val newItem = newItems[newItemPosition]
                return getItemId(oldItem) == getItemId(newItem)
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition] == newItems[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        if (bindHolder != null) {
            bindHolder.bind(holder, item)
        } else {
            bindView?.invoke(holder.itemView, item)
        }
        
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val currentItem = items.getOrNull(pos)
                if (currentItem != null) {
                    holder.itemView.requestFocus()
                    onClick(currentItem)
                }
            }
        }
        
        holder.itemView.setOnLongClickListener { 
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val currentItem = items.getOrNull(pos)
                if (currentItem != null) {
                    onLongClick?.invoke(currentItem)
                }
            }
            true
        }
        
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (enableZoom) {
                v.animate().cancel()
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
            }
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val currentItem = items.getOrNull(pos)
                if (currentItem != null) {
                    if (hasFocus) onFocus?.invoke(currentItem)
                    onFocusChange?.invoke(v, hasFocus, currentItem)
                }
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        val img = holder.itemView.findViewById<ImageView?>(R.id.iv_thumb)
        if (img != null) {
            try {
                Glide.with(holder.itemView.context).clear(img)
            } catch (_: Exception) {}
        }
    }

    override fun getItemCount() = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cache = mutableMapOf<Int, View>()

        @Suppress("UNCHECKED_CAST")
        fun <V : View> findViewById(id: Int): V {
            return cache.getOrPut(id) { itemView.findViewById<View>(id) } as V
        }
    }
}
