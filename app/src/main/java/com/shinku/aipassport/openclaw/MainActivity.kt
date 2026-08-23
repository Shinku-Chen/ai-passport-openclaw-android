package com.shinku.aipassport.openclaw

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shinku.aipassport.openclaw.databinding.ActivityMainBinding
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import com.shinku.aipassport.openclaw.service.VoiceBridgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 主界面:网关设置区(域名+端口+token)+ 探活 /health + 启停语音桥 + 状态展示。
 *
 * 网关设置在 App 内配置,存 SharedPreferences(GatewaySettings),token 只存本机、不写死。
 * 业务逻辑仍在 VoiceBridgeService;此处负责设置与入口。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: GatewaySettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val ok = grants.values.all { it }
            if (ok) {
                VoiceBridgeService.start(this)
            } else {
                Toast.makeText(this, "缺少权限,无法启动语音桥", Toast.LENGTH_LONG).show()
            }
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = intent.getStringExtra(VoiceBridgeService.EXTRA_STATUS) ?: return
            binding.statusText.text = s
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = GatewaySettings(this)

        loadSettings()

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnHealth.setOnClickListener { probeHealth() }
        binding.btnStart.setOnClickListener { startWithPermissions() }
        binding.btnStop.setOnClickListener { VoiceBridgeService.stop(this) }
    }

    override fun onStart() {
        super.onStart()
        startWithPermissions()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(VoiceBridgeService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: IllegalArgumentException) {
            // 已注销,忽略
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- 设置 ----

    private fun loadSettings() {
        binding.inputHost.setText(settings.host.ifBlank { "hs0033439-openclaw.my.hiksemi.net" })
        binding.inputPort.setText(settings.port)
        binding.checkUseTls.isChecked = settings.useTls
        binding.inputToken.setText(settings.token)
    }

    private fun saveSettings() {
        val host = binding.inputHost.text.toString().trim()
        val port = binding.inputPort.text.toString().trim()
        val token = binding.inputToken.text.toString().trim()
        if (host.isBlank() || port.isBlank()) {
            Toast.makeText(this, "请填写网关域名和端口", Toast.LENGTH_SHORT).show()
            return
        }
        settings.save(host, port, binding.checkUseTls.isChecked, token)
        binding.statusText.text = "网关设置已保存"
        Toast.makeText(this, "网关设置已保存", Toast.LENGTH_SHORT).show()
    }

    // ---- 探活 ----

    private fun probeHealth() {
        val host = binding.inputHost.text.toString().trim()
        val port = binding.inputPort.text.toString().trim()
        if (host.isBlank() || port.isBlank()) {
            Toast.makeText(this, "请先填写网关域名和端口", Toast.LENGTH_SHORT).show()
            return
        }
        binding.statusText.text = "探活中…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { doProbe(host, port, binding.checkUseTls.isChecked) }
            binding.statusText.text = result
        }
    }

    private fun doProbe(host: String, port: String, useTls: Boolean): String {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val url = "http${if (useTls) "s" else ""}://$host:$port/health"
            val req = okhttp3.Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    "探活成功 /health ${resp.code}: $body"
                } else {
                    "探活失败 /health HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            "探活失败:${e.message}"
        }
    }

    // ---- 权限与启动 ----

    private fun startWithPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (!has(Manifest.permission.BLUETOOTH_SCAN)) needed += Manifest.permission.BLUETOOTH_SCAN
            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) needed += Manifest.permission.RECORD_AUDIO

        if (needed.isEmpty()) {
            VoiceBridgeService.start(this)
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
