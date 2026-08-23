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
        loadSettings()

        binding.btnSave.setOnClickListener { saveSettings() }
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
