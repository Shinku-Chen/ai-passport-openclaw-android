package com.shinku.aipassport.openclaw.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.shinku.aipassport.openclaw.databinding.FragmentNotificationsBinding
import com.shinku.aipassport.openclaw.gateway.GatewayApi
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通知同步列表:调网关通知端点,展示手机同步过来的通知。
 * 不依赖云端智能灯效(那是云套餐业务,本端不涉及)。
 */
class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = NotificationAdapter()

    private lateinit var api: GatewayApi

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        api = GatewayApi(GatewaySettings(requireContext()))
        binding.notificationList.layoutManager = LinearLayoutManager(requireContext())
        binding.notificationList.adapter = adapter
        binding.btnRefreshNotifications.setOnClickListener { refresh() }
        refresh()
    }

    private fun refresh() {
        scope.launch {
            val r = api.notifications()
            if (r.ok) {
                val list = api.parseNotifications(r.body)
                adapter.set(list)
                binding.notificationsTitle.text = "通知同步 (${list.size})"
            } else {
                binding.notificationsTitle.text = "通知同步 (HTTP ${r.code})"
                adapter.set(emptyList())
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
