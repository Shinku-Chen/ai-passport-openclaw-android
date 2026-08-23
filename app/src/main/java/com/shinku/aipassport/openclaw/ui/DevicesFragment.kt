package com.shinku.aipassport.openclaw.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.shinku.aipassport.openclaw.databinding.FragmentDevicesBinding
import com.shinku.aipassport.openclaw.service.VoiceBridgeService

/**
 * 设备管理页:扫描/连接 BLE 设备、展示连接状态(连接/加密/订阅)。
 * 通过给前台服务发 ACTION_SCAN / ACTION_DISCONNECT 控制,状态经广播展示。
 */
class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!
    private val adapter = DeviceAdapter()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = intent.getStringExtra(VoiceBridgeService.EXTRA_STATUS) ?: return
            binding.deviceStatusText.text = s
            adapter.set(buildDeviceList(s))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.deviceList.layoutManager = LinearLayoutManager(requireContext())
        binding.deviceList.adapter = adapter

        binding.btnScan.setOnClickListener {
            VoiceBridgeService.start(requireContext())
            requireContext().startService(
                Intent(requireContext(), VoiceBridgeService::class.java)
                    .setAction(VoiceBridgeService.ACTION_SCAN)
            )
            binding.deviceStatusText.text = "正在扫描设备…"
        }
        binding.btnDisconnect.setOnClickListener {
            requireContext().startService(
                Intent(requireContext(), VoiceBridgeService::class.java)
                    .setAction(VoiceBridgeService.ACTION_DISCONNECT)
            )
            binding.deviceStatusText.text = "已断开"
            adapter.set(emptyList())
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(VoiceBridgeService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(statusReceiver)
        } catch (_: IllegalArgumentException) {
            // 已注销,忽略
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun buildDeviceList(status: String): List<DeviceAdapter.DeviceItem> {
        if (status.contains("已连接") || status.contains("已加密") ||
            status.contains("就绪") || status.contains("连接")
        ) {
            return listOf(
                DeviceAdapter.DeviceItem("AI Passport 对讲桥", "BLE", status)
            )
        }
        return emptyList()
    }
}
