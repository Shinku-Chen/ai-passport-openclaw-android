package com.shinku.aipassport.openclaw.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.shinku.aipassport.openclaw.R

/**
 * 聊天消息列表:用户/网关双向气泡,来源标记(语音/文字)。
 * 数据完全由 [ConversationStore] 的 StateFlow 驱动——Adapter 只负责渲染,
 * 每次 [setMessages] 全量刷新(历史 ≤200 条,成本可忽略),不做内存缓存。
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private var items: List<ConversationStore.Message> = emptyList()

    /** 用共享存储的最新历史整体刷新。 */
    fun setMessages(list: List<ConversationStore.Message>) {
        items = list
        notifyDataSetChanged()
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
        // 来源标签:语音走硬件,文字走对话框——让用户一眼看出这条是"说"的还是"打字"的
        val sourceLabel = if (msg.source == ConversationStore.SOURCE_VOICE) "语音" else "文字"
        holder.role.text = if (isUser) "我($sourceLabel)" else "网关($sourceLabel)"
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
