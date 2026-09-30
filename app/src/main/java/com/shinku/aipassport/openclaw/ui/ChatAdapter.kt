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
        // raw 回传条目(label 非 null)= 弱化小字样式;body 正文 = 正常气泡
        val isWeak = msg.label != null
        if (isWeak) {
            holder.role.text = msg.label
            holder.bubble.setBackgroundResource(0)
            holder.bubble.setTextColor(WEAK_TEXT_COLOR)
            holder.bubble.textSize = 12f
        } else {
            holder.role.text = if (isUser) "我($sourceLabel)" else "网关($sourceLabel)"
            holder.bubble.setBackgroundResource(
                if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_agent
            )
            holder.bubble.setTextColor(
                ContextCompat.getColor(holder.bubble.context, android.R.color.white)
            )
            holder.bubble.textSize = 15f
        }
        // 「疑似状态话术」只加标记,不改文本(msg.text 原样保留)
        holder.bubble.text = if (msg.flag != null) "${msg.text}\n${msg.flag}" else msg.text
        // 语音输入消息:在用户气泡下方另起一行弱化小字,展示实际附加给网关的提示词。
        // 只有语音输入才会带 appliedSuffix(文字输入不追加提示词),因此无需额外判断来源。
        val suffix = msg.appliedSuffix?.trim()?.takeIf { it.isNotEmpty() }
        holder.suffix.text = if (suffix != null) "＋附加提示：$suffix" else ""
        holder.suffix.visibility = if (isUser && suffix != null) View.VISIBLE else View.GONE
        // 气泡左右对齐:用户消息靠右、网关消息靠左(通过 FrameLayout 的 layout_gravity);弱化条目一律靠左
        val flp = holder.bubble.layoutParams as android.widget.FrameLayout.LayoutParams
        flp.gravity = if (isUser && !isWeak) android.view.Gravity.END else android.view.Gravity.START
        holder.bubble.layoutParams = flp
    }

    private companion object {
        /** raw 回传条目的弱化字色(与 role/suffix 小字同色)。 */
        val WEAK_TEXT_COLOR = 0xFF9E9E9E.toInt()
    }

    private fun Int.dp(view: View): Int =
        (this * view.resources.displayMetrics.density).toInt()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val role: TextView = view.findViewById(R.id.chatRole)
        val bubble: TextView = view.findViewById(R.id.chatBubble)
        val suffix: TextView = view.findViewById(R.id.chatSuffix)
    }
}
