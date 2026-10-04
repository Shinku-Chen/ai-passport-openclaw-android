package com.shinku.aipassport.openclaw.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.shinku.aipassport.openclaw.R
import com.shinku.aipassport.openclaw.ble.BleCentral
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
import com.shinku.aipassport.openclaw.service.KeepAliveState
import com.shinku.aipassport.openclaw.service.UpdateChecker
import com.shinku.aipassport.openclaw.service.VoiceBridgeService
import com.shinku.aipassport.openclaw.stt.XiaozhiActivator
import com.shinku.aipassport.openclaw.stt.XiaozhiBindGate
import com.shinku.aipassport.openclaw.stt.XiaozhiBinding
import com.shinku.aipassport.openclaw.stt.XiaozhiCredential
import com.shinku.aipassport.openclaw.stt.XiaozhiCredentialGate
import com.shinku.aipassport.openclaw.stt.XiaozhiCredentialStore
import com.shinku.aipassport.openclaw.stt.XiaozhiIdentity
import com.shinku.aipassport.openclaw.stt.XiaozhiOtaRequest
import com.shinku.aipassport.openclaw.stt.XiaozhiSaveStatus
import com.shinku.aipassport.openclaw.stt.XiaozhiSettings
import com.shinku.aipassport.openclaw.tts.TtsSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 设置页:网关类型(下拉)+ 三套网关配置(OpenClaw / Hermes / 自定义 OpenAI 兼容)+ 日志区。
 *
 * 三套配置分别持久化(见 [GatewaySettings]),切换类型只切可见分组,
 * 不丢配置。token/API key 只存本机,绝不进提交代码。
 * 每个字段都有常驻 label(hint 输入后会消失);开关项统一用 MaterialSwitch。
 * 语音桥服务常驻(主界面 onStart 自动拉起),因此页面上不再有启动/停止服务按钮。
 *
 * 「网关设置」= 网关类型下拉 + 该类型自己的连接参数 + 「保存网关设置」。
 *
 * 「小智识别」区块**只有一份**,在「高级」里(见 [Section.ADVANCED] 的视图清单):
 * 网关设置页不再重复摆一块同名 UI —— 两份并存的后果是同一状态被两个 section 各自拨可见性,
 * 行为/文案很容易分叉。
 *
 * 「保存网关设置」= 先用输入框里的草稿值校验连接(OpenClaw=WS 鉴权,Hermes=/health,
 * 自定义 OpenAI 兼容=/models 或最小对话请求,Echo=恒通),校验通过才落盘;
 * 失败一个字段都不写、原配置继续生效,原因用对话框展示。
 * 「小智 AI」没有可填的连接参数,但它走**另一道闸门**(且**只对它生效**):小智的设备绑定。
 * 保存「小智 AI」时**一律真的查一次云端激活状态**(复用 [XiaozhiActivator.queryCloud],本地 `bound_mac`
 * 只当提示、不作判据):云端未激活 → 弹 6 位绑定码并轮询授权,成功才落盘;云端已激活 → 允许保存,
 * 但必须弹框讲清楚并给出「要换账号就去 xiaozhi.me 删除设备再重新激活」的引导(不得静默保存);
 * 查询失败/超时/无网 → 不落盘 + 可读原因,下次保存重试。设备未连 → 拦截(见 [XiaozhiBindGate])。
 * 其余网关不触发查询/绑定,也不会因「设备未连接」被拦 —— 它们只把小智
 * 当识别引擎(见 [XiaozhiIdentity])。
 *
 * 「查云端」有**硬超时**([XiaozhiActivator.CLOUD_QUERY_TIMEOUT_MS],OTA 请求的 `callTimeout`
 * 与协程上的 `withTimeoutOrNull` 共用同一个值);保存按钮的复位写在 `finally` 里 ——
 * 任何路径(成功/失败/超时/异常/页面销毁)都必须回到「[XiaozhiSaveStatus.IDLE_BUTTON_TEXT] + 可点」,
 * 否则就是真机上的「点一次不返回就永远卡住」。
 *
 * 保存按钮**下方那行**是绑定流程状态的常驻出口(弹窗可能被用户关掉,这行留下结论与下一步):
 * 可见性/文案/按钮都交给纯逻辑 [XiaozhiSaveStatus](非小智与空闲时整行隐藏,单测钉住)。
 *
 * 小智侧的标识(Device-Id)按**当前网关类型**解析:小智 AI = 已连接设备的真 MAC;
 * 其余网关 = 全零匿名标识(见 [XiaozhiIdentity]);「高级」里的设备 ID 行与实际在用的一致。
 *
 * 「等待网关授权」(OpenClaw 设备未在网关被批准)单独处理:它不是配置错误 ——
 * 每 [PAIRING_RETRY_INTERVAL_MS] 自动重试一次、最长等 [PAIRING_RETRY_TIMEOUT_MS],
 * 批准后自动落盘并提示「授权完成,网关设置已保存」;期间按钮置灰显示「等待授权…」。
 * 与回复等待上限(180s 可配)无关,是两个独立常量。
 *
 * 三个「与网关无关」的开关/下拉走**切换即落盘**(不参与网关校验):
 * 「App 显示完整回传流（调试）」。
 *
 * 设备朗读(下行 TTS)默认开(`tts_enabled` 默认 true,固件播放通路已真机验收);
 * `tts_engine` 保留在 prefs(默认 android),当前只有系统 TTS 一种实现。
 *
 * 「设备朗读」有**两个入口**:「对话设置 → 设备朗读」与「网关设置 → 小智 AI → 播放小智语音」。
 * 两者读写的是**同一个** `tts_enabled`(小智模式下的音源就是小智下发的 opus,没有本地合成,
 * 所以那里的「设备朗读」就是「用小智的声音播」):任一处置位后另一处立刻同步成同一个值,
 * 渲染状态与可见性交给纯逻辑 [DeviceTtsSwitches](单测钉住「两处同源」「只在「小智 AI」下出现」)。
 * 本机合成不可用时**只置灰、不改值**,且小智 AI 下仍可点(见 [applyTtsAvailability])。
 *
 * 小智 AI 在网关设置里至今**只有这么一行专属控件**(识别/激活那整块 UI 仍只在「高级」)。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: GatewaySettings
    private lateinit var xzSettings: XiaozhiSettings
    private lateinit var xzBinding: XiaozhiBinding

    /** 提示词防抖落盘(见 schedulePromptSave)。 */
    private val promptSaveHandler = Handler(Looper.getMainLooper())
    private var promptSaveTask: Runnable? = null

    /** 最近一次落盘的语音附加提示(判断是否真的变了)。 */
    private var lastPromptSuffix: String = ""

    /** 「前台服务被系统拒绝」状态:决定警告与修复按钮是否可见(见 applyKeepAliveVisibility)。 */
    private var keepAliveDenied = false

    /**
     * 本机系统 TTS 探测结论(见 [applyTtsAvailability]):false = 正在探测或本机合成不可用。
     *
     * 两处「朗读」开关(**同一个设置**,见 [DeviceTtsSwitches])共用一个门控 —— 但**只决定是否可点**,
     * 不参与开关的值:不可用时两处一起置灰(小智 AI 下例外,它不经本机合成,见 [DeviceTtsSwitches.switchEnabled])。
     */
    private var ttsAvailable = false

    /**
     * 正在把状态回写到两处开关(见 [renderTtsSwitches])。
     *
     * 回写 isChecked 会触发另一个开关的监听器,用它把那次回调与「用户拨动」区分开,
     * 避免两处开关互相回拨。
     */
    private var applyingTtsSwitches = false

    /**
     * 当前语音桥服务实际使用的网关类型(页面加载时的落盘值)。
     * 保存成功且类型变化时才重启服务。
     */
    private var appliedType: String = GatewaySettings.TYPE_OPENCLAW

    /**
     * 语音桥服务广播的**最近一条状态**(「高级 → 小智识别」的连接状态行用)。
     * 与主界面顶部状态卡同源(ACTION_STATUS);"" = 本页还没收到过广播。
     */
    private var lastStatus: String = ""

    /** 语音桥状态广播接收器:只在「高级」里渲染小智的状态行(见 [renderXiaozhiStatus])。 */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val s = intent?.getStringExtra(VoiceBridgeService.EXTRA_STATUS) ?: return
            lastStatus = s
            renderXiaozhiStatus()
        }
    }

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
        xzBinding = XiaozhiBinding(requireContext())
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
                // 换了网关类型 = 上一轮的绑定流程状态已不适用(可能还挂着「绑定失败」),回到空闲并隐藏。
                // 流程正在跑时不清:按钮与状态行由那个流程的 finally 收尾,提前清会和终态打架。
                if (!XiaozhiSaveStatus.isBusy(xzSaveStage)) {
                    setXiaozhiSaveStage(XiaozhiSaveStatus.Stage.Idle)
                }
                log("网关类型已切换为 $type(点「保存网关设置」校验通过后生效)")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnActivateXz.setOnClickListener { activateXiaozhi() }
        setupSections()
    }

    // ---- 二级菜单(网关 / 对话 / 应用 / 高级)----

    /** 当前展开的 section。 */
    private enum class Section { GATEWAY, DEVICE, CHAT, APP, ADVANCED }

    /** 默认展开的 section(null = 一级菜单)。 */
    private var currentSection: Section? = null

    /**
     * 把原先一屏堆到底的设置项拆成二级菜单:一级只列四类入口,点进去才显示该类具体项。
     *
     * 为什么不用“隐藏整块容器”:原布局是平铺的(网关三套配置、对话项、应用项彼此穿插),
     * 把容器重新包裹一遍改动很大且容易把嵌套搞错;这里改成**按视图清单切显示**,
     * 每个 section 就是一组视图 id,切换时只拨 visibility。
     */
    private fun setupSections() {
        binding.btnMenuGateway.setOnClickListener { showSection(Section.GATEWAY, "网关设置") }
        binding.btnMenuDevice.setOnClickListener { showSection(Section.DEVICE, "设备管理") }
        binding.btnMenuChat.setOnClickListener { showSection(Section.CHAT, "对话设置") }
        binding.btnMenuApp.setOnClickListener { showSection(Section.APP, "应用设置") }
        binding.btnMenuAdvanced.setOnClickListener { showSection(Section.ADVANCED, "高级") }
        binding.btnBack.setOnClickListener { showSection(null) }

        // 「打开系统应用设置」必须**无条件**挂监听:旧实现只在检测到“前台服务被拒”时才挂,
        // 而按钮可见性会被 section 切换无条件打开 —— 于是出现「按钮亮着、点了没反应」
        // (用户真机反馈的「应用设置里面的打开系统应用无响应」就是这个原因)。
        binding.btnFixBackground.setOnClickListener { openSystemAppSettings() }

        // 系统返回键:在某个 section 里先退回菜单,再按一次才退出设置页。
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentSection != null) {
                        showSection(null)
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
        showSection(null)
    }

    private fun showSection(section: Section?, title: String? = null) {
        currentSection = section
        binding.settingsMenu.visibility = if (section == null) View.VISIBLE else View.GONE
        binding.btnBack.visibility = if (section == null) View.GONE else View.VISIBLE
        if (section != null && title != null) {
            binding.btnBack.text = "‹ 返回设置 · $title"
        }
        val sections = mapOf(
            Section.GATEWAY to listOf(
                binding.titleGateway, binding.labelGatewayType, binding.spinnerType,
                binding.textCleartextHint,
                // 「小智 AI」在本页的**唯一**专属控件:那行「播放小智语音」开关(它与「对话设置 → 设备朗读」
                // 是同一个设置,见 DeviceTtsSwitches)。识别/激活那整块 UI 仍只在「高级」，
                // 这里再摆一份就是两套 UI 拨同一批控件（见类 KDoc）。
                binding.groupXiaozhi,
                binding.groupOpenclaw, binding.groupHermes, binding.groupOpenai, binding.btnSave,
                // 保存按钮下方那行「绑定流程状态」：小智 AI 模式下的常驻提示（非小智/空闲整行隐藏）
                binding.xzBindStatus,
            ),
            Section.DEVICE to listOf(binding.groupDevice),
            Section.CHAT to listOf(
                binding.titleVoicePrompt, binding.inputVoicePromptSuffix,
                binding.checkShowRawStream, binding.checkTtsEnabled, binding.ttsHint,
            ),
            Section.APP to listOf(
                binding.checkBootAutoStart, binding.hintBootAutostart,
                binding.btnCheckUpdate, binding.updateStatusText,
                binding.textForegroundWarning, binding.btnFixBackground,
            ),
            // 「小智识别」的**唯一一份**（完整一块：标题/识别引擎+设备 ID/连接状态/说明/
            // 激活与绑定/激活结果）。服务广播只驱动这一份。
            Section.ADVANCED to listOf(
                binding.titleXiaozhi, binding.xzStatus, binding.xzConnectStatus,
                binding.xzGatewayHint, binding.btnActivateXz, binding.xzActiveStatus,
                binding.titleLog, binding.logText,
            ),
        )
        // 先全部收起来,再展开当前 section —— 避免上一次展开的项留在屏上。
        sections.values.flatten().forEach { it.visibility = View.GONE }
        if (section != null) {
            val views = sections.getValue(section)
            views.forEach { it.visibility = View.VISIBLE }
            // 网关三套配置的内部可见性由类型决定(见 applyTypeVisibility),
            // 这里把当前类型对应的那套放出来,其余保持隐藏。
            if (section == Section.GATEWAY) applyTypeVisibility()
            // 「高级」里的小智识别两行状态(设备 ID / 连接状态)由广播驱动,进页时先渲染一次。
            if (section == Section.ADVANCED) renderXiaozhiStatus()
            // 「对话设置」里依赖本地管线的两项在小智模式下隐藏(见 applyChatItemVisibility)。
            if (section == Section.CHAT) applyChatItemVisibility()
            // 「前台服务被拒」的警告/修复按钮是**状态驱动**可见性,不能跟着 section 无条件露出来。
            applyKeepAliveVisibility()
            binding.root.scrollTo(0, 0)
        }
    }

    // ---- 类型选择 ----

    /** 网关类型下拉选项(下标即 settings.type 的取值,顺序即 spinner position)。 */
    private val typeLabels =
        listOf("OpenClaw", "Hermes", "自定义 OpenAI 兼容", "Echo(本地回环)", "小智 AI")
    private val typeValues = listOf(
        GatewaySettings.TYPE_OPENCLAW,
        GatewaySettings.TYPE_HERMES,
        GatewaySettings.TYPE_OPENAI,
        GatewaySettings.TYPE_ECHO,
        GatewaySettings.TYPE_XIAOZHI,
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

    /**
     * 按当前选中的类型显示/隐藏各字段分组(Echo 不需要任何字段;小智 AI 只有那行朗读开关)。
     *
     * 「小智 AI」在本页**只有**那行「播放小智语音」开关(识别/绑定 UI 在「高级」，见类 KDoc):
     * 它只在选到小智 AI 时出现,切到其它四种类型(或选回小智 AI)都立即跟着切换。
     * 「是不是小智 AI」的判定在纯逻辑 [DeviceTtsSwitches.xiaozhiRowVisible] 里(与识别通道同源,单测钉住)。
     */
    private fun applyTypeVisibility() {
        val type = selectedType()
        binding.groupOpenclaw.visibility =
            if (type == GatewaySettings.TYPE_OPENCLAW) View.VISIBLE else View.GONE
        binding.groupHermes.visibility =
            if (type == GatewaySettings.TYPE_HERMES) View.VISIBLE else View.GONE
        binding.groupOpenai.visibility =
            if (type == GatewaySettings.TYPE_OPENAI) View.VISIBLE else View.GONE
        binding.groupXiaozhi.visibility =
            if (DeviceTtsSwitches.xiaozhiRowVisible(type)) View.VISIBLE else View.GONE
        // 按钮下方那行「绑定流程状态」随类型变（切成别的类型就隐藏)，所以跟着重画一次。
        renderXiaozhiSaveStatus()
    }

    /**
     * 「对话设置」里依赖**本地管线**的两项(语音附加提示 / 完整回传流)在小智模式下隐藏。
     *
     * 为什么:小智的正文与提示词都在云端 —— 附加提示不会参与它的对话,回传流也只有 `llm` 一条正文,
     * 留着会让用户以为改了有效。设备朗读开关**不**在其中:它在小智模式下控制的是
     * 「小智的音频要不要转发给设备」,语义仍然成立。
     *
     * 用已落盘的 settings.type(生效中的模式)而不是下拉框当前选择 —— 类型要保存后才真的切换。
     */
    private fun applyChatItemVisibility() {
        val v = if (settings.type == GatewaySettings.TYPE_XIAOZHI) View.GONE else View.VISIBLE
        binding.titleVoicePrompt.visibility = v
        binding.inputVoicePromptSuffix.visibility = v
        binding.checkShowRawStream.visibility = v
    }

    /**
     * 「高级 → 小智识别」的两行状态(唯一一份 UI，网关设置页不再有这块)。
     *
     *  - 识别引擎行:小智云端 + **实际在用的设备 ID**(与识别通道/绑定同一个 [XiaozhiIdentity.resolve]:
     *    小智 AI 模式 = 已连接设备的真 MAC;其余网关 = 全零匿名标识并附一句说明;小智模式没连设备 = 可读原因);
     *  - 连接状态行:语音桥服务的状态广播(与主界面顶部状态卡**同源**)。小智网关没有独立连接 ——
     *    它的「可收发」就是识别通道那条 WS 是否握手(见 `XiaozhiGateway.isReady`),
     *    这里不再引入第二套探活。
     */
    private fun renderXiaozhiStatus() {
        val b = _binding ?: return
        // 小智识别只有「高级」这一份 → 只在那个 section 里渲染(避免又出现第二套状态)。
        if (currentSection != Section.ADVANCED) return
        // 设备 ID 必须与**实际在用**的标识一致:同一个解析函数,不在这里另写一套类型判断。
        b.xzStatus.text = when (val id = currentIdentity()) {
            is XiaozhiIdentity.Resolution.DeviceMac ->
                "识别引擎:小智云端 · 设备 ID ${id.deviceId}"

            XiaozhiIdentity.Resolution.Anonymous ->
                "识别引擎:小智云端 · 设备 ID ${XiaozhiIdentity.ANONYMOUS_LABEL}\n" +
                    XiaozhiIdentity.DEVICE_MAC_HINT

            is XiaozhiIdentity.Resolution.Unavailable ->
                "识别引擎:小智云端 · 未连接设备\n${id.reason}"
        }
        b.xzConnectStatus.text =
            "连接状态:${lastStatus.ifBlank { "等待语音桥服务上报…" }}"
    }

    /**
     * 已连接对讲设备的蓝牙地址(原始值)。
     *
     * 用 [BleCentral.lastConnectedAddr] 这一个来源(与设备页/服务同一份 prefs);
     * 「取哪个标识」不在这里判断 —— 统一交给 [XiaozhiIdentity](见 [currentIdentity])。
     */
    private fun deviceAddress(): String? = try {
        BleCentral.lastConnectedAddr(requireContext())
    } catch (_: IllegalStateException) {
        // 视图已脱离 Activity:当作没有设备地址，不抛给调用方
        null
    }

    /**
     * 当前**生效中**网关类型下小智侧的标识(纯逻辑,与识别通道/绑定/状态行同一套规则)。
     *
     * 用已落盘的 [GatewaySettings.type](生效中的模式)而不是下拉框当前选择 —— 类型要保存后才真的切换
     * (保存后会重启服务,服务侧用同一个解析函数取值)。小智模式取不到设备地址时返回
     * [XiaozhiIdentity.Resolution.Unavailable],**不**回退匿名标识。
     */
    private fun currentIdentity(): XiaozhiIdentity.Resolution =
        XiaozhiIdentity.resolve(settings.type, deviceAddress())

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
        // 语音附加提示:用户要求「要不实时保存」—— 不设保存闸门,输入停手 0.7s 即落盘,
        // 并通知服务更新运行中的流水线(只影响上行文本,不会重连网关)。
        binding.inputVoicePromptSuffix.setText(settings.voicePromptSuffix)
        lastPromptSuffix = settings.voicePromptSuffix
        binding.inputVoicePromptSuffix.doAfterTextChanged { schedulePromptSave(it?.toString().orEmpty()) }
        // App 调试展示开关(默认开):切换即落盘(纯 App 展示,与网关连接无关,不走保存校验)
        binding.checkShowRawStream.isChecked = settings.showRawStream
        binding.checkShowRawStream.setOnCheckedChangeListener { _, checked ->
            settings.showRawStream = checked
        }

        // 开机自启（与网关无关，切换即落盘）：重启后由 BootReceiver 拉起前台服务。
        // 小米/HyperOS 还需用户在系统里开本应用的「自启动」，布局里已给提示。
        binding.checkBootAutoStart.isChecked = settings.bootAutoStart
        binding.checkBootAutoStart.setOnCheckedChangeListener { _, checked ->
            settings.bootAutoStart = checked
            log("开机自动启动：${if (checked) "开" else "关"}")
        }

        // 设备朗读（下行 TTS，默认开）：与网关无关，切换即落盘。
        // 打开后回复上屏之后再念一遍 —— 设备优先（需设备 hello 报 caps:["tts_opus"]，
        // 固件只在 Opus 解码器就绪时报），设备播不了才退回手机朗读；关闭时两边都不出声。
        // 服务启动时已打开则预热一次引擎，日志里会列出这台机器的可用音色与最终选用项。
        //
        // 这个设置**两处入口**:本页「对话设置 → 设备朗读」与「网关设置 → 小智 AI → 播放小智语音」
        // （小智模式下的音源就是小智下发的 opus,没有本地合成,所以两处就是同一件事）。
        // 两处读写同一个 `tts_enabled`、走同一个落盘/同步路径 [onTtsSwitchToggled],
        // 任一处置位后另一处立即同步 —— 见 [DeviceTtsSwitches] 与 [renderTtsSwitches]。
        bindTtsSwitches()
        // 手机自己合不成语音时,本机朗读那条路置灰并说明原因;
        // 但**不改** tts_enabled,且小智 AI 下这个开关仍可点(见 applyTtsAvailability)。
        applyTtsAvailability()

        // 版本更新（App 新版 / 设备固件新版）：显示上次结论，点一下立刻检查。
        binding.btnCheckUpdate.setOnClickListener { checkUpdateNow() }
        renderUpdateState()

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

        // 小智 URL/token 写死,不在 UI 展示;识别引擎/设备 ID/连接状态行统一由 renderXiaozhiStatus 渲染
        // (在「高级」里那一份上;本页只保存状态，不另外维护一套小智状态文案)。
        renderXiaozhiStatus()

        refreshKeepAliveWarning()
        // loadSettings 会把网关三套配置按类型置为可见 —— 若当前停在某个 section,
        // 需要把“只显示本 section”的状态重新拨回去(否则那三套会漏到一级菜单上)。
        if (currentSection != null) showSection(currentSection)
    }

    /**
     * 后台运行许可提示：前台服务被系统**静默拒绝**时把它摆到设置页上。
     *
     * 为什么要在设置页说：那种拒绝**不抛异常**（只写一条 `not allowed due to bg restriction` 系统日志），
     * App 除了自查通知标记外没有任何感知，界面看上去一切正常 ✗ ——
     * 而实际后果很重：服务降级成普通后台服务，App 闲置满 60s 被系统停掉，设备直接用不了
     * （真机实测 60.379s 精确复现，见 ServiceGuard）。
     */
    /**
     * 「设备朗读(TTS)」开关的可用性门控。
     *
     * 探测的是**本机系统 TTS 能不能用**(即「手机合成 → 下发」那条路);结果只决定开关**是否可点**,
     * **不决定开关的值** ——
     *  - 不可用时**不改** `tts_enabled`(既不写 false,也不恢复 true),开关保持用户/默认的值并置灰;
     *  - 当前网关是小智 AI 时**不置灰**(小智的音频由云端下发,不经本机合成,本机能力限制不到它);
     *  - 置灰时的提示会把原因与「小智 AI 下仍可开启」一起说出来(见 [DeviceTtsSwitches.unsupportedHint])。
     *
     * 为什么曾经是错的:旧实现探测到本机不支持合成就把 `tts_enabled` 落盘成 false 并置灰,
     * 真机上把用户的小智音色一并关掉了 —— 直通门打出 `enabled=false → 拦截`,小智音频一句没下发。
     *
     * 两处「朗读」开关是**同一个设置**(见 [DeviceTtsSwitches]),所以探测中/不可用时
     * 两处一起置灰(小智模式下则一起可点),不出现一灰一亮的分叉。
     */
    private fun applyTtsAvailability() {
        val appContext = context?.applicationContext ?: return
        // 探测本机合成能力:只用来决定**提示文案**(开关永远可点,见 bindTtsSwitches 的注释)。
        ttsAvailable = false
        renderTtsSwitches()
        scope.launch {
            val reason = TtsSupport.unsupportedReason(appContext)
            val b = _binding ?: return@launch
            ttsAvailable = reason == null
            b.ttsHint.text = DeviceTtsSwitches.hint(
                gatewayType = selectedType(),
                ttsSupported = ttsAvailable,
                deviceCapable = deviceTtsCapableSnapshot(),
                unsupportedReason = reason,
            )
            renderTtsSwitches()
            if (reason != null) log("本机不支持合成语音：$reason（开关不置灰；小智 AI 下由小智的声音朗读）")
        }
    }

    /** 服务侧落盘的"设备这一条连接上报过什么能力"(见 DeviceTtsSwitches.PREFS_DEVICE_CAPS)。 */
    private fun deviceTtsCapableSnapshot(): Boolean {
        val ctx = context?.applicationContext ?: return true
        val p = ctx.getSharedPreferences(DeviceTtsSwitches.PREFS_DEVICE_CAPS, android.content.Context.MODE_PRIVATE)
        // 从没上报过 → 视为"未知",给提示(与服务侧直通门的拦截理由同一件事)
        return if (!p.getBoolean(DeviceTtsSwitches.KEY_CAPS_SEEN, false)) false
        else p.getBoolean(DeviceTtsSwitches.KEY_TTS_CAPABLE, false)
    }

    // ---- 两处「朗读」开关(设备朗读 ⇄ 播放小智语音,同一个设置)----

    /**
     * 两处开关的初始化:回填同一个状态、挂同一个监听器。
     *
     * 两个开关读写的都是 [GatewaySettings.ttsEnabled](切换即落盘,不参与网关校验闸门,
     * 与既有「设备朗读」语义一致);默认开由设置项自己的默认值给出 ——
     * 这里只回填一次,用户手动关掉后 prefs 里就是 false,不会再被改写回 true。
     */
    private fun bindTtsSwitches() {
        // 小智分组里的开关已按作者要求移除,那里只留一行说明指向本开关。
        binding.xzTtsHint.text = DeviceTtsSwitches.XIAOZHI_CARD_LINE
        binding.checkTtsEnabled.setOnCheckedChangeListener { _, checked ->
            onTtsSwitchToggled(checked, DeviceTtsSwitches.ENTRY_CHAT)
        }
        renderTtsSwitches()
    }

    /**
     * 两处「朗读」开关的**唯一**拨动入口:切换即落盘,并把另一处同步成同一个值。
     *
     * 不走「保存网关设置」的校验闸门(那个闸门只管网关连接是否可用),与既有「设备朗读」行为一致;
     * 服务侧读的是同一份 SharedPreferences(`GatewaySettings.ttsEnabled`;小智直通门每帧实时读),
     * 所以这里落盘后**立即生效** —— 不需要重启服务、也不需要点「保存网关设置」。
     *
     * @param entry 用户拨的是哪一处入口(只进日志,便于对照「两处是同一个设置」)
     */
    private fun onTtsSwitchToggled(checked: Boolean, entry: String) {
        // 回写另一处开关触发的回调:不是用户操作,状态已经在同一个值上,直接忽略
        if (applyingTtsSwitches) return
        settings.ttsEnabled = checked
        log("$entry(TTS)：${if (checked) "开" else "关"}（设备朗读 / 播放小智语音是同一个开关，两处已同步）")
        renderTtsSwitches()
        // 打开时立刻预热合成引擎:马上把「引擎能不能用 + 有哪些可用音色」写进日志,
        // 不用等第一条回复(也方便在没有网关时先确认 TTS 是否可用)。
        if (checked) context?.let { VoiceBridgeService.prewarmTts(it) }
    }

    /**
     * 两处开关的**唯一**渲染出口(双向同步就发生在这里)。
     *
     * 勾选状态取同一个来源 [GatewaySettings.ttsEnabled](见 [DeviceTtsSwitches]),
     * 所以任一处拨动后调一次这里,另一处(若当前渲染着)立刻变成同一个值 ——
     * 不存在「网关一处、对话一处」两套状态。回写 isChecked 会触发另一个开关的监听器,
     * 用 [applyingTtsSwitches] 挡住,避免两处来回回拨。
     *
     * 「可点与否」与「勾选状态」是**两条独立的线**:可点由「本机合成是否可用」与「当前是不是小智 AI」
     * 决定(小智 → 一定可点),置灰**不会**动 checked(真机 bug:置灰时顺手把值写成 false,
     * 把小智音色也一并关掉了)。
     *
     * @param ttsSupported 本机系统 TTS 是否可用(探测中/不可用且非小智时两处一起置灰;
     *   只影响 `isEnabled`,不影响 `isChecked`。网关类型按下拉框当前选中值 [selectedType])
     */
    private fun renderTtsSwitches(ttsSupported: Boolean = ttsAvailable) {
        val b = _binding ?: return
        val view = DeviceTtsSwitches.view(selectedType(), settings.ttsEnabled, ttsSupported)
        applyingTtsSwitches = true
        try {
            // 只回写「勾选 / 可点」两种状态,**不**动 groupXiaozhi 的可见性:
            // 那一行的可见性归 section 机制 + 网关类型(见 applyTypeVisibility),
            // 在这里拨会把它漏到别的 section 上(比如在「对话设置」里拨一下设备朗读)。
            // 只在真的不一致时才写:既少一次回调,也不把正在拖动的开关打断
            if (b.checkTtsEnabled.isChecked != view.checked) b.checkTtsEnabled.isChecked = view.checked
            // 作者 2026-10-04 定:这个开关**永不置灰** —— 能不能出声由运行时决定,用户随时能开关。
            b.checkTtsEnabled.isEnabled = true
        } finally {
            applyingTtsSwitches = false
        }
    }

    /**
     * 刷新「前台服务是否被系统拒绝」的状态,并按状态摆好警告与修复按钮。
     *
     * 真机 bug(用户反馈「打开系统应用无响应」):旧实现把点击监听放在 `if (!denied) return`
     * **之后**,状态正常时按钮根本没挂监听;而 section 切换又会无条件把它拨成可见 ——
     * 结果就是一个亮着却点不动的按钮。
     */
    private fun refreshKeepAliveWarning() {
        keepAliveDenied = KeepAliveState(requireContext()).foregroundDenied
        if (keepAliveDenied) {
            binding.textForegroundWarning.text =
                "\u26a0\ufe0f 系统拒绝了本应用的前台服务(App 收不到异常)：" +
                    "锁屏/息屏约 1 分钟后服务会被系统停掉。" +
                    "请把本应用设为「自启动」并允许「后台无限制 / 无限制省电」。"
        }
        applyKeepAliveVisibility()
    }

    /**
     * 警告与修复按钮的可见性:只由「前台服务是否被系统拒绝」决定。
     * 单独抽出来是因为 section 切换也会拨可见性,不能让它俩被无条件拨亮。
     */
    private fun applyKeepAliveVisibility() {
        val v = if (keepAliveDenied) View.VISIBLE else View.GONE
        binding.textForegroundWarning.visibility = v
        binding.btnFixBackground.visibility = v
    }

    /**
     * 打开本应用在系统设置里的详情页(去开「自启动」「后台无限制」)。
     *
     * 逐级回退:真机上只发 ACTION_APPLICATION_DETAILS_SETTINGS 有跳不过去的情况,
     * 因此依次尝试 应用详情页 → 应用列表 → 设置首页,全部失败才退化成 Toast 指路
     * (任何时候都不要「点了没反应」)。
     */
    private fun openSystemAppSettings() {
        val pkg = requireContext().packageName
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                log("已打开系统设置(${intent.action})")
                return
            } catch (e: Exception) {
                log("打开系统设置失败(${intent.action}):${e.message}")
            }
        }
        toast("打不开系统设置,请手动到:设置 → 应用管理 → 本应用 → 自启动 / 省电策略")
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

            // 小智 AI 无字段(ws/OTA 地址与 token 写死在 XiaozhiSettings)。
            // **必须显式写**:落进 else 就会拿空表单去写 OpenClaw 配置,把用户已存的 OpenClaw 清掉。
            GatewaySettings.TYPE_XIAOZHI -> Unit

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
    /**
     * 保存/绑定过程中弹出的等待框(两个用途共用一个槽位，不会并发):
     *  - OpenClaw 的「等待网关授权」(见 [showApprovalDialog]);
     *  - 小智的「等待小智绑定」(见 [showXiaozhiBindDialog])。
     * 批准/绑定成功、超时或失败时关闭。
     */
    private var approvalDialog: AlertDialog? = null

    /**
     * 保存时遇到「等待网关授权」弹出等待框。
     *
     * 真机反馈:点「保存网关设置」后只看到按钮变字，不知道要去干什么。
     * 这里把原文(含 deviceId)与两条批准路径直接摆出来；不阻塞重试循环，批准后会自动关掉。
     */
    private fun showApprovalDialog(reason: String) {
        if (!isAdded || view == null) return
        if (approvalDialog?.isShowing == true) return
        val message = buildString {
            appendLine(reason.ifBlank { "$AWAITING_PAIRING_PREFIX$AWAITING_PAIRING_HINT" })
            appendLine()
            appendLine("怎么批准（二选一）：")
            appendLine("1) 浏览器打开网关 Web 控制台 → Devices / 设备 → 找到上面这串 deviceId → 批准；")
            appendLine("2) 在网关主机执行：openclaw devices list 找到待批设备，再 openclaw devices approve <deviceId>。")
            appendLine()
            append("批准后 App 会自动继续校验并保存（每 5 秒重试，最多等 180 秒）。")
        }
        approvalDialog = AlertDialog.Builder(requireContext())
            .setTitle("等待网关授权")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun dismissApprovalDialog() {
        try {
            approvalDialog?.dismiss()
        } catch (_: Exception) {
            // 视图已销毁等情况直接忽略
        }
        approvalDialog = null
    }

    // ---- 保存按钮 + 它下方那行「绑定流程状态」----

    /**
     * 保存按钮下方那行绑定流程状态的**当前阶段**(以及渲染它需要的数据)。
     *
     * 存阶段而不是直接存文案:切类型/重建视图时需要按当前类型与阶段重算一遍可见性与文案,
     * 统一交给纯逻辑 [XiaozhiSaveStatus](单测钉住「空闲/非小智隐藏」与「任何终局都回到可点」)。
     */
    private var xzSaveStage: XiaozhiSaveStatus.Stage = XiaozhiSaveStatus.Stage.Idle
    private var xzSaveMac: String? = null
    private var xzSaveCode: String? = null
    private var xzSaveReason: String? = null

    /**
     * 切换「绑定流程状态」行的阶段(同时按 [XiaozhiSaveStatus.button] 拨保存按钮)。
     *
     * 只有 [XiaozhiSaveStatus.Stage.QueryingCloud] / [XiaozhiSaveStatus.Stage.WaitingBind] 两个
     * 进行中阶段会把按钮置灰(防重复点击),其余阶段(含全部终局)**一律回到可点** ——
     * 流程必须有出口,否则就是真机上的「点一次不返回、永远卡住」。
     */
    private fun setXiaozhiSaveStage(
        stage: XiaozhiSaveStatus.Stage,
        mac: String? = null,
        code: String? = null,
        reason: String? = null,
    ) {
        xzSaveStage = stage
        xzSaveMac = mac
        xzSaveCode = code
        xzSaveReason = reason
        _binding?.let { b ->
            val button = XiaozhiSaveStatus.button(stage)
            b.btnSave.text = button.text
            b.btnSave.isEnabled = button.enabled
        }
        renderXiaozhiSaveStatus()
    }

    /**
     * 按**存下的阶段 + 当前选中的网关类型**重画那行状态(不动按钮)。
     *
     * 可见性交给 [XiaozhiSaveStatus.line]:非小智模式(不查云端、不绑定)与空闲时整行隐藏。
     * 类型看下拉框的当前选择,而不是已落盘的类型 —— 保存时生效的是将要写入的那个类型。
     */
    private fun renderXiaozhiSaveStatus() {
        val b = _binding ?: return
        val line = XiaozhiSaveStatus.line(
            selectedType(), xzSaveStage, xzSaveMac, xzSaveCode, xzSaveReason,
        )
        b.xzBindStatus.visibility = if (line.visible) View.VISIBLE else View.GONE
        b.xzBindStatus.text = line.text
        // 视图重建(切页/重进设置)会把按钮重新 inflate 成可点:流程还在跑就把置灰状态补回去,
        // 免得同一个保存流程能并发启动两次。流程结束时终态不是忙碌阶段,按钮由 restoreSaveButton 复位。
        if (XiaozhiSaveStatus.isBusy(xzSaveStage)) {
            val button = XiaozhiSaveStatus.button(xzSaveStage)
            b.btnSave.text = button.text
            b.btnSave.isEnabled = button.enabled
        }
    }

    /**
     * 保存按钮复位:文案回 [XiaozhiSaveStatus.IDLE_BUTTON_TEXT]、恢复可点。
     *
     * **所有**路径(成功/失败/超时/异常/取消)都得走这里:旧实现只在正常路径的最后一行复位,
     * 于是异常或超时就永远卡在「查询小智云端…」且点不动(真机反馈)。
     * 视图已销毁时跳过 —— 重新创建时会重新 inflate 成空闲态。
     */
    private fun restoreSaveButton() {
        _binding?.let { b ->
            b.btnSave.isEnabled = true
            b.btnSave.text = XiaozhiSaveStatus.IDLE_BUTTON_TEXT
        }
    }

    private fun saveSettings() {
        val form = snapshotForm()
        // 「小智 AI」没有任何可填的连接参数(ws/OTA 地址与 token 写死在 XiaozhiSettings,会话由识别通道提供),
        // 所以**不走探活闸门**:不造假的必填项,也不拿一个没有会话的适配器去 connect。
        // 但它有自己的另一道闸门 —— **小智设备绑定**([XiaozhiBindGate]):保存「小智 AI」时
        // **一律真的查一次云端激活状态**(复用 [XiaozhiActivator.queryCloud],不看本地 `bound_mac`):
        //  - 云端已激活 → 允许保存,但必须弹框讲清楚(不静默);
        //  - 云端未激活 → 发 6 位绑定码 + 网页引导 + 轮询授权,成功才落盘;
        //  - 查询失败/超时/无网 → 不落盘 + 可读原因,下次保存重试。
        // **这道闸门只对「小智 AI」生效**:其余类型拿到 NotXiaozhi 就直接落到下面的探活闸门,
        // 既不查云端、不触发绑定,也不会因为「设备未连接」被拦下。
        when (val gate = XiaozhiBindGate.beforeSave(form.type, deviceAddress())) {
            // 非小智网关(OpenClaw/Hermes/自定义/回显):原样继续,保存不受小智绑定与设备在线状态影响。
            XiaozhiBindGate.BeforeSave.NotXiaozhi -> Unit

            // 小智模式但设备没连(取不到真 MAC):拦住并给可读原因(不回退匿名标识,也不发任何请求)
            XiaozhiBindGate.BeforeSave.NoDevice -> {
                log("小智 AI 保存被拦截:未连接设备(未保存任何字段)")
                // 按钮下方那行也要说清结论与下一步(弹窗会被关掉,这行留下)
                setXiaozhiSaveStage(
                    XiaozhiSaveStatus.Stage.Failed,
                    reason = XiaozhiIdentity.NO_DEVICE_REASON,
                )
                showSaveFailure(
                    "${XiaozhiIdentity.NO_DEVICE_REASON}\n" +
                        "请在「设备管理」里连接对讲设备后，再点「保存网关设置」。"
                )
                return
            }

            // 小智模式且拿到真 MAC:**一律**查一次云端,按云端结论决定是否落盘(见 [saveXiaozhiAfterCloudQuery])
            is XiaozhiBindGate.BeforeSave.QueryCloud -> {
                saveXiaozhiAfterCloudQuery(form, gate.mac)
                return
            }
        }
        val draft = buildDraft(form) ?: return
        binding.btnSave.isEnabled = false
        binding.btnSave.text = "校验中…"
        log("校验网关连接(${form.type})…")
        // 用 applicationContext:重试循环可能跨页面销毁,不能持有 Fragment 的 view 生命周期上下文
        val ctx = requireContext().applicationContext
        scope.launch {
            // 整段包在 try/finally 里:异常/取消路径也必须把按钮复位 ——
            // 旧实现只在正常路径末尾复位,一旦抛异常就永远卡在「校验中…」且点不动。
            try {
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
                            // 需要授权就弹等待框（含 deviceId 与批准步骤），不用等超时才明白发生了什么
                            showApprovalDialog(result.reason)
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
                dismissApprovalDialog()
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
            } catch (e: CancellationException) {
                // 页面销毁/流程被取消:不落盘;视图可能已不存在,只记日志
                log("保存流程已取消,未保存任何字段")
                throw e
            } catch (e: Exception) {
                dismissApprovalDialog()
                val text = "保存时出现异常(设置未改动):${e.message ?: e.javaClass.simpleName}"
                log(text)
                showSaveFailure(text)
            } finally {
                // 视图可能已销毁(切页/离开设置页):落盘与重启服务仍然要完成(否则“批准后自动保存”会失效),
                // 只把界面提示降级 —— log()/showSaveFailure() 自己会在无视图/无 context 时安全退出。
                // 按钮复位放在 finally:成功/失败/超时/异常都没有别的出口。
                restoreSaveButton()
            }
        }
    }

    /** Toast 包装:视图已销毁(context 为 null) 时静默跳过,不让后台的重试循环把进程弄挂。 */
    /**
     * 手动检查更新：让服务立刻查一次（绕过 CDN 缓存），几秒后读回结论渲染。
     *
     * 失败不弹窗（服务侧静默保留上次结论，见 UpdateChecker）—— 就是个后台小检查，
     * 不该用「检查失败」打断用户。
     */
    private fun checkUpdateNow() {
        val appContext = context?.applicationContext ?: return
        binding.updateStatusText.text = "当前版本 ${currentVersionName()} · 正在检查更新…"
        appContext.startService(
            Intent(appContext, VoiceBridgeService::class.java)
                .setAction(VoiceBridgeService.ACTION_CHECK_UPDATE),
        )
        scope.launch {
            delay(6000)
            renderUpdateState()
        }
    }

    /** 渲染更新结论：有新版就列出来，没有就说「已是最新」，从没查过就说明会自动查。 */
    private fun renderUpdateState() {
        val b = _binding ?: return
        // 传当前版本：落盘文案里写着检查时的版本号，App 升级后就别再展示了（见 UpdateChecker）
        val s = UpdateChecker.cachedNotices(requireContext().applicationContext, currentVersionName())
        val version = currentVersionName()
        // 当前版本**始终显示**(用户要求「应用设置里能看到本 App 版本」):反馈问题时报版本号最有用。
        val lines = listOf(s.appNotice, s.firmwareNotice).filter { it.isNotBlank() }
        b.updateStatusText.text = when {
            lines.isNotEmpty() -> "当前版本 $version" + "\n" + lines.joinToString("\n")
            s.checkedAt <= 0L -> "当前版本 $version · 还没检查过；App 每天会自动检查一次"
            else -> "当前版本 $version · 已是最新"
        }
    }

    /** 本 App 的 versionName（运行时读取，不依赖 BuildConfig —— AGP 8 默认不生成它）。 */
    private fun currentVersionName(): String = try {
        val ctx = requireContext()
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

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
     * 落盘小智 AI 的网关设置，并提示用户。只在绑定/云端闸门放行后才调。
     */
    private fun persistXiaozhiAndApply(form: FormSnapshot, mac: String) {
        persistForm(form)
        // 落盘后用 prefs 回填一遍表单(与其它类型的校验-落盘闸门一致)
        if (view != null) loadSettings()
        log("小智 AI 设备 $mac 已就绪,网关设置已保存(类型 ${form.type})")
        toast("已保存")
        applySavedServiceConfig()
    }

    /** 小智「查云端 → 按闸门分流」的唯一执行路径的结果(保存与手动激活共用)。 */
    private sealed interface XzFlow {
        /** 云端已激活:允许保存/已可用。[notice] 必须展示给用户(不静默)。 */
        data class Activated(val mac: String, val notice: String) : XzFlow

        /** 云端未激活,但用户已完成 6 位码绑定:允许保存/已可用。[notice] = 绑定成功但没取到凭据等需要告知的事(空 = 无需额外提示)。 */
        data class Bound(val mac: String, val notice: String = "") : XzFlow

        /** 查询失败 / 绑定失败或超时:不落盘 + 可读原因。 */
        data class Failed(val reason: String) : XzFlow

        /**
         * 查云端的**硬超时**:不落盘,文案见 [XiaozhiSaveStatus.QUERY_TIMEOUT_REASON]。
         *
         * 与 [Failed] 分开的原因:用户要做的下一步不同 —— 超时直接用重试即可,
         * 不必去查配置;文案也必须说清「未保存」。
         */
        data object QueryTimeout : XzFlow
    }

    /**
     * 小智「查云端 → 按闸门分流」的**唯一**网络流程:查一次云端([XiaozhiActivator.queryCloud])
     * → 按 [XiaozhiBindGate.decide] 分流 → 需要绑定时发 6 位码并轮询授权。
     *
     * 两个入口(保存「小智 AI」、手动「激活小智设备」)都调这里,保证语义一致 ——
     * 已激活就提示(不再发码)、未激活才发码轮询、查询失败就不落盘。
     *
     * @param mac 已归一化的设备 MAC(调用方已过 [XiaozhiBindGate.beforeSave])
     * @param onCode 拿到 6 位绑定码后的 UI 回调（在主线程调用）
     *
     * 查云端套了**硬超时**（[XiaozhiActivator.CLOUD_QUERY_TIMEOUT_MS]）:超时返回 [XzFlow.QueryTimeout],
     * **不落盘**且调用方给可读文案 —— 没有它时保存按钮会无限期停在「查询小智云端…」。
     */
    private suspend fun runXiaozhiFlow(
        mac: String,
        onCode: (code: String, message: String) -> Unit,
    ): XzFlow {
        // 激活器不需要 Fragment 视图;页面已销毁时用 application context 把流程跑完（与保存其它分支一致）
        val ctx = context?.applicationContext
            ?: return XzFlow.Failed("设置页已关闭,请重新打开后重试")
        // 版本号优先用**设备固件版本**(hello.fw):设备真的在跑什么版本;服务没跑/设备没上报时
        // 回退 App 的 versionName(见 [XiaozhiOtaRequest.version])—— 不再写死 0.1.0。
        val activator = XiaozhiActivator(
            ctx,
            GatewaySettings.TYPE_XIAOZHI,
            mac,
            xzSettings.otaUrl,
            deviceFirmwareVersionProvider = { VoiceBridgeService.lastKnownFirmwareVersion() },
        )
        // 绑定得到的识别凭据(OTA 的 websocket url/token):**这次要落盘**——识别通道靠它握手。
        val credentials = XiaozhiCredentialStore(ctx)
        // 保存与手动激活都是小智模式 + 已知 MAC，所以闸门一定会查云端。
        // **硬超时**（[XiaozhiActivator.CLOUD_QUERY_TIMEOUT_MS]，与 OTA 请求的 callTimeout 同值）：
        // 查云端不能无限期不返回，否则保存按钮就永远停在「查询小智云端…」且点不动（真机反馈）。
        // 超时 = 未落盘，由调用方给可读文案并复位按钮。
        val query = withTimeoutOrNull(XiaozhiActivator.CLOUD_QUERY_TIMEOUT_MS) { activator.queryCloud() }
            ?: return XzFlow.QueryTimeout
        return when (val decision = XiaozhiBindGate.decide(
            GatewaySettings.TYPE_XIAOZHI, mac, query.state, xzBinding.boundMac, query.detail,
        )) {
            is XiaozhiBindGate.Decision.Activated -> {
                // 云端已激活 = 设备已可用。凭据可能还没在本机(换手机/清过数据/以前只判了「已激活」就把
                // OTA 响应丢掉):先把这次 OTA 的凭据落盘;没有就**再取一次** OTA(见 B4 自愈),
                // 仍拿不到只能明确告诉用户「删除后重新绑定」——否则识别会一直「无语音」。
                var saved = saveCredential(credentials, mac, query)
                if (!saved) {
                    val retry = withTimeoutOrNull(XiaozhiActivator.CLOUD_QUERY_TIMEOUT_MS) {
                        activator.queryCloud()
                    }
                    if (retry != null) saved = saveCredential(credentials, mac, retry)
                }
                if (!saved) log("云端已激活但未取到识别凭据:${XiaozhiCredentialGate.MISSING_CREDENTIAL_NOTICE}")
                XzFlow.Activated(
                    decision.mac,
                    if (saved) decision.notice else decision.notice + "\n" + XiaozhiCredentialGate.MISSING_CREDENTIAL_NOTICE,
                )
            }

            is XiaozhiBindGate.Decision.NeedBind -> {
                onCode(query.code.orEmpty(), query.message.orEmpty())
                val ok = activator.pollActivate(mac, query.challenge.orEmpty())
                when (XiaozhiBindGate.afterBind(ok)) {
                    XiaozhiBindGate.AfterBind.Persist -> {
                        // 绑定成功后云端才会给出可用的识别凭据:再取一次 OTA 并存下。
                        // 取不到 = 这次绑定没换回凭据,只能重新绑一遍(明确提示,不静默)。
                        val fresh = withTimeoutOrNull(XiaozhiActivator.CLOUD_QUERY_TIMEOUT_MS) {
                            activator.queryCloud()
                        }
                        val saved = fresh != null && saveCredential(credentials, mac, fresh)
                        if (!saved) log("绑定成功但未取到识别凭据:${XiaozhiCredentialGate.MISSING_CREDENTIAL_NOTICE}")
                        XzFlow.Bound(
                            mac,
                            notice = if (saved) "" else XiaozhiCredentialGate.MISSING_CREDENTIAL_NOTICE,
                        )
                    }

                    XiaozhiBindGate.AfterBind.KeepOldConfig -> XzFlow.Failed(
                        query.detail
                            ?: "小智绑定超时:请确认已在 xiaozhi.me 输入绑定码 ${query.code.orEmpty()} 后重试。"
                    )
                }
            }

            is XiaozhiBindGate.Decision.QueryFailed -> XzFlow.Failed(decision.reason)

            // 保存/激活路径已过 beforeSave;这里只为穷尽分支（理论上到不了）
            XiaozhiBindGate.Decision.NotXiaozhi ->
                XzFlow.Failed(XiaozhiIdentity.bindingNotApplicableReason(GatewaySettings.TYPE_XIAOZHI))

            XiaozhiBindGate.Decision.NoDevice -> XzFlow.Failed(XiaozhiIdentity.NO_DEVICE_REASON)
        }
    }

    /**
     * 把这一次 OTA 下发的识别凭据落盘(小智 AI 模式)。
     *
     * 只有 url 与 token **都**拿到的完整凭据才存(见 [XiaozhiCredential.fromOta]);
     * token 明文**不进日志**(只打长度与前 4 位)。
     *
     * @return 是否存下:false = 这次 OTA 没有完整凭据(调用方据此自愈重取或提示重新绑定)
     */
    private fun saveCredential(
        credentials: XiaozhiCredentialStore,
        mac: String,
        query: XiaozhiActivator.CloudQuery,
    ): Boolean {
        val credential = XiaozhiCredential.fromOta(query.wsUrl, query.wsToken) ?: return false
        credentials.save(mac, credential)
        log(
            "已保存小智识别凭据(设备 $mac):ws=${credential.url}" +
                " token=${XiaozhiOtaRequest.describeSecret(credential.token)}"
        )
        return true
    }

    /**
     * 保存「小智 AI」:先查云端(见 [runXiaozhiFlow]),**只有云端的结论允许时才落盘**。
     *
     * 与旧实现的区别(真机问题):原来命中「本地 `bound_mac` == 当前设备」就直接落盘、云端一次都不查,
     * 于是既不给绑定码也不告诉用户任何事。现在本地记录只是提示,判据永远是云端。
     */
    private fun saveXiaozhiAfterCloudQuery(form: FormSnapshot, mac: String) {
        // 按钮 + 按钮下方那行一起进「查询中」:只改按钮文案的话,用户不知道卡在哪一步、也不知道要不要等
        setXiaozhiSaveStage(XiaozhiSaveStatus.Stage.QueryingCloud, mac = mac)
        log("小智 AI 保存:查询云端激活状态(设备 $mac)…")
        scope.launch {
            // null = 流程被取消(页面销毁):此时不落盘、也不弹任何结果
            var outcome: XiaozhiSaveStatus.Outcome? = null
            /** 云端已激活时的说明文案;由 [showXiaozhiActivatedDialog] 在流程结束后展示。 */
            var activatedNotice: String? = null
            try {
                val flow = runXiaozhiFlow(mac) { code, msg ->
                    scope.launch {
                        _binding?.let { b ->
                            b.xzActiveStatus.text =
                                "请到 xiaozhi.me 登录→添加设备→输入绑定码:\n$code\n($msg)\n完成后自动检测…"
                        }
                        // 弹窗可能被用户点掉:按钮下方那行必须常驻绑定码与「正在等授权」,用户据此继续
                        setXiaozhiSaveStage(XiaozhiSaveStatus.Stage.WaitingBind, mac = mac, code = code)
                        // 保存动作可能是在「网关设置」页发起的，而绑定码专属于「高级」里的那块 UI；
                        // 用对话框把 6 位绑定码直接摆到眼前，不用用户自己去找那一页。
                        showXiaozhiBindDialog(code, msg)
                        log("请到 xiaozhi.me 输入绑定码 $code")
                    }
                }
                outcome = when (flow) {
                    is XzFlow.Activated -> {
                        // 云端已激活:保存允许，但必须先告诉用户(不静默)，并把本地记录同步成当前设备
                        xzBinding.boundMac = mac
                        persistXiaozhiAndApply(form, mac)
                        activatedNotice = flow.notice
                        log("小智设备 $mac 已在云端激活,网关设置已保存(无需重新绑定)")
                        XiaozhiSaveStatus.Outcome.Saved(mac)
                    }

                    is XzFlow.Bound -> {
                        // 绑定成功才记住这台设备 + 落盘；下次保存不会再绑一遍
                        xzBinding.boundMac = mac
                        persistXiaozhiAndApply(form, mac)
                        // 绑定成功但没换回识别凭据:必须明确告知「删除后重新绑定」(不能静默)
                        if (flow.notice.isNotBlank()) {
                            log("小智绑定成功,但:${flow.notice}")
                            showXiaozhiActivatedDialog(flow.notice)
                        }
                        XiaozhiSaveStatus.Outcome.Bound(mac)
                    }
                    is XzFlow.Failed -> XiaozhiSaveStatus.Outcome.Failed(flow.reason)

                    // 查云端硬超时:未落盘 + 可读文案(不是静默)
                    XzFlow.QueryTimeout -> XiaozhiSaveStatus.Outcome.QueryTimeout
                }
            } catch (e: CancellationException) {
                // 页面销毁/流程被取消:不落盘;按钮由 finally 复位
                log("已取消小智保存流程,未保存任何字段")
                throw e
            } catch (e: Exception) {
                outcome = XiaozhiSaveStatus.Outcome.Failed(
                    "保存小智 AI 时出错:${e.message ?: e.javaClass.simpleName}"
                )
            } finally {
                // **任何**路径（成功/失败/超时/异常）都必须把按钮复位成「保存网关设置 + 可点」,
                // 并把状态行落到终态：旧实现只在正常路径末尾复位,异常或超时就会永远卡在
                // 「查询小智云端…」且点不动（真机反馈）。取消时视图已不存在,状态行写不进去也无妨。
                restoreSaveButton()
                outcome?.let { applyXiaozhiSaveOutcome(it) }
            }
            dismissApprovalDialog()
            // 走到这里 outcome 一定有值(被取消时已经在上面 rethrow);以防万一再兜一层
            val result = outcome ?: return@launch
            when (result) {
                is XiaozhiSaveStatus.Outcome.Failed -> {
                    log("小智 AI 未保存:${result.reason}")
                    showSaveFailure("小智 AI 未保存(设置未改动,原配置继续生效)\n${result.reason}")
                }

                XiaozhiSaveStatus.Outcome.QueryTimeout -> {
                    log("查询小智云端超时,未保存任何字段")
                    showSaveFailure(XiaozhiSaveStatus.QUERY_TIMEOUT_REASON)
                }

                // 已激活/绑定成功:落盘已经完成,弹框只负责「不静默」地告知结论(绑定成功的结论在状态行上)
                is XiaozhiSaveStatus.Outcome.Saved ->
                    activatedNotice?.let { showXiaozhiActivatedDialog(it) }

                is XiaozhiSaveStatus.Outcome.Bound -> Unit
            }
        }
    }

    /**
     * 把一次小智保存的终局摆到界面上:状态行给终态、保存按钮回可点（任何终局都不例外）。
     *
     * 终局→阶段 / 阶段→文案与按钮的映射全在纯逻辑 [XiaozhiSaveStatus]（单测钉住）。
     */
    private fun applyXiaozhiSaveOutcome(outcome: XiaozhiSaveStatus.Outcome) {
        setXiaozhiSaveStage(
            XiaozhiSaveStatus.stageOf(outcome),
            mac = when (outcome) {
                is XiaozhiSaveStatus.Outcome.Saved -> outcome.mac
                is XiaozhiSaveStatus.Outcome.Bound -> outcome.mac
                else -> null
            },
            reason = (outcome as? XiaozhiSaveStatus.Outcome.Failed)?.reason,
        )
    }

    /**
     * 「云端已激活」提示框:让用户看到结论与「要换账号怎么重来」的下一步（保证「不静默」，
     * 也不会按住用户 —— 保存已经在弹框前完成）。
     */
    private fun showXiaozhiActivatedDialog(notice: String) {
        if (!isAdded || view == null) {
            // 页面已销毁:至少用提示保证「不静默」
            toast("该设备已在云端激活,网关设置已保存")
            return
        }
        dismissApprovalDialog()
        approvalDialog = AlertDialog.Builder(requireContext())
            .setTitle("小智设备已激活")
            .setMessage(notice)
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 绑定码等待框:与 [showApprovalDialog] 共用同一个对话框槽位(两者不可能并发)。
     *
     * 弹窗只是**放大器**:关掉它之后，按钮下方那行([renderXiaozhiSaveStatus])仍带着绑定码与
     * 「等待授权…」，用户不会因为随手点掉弹窗就不知道下一步做什么。
     */
    private fun showXiaozhiBindDialog(code: String, message: String) {
        if (!isAdded || view == null) return
        dismissApprovalDialog()
        val text = buildString {
            appendLine("小智绑定码:$code")
            if (message.isNotBlank()) appendLine("($message)")
            appendLine()
            appendLine("请在手机浏览器打开 xiaozhi.me → 登录 → 添加设备 → 输入上面的绑定码。")
            append("完成后 App 会自动继续并保存(最多等 60 秒)。")
        }
        approvalDialog = AlertDialog.Builder(requireContext())
            .setTitle("等待小智绑定")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .show()
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

            // 小智 AI 无字段。正常流程已在 [saveSettings] 里直接落盘、不会走到这里;
            // 这里显式列出是为了不让它默默落进 else 的 OpenClaw 分支。
            GatewaySettings.TYPE_XIAOZHI -> GatewayDraft.Xiaozhi

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

    /**
     * 手动「激活小智设备」:与「保存小智 AI」**共用同一套语义**([XiaozhiBindGate] + [runXiaozhiFlow])
     * —— 先看类型/设备,再**一律查一次云端**:已激活就提示(不发码)、未激活才发码轮询。
     * 不造第二条不一致的路径。
     */
    private fun activateXiaozhi() {
        if (!xzSettings.enabled()) {
            Toast.makeText(requireContext(), "请先填写并保存小智地址", Toast.LENGTH_SHORT).show()
            return
        }
        // 标识与识别通道**同一套规则**([XiaozhiIdentity],激活器内部解析):
        //  - 网关类型是小智 AI → 用已连接设备的真 MAC 绑;其它类型 → 压根不做绑定(给可读原因,不发请求);
        //  - 小智模式但没连设备 → 同样停手报错,不猜、不回退匿名。
        when (val gate = XiaozhiBindGate.beforeSave(settings.type, deviceAddress())) {
            XiaozhiBindGate.BeforeSave.NotXiaozhi -> {
                val reason = XiaozhiIdentity.bindingNotApplicableReason(settings.type)
                binding.xzActiveStatus.text = reason
                log("小智激活:不适用(网关类型 ${settings.type})")
                Toast.makeText(requireContext(), reason, Toast.LENGTH_LONG).show()
            }

            XiaozhiBindGate.BeforeSave.NoDevice -> {
                binding.xzActiveStatus.text = XiaozhiIdentity.NO_DEVICE_REASON
                log("小智激活被拦截:未连接设备")
                Toast.makeText(requireContext(), XiaozhiIdentity.NO_DEVICE_REASON, Toast.LENGTH_LONG).show()
            }

            is XiaozhiBindGate.BeforeSave.QueryCloud -> {
                binding.btnActivateXz.isEnabled = false
                binding.xzActiveStatus.text = "正在查询小智云端激活状态…"
                log("小智激活:查询云端…(设备 ${gate.mac},网关类型 ${settings.type})")
                scope.launch {
                    // null = 被取消(页面销毁):不弹任何结果;按钮由 finally 复位
                    var flow: XzFlow? = null
                    try {
                        flow = runXiaozhiFlow(gate.mac) { code, msg ->
                            // 拿到绑定码 → 主线程展示,让用户去 xiaozhi.me 绑定
                            scope.launch {
                                _binding?.let { b ->
                                    b.xzActiveStatus.text =
                                        "请到 xiaozhi.me 登录→添加设备→输入绑定码:\n$code\n($msg)\n完成后自动检测…"
                                }
                                log("请到 xiaozhi.me 输入绑定码 $code")
                            }
                        }
                    } catch (e: CancellationException) {
                        log("已取消小智激活流程(未改动任何配置)")
                        throw e
                    } catch (e: Exception) {
                        flow = XzFlow.Failed("激活小智设备时出错:${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        // 任何路径(含超时/异常)都要恢复可点:否则按钮永久置灰 = 真机上的「卡住」
                        _binding?.let { b -> b.btnActivateXz.isEnabled = true }
                    }
                    // 走到这里 flow 一定有值(被取消时已经在上面 rethrow);以防万一再兜一层
                    val result = flow ?: return@launch
                    when (result) {
                        is XzFlow.Activated -> {
                            // 云端已激活:提示即可(不重新发码),并同步本地记录
                            xzBinding.boundMac = gate.mac
                            _binding?.let { b -> b.xzActiveStatus.text = result.notice }
                            log("小智设备 ${gate.mac} 已在云端激活,无需重新绑定")
                            showXiaozhiActivatedDialog(result.notice)
                        }

                        is XzFlow.Bound -> {
                            // 手动激活成功 = 这台设备已可用:记下绑定,之后「保存网关设置」不必再绑一遍。
                            xzBinding.boundMac = gate.mac
                            // 绑定成功但没换回凭据时把下一步直接写在状态行上(同时 toast)
                            val warn = result.notice.trim().takeIf { it.isNotEmpty() }
                            _binding?.let { b ->
                                b.xzActiveStatus.text = "激活成功!小智识别已可用" +
                                    (warn?.let { "\n$it" } ?: "")
                            }
                            log(
                                "小智激活成功,设备 ${gate.mac} 已绑定" +
                                    (warn?.let { ";但 $it" } ?: "")
                            )
                            toast(warn ?: "激活成功,请重启语音桥服务")
                        }

                        is XzFlow.Failed -> {
                            _binding?.let { b -> b.xzActiveStatus.text = result.reason }
                            log("小智激活失败: ${result.reason}")
                            toast(result.reason)
                        }

                        XzFlow.QueryTimeout -> {
                            // 与「查云端失败」分开:超时直接用重试,不必去查配置
                            _binding?.let { b -> b.xzActiveStatus.text = XiaozhiSaveStatus.QUERY_TIMEOUT_REASON }
                            log("小智激活:查询云端超时(未改动任何配置)")
                            toast(XiaozhiSaveStatus.QUERY_TIMEOUT_REASON)
                        }
                    }
                }
            }
        }
    }

    /** 适配器状态回调(校验期间可能从 IO 线程来)→ 切主线程写日志。 */
    private fun publishLog(line: String) {
        scope.launch { log(line) }
    }

    /** 提示词防抖:打字过程中不写盘、不打扰服务。 */
    private fun schedulePromptSave(text: String) {
        promptSaveTask?.let { promptSaveHandler.removeCallbacks(it) }
        val task = Runnable { savePromptSuffix(text) }
        promptSaveTask = task
        promptSaveHandler.postDelayed(task, PROMPT_SAVE_DEBOUNCE_MS)
    }

    /** 立刻结算待落盘的提示词(离开页面时调用,避免丢掉最后一次编辑)。 */
    private fun flushPromptSave() {
        val pending = promptSaveTask
        promptSaveTask = null
        if (pending != null) {
            promptSaveHandler.removeCallbacks(pending)
            pending.run()
        }
    }

    /**
     * 落盘语音附加提示,并在值真的变了时通知服务重载。
     * 只影响上行文本,与网关连接无关(服务侧 reloadGatewaySettings 会把它同步给流水线)。
     */
    private fun savePromptSuffix(text: String) {
        val trimmed = text.trim()
        promptSaveTask = null
        if (trimmed == lastPromptSuffix) return
        settings.voicePromptSuffix = trimmed
        lastPromptSuffix = trimmed
        context?.applicationContext?.let { VoiceBridgeService.reloadSettings(it) }
        log("语音附加提示已保存(即时生效)")
    }

    private fun log(line: String) {
        val b = _binding ?: return
        val cur = b.logText.text.toString()
        b.logText.text = if (cur.isBlank() || cur == "(空)") line else "$cur\n$line"
    }

    override fun onStart() {
        super.onStart()
        // 小智模式的连接/就绪状态来自语音桥服务的状态广播;与其它页面一样在可见期间监听。
        val filter = IntentFilter(VoiceBridgeService.ACTION_STATUS)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                requireContext().registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                requireContext().registerReceiver(statusReceiver, filter)
            }
        } catch (e: Exception) {
            log("监听语音桥状态失败(小智状态行可能不刷新):${e.message}")
        }
        // 广播只在状态变化时发:进页面主动问一次(与设备页同款做法),否则小智的连接/就绪行会一直空着。
        try {
            requireContext().startService(
                Intent(requireContext(), VoiceBridgeService::class.java)
                    .setAction(VoiceBridgeService.ACTION_REQUEST_STATUS),
            )
        } catch (e: Exception) {
            log("请求语音桥状态失败:${e.message}")
        }
    }

    override fun onStop() {
        try {
            requireContext().unregisterReceiver(statusReceiver)
        } catch (_: IllegalArgumentException) {
            // 已注销/未注册:忽略
        }
        super.onStop()
    }

    override fun onDestroyView() {
        // 输完提示词立刻切页/切 Tab 时,最后一次编辑也要落盘。
        flushPromptSave()
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** 重启服务时 stop 与 start 之间的间隔,等旧实例走完 onDestroy。 */
        /** 语音附加提示的防抖落盘间隔(输入停手后才写盘)。 */
        private const val PROMPT_SAVE_DEBOUNCE_MS = 700L

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
