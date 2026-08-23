package com.shinku.aipassport.openclaw.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.gson.JsonParser
import com.shinku.aipassport.openclaw.databinding.FragmentOverviewBinding
import com.shinku.aipassport.openclaw.gateway.GatewayClient
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 网关完整控制台:Agent / 模型 / 会话 / 概览 / Cron / 渠道 / 技能 / 用量。
 *
 * 数据全部走 WebSocket RPC(GatewayClient.rpc*),因为该网关 REST 报表端点只返回
 * 控制台 HTML,只有 /health 是 JSON。实测可用 method:agent.identity.get(agents)、
 * health(models+commands)、sessions.list、system.info、cron.list、directory.list;
 * 需 operator.admin 的:skills / usage / channels / nodes / devices(拉不到显示原因)。
 */
class OverviewFragment : Fragment() {

    private var _binding: FragmentOverviewBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var client: GatewayClient

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        client = GatewayClient(requireContext(), GatewaySettings(requireContext()))
        binding.btnRefreshOverview.setOnClickListener { refresh() }
        refresh()
    }

    private fun refresh() {
        setLoadingAll()
        scope.launch {
            // 并行查询各区(各自独立,失败互不影响)
            kotlinx.coroutines.coroutineScope {
                launch { showAgents() }
                launch { showModels() }
                launch { showSessions() }
                launch { showOverview() }
                launch { showCron() }
                launch { showChannels() }
                launch { showSkills() }
                launch { showUsage() }
            }
        }
    }

    private fun setLoadingAll() {
        listOf(
            binding.textAgents, binding.textModels, binding.textSessions,
            binding.textOverview, binding.textCron, binding.textChannels,
            binding.textSkills, binding.textUsage,
        ).forEach { it.text = "加载中…" }
    }

    private suspend fun showAgents() {
        val r = client.rpcAgents()
        binding.textAgents.text = renderResult(r) { payload ->
            // agent.identity.get → payload.agents:[{id,name,...}]
            val root = JsonParser.parseString(payload).asJsonObject
            val agents = root.getAsJsonArray("agents") ?: root.getAsJsonArray("result")
            if (agents == null || agents.size() == 0) return@renderResult payload
            agents.joinToString("\n") { el ->
                val o = el.asJsonObject
                val id = o.get("id")?.asString ?: "?"
                val name = o.get("name")?.asString ?: id
                val ws = o.get("workspace")?.asString ?: ""
                "$name  (id=$id)\n  workspace: $ws"
            }
        }
    }

    private suspend fun showModels() {
        val r = client.rpcModels()
        binding.textModels.text = renderResult(r) { payload ->
            // health → payload.models + payload.commands
            val root = JsonParser.parseString(payload).asJsonObject
            val sb = StringBuilder()
            root.getAsJsonArray("models")?.let { models ->
                sb.append("模型:\n")
                models.forEach { el ->
                    val o = el.asJsonObject
                    val id = o.get("id")?.asString ?: "?"
                    val provider = o.get("provider")?.asString ?: ""
                    val avail = o.get("available")?.asBoolean ?: false
                    sb.append("  $id ($provider) ${if (avail) "可用" else "不可用"}\n")
                }
            }
            root.getAsJsonArray("commands")?.let { cmds ->
                sb.append("命令(${cmds.size()}):\n")
                cmds.take(20).forEach { el ->
                    val o = el.asJsonObject
                    val name = o.get("name")?.asString ?: "?"
                    val desc = o.get("description")?.asString ?: ""
                    sb.append("  /$name — $desc\n")
                }
            }
            sb.toString().ifBlank { payload }
        }
    }

    private suspend fun showSessions() {
        val r = client.rpcSessions()
        binding.textSessions.text = renderResult(r) { payload ->
            val root = JsonParser.parseString(payload).asJsonObject
            val count = root.get("count")?.asLong ?: root.getAsJsonArray("sessions")?.size()?.toLong() ?: 0
            val total = root.get("totalCount")?.asLong ?: count
            "会话数: $total  (本次返回 $count)\n" + (root.get("path")?.asString ?: "")
        }
    }

    private suspend fun showOverview() {
        val r = client.rpcOverview()
        binding.textOverview.text = renderResult(r) { payload ->
            val root = JsonParser.parseString(payload).asJsonObject
            val sb = StringBuilder()
            root.get("machineName")?.asString?.let { sb.append("主机: $it\n") }
            root.get("osLabel")?.asString?.let { sb.append("系统: $it\n") }
            root.get("arch")?.asString?.let { sb.append("架构: $it\n") }
            root.get("lanAddress")?.asString?.let { sb.append("内网: $it\n") }
            sb.toString().ifBlank { payload }
        }
    }

    private suspend fun showCron() {
        val r = client.rpcCron()
        binding.textCron.text = renderResult(r) { payload ->
            val root = JsonParser.parseString(payload).asJsonObject
            val wts = root.getAsJsonArray("worktrees")
            if (wts == null || wts.size() == 0) return@renderResult payload
            wts.joinToString("\n") { el ->
                val o = el.asJsonObject
                (o.get("name")?.asString ?: o.get("id")?.asString ?: "?")
            }
        }
    }

    private suspend fun showChannels() {
        val r = client.rpcChannels()
        binding.textChannels.text = renderResult(r) { payload -> payload }
    }

    private suspend fun showSkills() {
        val r = client.rpcSkills()
        binding.textSkills.text = renderResult(r) { payload -> payload }
    }

    private suspend fun showUsage() {
        val r = client.rpcUsage()
        binding.textUsage.text = renderResult(r) { payload -> payload }
    }

    private fun renderResult(r: GatewayClient.RpcResult, render: (String) -> String): String {
        if (!r.ok) {
            return "不可用: ${r.error ?: "网关未响应"}"
        }
        val payload = r.payload ?: return "空"
        return try {
            truncate(render(payload))
        } catch (e: Exception) {
            truncate(payload)
        }
    }

    private fun truncate(s: String): String = if (s.length > 1200) s.take(1200) + "…" else s

    override fun onDestroyView() {
        client.close()
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
