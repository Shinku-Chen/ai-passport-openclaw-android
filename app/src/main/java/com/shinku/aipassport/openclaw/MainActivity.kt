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
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.shinku.aipassport.openclaw.databinding.ActivityMainBinding
import com.shinku.aipassport.openclaw.gateway.AWAITING_PAIRING_PREFIX
import com.shinku.aipassport.openclaw.gateway.GatewayFactory
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import com.shinku.aipassport.openclaw.service.VoiceBridgeService
import com.shinku.aipassport.openclaw.ui.ChatFragment
import com.shinku.aipassport.openclaw.ui.DevicesFragment
import com.shinku.aipassport.openclaw.ui.OverviewFragment
import com.shinku.aipassport.openclaw.ui.SettingsFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 主界面:顶部网关连接状态卡片 + 4 个 Tab(对话/概览/设备/设置)。
 *
 * 本地 OpenClaw 网关控制台 + 设备管理 + 通知同步 —— 对标原型 App 的本地子集,
 * 不包含登录/账号/订阅/订单/云智能灯效(那是云套餐业务,本端不依赖)。
 * 网关设置在"设置"Tab 内(域名+token 存 SharedPreferences,不预填任何测试值,用户可改)。
 * 状态卡展示服务广播的流程状态(含网关断开的可读原因)与手动「刷新」探活结果。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: GatewaySettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前是否处于「等待网关授权」:按钮改为「去授权」并主动弹一次提醒。 */
    private var awaitingApproval = false

    /** 上一次弹过提醒的文案(同一个 deviceId 只提醒一次,避免反复弹窗)。 */
    private var lastApprovalDetail: String? = null

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
            updateApprovalAffordance(s)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppActivity.set(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = GatewaySettings(this)

        setupTabs()
        applyWindowInsets(binding.root)
        binding.btnRefreshStatus.setOnClickListener {
            // 等待网关授权时这个按钮的含义变成“怎么做才能授权”,把步骤直接摆给用户看
            if (awaitingApproval) showApprovalDialog(binding.statusText.text.toString()) else refreshStatus()
        }
        refreshStatus()
    }

    /**
     * 把系统状态栏/导航栏/挖孔区域作为 padding 补回根布局。
     *
     * 为什么需要:主题继承 Material3 NoActionBar(状态栏透明)+ targetSdk 35 强制 edge-to-edge,
     * 根布局会画到状态栏与导航栏之下——不处理时顶栏(状态圆点/状态文案/刷新按钮)
     * 会与系统时钟、电量重叠,底部也会贴住导航栏。
     * 这里只补 inset 内边距,不改配色与 UI 结构。
     */
    private fun applyWindowInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            // 原样返回,让子布局(如对话页的输入行)继续处理 IME insets
            insets
        }
        ViewCompat.requestApplyInsets(root)
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
        else -> SettingsFragment()
    }

    /**
     * 「等待网关授权」时把提示做到位：按钮改成「去授权」并主动弹一次弹窗。
     *
     * 真机反馈：等待授权时只有一行灰字，用户不知道该做什么、也不知道要等哪台设备。
     * 这里在状态变化时弹一次对话框（同一文案只弹一次），说明去哪里批准、批准哪台。
     */
    private fun updateApprovalAffordance(status: String) {
        if (!status.startsWith(AWAITING_PAIRING_PREFIX)) {
            awaitingApproval = false
            lastApprovalDetail = null
            binding.btnRefreshStatus.text = "刷新"
            return
        }
        awaitingApproval = true
        binding.btnRefreshStatus.text = "去授权"
        if (status == lastApprovalDetail) return
        lastApprovalDetail = status
        showApprovalDialog(status)
    }

    /** 等待网关授权时的可操作弹窗：去哪批准、批准哪台、批准后会自己继续。 */
    private fun showApprovalDialog(status: String) {
        if (isFinishing || isDestroyed) return
        val message = buildString {
            appendLine(status)
            appendLine()
            appendLine("怎么批准（二选一）：")
            appendLine("1) 浏览器打开网关 Web 控制台 → Devices / 设备 → 找到上面这串 deviceId → 批准；")
            appendLine("2) 在网关主机执行：openclaw devices list 找到待批设备，再 openclaw devices approve <deviceId>。")
            appendLine()
            append("批准后 App 会自动继续（每 5 秒重试，最多等 180 秒）；超时的话再点「去授权」或重新保存一次网关设置。")
        }
        AlertDialog.Builder(this)
            .setTitle("等待网关授权")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 顶部「刷新」探针。
     *
     * 与「保存前校验」走同一条路:用当前类型的 [com.shinku.aipassport.openclaw.gateway.GatewayAdapter]
     * 的 `connect()` / `isReady()` / `lastError` 决定状态文案。
     * 不再对 OpenClaw 单独请求写死的 REST `/health`——那样探针协议与真实对话通道(WS)是两套判断,
     * WS 对话已通、REST 端点不可用时状态卡会误报「网关不可达」。
     * Echo 不依赖任何外部服务,恒为可用。
     *
     * OpenClaw 下这里拿到的是【共享实例】([com.shinku.aipassport.openclaw.gateway.OpenClawGatewayRegistry]),`close()`
     * 只减一次引用:所以探针不会新开一条 WS,也不会把服务/对话页正在用的健康连接关掉。
     */
    private fun refreshStatus() {
        val type = settings.type
        binding.statusText.text = "探活中…"
        binding.statusDot.background.setTint(Color.GRAY)
        scope.launch {
            // 探活只是读共享实例的状态;失败只影响文案、不改任何配置
            val adapter = GatewayFactory.create(this@MainActivity, settings)
            val ok = try {
                adapter.connect()
            } catch (e: Exception) {
                false
            }
            val ready = adapter.isReady()
            val reason = adapter.lastError
            adapter.close()

            if (type == GatewaySettings.TYPE_ECHO) {
                binding.statusText.text = "本地回显已启用(无网关联调)"
                binding.statusDot.background.setTint(Color.GREEN)
                return@launch
            }

            val label = when (type) {
                GatewaySettings.TYPE_HERMES -> "Hermes 网关"
                GatewaySettings.TYPE_OPENAI -> "OpenAI 兼容网关"
                else -> "OpenClaw 网关"
            }
            val endpoint = when (type) {
                GatewaySettings.TYPE_HERMES -> "${settings.hermesHost}:${settings.hermesPort}"
                GatewaySettings.TYPE_OPENAI -> "${settings.openaiHost}:${settings.openaiPort}"
                else -> "${settings.host}:${settings.port}"
            }
            if (ok || ready) {
                binding.statusText.text = "$label 可用 — $endpoint"
                binding.statusDot.background.setTint(Color.GREEN)
            } else {
                binding.statusText.text = "$label 不可达: ${reason ?: "未知原因"}"
                binding.statusDot.background.setTint(Color.RED)
            }
        }
    }

    /**
     * 状态圆点颜色。
     * 「断开/错误/失败/不可达」算异常(红),「连接中/重连/扫描/探活」算过渡态(黄),
     * 其余灰;顺序不能颠倒——「网关断开(…)— 正在重连…」同时含「断开」与「重连」,应先判红。
     */
    private fun updateStatusDot(status: String) {
        val color = when {
            status.contains("就绪") || status.contains("已加密") || status.contains("健康") -> Color.GREEN
            status.contains("错误") || status.contains("失败") || status.contains("不可达") ||
                status.contains("断开") -> Color.RED
            status.contains("连接") || status.contains("扫描") || status.contains("探活") ||
                status.contains("重连") || status.contains("工作中") -> Color.YELLOW
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
