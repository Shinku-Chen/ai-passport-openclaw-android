package com.shinku.aipassport.openclaw.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.gson.JsonParser
import com.shinku.aipassport.openclaw.databinding.FragmentOverviewBinding
import com.shinku.aipassport.openclaw.gateway.OpenClawConfig
import com.shinku.aipassport.openclaw.gateway.OpenClawGateway
import com.shinku.aipassport.openclaw.gateway.OpenClawGatewayRegistry
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import com.shinku.aipassport.openclaw.gateway.QuerySupport
import com.shinku.aipassport.openclaw.gateway.queryUnavailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 网关完整控制台:Agent / 模型 / 会话 / 概览 / Cron / 渠道 / 技能 / 用量。
 *
 * 数据全部走 WebSocket RPC(OpenClawGateway.rpc*),因为该网关 REST 报表端点只返回
 * 控制台 HTML,只有 /health 是 JSON。实测可用 method:agent.identity.get(agents)、
 * health(models+commands)、sessions.list、system.info、cron.list、directory.list;
 * 需 operator.admin 的:skills / usage / channels / nodes / devices(拉不到显示原因)。
 *
 * 注意:这套 RPC 只有 OpenClaw 网关提供,Hermes/Echo 模式下本页没有数据(见 bindConsoleHint)。
 *
 * **错误隔离**(修 bug「Hermes 切换为 openclaw,网关不可达,重启 app 后正常」):
 * 本页八个分区全是**辅助查询**(`rpcQuery` 默认 `critical = false`)—— 失败只改本分区的文案,
 * **绝不写网关的 `lastError`、绝不影响网关状态**(否则一次「这台网关没有 usage 方法」会被
 * 「网关配置已重载,正在重连… — unknown method: usage」拼出来,看着就像网关不可达)。
 * 网关不支持 / 无权限时显示友好文案并**隐藏卡片**,且在本次会话内记住(见 [QuerySupport])。
 */
class OverviewFragment : Fragment() {

    private var _binding: FragmentOverviewBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var client: OpenClawGateway

    /** 「这台网关不支持某方法」的进程级记忆(按配置指纹区分,App 重启即重新探测)。 */
    private val support: QuerySupport get() = QuerySupport.shared

    /** [support] 的索引键:当前配置指纹。换网关/地址/token 时自动作废旧结论。 */
    private var supportKey: String = ""

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
        // 控制台 RPC 与语音桥服务共用同一条 WS(引用计数复用,不另开连接):
        // 以前这里 new 一个 OpenClawGateway,每次进概览页都会多一条同名连接,
        // 网关侧互相顶掉后表现为「每 ~6s 一次 WS 失败」。
        client = OpenClawGatewayRegistry.acquire(
            requireContext(),
            OpenClawConfig.of(GatewaySettings(requireContext())),
        )
        // 「不支持某方法」的记忆按配置指纹索引:换网关/地址/token 时自动作废旧结论
        supportKey = OpenClawGatewayRegistry.keyOf(
            OpenClawConfig.of(GatewaySettings(requireContext())),
        )
        binding.btnRefreshOverview.setOnClickListener { refresh() }
        bindConsoleHint()
        refresh()
    }

    /**
     * 控制台 RPC(agents/models/sessions/cron…)是 OpenClaw 独有能力。
     * 其它网关类型下本页仍会尝试请求(逻辑不变),但先说明原因,不留看起来像坏了的空白页。
     */
    private fun bindConsoleHint() {
        val hint = when (GatewaySettings(requireContext()).type) {
            GatewaySettings.TYPE_HERMES -> "网关控制台当前仅支持 OpenClaw;Hermes 模式下本页无数据"
            GatewaySettings.TYPE_OPENAI ->
                "网关控制台当前仅支持 OpenClaw;自定义 OpenAI 兼容模式下本页无数据"
            GatewaySettings.TYPE_ECHO -> "网关控制台当前仅支持 OpenClaw;本地回显模式下本页无数据"
            else -> null
        }
        binding.textConsoleHint.visibility = if (hint == null) View.GONE else View.VISIBLE
        if (hint != null) binding.textConsoleHint.text = hint
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
        // 已知「这台网关不支持」的分区直接隐藏(不再请求、也不再报错),其余置为加载中
        listOf(
            Triple(OpenClawGateway.RPC_METHOD_AGENTS, binding.textAgents, binding.labelAgents),
            Triple(OpenClawGateway.RPC_METHOD_MODELS, binding.textModels, binding.labelModels),
            Triple(OpenClawGateway.RPC_METHOD_SESSIONS, binding.textSessions, binding.labelSessions),
            Triple(OpenClawGateway.RPC_METHOD_OVERVIEW, binding.textOverview, binding.labelOverview),
            Triple(OpenClawGateway.RPC_METHOD_CRON, binding.textCron, binding.labelCron),
            Triple(OpenClawGateway.RPC_METHOD_CHANNELS, binding.textChannels, binding.labelChannels),
            Triple(OpenClawGateway.RPC_METHOD_SKILLS, binding.textSkills, binding.labelSkills),
            Triple(OpenClawGateway.RPC_METHOD_USAGE, binding.textUsage, binding.labelUsage),
        ).forEach { (method, value, label) ->
            if (support.isUnsupported(supportKey, method)) {
                hideSection(value, label)
                return@forEach
            }
            label.visibility = View.VISIBLE
            value.visibility = View.VISIBLE
            value.text = "加载中…"
        }
    }

    /**
     * 一个控制台分区的**辅助查询**:
     *
     *  - 已知网关不支持该方法 → 直接隐藏卡片(本次会话不再重复请求);
     *  - 成功 → 渲染到本分区;
     *  - 「网关不支持 / 无权限」→ 友好文案 + 隐藏卡片,并记下「不支持」;
     *  - 其它失败(断开/超时)→ 保留卡片并显示可读原因(这是真的连不上,用户需要看到)。
     *
     * 失败只经 `RpcResult` 返回,绝不写网关 `lastError`、绝不影响网关状态。
     *
     * @param method 分区的方法名(只用做「不支持」记忆的键;请求走 [request],避免两处写不同字符串)
     */
    private suspend fun showSection(
        method: String,
        section: String,
        value: TextView,
        label: TextView,
        request: suspend () -> OpenClawGateway.RpcResult,
        render: (String) -> String,
    ) {
        if (support.isUnsupported(supportKey, method)) {
            hideSection(value, label)
            return
        }
        val r = request()
        if (r.ok) {
            label.visibility = View.VISIBLE
            value.visibility = View.VISIBLE
            value.text = renderResult(r, render)
            return
        }
        val hint = queryUnavailable(section, null, r.error)
        value.text = hint.text
        if (hint.hideCard) {
            // 同一次会话内记住「这台网关不支持」:不再每次进页都报一次错
            support.rememberUnsupported(supportKey, method)
            hideSection(value, label)
        } else {
            label.visibility = View.VISIBLE
            value.visibility = View.VISIBLE
        }
    }

    /** 隐藏一个分区(值 + 标题一起隐藏,不留一片空白标题)。 */
    private fun hideSection(value: TextView, label: TextView) {
        value.text = ""
        value.visibility = View.GONE
        label.visibility = View.GONE
    }

    private suspend fun showAgents() = showSection(
        OpenClawGateway.RPC_METHOD_AGENTS, "Agent", binding.textAgents, binding.labelAgents,
        request = { client.rpcAgents() },
    ) { payload ->
        // agent.identity.get → payload.agents:[{id,name,...}]
        val root = JsonParser.parseString(payload).asJsonObject
        val agents = root.getAsJsonArray("agents") ?: root.getAsJsonArray("result")
        if (agents == null || agents.size() == 0) return@showSection payload
        agents.joinToString("\n") { el ->
            val o = el.asJsonObject
            val id = o.get("id")?.asString ?: "?"
            val name = o.get("name")?.asString ?: id
            val ws = o.get("workspace")?.asString ?: ""
            "$name  (id=$id)\n  workspace: $ws"
        }
    }

    private suspend fun showModels() = showSection(
        OpenClawGateway.RPC_METHOD_MODELS, "模型与命令", binding.textModels, binding.labelModels,
        request = { client.rpcModels() },
    ) { payload ->
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

    private suspend fun showSessions() = showSection(
        OpenClawGateway.RPC_METHOD_SESSIONS, "会话", binding.textSessions, binding.labelSessions,
        request = { client.rpcSessions() },
    ) { payload ->
        val root = JsonParser.parseString(payload).asJsonObject
        val count = root.get("count")?.asLong ?: root.getAsJsonArray("sessions")?.size()?.toLong() ?: 0
        val total = root.get("totalCount")?.asLong ?: count
        "会话数: $total  (本次返回 $count)\n" + (root.get("path")?.asString ?: "")
    }

    private suspend fun showOverview() = showSection(
        OpenClawGateway.RPC_METHOD_OVERVIEW, "系统概览", binding.textOverview, binding.labelOverview,
        request = { client.rpcOverview() },
    ) { payload ->
        val root = JsonParser.parseString(payload).asJsonObject
        val sb = StringBuilder()
        root.get("machineName")?.asString?.let { sb.append("主机: $it\n") }
        root.get("osLabel")?.asString?.let { sb.append("系统: $it\n") }
        root.get("arch")?.asString?.let { sb.append("架构: $it\n") }
        root.get("lanAddress")?.asString?.let { sb.append("内网: $it\n") }
        sb.toString().ifBlank { payload }
    }

    private suspend fun showCron() = showSection(
        OpenClawGateway.RPC_METHOD_CRON, "定时任务", binding.textCron, binding.labelCron,
        request = { client.rpcCron() },
    ) { payload ->
        val root = JsonParser.parseString(payload).asJsonObject
        val wts = root.getAsJsonArray("worktrees")
        if (wts == null || wts.size() == 0) return@showSection payload
        wts.joinToString("\n") { el ->
            val o = el.asJsonObject
            (o.get("name")?.asString ?: o.get("id")?.asString ?: "?")
        }
    }

    private suspend fun showChannels() = showSection(
        OpenClawGateway.RPC_METHOD_CHANNELS, "渠道", binding.textChannels, binding.labelChannels,
        request = { client.rpcChannels() },
    ) { payload -> payload }

    private suspend fun showSkills() = showSection(
        OpenClawGateway.RPC_METHOD_SKILLS, "技能", binding.textSkills, binding.labelSkills,
        request = { client.rpcSkills() },
    ) { payload -> payload }

    private suspend fun showUsage() = showSection(
        OpenClawGateway.RPC_METHOD_USAGE, "用量", binding.textUsage, binding.labelUsage,
        request = { client.rpcUsage() },
    ) { payload -> payload }

    /** 只处理成功结果:失败分支已在 [showSection] 里按「不支持/无权限」分流。 */
    private fun renderResult(r: OpenClawGateway.RpcResult, render: (String) -> String): String {
        val payload = r.payload ?: return "空"
        return try {
            truncate(render(payload))
        } catch (e: Exception) {
            truncate(payload)
        }
    }

    private fun truncate(s: String): String = if (s.length > 1200) s.take(1200) + "…" else s

    override fun onDestroyView() {
        // 只释放一次引用:服务/对话页还在用时不会把健康 socket 关掉
        OpenClawGatewayRegistry.release(client)
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
