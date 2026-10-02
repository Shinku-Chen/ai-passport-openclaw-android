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
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.shinku.aipassport.openclaw.databinding.FragmentDevicesBinding
import com.shinku.aipassport.openclaw.service.VoiceBridgeService

/**
 * 设备管理页:扫描/连接 BLE 设备、展示**真实连接设备**(名称 + MAC + 链路状态)。
 * 通过给前台服务发 ACTION_SCAN / ACTION_DISCONNECT 控制,状态经广播展示。
 * 未连接时不显示设备卡片,只留状态行(未连接/正在扫描)。
 */
class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!
    private val adapter = DeviceAdapter()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = intent.getStringExtra(VoiceBridgeService.EXTRA_STATUS) ?: return
            renderStatus(
                status = s,
                deviceName = intent.getStringExtra(VoiceBridgeService.EXTRA_DEVICE_NAME),
                deviceAddr = intent.getStringExtra(VoiceBridgeService.EXTRA_DEVICE_ADDR),
                linkState = intent.getStringExtra(VoiceBridgeService.EXTRA_DEVICE_STATE),
                deviceFirmware = intent.getStringExtra(VoiceBridgeService.EXTRA_DEVICE_FW),
            )
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
        binding.btnForget.setOnClickListener {
            // 忘记设备:本机停链路 + 清掉记住的地址(并尽力解除系统绑定)。
            // Android 不允许 App 自行解除绑定，所以同时把要用户做的事说清楚。
            requireContext().startService(
                Intent(requireContext(), VoiceBridgeService::class.java)
                    .setAction(VoiceBridgeService.ACTION_FORGET_DEVICE)
            )
            binding.deviceStatusText.text = "已忘记,等待重新配对"
            adapter.set(emptyList())
            AlertDialog.Builder(requireContext())
                .setTitle("忘记设备 / 重新配对")
                .setMessage(
                    "已在本 App 里忘记这台设备（并尝试解除系统绑定）。\n\n" +
                        "如果系统里还保留着配对，请再到：设置 → 蓝牙 → 找到本设备 → 取消配对；\n" +
                        "然后回到这里点「扫描并连接」，按设备小屏新显示的 6 位码完成配对。\n\n" +
                        "（也可以直接在设备上长按 UP → 重新配对，设备侧会清掉自己的配对信息。）"
                )
                .setPositiveButton("知道了", null)
                .show()
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
        // 广播只在状态变化时发:进页面主动问一次,否则刚打开会一直停在「未连接」
        requireContext().startService(
            Intent(requireContext(), VoiceBridgeService::class.java)
                .setAction(VoiceBridgeService.ACTION_REQUEST_STATUS)
        )
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

    private fun renderStatus(
        status: String,
        deviceName: String?,
        deviceAddr: String?,
        linkState: String?,
        deviceFirmware: String?,
    ) {
        binding.deviceStatusText.text = statusLine(status, linkState)
        adapter.set(buildDeviceList(deviceName, deviceAddr, linkState, deviceFirmware))
    }

    /**
     * 设备页状态行只讲 BLE 链路。
     * 状态广播里大量是网关文案(如「网关连接已断开…」),直接显示会把设备页讲成网关页;
     * 但 BLE 自身的错误(扫描失败/蓝牙未开/配对失败)要原样透出,否则排查时看不到原因。
     */
    private fun statusLine(status: String, linkState: String?): String {
        if (status.contains("BLE") || status.contains("蓝牙") || status.contains("配对")) return status
        return when (linkState) {
            VoiceBridgeService.LINK_SCANNING -> "正在扫描设备…"
            VoiceBridgeService.LINK_CONNECTING -> "正在连接设备…"
            VoiceBridgeService.LINK_CONNECTED -> "已连上,等待配对…"
            VoiceBridgeService.LINK_ENCRYPTED -> "已加密"
            VoiceBridgeService.LINK_READY -> "已就绪,长按设备 OK 说话"
            VoiceBridgeService.LINK_DISCONNECTED -> "未连接"
            else -> status
        }
    }

    /**
     * 设备列表:只在链路已建立时给出一张真实设备卡片(名称 + MAC + 链路状态)。
     * 名称/地址来自服务状态广播(断开后服务仍保留地址);未连接时不显示任何卡片。
     */
    private fun buildDeviceList(
        deviceName: String?,
        deviceAddr: String?,
        linkState: String?,
        deviceFirmware: String?,
    ): List<DeviceAdapter.DeviceItem> {
        if (!isLinkPresent(linkState) || deviceAddr.isNullOrBlank()) return emptyList()
        return listOf(
            DeviceAdapter.DeviceItem(
                name = deviceName?.takeIf { it.isNotBlank() } ?: "AI Passport 设备",
                // 地址行顺带把固件版本带上:设备 hello 报过才有(老固件不上报就不显示)
                address = deviceAddr + deviceFirmware
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "  ·  固件 $it" }.orEmpty(),
                // 必须用映射后的中文状态:真机反馈过「没配对却在列表里显示已连接」——
                // 这里以前直接用了原始 linkState(值为「已连接」)，把"连上但未加密"写成了已连接。
                state = when (linkState) {
                    VoiceBridgeService.LINK_CONNECTED -> "未配对：等待配对完成"
                    VoiceBridgeService.LINK_ENCRYPTED -> "已配对（已加密）"
                    VoiceBridgeService.LINK_READY -> "已连接（已就绪）"
                    else -> "未连接"
                },
            )
        )
    }

    /** 卡片是否显示:连上(含尚未配对)、已配对、已就绪都显示，便于用户看到"正在配对"。 */
    private fun isLinkPresent(linkState: String?): Boolean = linkState == VoiceBridgeService.LINK_CONNECTED ||
        linkState == VoiceBridgeService.LINK_ENCRYPTED ||
        linkState == VoiceBridgeService.LINK_READY

    /** 「已连接」的语义收紧:必须完成配对(加密)才算连上，没加密的只能叫未配对。 */
    private fun isLinkUp(linkState: String?): Boolean = linkState == VoiceBridgeService.LINK_ENCRYPTED ||
        linkState == VoiceBridgeService.LINK_READY
}
