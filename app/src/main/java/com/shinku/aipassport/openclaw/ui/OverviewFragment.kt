package com.shinku.aipassport.openclaw.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.shinku.aipassport.openclaw.databinding.FragmentOverviewBinding
import com.shinku.aipassport.openclaw.gateway.GatewayApi
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 网关概览面板:Agent / 模型 / 技能 / 用量 / 概览,从网关端点拉取展示。
 */
class OverviewFragment : Fragment() {

    private var _binding: FragmentOverviewBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = AgentAdapter()

    private lateinit var api: GatewayApi

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
        api = GatewayApi(GatewaySettings(requireContext()))
        binding.agentList.layoutManager = LinearLayoutManager(requireContext())
        binding.agentList.adapter = adapter
        binding.btnRefreshOverview.setOnClickListener { refresh() }
        refresh()
    }

    private fun refresh() {
        binding.textModels.text = "加载中…"
        binding.textSkills.text = "加载中…"
        binding.textUsage.text = "加载中…"
        scope.launch {
            val agents = api.agents()
            adapter.set(api.parseAgents(agents.body))
            if (!agents.ok) binding.overviewTitle.text = "Agent / 概览 (HTTP ${agents.code})"

            val models = api.models()
            binding.textModels.text = summarize("模型", models)

            val skills = api.skills()
            binding.textSkills.text = summarize("技能", skills)

            val usage = api.usage()
            val overview = api.overview()
            binding.textUsage.text = summarize("用量", usage) + "\n" + summarize("概览", overview)
        }
    }

    private fun summarize(label: String, r: GatewayApi.ApiResult): String {
        if (!r.ok) return "$label: HTTP ${r.code}"
        return "$label:\n${truncate(r.body)}"
    }

    private fun truncate(s: String): String = if (s.length > 600) s.take(600) + "…" else s

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
