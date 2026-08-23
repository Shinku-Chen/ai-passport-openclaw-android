package com.shinku.aipassport.openclaw

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.shinku.aipassport.openclaw.databinding.ActivityMainBinding
import com.shinku.aipassport.openclaw.gateway.GatewayApi
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import com.shinku.aipassport.openclaw.service.VoiceBridgeService
import com.shinku.aipassport.openclaw.ui.ChatFragment
import com.shinku.aipassport.openclaw.ui.DevicesFragment
import com.shinku.aipassport.openclaw.ui.NotificationsFragment
import com.shinku.aipassport.openclaw.ui.OverviewFragment
import com.shinku.aipassport.openclaw.ui.SettingsFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 主界面:顶部网关连接状态卡片 + 5 个 Tab(对话/概览/设备/通知/设置)。
 *
 * 本地 OpenClaw 网关控制台 + 设备管理 + 通知同步 —— 对标原型 App 的本地子集,
 * 不包含登录/账号/订阅/订单/云智能灯效(那是云套餐业务,本端不依赖)。
 * 网关设置在"设置"Tab 内(域名+token 存 SharedPreferences,仅测试值预填,用户可改)。
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
            updateStatusDot(s)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppActivity.set(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = GatewaySettings(this)

        setupTabs()
        binding.btnRefreshStatus.setOnClickListener { refreshStatus() }
        refreshStatus()
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showTab(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        // 显式展示首个 Tab(XML TabItem 不会自动触发选中回调)
        showTab(0)
        binding.tabLayout.getTabAt(0)?.select()
    }

    private fun showTab(position: Int) {
        supportFragmentManager.beginTransaction()
            .replace(binding.fragmentContainer.id, createFragment(position), "tab_$position")
            .commit()
    }

    private fun createFragment(position: Int): Fragment = when (position) {
        0 -> ChatFragment()
        1 -> OverviewFragment()
        2 -> DevicesFragment()
        3 -> NotificationsFragment()
        else -> SettingsFragment()
    }

    private fun refreshStatus() {
        binding.statusText.text = "探活中…"
        binding.statusDot.background.setTint(Color.GRAY)
        scope.launch {
            val r = GatewayApi(settings).health()
            if (r.ok) {
                binding.statusText.text = "网关健康 (HTTP ${r.code}) — ${settings.host}:${settings.port}"
                binding.statusDot.background.setTint(Color.GREEN)
            } else {
                binding.statusText.text = "网关不可达: ${r.body}"
                binding.statusDot.background.setTint(Color.RED)
            }
        }
    }

    private fun updateStatusDot(status: String) {
        val color = when {
            status.contains("就绪") || status.contains("已加密") || status.contains("健康") -> Color.GREEN
            status.contains("错误") || status.contains("失败") || status.contains("不可达") -> Color.RED
            status.contains("连接") || status.contains("扫描") || status.contains("探活") -> Color.YELLOW
            else -> Color.GRAY
        }
        binding.statusDot.background.setTint(color)
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
        AppActivity.clear(this)
        scope.cancel()
        super.onDestroy()
    }

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
