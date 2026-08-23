package com.shinku.aipassport.openclaw.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shinku.aipassport.openclaw.R
import com.shinku.aipassport.openclaw.gateway.GatewayApi.NotificationItem

/**
 * 通知同步列表。
 */
class NotificationAdapter : RecyclerView.Adapter<NotificationAdapter.VH>() {

    private val items = mutableListOf<NotificationItem>()

    fun set(list: List<NotificationItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val n = items[position]
        holder.title.text = n.title
        holder.detail.text = n.detail
        holder.time.text = n.time
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.notifTitle)
        val detail: TextView = view.findViewById(R.id.notifDetail)
        val time: TextView = view.findViewById(R.id.notifTime)
    }
}
