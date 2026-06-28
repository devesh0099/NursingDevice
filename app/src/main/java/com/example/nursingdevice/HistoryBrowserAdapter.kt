package com.example.nursingdevice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class HistoryBrowserItem {
    data class DateFolder(val date: String, val count: Int) : HistoryBrowserItem()
    data class RecordFile(val title: String, val content: String, val updatedAt: Long) : HistoryBrowserItem()
}

class HistoryBrowserAdapter(
    private var items: List<HistoryBrowserItem>,
    private val onFolderClick: (HistoryBrowserItem.DateFolder) -> Unit,
    private val onRecordClick: (HistoryBrowserItem.RecordFile) -> Unit
) : RecyclerView.Adapter<HistoryBrowserAdapter.HistoryViewHolder>() {

    class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.historyItemIcon)
        val title: TextView = view.findViewById(R.id.historyItemTitle)
        val details: TextView = view.findViewById(R.id.historyItemDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_browser, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        when (val item = items[position]) {
            is HistoryBrowserItem.DateFolder -> {
                holder.icon.setImageResource(android.R.drawable.ic_menu_agenda)
                holder.title.text = item.date
                holder.details.text = "${item.count} record(s)"
                holder.itemView.setOnClickListener { onFolderClick(item) }
            }

            is HistoryBrowserItem.RecordFile -> {
                holder.icon.setImageResource(android.R.drawable.ic_menu_gallery)
                holder.title.text = item.title
                val sizeKB = item.content.toByteArray().size / 1024
                val modifiedDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                    .format(Date(item.updatedAt))
                holder.details.text = "$sizeKB KB • $modifiedDate"
                holder.itemView.setOnClickListener { onRecordClick(item) }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<HistoryBrowserItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
