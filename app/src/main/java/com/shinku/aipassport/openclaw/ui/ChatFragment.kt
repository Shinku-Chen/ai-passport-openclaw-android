package com.shinku.aipassport.openclaw.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.shinku.aipassport.openclaw.databinding.FragmentChatBinding
import com.shinku.aipassport.openclaw.gateway.GatewayClient
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网关对话界面:用户文本气泡 + 网关回复气泡(双向)。
 * 走 GatewayClient(WS chat.send)拿回复。
 */
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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

        binding.chatList.layoutManager = LinearLayoutManager(requireContext())
        binding.chatList.adapter = adapter

        binding.btnSend.setOnClickListener { send() }
        binding.chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
    }

    private fun send() {
        val text = binding.chatInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.chatInput.setText("")
        adapter.add("user", text)
        adapter.add("agent", "…")
        binding.chatList.scrollToPosition(adapter.itemCount - 1)

        scope.launch {
            val reply = withContext(Dispatchers.IO) { gateway.chat(text) }
            adapter.replaceLast(reply ?: "(网关无回复)")
            binding.chatList.scrollToPosition(adapter.itemCount - 1)
        }
    }

    override fun onDestroyView() {
        gateway.close()
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
