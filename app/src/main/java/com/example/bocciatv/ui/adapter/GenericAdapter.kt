package com.example.bocciatv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.bocciatv.R

class GenericAdapter<T>(
    private val layoutId: Int,
    private val bind: (View, T) -> Unit,
    private val onClick: (T) -> Unit,
    private val onFocus: ((T) -> Unit)? = null,
    private val onLongClick: ((T) -> Unit)? = null,
    private val enableZoom: Boolean = true,
    private val onFocusChange: ((View, Boolean, T) -> Unit)? = null,
) : RecyclerView.Adapter<GenericAdapter.ViewHolder>() {

    var items = emptyList<T>()

    fun update(newItems: List<T>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        bind(holder.itemView, item)
        
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
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
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

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
