package com.shinku.aipassport.openclaw.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.shinku.aipassport.openclaw.R
import com.shinku.aipassport.openclaw.databinding.FragmentSettingsBinding
import com.shinku.aipassport.openclaw.gateway.AWAITING_PAIRING_HINT
import com.shinku.aipassport.openclaw.gateway.AWAITING_PAIRING_PREFIX
import com.shinku.aipassport.openclaw.gateway.GatewayConfig
import com.shinku.aipassport.openclaw.gateway.GatewayDraft
import com.shinku.aipassport.openclaw.gateway.GatewayFactory
import com.shinku.aipassport.openclaw.gateway.GatewaySaveGuard
import com.shinku.aipassport.openclaw.gateway.GatewaySaveGuard.SaveValidation
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import com.shinku.aipassport.openclaw.gateway.OpenClawConfig
import com.shinku.aipassport.openclaw.service.VoiceBridgeService
import com.shinku.aipassport.openclaw.stt.XiaozhiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 设置页:网关类型(下拉)+ 三套网关配置(OpenClaw / Hermes / 自定义 OpenAI 兼容)+ 日志区。
 *
 * 三套配置分别持久化(见 [GatewaySettings]),切换类型只切可见分组,
 * 不丢配置。token/API key 只存本机,绝不进提交代码。
 * 每个字段都有常驻 label(hint 输入后会消失);开关项统一用 MaterialSwitch。
 * 语音桥服务常驻(主界面 onStart 自动拉起),因此页面上不再有启动/停止服务按钮。
 *
 * 「保存网关设置」= 先用输入框里的草稿值校验连接(OpenClaw=WS 鉴权,Hermes=/health,
 * 自定义 OpenAI 兼容=/models 或最小对话请求,Echo=恒通),校验通过才落盘;
 * 失败一个字段都不写、原配置继续生效,原因用对话框展示。
 *
 * 「等待网关授权」(OpenClaw 设备未在网关被批准)单独处理:它不是配置错误 ——
 * 每 [PAIRING_RETRY_INTERVAL_MS] 自动重试一次、最长等 [PAIRING_RETRY_TIMEOUT_MS],
 * 批准后自动落盘并提示「授权完成,网关设置已保存」;期间按钮置灰显示「等待授权…」。
 * 与回复等待上限(180s 可配)无关,是两个独立常量。
 *
 * 三个「与网关无关」的开关/下拉走**切换即落盘**(不参与网关校验):
 * 「App 显示完整回传流（调试）」。
 *
 * 设备朗读(下行 TTS)的设置项已移除(项目暂不考虑文字转语音);
 * `tts_enabled`/`tts_engine` 仍保留在 prefs(默认 false / android),代码作为休眠能力保留。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: GatewaySettings
    private lateinit var xzSettings: XiaozhiSettings

    /**
     * 当前语音桥服务实际使用的网关类型(页面加载时的落盘值)。
     * 保存成功且类型变化时才重启服务。
     */
    private var appliedType: String = GatewaySettings.TYPE_OPENCLAW

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
        xzSettings = XiaozhiSettings()
        // 先装网关类型下拉的 adapter,再回填字段。
        // 真机 bug:旧顺序是 loadSettings() -> 然后才 adapter = …,而 loadSettings() 里的
        // bindTypeSpinner() 会 setSelection(idx) —— 作用在【空 adapter】上会被忽略,
        // 装上 adapter 后默认落回第 0 项 → 无论保存的是哪种网关,进设置页永远显示 OpenClaw。
        binding.spinnerType.adapter =
            ArrayAdapter(requireContext(), R.layout.item_spinner_option, typeLabels)
        loadSettings()
        bindCleartextHint()

        // 监听在回填之后挂:避免回填触发的选中回调被当成“用户切换”
        binding.spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                v: View?,
                position: Int,
                id: Long,
            ) {
                applyTypeVisibility()
                updateCleartextHint()
                val type = selectedType()
                // Spinner 回填/首次布局也会触发一次选中回调,这里用实际值去重,避免误报「已切换」
                if (type == lastSelectedType) return
                lastSelectedType = type
                log("网关类型已切换为 $type(点「保存网关设置」校验通过后生效)")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnActivateXz.setOnClickListener { activateXiaozhi() }
    }

    // ---- 类型选择 ----

    /** 网关类型下拉选项(下标即 settings.type 的取值,顺序即 spinner position)。 */
    private val typeLabels = listOf("OpenClaw", "Hermes", "自定义 OpenAI 兼容", "Echo(本地回环)")
    private val typeValues = listOf(
        GatewaySettings.TYPE_OPENCLAW,
        GatewaySettings.TYPE_HERMES,
        GatewaySettings.TYPE_OPENAI,
        GatewaySettings.TYPE_ECHO,
    )

    /** 已向用户播报过的类型(避免回填触发选中回调时误报「已切换」)。 */
    private var lastSelectedType: String? = null

    /** 按 settings.type 回填下拉框(不写回设置,供初次加载/界面同步)。 */
    private fun bindTypeSpinner() {
        val idx = typeValues.indexOf(settings.type).coerceAtLeast(0)
        lastSelectedType = typeValues[idx]
        binding.spinnerType.setSelection(idx)
        applyTypeVisibility()
    }

    // ---- 网关类型下拉 ----

    /** 按当前选中的类型显示/隐藏三套字段分组(Echo 不需要任何字段)。 */
    private fun applyTypeVisibility() {
        val type = selectedType()
        binding.groupOpenclaw.visibility =
            if (type == GatewaySettings.TYPE_OPENCLAW) View.VISIBLE else View.GONE
        binding.groupHermes.visibility =
            if (type == GatewaySettings.TYPE_HERMES) View.VISIBLE else View.GONE
        binding.groupOpenai.visibility =
            if (type == GatewaySettings.TYPE_OPENAI) View.VISIBLE else View.GONE
    }

    /** 读下拉框上选中的类型(未选中时回退 OpenClaw,与旧单选默认一致)。 */
    private fun selectedType(): String =
        typeValues.getOrElse(binding.spinnerType.selectedItemPosition) { GatewaySettings.TYPE_OPENCLAW }

    /**
     * 明文连接提示(非阻断)。
     *
     * host 填成 `http://` 或关掉 TLS 时,请求会走明文:token/API Key 与对话内容都在链路上裸奔,
     * 所以只做提示、不拦保存(内网/自签联调是合法场景)。
     */
    private fun updateCleartextHint() {
        val b = _binding ?: return
        val type = selectedType()
        val label = when (type) {
            GatewaySettings.TYPE_HERMES -> "Hermes"
            GatewaySettings.TYPE_OPENAI -> "自定义 OpenAI 兼容"
            GatewaySettings.TYPE_OPENCLAW -> "OpenClaw"
            else -> null
        }
        val plain = when (type) {
            GatewaySettings.TYPE_OPENCLAW ->
                !b.checkUseTls.isChecked || b.inputHost.text.toString().trim().startsWith("http://")
            GatewaySettings.TYPE_HERMES ->
                !b.checkHermesUseTls.isChecked || b.inputHermesHost.text.toString().trim().startsWith("http://")
            GatewaySettings.TYPE_OPENAI ->
                !b.checkOpenaiUseTls.isChecked || b.inputOpenaiHost.text.toString().trim().startsWith("http://")
            else -> false
        }
        if (label == null || !plain) {
            b.textCleartextHint.visibility = View.GONE
            return
        }
        b.textCleartextHint.text =
            "明文连接($label):token 会以明文发送,建议仅在局域网/自签环境使用"
        b.textCleartextHint.visibility = View.VISIBLE
    }

    /** 给三套的 host 输入框与 TLS 开关挂监听,实时刷新明文提示。 */
    private fun bindCleartextHint() {
        binding.inputHost.doAfterTextChanged { updateCleartextHint() }
        binding.inputHermesHost.doAfterTextChanged { updateCleartextHint() }
        binding.inputOpenaiHost.doAfterTextChanged { updateCleartextHint() }
        binding.checkUseTls.setOnCheckedChangeListener { _, _ -> updateCleartextHint() }
        binding.checkHermesUseTls.setOnCheckedChangeListener { _, _ -> updateCleartextHint() }
        binding.checkOpenaiUseTls.setOnCheckedChangeListener { _, _ -> updateCleartextHint() }
        updateCleartextHint()
    }

    // ---- 加载/保存 ----

    private fun loadSettings() {
        // OpenClaw(不再预填任何测试域名/token,一律由用户填写)
        binding.inputHost.setText(settings.host)
        binding.inputPort.setText(settings.port)
        binding.checkUseTls.isChecked = settings.useTls
        binding.checkOpenclawAllowInsecure.isChecked = settings.openclawAllowInsecureTls
        binding.inputToken.setText(settings.token)
        binding.inputWsPath.setText(settings.wsPath)
        binding.inputReplyTimeout.setText(settings.openclawReplyTimeoutSeconds.toString())
        binding.inputSessionName.setText(settings.openclawSessionName)
        // 语音附加提示(与网关类型无关,所有通道共用)
        binding.inputVoicePromptSuffix.setText(settings.voicePromptSuffix)
        // App 调试展示开关(默认开):切换即落盘(纯 App 展示,与网关连接无关,不走保存校验)
        binding.checkShowRawStream.isChecked = settings.showRawStream
        binding.checkShowRawStream.setOnCheckedChangeListener { _, checked ->
            settings.showRawStream = checked
        }

        // 设备朗读(下行 TTS)的设置项已移除(项目暂不考虑文字转语音):
        // prefs 里的 tts_enabled 保持默认 false,代码作为休眠能力保留。
        // 注意:不要因为删掉开关就把 tts_enabled 改写成 true。

        // Hermes
        binding.inputHermesHost.setText(settings.hermesHost)
        binding.inputHermesPort.setText(settings.hermesPort)
        binding.inputHermesBasePath.setText(settings.hermesBasePath)
        binding.inputHermesToken.setText(settings.hermesToken)
        binding.inputHermesModel.setText(settings.hermesModel)
        binding.inputHermesConversation.setText(settings.hermesConversation)
        binding.checkHermesUseTls.isChecked = settings.hermesUseTls
        binding.checkHermesAllowInsecure.isChecked = settings.hermesAllowInsecureTls
        binding.checkHermesServerSideConversation.isChecked = settings.hermesUseServerSideConversation
        binding.checkHermesStream.isChecked = settings.hermesStream

        // 自定义 OpenAI 兼容
        binding.inputOpenaiHost.setText(settings.openaiHost)
        binding.inputOpenaiPort.setText(settings.openaiPort)
        binding.inputOpenaiBasePath.setText(settings.openaiBasePath)
        binding.inputOpenaiApiKey.setText(settings.openaiApiKey)
        binding.inputOpenaiModel.setText(settings.openaiModel)
        binding.inputOpenaiSystemPrompt.setText(settings.openaiSystemPrompt)
        binding.inputOpenaiMaxHistory.setText(settings.openaiMaxHistory.toString())
        binding.checkOpenaiUseTls.isChecked = settings.openaiUseTls
        binding.checkOpenaiAllowInsecure.isChecked = settings.openaiAllowInsecureTls
        binding.checkOpenaiStream.isChecked = settings.openaiStream

        bindTypeSpinner()
        // 记录本次页面加载时服务正在用的类型,作为「是否需要重启」的基准
        appliedType = settings.type

        // 小智 URL/token 写死,不在 UI 展示;只显示当前识别引擎状态
        binding.xzStatus.text = "识别引擎:小智云端(已启用)"
    }

    /**
     * 把界面上的两套字段与网关类型写入 SharedPreferences;只在校验通过后被调用。
     * 写入的值来自 [FormSnapshot],因此「校验的」与「落盘的」逐字一致。
     */
    /**
     * 落盘:只写【当前类型这一组】字段 + 跨类型共用的语音附加提示词。
     *
     * 修的真机 bug:旧实现把三套网关字段【全写一遍】✗ —— 切换网关类型后点保存时,
     * 那些没在编辑的分组(表单值是空/默认的)会把已保存的配置覆盖成默认值
     * (用户报告的原话:「网关类型在切换的时候恢复为默认设置」)。
     * 类型下拉本来就只影响可见分组,落盘也应只写这一组。
     */
    private fun persistForm(form: FormSnapshot) {
        settings.type = form.type
        // 跨类型共用:语音输入末尾附加的提示词(所有网关通道都走语音链路)
        settings.voicePromptSuffix = form.voicePromptSuffix
        when (form.type) {
            GatewaySettings.TYPE_HERMES -> settings.saveHermes(
                host = form.hermesHost,
                port = form.hermesPort,
                useTls = form.hermesUseTls,
                allowInsecureTls = form.hermesAllowInsecureTls,
                basePath = form.hermesBasePath,
                token = form.hermesToken,
                model = form.hermesModel,
                conversation = form.hermesConversation,
                useServerSideConversation = form.hermesServerSideConversation,
                stream = form.hermesStream,
            )

            GatewaySettings.TYPE_OPENAI -> settings.saveOpenAi(
                host = form.openaiHost,
                port = form.openaiPort,
                useTls = form.openaiUseTls,
                allowInsecureTls = form.openaiAllowInsecureTls,
                basePath = form.openaiBasePath,
                apiKey = form.openaiApiKey,
                model = form.openaiModel,
                systemPrompt = form.openaiSystemPrompt,
                maxHistory = form.openaiMaxHistory,
                stream = form.openaiStream,
            )

            GatewaySettings.TYPE_ECHO -> Unit   // 本地回环无字段

            else -> settings.save(        // OpenClaw
                host = form.openclawHost,
                port = form.openclawPort,
                useTls = form.openclawUseTls,
                allowInsecureTls = form.openclawAllowInsecureTls,
                token = form.openclawToken,
                wsPath = form.openclawWsPath,
                replyTimeoutSeconds = form.openclawReplyTimeout,
                sessionName = form.openclawSessionName,
                voicePromptSuffix = form.voicePromptSuffix,
            )
        }
    }

    /**
     * 「保存」时对输入框的一次性快照。
     * 校验与落盘都只用这一份:校验期间用户继续改字段、或切页销毁视图,
     * 都不会出现「校验的是 A、写进去的是 B」或空视图访问。
     */
    private data class FormSnapshot(
        val type: String,
        val openclawHost: String,
        val openclawPort: String,
        val openclawUseTls: Boolean,
        val openclawAllowInsecureTls: Boolean,
        val openclawToken: String,
        val openclawWsPath: String,
        /** 回复等待上限(秒)的输入框原文;空/非法时保留原值。 */
        val openclawReplyTimeout: String,
        /** 会话名(sessionKey = agent:main:<这个名字>)。 */
        val openclawSessionName: String,
        /** 语音输入末尾自动附加的提示词(仅语音;文字输入不追加)。 */
        val voicePromptSuffix: String,
        val hermesHost: String,
        val hermesPort: String,
        val hermesUseTls: Boolean,
        val hermesAllowInsecureTls: Boolean,
        val hermesBasePath: String,
        val hermesToken: String,
        val hermesModel: String,
        val hermesConversation: String,
        val hermesServerSideConversation: Boolean,
        val hermesStream: Boolean,
        val openaiHost: String,
        val openaiPort: String,
        val openaiUseTls: Boolean,
        val openaiAllowInsecureTls: Boolean,
        val openaiBasePath: String,
        val openaiApiKey: String,
        val openaiModel: String,
        val openaiSystemPrompt: String,
        val openaiMaxHistory: String,
        val openaiStream: Boolean,
    )

    /** 读一份当前表单快照。 */
    private fun snapshotForm(): FormSnapshot = FormSnapshot(
        type = selectedType(),
        openclawHost = binding.inputHost.text.toString(),
        openclawPort = binding.inputPort.text.toString(),
        openclawUseTls = binding.checkUseTls.isChecked,
        openclawAllowInsecureTls = binding.checkOpenclawAllowInsecure.isChecked,
        openclawToken = binding.inputToken.text.toString(),
        openclawWsPath = binding.inputWsPath.text.toString(),
        openclawReplyTimeout = binding.inputReplyTimeout.text.toString(),
        openclawSessionName = binding.inputSessionName.text.toString(),
        voicePromptSuffix = binding.inputVoicePromptSuffix.text.toString(),
        hermesHost = binding.inputHermesHost.text.toString(),
        hermesPort = binding.inputHermesPort.text.toString(),
        hermesUseTls = binding.checkHermesUseTls.isChecked,
        hermesAllowInsecureTls = binding.checkHermesAllowInsecure.isChecked,
        hermesBasePath = binding.inputHermesBasePath.text.toString(),
        hermesToken = binding.inputHermesToken.text.toString(),
        hermesModel = binding.inputHermesModel.text.toString(),
        hermesConversation = binding.inputHermesConversation.text.toString(),
        hermesServerSideConversation = binding.checkHermesServerSideConversation.isChecked,
        hermesStream = binding.checkHermesStream.isChecked,
        openaiHost = binding.inputOpenaiHost.text.toString(),
        openaiPort = binding.inputOpenaiPort.text.toString(),
        openaiUseTls = binding.checkOpenaiUseTls.isChecked,
        openaiAllowInsecureTls = binding.checkOpenaiAllowInsecure.isChecked,
        openaiBasePath = binding.inputOpenaiBasePath.text.toString(),
        openaiApiKey = binding.inputOpenaiApiKey.text.toString(),
        openaiModel = binding.inputOpenaiModel.text.toString(),
        openaiSystemPrompt = binding.inputOpenaiSystemPrompt.text.toString(),
        openaiMaxHistory = binding.inputOpenaiMaxHistory.text.toString(),
        openaiStream = binding.checkOpenaiStream.isChecked,
    )

    // ---- 保存(先校验再落盘) ----

    /**
     * 保存网关设置:先用【输入框里的草稿值】校验连接,成功才把字段与类型写进 SharedPreferences。
     *
     * 关键点:
     *  - 校验用 [GatewayDraft] 显式传配置,全程不预写/回滚 SharedPreferences;
     *  - 校验有超时(见 [GatewaySaveGuard]),期间保存按钮置灰并显示「校验中…」;
     *  - 「等待网关授权」自动继续校验(每 [PAIRING_RETRY_INTERVAL_MS],最长 [PAIRING_RETRY_TIMEOUT_MS]),
     *    按钮改显示「等待授权…」;批准后自动落盘并提示「授权完成,网关设置已保存」;
     *  - 其余失败立刻停手,一个字段都不落盘(原配置继续生效),原因用对话框展示。
     */
    private fun saveSettings() {
        val form = snapshotForm()
        val draft = buildDraft(form) ?: return
        binding.btnSave.isEnabled = false
        binding.btnSave.text = "校验中…"
        log("校验网关连接(${form.type})…")
        // 用 applicationContext:重试循环可能跨页面销毁,不能持有 Fragment 的 view 生命周期上下文
        val ctx = requireContext().applicationContext
        scope.launch {
            val deadlineAt = System.currentTimeMillis() + PAIRING_RETRY_TIMEOUT_MS
            var reason: String? = null
            var saved = false
            var awaiting = false
            var pairingTimedOut = false
            var attempt = 0
            while (true) {
                attempt++
                when (val result = validateOnce(ctx, draft)) {
                    is SaveValidation.Ok -> {
                        // 只在真正通过时落盘:校验不通过绝不落盘(含等待授权)
                        persistForm(form)
                        // 落盘后用 prefs 回填一遍表单:保证可见分组显示的就是已保存值
                        // (不再是陈旧值,也避免下一个人手误改到别的分组)。
                        // 视图可能已被销毁(重试循环跨页面),所以先判 view 是否还在。
                        if (view != null) loadSettings()
                        saved = true
                    }

                    is SaveValidation.AwaitingPairing -> {
                        reason = result.reason
                        awaiting = true
                    }

                    is SaveValidation.Failed -> {
                        reason = result.reason
                        awaiting = false
                    }
                }
                if (saved) break
                // 不是「等待授权」:立刻停手(重试无意义,必须先让用户改配置)
                if (!awaiting) break
                if (System.currentTimeMillis() >= deadlineAt) {
                    pairingTimedOut = true
                    break
                }
                // 等待授权:按钮改文案并置灰,等一个间隔后自动继续校验(_binding 可能已置空)
                _binding?.let { b ->
                    b.btnSave.isEnabled = false
                    b.btnSave.text = "等待授权…"
                }
                log("等待网关授权…(第 $attempt 次校验未通过,${PAIRING_RETRY_INTERVAL_MS / 1000}s 后自动重试)")
                try {
                    delay(PAIRING_RETRY_INTERVAL_MS)
                } catch (e: CancellationException) {
                    // 用户取消(离开设置页/销毁):不落盘、不再重试;视图可能已销毁,只记日志
                    log("已取消等待网关授权,未保存任何字段")
                    throw e
                }
            }
            // 视图可能已销毁(切页/离开设置页):落盘与重启服务仍然要完成(否则“批准后自动保存”会失效),
            // 只把界面提示降级 —— log()/showSaveFailure() 自己会在无视图/无 context 时安全退出。
            _binding?.let { b ->
                b.btnSave.isEnabled = true
                b.btnSave.text = "保存网关设置"
            }
            if (saved) {
                // 第 1 次就通过 = 普通保存;多次才通过 = 等过授权的自动重试
                if (attempt > 1) {
                    log("授权完成,网关设置已保存(类型 ${form.type})")
                    toast("授权完成,网关设置已保存")
                } else {
                    log("校验通过,网关设置已保存(类型 ${form.type})")
                    toast("已保存")
                }
                applySavedServiceConfig()
            } else if (pairingTimedOut) {
                val text = pairingTimeoutReason(reason)
                log("等待网关授权超时,未保存任何字段: $text")
                showSaveFailure(text)
            } else {
                val text = reason ?: "连接校验失败(未取得失败原因,请检查网关配置)"
                log("校验失败,未保存任何字段: $text")
                showSaveFailure(text)
            }
        }
    }

    /** Toast 包装:视图已销毁(context 为 null) 时静默跳过,不让后台的重试循环把进程弄挂。 */
    private fun toast(message: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    /** 用草稿建一次适配器做一次连接校验,用完即释放(语音桥服务另有自己的适配器)。 */
    private suspend fun validateOnce(
        ctx: android.content.Context,
        draft: GatewayDraft,
    ): SaveValidation {
        val adapter = GatewayFactory.createFromDraft(ctx, draft) { publishLog(it) }
        return try {
            GatewaySaveGuard.validate(adapter)
        } finally {
            adapter.close()
        }
    }

    /**
     * 等待授权超时的文案:必须带上 deviceId 前 8 位与「去控制台/CLI 批准后重试」的下一步,
     * 否则用户只看到「超时」不知道该做什么。
     */
    private fun pairingTimeoutReason(reason: String?): String {
        val detail = reason?.takeIf { it.isNotBlank() }
            ?: "$AWAITING_PAIRING_PREFIX$AWAITING_PAIRING_HINT"
        return "等待网关授权超时(已等待 ${PAIRING_RETRY_TIMEOUT_MS / 1000} 秒,每 ${
            PAIRING_RETRY_INTERVAL_MS / 1000
        } 秒重试一次)\n$detail\n" +
            "请在 OpenClaw 控制台/CLI(openclaw devices approve)批准本设备后,再点「保存网关设置」重试。"
    }

    /**
     * 用快照里的草稿值组装显式配置(不读也不写 SharedPreferences)。
     * 返回 null 表示必填项缺失,已提示用户且不落盘。
     */
    private fun buildDraft(form: FormSnapshot): GatewayDraft? {
        return when (form.type) {
            GatewaySettings.TYPE_HERMES -> {
                val host = form.hermesHost.trim()
                if (host.isBlank()) {
                    showSaveFailure("请先填写 Hermes 域名/内网名/Tailscale 名")
                    return null
                }
                GatewayDraft.Hermes(
                    GatewaySettings.hermesConfigOf(
                        host = host,
                        port = form.hermesPort,
                        useTls = form.hermesUseTls,
                        allowInsecureTls = form.hermesAllowInsecureTls,
                        basePath = form.hermesBasePath,
                        token = form.hermesToken,
                        model = form.hermesModel,
                        conversation = form.hermesConversation,
                        useServerSideConversation = form.hermesServerSideConversation,
                        stream = form.hermesStream,
                        defaultConversation = GatewayFactory.defaultConversation(requireContext()),
                    )
                )
            }

            GatewaySettings.TYPE_OPENAI -> {
                val host = form.openaiHost.trim()
                if (host.isBlank()) {
                    showSaveFailure("请先填写自定义 OpenAI 兼容服务的域名/内网名/Tailscale 名")
                    return null
                }
                if (form.openaiModel.trim().isBlank()) {
                    showSaveFailure("请先填写模型名(Model,必填)")
                    return null
                }
                GatewayDraft.OpenAi(
                    GatewaySettings.openAiConfigOf(
                        host = host,
                        port = form.openaiPort,
                        useTls = form.openaiUseTls,
                        allowInsecureTls = form.openaiAllowInsecureTls,
                        basePath = form.openaiBasePath,
                        apiKey = form.openaiApiKey,
                        model = form.openaiModel,
                        systemPrompt = form.openaiSystemPrompt,
                        maxHistory = form.openaiMaxHistory,
                        stream = form.openaiStream,
                    )
                )
            }

            GatewaySettings.TYPE_ECHO -> GatewayDraft.Echo

            else -> {
                val host = form.openclawHost.trim()
                val port = form.openclawPort.trim()
                val token = form.openclawToken.trim()
                if (host.isBlank() || port.isBlank()) {
                    showSaveFailure("请先填写 OpenClaw 网关域名与端口")
                    return null
                }
                if (token.isBlank()) {
                    showSaveFailure("请先填写 OpenClaw 网关 token")
                    return null
                }
                GatewayDraft.OpenClaw(
                    OpenClawConfig.of(
                        host = host,
                        port = port,
                        useTls = form.openclawUseTls,
                        token = token,
                        wsPath = form.openclawWsPath,
                        sessionName = form.openclawSessionName,
                        allowInsecureTls = form.openclawAllowInsecureTls,
                        // 草稿也带上等待上限:校验用的配置与实际落盘的逐字一致
                        replyTimeoutSeconds = form.openclawReplyTimeout.trim().toLongOrNull()
                            ?: GatewayConfig.DEFAULT_REPLY_TIMEOUT_SECONDS,
                    )
                )
            }
        }
    }

    /** 保存/校验失败提示:用对话框展示,保证原因(可能较长)看得清;同时不改动任何已保存配置。 */
    private fun showSaveFailure(reason: String) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("保存失败,设置未改动")
            .setMessage(reason)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---- 保存后让服务用上新配置 ----

    /**
     * 保存成功后让运行中的语音桥服务用上新配置。
     *
     * - 网关类型变了 → 保留原有「重启服务」逻辑(换的是整个后端实现,旧适配器无法继续用);
     * - 类型没变(只改了 host/端口/token/提示词等) → 发 [VoiceBridgeService.ACTION_RELOAD_SETTINGS],
     *   由服务重建适配器并重连。
     *
     * 这修的是个真实 bug:服务只在 [VoiceBridgeService] 启动时读一次设置,而原来只有类型变化才重启,
     * 于是只改 host/端口/token 时:保存返回成功、prefs 也写了,运行中的服务却还在用旧配置
     * (真机表现:顶部横幅一直「网关未配置,请在 App 设置中填写」,而 prefs 里 host/port/token 齐全)。
     */
    private fun applySavedServiceConfig() {
        if (settings.type != appliedType) {
            restartServiceIfTypeChanged(appliedType)
            return
        }
        // 重试循环可能在页面销毁后才落盘结束:context 已为 null 时跳过(下次服务启动自会读新类型)
        val appContext = context?.applicationContext ?: return
        VoiceBridgeService.reloadSettings(appContext)
        log("网关设置已保存,已通知语音桥服务重载配置(${settings.type})")
    }

    // ---- 网关类型变化 → 重启服务 ----

    /**
     * 保存成功后网关类型发生变化时,重启 [VoiceBridgeService],让它用新类型的适配器重新建链。
     *
     * 为什么只有类型变化才重启:适配器在服务启动时按类型一次性构造,
     * 改 host/token 等参数由下一次连接/重连自然生效,没必要中断正在进行的 BLE 与语音桥;
     * 而类型变化会换掉整个后端实现,旧适配器无法继续使用,必须重建。
     *
     * 校验失败时不会走到这里(类型根本没落盘),原配置继续生效。
     * stopService/startForegroundService 都是异步 binder 调用,不会阻塞主线程;
     * 两者之间用协程错开一小段时间,避免系统把 start 派发给尚未走完 onDestroy 的旧实例
     * (旧实例 initialized 仍为 true 时会直接 return,导致新类型不生效)。
     * 重启后服务会广播状态(见 VoiceBridgeService.publishStatus),主界面状态卡随之自更新。
     */
    private fun restartServiceIfTypeChanged(prevType: String) {
        val newType = settings.type
        if (newType == prevType) return
        appliedType = newType
        // 重试循环可能在页面销毁后才落盘结束:context 已为 null 时跳过重启(下次服务启动自会读新类型)
        val appContext = context?.applicationContext ?: return
        log("网关类型 $prevType → $newType,重启语音桥服务")
        scope.launch {
            VoiceBridgeService.stop(appContext)
            kotlinx.coroutines.delay(RESTART_GAP_MS)
            VoiceBridgeService.start(appContext)
            log("语音桥服务已按 $newType 重启")
        }
    }

    // ---- 小智激活 ----

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
                // URL/token 写死,激活后无需改配置
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

    /** 适配器状态回调(校验期间可能从 IO 线程来)→ 切主线程写日志。 */
    private fun publishLog(line: String) {
        scope.launch { log(line) }
    }

    private fun log(line: String) {
        val b = _binding ?: return
        val cur = b.logText.text.toString()
        b.logText.text = if (cur.isBlank() || cur == "(空)") line else "$cur\n$line"
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** 重启服务时 stop 与 start 之间的间隔,等旧实例走完 onDestroy。 */
        private const val RESTART_GAP_MS = 600L

        /**
         * 等待网关授权时的自动重试间隔(5s)。
         * 与「回复等待上限」解耦:那个是对话时等网关跑完工具的上限(15..900 可配),
         * 这里是“等人去控制台点批准”的上限,固定常量。
         */
        private const val PAIRING_RETRY_INTERVAL_MS = 5_000L

        /** 等待网关授权的最长等待时间(180s);到时给出明确文案并要求用户批准后重试。 */
        private const val PAIRING_RETRY_TIMEOUT_MS = 180_000L
    }
}
