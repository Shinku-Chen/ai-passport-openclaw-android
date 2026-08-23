package com.shinku.aipassport.openclaw.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.shinku.aipassport.openclaw.databinding.FragmentNotificationsBinding
import com.shinku.aipassport.openclaw.gateway.GatewayApi
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import com.shinku.aipassport.openclaw.gateway.NotificationFilterPolicy
import com.shinku.aipassport.openclaw.gateway.NotificationFilterPolicy.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通知同步列表:调网关通知端点,按本地过滤策略(白/黑名单,按 App 名)展示。
 * 过滤只是本地展示层,不改网关数据;不依赖云端智能灯效。
 */
class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = NotificationAdapter()

    private lateinit var api: GatewayApi
    private lateinit var filter: NotificationFilterPolicy

    private val allNotifications = mutableListOf<GatewayApi.NotificationItem>()

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
        filter = NotificationFilterPolicy(requireContext())

        binding.notificationList.layoutManager = LinearLayoutManager(requireContext())
        binding.notificationList.adapter = adapter
        binding.btnRefreshNotifications.setOnClickListener { refresh() }
        binding.btnEditFilterApps.setOnClickListener { showAppListEditor() }

        setupFilterSpinner()
        updateFilterStatus()
        refresh()
    }

    private fun setupFilterSpinner() {
        val modes = arrayOf("关闭", "白名单(仅显示)", "黑名单(排除)")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.filterModeSpinner.adapter = adapter
        binding.filterModeSpinner.setSelection(
            when (filter.mode) {
                Mode.OFF -> 0
                Mode.WHITELIST -> 1
                Mode.BLACKLIST -> 2
            }
        )
        binding.filterModeSpinner.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    filter.mode = when (position) {
                        1 -> Mode.WHITELIST
                        2 -> Mode.BLACKLIST
                        else -> Mode.OFF
                    }
                    updateFilterStatus()
                    applyFilter()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        )
    }

    private fun refresh() {
        scope.launch {
            val r = api.notifications()
            if (r.ok) {
                allNotifications.clear()
                allNotifications.addAll(api.parseNotifications(r.body))
                binding.notificationsTitle.text = "通知同步 (${allNotifications.size})"
            } else {
                binding.notificationsTitle.text = "通知同步 (HTTP ${r.code})"
                allNotifications.clear()
            }
            applyFilter()
        }
    }

    /** 按当前过滤策略展示(本地过滤,不改网关数据)。 */
    private fun applyFilter() {
        val shown = allNotifications.filter { filter.shouldShow(it.app) }
        adapter.set(shown)
        updateFilterStatus()
    }

    private fun updateFilterStatus() {
        val desc = when (filter.mode) {
            Mode.OFF -> "过滤: 关闭"
            Mode.WHITELIST -> "过滤: 白名单 (${filter.appList.size} 个 App)"
            Mode.BLACKLIST -> "过滤: 黑名单 (${filter.appList.size} 个 App)"
        }
        binding.filterStatusText.text = desc
    }

    /** 弹 App 列表编辑器:输入框添加 + 列表展示(点击删除)。 */
    private fun showAppListEditor() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "App 名(如 Telegram、微信)"
        }
        val listText = TextView(ctx).apply {
            text = if (filter.appList.isEmpty()) "(空)" else filter.appList.joinToString("、")
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
            addView(input)
            addView(listText)
        }
        AlertDialog.Builder(ctx)
            .setTitle("过滤 App 列表")
            .setView(col)
            .setPositiveButton("添加") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "请输入 App 名", Toast.LENGTH_SHORT).show()
                } else {
                    filter.addApp(name)
                    updateFilterStatus()
                    applyFilter()
                    Toast.makeText(ctx, "已添加 $name,再次点击可继续添加", Toast.LENGTH_SHORT).show()
                    showAppListEditor()
                }
            }
            .setNegativeButton("清空", null)
            .setNeutralButton("关闭", null)
            .show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
