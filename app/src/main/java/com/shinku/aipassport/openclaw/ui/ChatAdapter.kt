package com.shinku.aipassport.openclaw.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.shinku.aipassport.openclaw.R

/**
 * 聊天消息列表:用户/网关双向气泡。
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    data class Message(val role: String, val text: String)

    private val items = mutableListOf<Message>()

    fun currentMessages(): List<Message> = items.toList()

    fun setMessages(list: List<Message>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(role: String, text: String) {
        items.add(Message(role, text))
        notifyItemInserted(items.size - 1)
    }

    /** 把最后一条消息替换为给定文本(用于占位"…"→ 真实回复)。 */
    fun replaceLast(text: String) {
        if (items.isEmpty()) return
        items[items.size - 1] = items[items.size - 1].copy(text = text)
        notifyItemChanged(items.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        val isUser = msg.role == "user"
        holder.role.text = if (isUser) "我" else "网关"
        holder.bubble.text = msg.text
        holder.bubble.setBackgroundResource(
            if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_agent
        )
        holder.bubble.setTextColor(
            ContextCompat.getColor(holder.bubble.context, android.R.color.white)
        )
        val lp = holder.bubble.layoutParams as ViewGroup.MarginLayoutParams
        lp.marginStart = if (isUser) 80.dp(holder.itemView) else 0
        lp.marginEnd = if (isUser) 0 else 80.dp(holder.itemView)
        holder.bubble.layoutParams = lp
    }

    private fun Int.dp(view: View): Int =
        (this * view.resources.displayMetrics.density).toInt()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val role: TextView = view.findViewById(R.id.chatRole)
        val bubble: TextView = view.findViewById(R.id.chatBubble)
    }
}
