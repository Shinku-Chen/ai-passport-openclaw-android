package com.shinku.aipassport.openclaw.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shinku.aipassport.openclaw.R
import com.shinku.aipassport.openclaw.gateway.GatewayApi.AgentSummary

/**
 * Agent 列表。
 */
class AgentAdapter : RecyclerView.Adapter<AgentAdapter.VH>() {

    private val items = mutableListOf<AgentSummary>()

    fun set(list: List<AgentSummary>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_agent, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        holder.name.text = a.name
        holder.id.text = a.id
        holder.status.text = "状态: ${a.status}"
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.agentName)
        val id: TextView = view.findViewById(R.id.agentId)
        val status: TextView = view.findViewById(R.id.agentStatus)
    }
}
