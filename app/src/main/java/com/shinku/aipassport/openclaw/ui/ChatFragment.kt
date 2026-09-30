package com.shinku.aipassport.openclaw.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.shinku.aipassport.openclaw.R
import com.shinku.aipassport.openclaw.databinding.FragmentChatBinding
import com.shinku.aipassport.openclaw.gateway.GatewayAdapter
import com.shinku.aipassport.openclaw.gateway.GatewayFactory
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import com.shinku.aipassport.openclaw.gateway.RawEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网关对话界面:用户文本气泡 + 网关回复气泡(双向)。
 *
 * 历史由 [ConversationStore] 统一管理并持久化:
 *  - 打字消息走 [ConversationStore.add]("text"),与硬件语音("voice")共用同一条历史。
 *  - Adapter 直接 [setMessages] 于 store 的 StateFlow 快照(collect 实时刷新),
 *    因此切回本 Tab、或硬件语音在后台产生新消息,列表都会立刻出现。
 */
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val adapter = ChatAdapter()

    private lateinit var gateway: GatewayAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = GatewaySettings(requireContext())
        // 对话框也跟随设置里的网关类型(与硬件语音保持一致)
        gateway = GatewayFactory.create(requireContext(), settings)

        ConversationStore.init(requireContext())
        binding.chatList.layoutManager = LinearLayoutManager(requireContext())
        binding.chatList.adapter = adapter

        // 实时订阅共享历史:打字、硬件语音、占位替换都会触发这里刷新
        viewLifecycleOwner.lifecycleScope.launch {
            ConversationStore.messages.collectLatest { list ->
                adapter.setMessages(list)
                if (list.isNotEmpty()) binding.chatList.scrollToPosition(list.size - 1)
            }
        }

        binding.btnSend.setOnClickListener { send() }
        binding.chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }

        // 输入法弹出时把底部输入行抬到键盘上方(adjustResize 之外的兜底,横竖/沉浸都稳)。
        // 监听 ime insets,把输入行 bottom 设为屏底减 ime 高度;聊天列表随之被压缩,输入框不被遮挡。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // 输入行 bottom padding = ime 高度,列表受到足够空间
            val inputRow = binding.root.findViewById<View>(R.id.chatInputRow)
            inputRow?.setPadding(0, 0, 0, ime.bottom)
            insets
        }
    }

    private fun send() {
        val text = binding.chatInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.chatInput.setText("")
        // 用户消息立即入历史;占位"…"先入历史,网关返回后按 id 替换成真实回复。
        ConversationStore.add("user", text, ConversationStore.SOURCE_TEXT)
        val placeholderId =
            ConversationStore.add("agent", "…", ConversationStore.SOURCE_TEXT)
        // 本轮正文气泡记账:「…」占位气泡就是本轮正文气泡的槽位,历史补正到达时**就地替换**它,
        // 不再追加一个新气泡(否则一轮里会出现「[流式] + [来自历史]」两个正常正文气泡)。
        val bodies = BodyBubbles()
        bodies.onBodyWritten(placeholderId)
        // 「App 显示完整回传流(调试)」在主线程读一次给迟到帧增量回调用:
        // 回调可能在网关线程执行,那时 Fragment 可能已 detach,不能在里面 requireContext()。
        val lateRawShowRaw = GatewaySettings(requireContext()).showRawStream

        viewLifecycleOwner.lifecycleScope.launch {
            // 一轮可能有多条回复(OpenClaw:答案 + 后续状态消息),用 chatMulti 全部拿到。
            // 终局宽限窗内迟到的帧经回调增量补进 App 列表;这里不等宽限窗,body 到手就上屏。
            val reply = withContext(Dispatchers.IO) {
                gateway.chatMulti(
                    text,
                    onRawUpdate = { added -> appendLateRaw(added, lateRawShowRaw) },
                    // 流式帧只推最后一条 assistant 消息(常是状态话术):网关用 chat.history
                    // 补正出真正的答案后,把本轮正文气泡**就地替换**成 [来自历史] 正文
                    // (与语音路径同一套规则;不再追加第二个正文气泡)。
                    onBodyCorrection = { corrected -> replaceBodyBubble(bodies, corrected) },
                )
            }
            val err = reply.error?.trim()?.takeIf { it.isNotEmpty() }
                ?: gateway.lastError?.trim()?.takeIf { it.isNotEmpty() }
            val messages = reply.messages
            if (messages.isEmpty()) {
                // 失败原因优先用网关的可读描述(连接中断/超时…),不再只写一句「(网关无回复)」
                val shown = err ?: if (reply.error == null) "(网关空回复)" else "(网关无回复)"
                // 补正已经就地落地(占位气泡里是 [来自历史] 的真正答案)→ 不要被空回复/失败文案冲掉;
                // 补正还没到 → 占位气泡已被登记为本轮正文气泡槽位,它晚到时仍会就地替换这一条。
                if (!bodies.isCorrected) ConversationStore.replaceById(placeholderId, shown)
                return@launch
            }
            // 第一条占用「…」占位气泡,其余逐条新增一个气泡,顺序与到达一致。
            // body / raw 双路:开关开启时展示 raw 全部条目(状态/步骤/工具输出…
            // 弱化小字,正文正常气泡);关闭时只展示 body(正式演示用)。
            val showRaw = GatewaySettings(requireContext()).showRawStream
            // 排查用:raw 条目的 kind/seq(验证是否按 seq 升序、网关是否多轮重试)
            if (android.util.Log.isLoggable("ChatFragment", android.util.Log.DEBUG)) {
                reply.raw.forEach { e ->
                    android.util.Log.d(
                        "ChatFragment",
                        "raw 条目 kind=${e.kind} seq=${e.seq} label=${e.label} 文本长度=${e.text.length}",
                    )
                }
            }
            writeReplyDisplay(
                replyDisplayEntries(messages, reply.raw, showRaw),
                placeholderId,
                bodies,
                ConversationStore.SOURCE_TEXT,
            )
            // 超时/中断但已收到部分消息:消息照常上屏,只把原因另起一条提醒
            if (err != null) {
                ConversationStore.add("agent", err, ConversationStore.SOURCE_TEXT)
            }
        }
    }

    /**
     * 终局宽限窗内到达的 raw **增量**条目:按与首批完全相同的规则追加到 App 对话列表
     * (正文正常气泡、其它条目弱化小字标签、命中疑似状态话术只加标记)。
     *
     * 增量来源包括 `chat.history` 补进来的本轮历史条目(标签 `正文(历史) · 全文` 等,
     * 追加在流式条目之后)。
     *
     * 只影响 App 展示:设备屏与 TTS 只吃 body,不走这里也不受宽限窗影响。
     * 回调可能在网关线程执行,因此只用线程安全的 [ConversationStore],不碰 View/Context。
     */
    private fun appendLateRaw(added: List<RawEntry>, showRaw: Boolean) {
        if (added.isEmpty() || !showRaw) return
        replyDisplayEntries(emptyList(), added, true).forEach { entry ->
            val shown =
                if (entry.label == null && entry.text.isBlank()) "(网关空回复)" else entry.text
            ConversationStore.add(
                "agent",
                shown,
                ConversationStore.SOURCE_TEXT,
                label = entry.label,
                flag = entry.flag,
            )
        }
    }

    /**
     * 用 `chat.history` 补正的正文(流式帧只推了状态话术时,真正的答案在这里):
     * 把本轮正文气泡**就地替换**成 [来自历史] 正文([ConversationStore.replaceById]),
     * **不再追加第二个正文气泡** —— 与语音路径同一套规则,保证同一轮只有一个正常正文气泡;
     * raw 弱化条目仍由 [appendLateRaw] / [writeReplyDisplay] 照常追加。
     * 这是正文而不是调试流,所以与「App 显示完整回传流(调试)」开关无关。
     * 只有「历史正文与已下发正文不同」才会被调到(网关侧已按文本去重),不会重复上屏。
     * 回调可能在网关线程执行:只用线程安全的 [ConversationStore],不碰 View/Context。
     */
    private fun replaceBodyBubble(bodies: BodyBubbles, corrected: String) {
        val text = corrected.trim()
        if (text.isEmpty()) return
        val plan = bodies.onCorrection()
        android.util.Log.i(
            "ChatFragment",
            if (plan is BodyCorrectionPlan.ReplaceInPlace) {
                "历史补正: 就地替换正文气泡(${text.length} 字,不新增气泡)"
            } else {
                "历史补正: 新增正文气泡(${text.length} 字)"
            },
        )
        writeBodyCorrection(plan, text, ConversationStore.SOURCE_TEXT)
    }

    override fun onDestroyView() {
        gateway.close()
        _binding = null
        super.onDestroyView()
    }
}
