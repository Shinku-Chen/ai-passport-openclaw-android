package com.shinku.aipassport.openclaw.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.shinku.aipassport.openclaw.databinding.FragmentChatBinding
import com.shinku.aipassport.openclaw.gateway.GatewayClient
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
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

    private lateinit var gateway: GatewayClient

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
        gateway = GatewayClient(requireContext(), settings)

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
    }

    private fun send() {
        val text = binding.chatInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.chatInput.setText("")
        // 用户消息立即入历史;占位"…"先入历史,网关返回后按 id 替换成真实回复。
        ConversationStore.add("user", text, ConversationStore.SOURCE_TEXT)
        val placeholderId =
            ConversationStore.add("agent", "…", ConversationStore.SOURCE_TEXT)

        viewLifecycleOwner.lifecycleScope.launch {
            val reply = withContext(Dispatchers.IO) { gateway.chat(text) }
            ConversationStore.replaceById(placeholderId, reply ?: "(网关无回复)")
        }
    }

    override fun onDestroyView() {
        gateway.close()
        _binding = null
        super.onDestroyView()
    }
}
