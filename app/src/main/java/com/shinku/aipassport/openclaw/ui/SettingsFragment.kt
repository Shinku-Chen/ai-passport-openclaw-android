package com.shinku.aipassport.openclaw.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.shinku.aipassport.openclaw.databinding.FragmentSettingsBinding
import com.shinku.aipassport.openclaw.gateway.GatewayApi
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import com.shinku.aipassport.openclaw.service.VoiceBridgeService
import com.shinku.aipassport.openclaw.stt.XiaozhiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 设置页:网关域名+token(存 SharedPreferences)、健康检查按钮、日志区、服务启停。
 * token 只存本机,绝不进提交代码。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: GatewaySettings
    private lateinit var xzSettings: XiaozhiSettings

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = GatewaySettings(requireContext())
        xzSettings = XiaozhiSettings(requireContext())
        loadSettings()

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnSaveXz.setOnClickListener { saveXiaozhi() }
        binding.btnActivateXz.setOnClickListener { activateXiaozhi() }
        binding.btnHealth.setOnClickListener { healthCheck() }
        binding.btnStartService.setOnClickListener { VoiceBridgeService.start(requireContext()) }
        binding.btnStopService.setOnClickListener { VoiceBridgeService.stop(requireContext()) }
    }

    private fun loadSettings() {
        binding.inputHost.setText(settings.host.ifBlank { "hs0033439-openclaw.my.hiksemi.net" })
        binding.inputPort.setText(settings.port)
        binding.checkUseTls.isChecked = settings.useTls
        binding.inputToken.setText(settings.token)
        binding.inputWsPath.setText(settings.wsPath)
        // 小智识别配置
        val url = xzSettings.serverUrl
        binding.inputXzUrl.setText(url)
        binding.inputXzToken.setText(xzSettings.token)
        binding.xzStatus.text = if (url.isBlank()) "未启用小智,使用 Vosk 本地识别" else "已启用小智云端识别"
    }

    private fun activateXiaozhi() {
        if (!xzSettings.enabled()) {
            Toast.makeText(requireContext(), "请先填写并保存小智地址", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnActivateXz.isEnabled = false
        binding.xzActiveStatus.text = "正在请求小智 OTA/激活…"
        log("小智激活:请求 OTA…")

        // 设备蓝牙 MAC(小智 Device-Id 必须是真实设备 MAC;从 BleCentral 记住的地址读取)
        val mac = bleCentralDeviceMac()
        val activator = com.shinku.aipassport.openclaw.stt.XiaozhiActivator(
            requireContext(), mac, xzSettings.otaUrl,
        )
        scope.launch {
            val result = activator.activateAndPoll { code, msg ->
                // 拿到绑定码 → 主线程展示,让用户去 xiaozhi.me 绑定
                scope.launch {
                    binding.xzActiveStatus.text = "请到 xiaozhi.me 登录→添加设备→输入绑定码:\n$code\n($msg)\n完成后自动检测…"
                    log("请到 xiaozhi.me 输入绑定码 $code")
                }
            }
            binding.btnActivateXz.isEnabled = true
            if (result.activated) {
                xzSettings.activated = true
                xzSettings.wsUrl = result.wsUrl ?: ""
                xzSettings.wsToken = result.wsToken ?: "test-token"
                binding.xzActiveStatus.text = "激活成功!小智识别已可用"
                log("小智激活成功,ws=${result.wsUrl}")
                Toast.makeText(requireContext(), "激活成功,请重启语音桥服务", Toast.LENGTH_LONG).show()
            } else {
                binding.xzActiveStatus.text = result.detail ?: "激活失败"
                log("小智激活失败: ${result.detail ?: result.message}")
                Toast.makeText(requireContext(), result.detail ?: "激活失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 从 BleCentral 的 prefs 读取上次连接的设备蓝牙 MAC(小智激活用真实设备 MAC)。 */
    private fun bleCentralDeviceMac(): String {
        val p = requireContext().getSharedPreferences("ble_central", android.content.Context.MODE_PRIVATE)
        return p.getString("last_device_addr", "") ?: ""
    }

    private fun saveXiaozhi() {
        val url = binding.inputXzUrl.text.toString().trim()
        val token = binding.inputXzToken.text.toString().trim()
        xzSettings.save(url, token)
        val msg = if (url.isBlank()) {
            "已保存:未启用小智,回退 Vosk"
        } else {
            "已保存小智识别。重启语音桥服务生效"
        }
        log(msg)
        binding.xzStatus.text = if (url.isBlank()) "未启用小智,使用 Vosk 本地识别" else "已启用小智云端识别"
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun saveSettings() {
        val host = binding.inputHost.text.toString().trim()
        val port = binding.inputPort.text.toString().trim()
        val token = binding.inputToken.text.toString().trim()
        if (host.isBlank() || port.isBlank()) {
            Toast.makeText(requireContext(), "请填写网关域名和端口", Toast.LENGTH_SHORT).show()
            return
        }
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "请填写网关 token", Toast.LENGTH_SHORT).show()
            return
        }
        // 先验证 token 有效性(调 /tools/invoke,401 = 无效):通过才保存。
        log("校验 token 中…")
        scope.launch {
            // 用输入框里的值探针验证 token(不写入已保存设置,避免误存无效 token)。
            val api = GatewayApi(settings)
            val valid = api.verifyToken(probeToken = token)
            if (valid) {
                settings.save(host, port, binding.checkUseTls.isChecked, token, binding.inputWsPath.text.toString().trim())
                log("token 有效,网关设置已保存: $host:$port")
                Toast.makeText(requireContext(), "token 有效,已保存", Toast.LENGTH_SHORT).show()
            } else {
                log("token 无效或网关不可达,未保存")
                Toast.makeText(requireContext(), "token 无效,请检查后重试", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun healthCheck() {
        val api = GatewayApi(settings)
        log("探活 /health …")
        scope.launch {
            val r = api.health()
            log(if (r.ok) "探活成功 HTTP ${r.code}: ${r.body}" else "探活失败: ${r.body}")
        }
    }

    private fun log(line: String) {
        val cur = binding.logText.text.toString()
        binding.logText.text = if (cur.isBlank() || cur == "(空)") line else "$cur\n$line"
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
