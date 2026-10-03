package com.shinku.aipassport.openclaw.gateway

import android.content.Context
import android.os.Build
import com.shinku.aipassport.openclaw.stt.XiaozhiLlmSource
import com.shinku.aipassport.openclaw.ui.ConversationStore

/**
 * 网关抽象层:流水线只面对这一个接口,不关心背后是 OpenClaw WebSocket、Hermes HTTP 还是本地回显。
 *
 * 约定(实现方必须遵守):
 *  - 所有失败都不向上抛异常:返回 false / null,并把可读原因写进 [lastError]。
 *  - [chat] 在被打断([interrupt])时返回 null;需要「已收到的部分消息」的调用方用 [chatMulti]。
 *    调用方仍必须用自己的轮次标记(如 VoicePipeline 的 turnId)丢弃过期回复。
 *  - 实现需自行保证线程安全(调用发生在 IO 协程与主线程回调两处)。
 */
interface GatewayAdapter {

    /**
     * 建链/鉴权;返回是否可用(可对话)。
     *
     * OpenClaw:建立 WS 并完成 connect 鉴权;Hermes:GET /health 探活;Echo:恒为 true。
     * 失败不抛异常,原因写进 [lastError]。
     */
    suspend fun connect(): Boolean
    /** 是否已完成鉴权、可收发。 */
    fun isReady(): Boolean

    /**
     * 发一段文本,返回完整回复文本;失败/超时/被打断返回 null(不抛异常)。
     *
     * 返回 null 时,可读失败原因在 [lastError](如「网关连接中断:…」);
     * 返回空串表示「网关确实回了 final 但文本为空」——真正的空回复,不是失败。
     *
     * 只想要「一条回复」的调用方(设置页校验、旧对话框)用它;
     * OpenClaw 一轮可能推多条回复,需要全部消息的调用方用 [chatMulti]。
     * @param text 用户输入(已由 STT 或对话框得到,不含任何协议包装)
     */
    suspend fun chat(text: String): String?

    /**
     * 一轮对话的**全部**回复(OpenClaw 一轮可能有多条 assistant 消息:答案 + 后续状态消息)。
     *
     * 默认实现 = [chat] 的单条回复包成单元素列表 —— Hermes / 自定义 OpenAI 兼容 / Echo
     * 行为不变。OpenClaw 重写它,把 [ReplyCollector] 收集到的整轮消息都返回。
     *
     * 失败/超时/被打断时返回**已收到的部分消息**(可能为空),可读原因在 [ChatReply.error]
     * (同时也写进 [lastError]);部分消息不上屏的语义由调用方按 `messages.isEmpty()` 判断。
     *
     * @param onRawUpdate 可选的**迟到帧增量**回调:仅 OpenClaw 支持(其余通道恒不回调)。
     *   网关终局(`lifecycle end` / `chat final`)之后仍会有帧到达(实测 final 与 end 同 seq,
     *   `item/tool/command_output` 更晚),OpenClaw 会在**终局后宽限窗**内把它们继续收进 raw,
     *   并把**新条目**(只含增量、不重复 [ChatReply.raw] 已发过的、按 seq 升序)分批回调到这里。
     *   同一窗内还会把 `chat.history` 里**本轮**的历史条目(标签见 `RawLabel.HISTORY_*`)
     *   以同样的增量形式回调(追加在流式条目之后)。
     *   回调可能在网关线程执行,实现必须线程安全且不得回调网关。**设备与 TTS 不受影响**:
     *   body 仍由 [ChatReply.messages] 立即返回,本回调只服务 App 的调试展示。
     * @param onBodyCorrection 可选的**正文补正/逐段交付**回调:[BodyDelivery]。
     *   OpenClaw 在**同一个终局后宽限窗**内查一次历史,当历史里的正文与流式 body **不同**时
     *   回调该正文([BodyDelivery.segmentOrdinal] = 0 = 不分段的整轮补正,调用方沿用旧语义:
     *   在 App 里**就地替换**本轮第一条正文气泡 + 多余正文降级)。
     *   小智 AI 按段交付:[BodyDelivery.isNewSegment] = true 的每一条都是**新的一段**
     *   ([BodyDelivery.segmentOrdinal] ≥ 1,调用方**追加**一条新气泡),false = 同一段变完整
     *   (调用方**就地替换该段那一条**,不新增)。
     *   相同/查不到/查询失败时**不会**回调(不重复上屏、不重复朗读),不额外增加设备侧时延。
     */
    suspend fun chatMulti(
        text: String,
        onRawUpdate: ((List<RawEntry>) -> Unit)? = null,
        onBodyCorrection: ((BodyDelivery) -> Unit)? = null,
    ): ChatReply {
        val one = chat(text)
        return if (one == null) {
            ChatReply(emptyList(), lastError?.trim()?.takeIf { it.isNotEmpty() })
        } else {
            ChatReply(listOf(one))
        }
    }
    /**
     * 打断在途回复收集(barge:回复中用户再次按住说话)。
     *
     * 必须真正取消在途请求(取消 OkHttp Call / 取消协程),不能只丢弃结果。
     */
    fun interrupt()

    /**
     * 最近一次错误的可读描述,供设置页与状态面板显示;无错误时为 null。
     * 成功一轮对话后清空。
     */
    val lastError: String?

    /**
     * 清掉上一次的错误(重载/重连**开始**时调用)。
     *
     * 目的:状态文案只允许拼接【本次尝试】的原因,绝不能把上一次的旧原因(例如概览页问了一个
     * 这台网关不认识的方法)带进「网关配置已重载,正在重连… ｜ …」里 —— 那看起来就是「网关不可达」。
     * 默认空实现:把错误缓存在字段里的通道需重写(见各实现)。
     */
    fun clearLastError() {}

    /**
     * 是否支持「用 `chat.history` 补正正文」(即 [chatMulti] 的 [onBodyCorrection] 会不会回调)。
     *
     * `VoicePipeline` 用它决定「命中状态话术的 body 要不要先缓发」:
     * 只有支持补正的通道(OpenClaw)才会在终局宽限窗内查历史并回调真答案,
     * 不支持的通道(Hermes / 自定义 OpenAI 兼容 / Echo)一律**立即下发**,绝不让设备白等。
     * 默认 false,只有 OpenClaw 重写。
     */
    val supportsBodyCorrection: Boolean get() = false

    /**
     * 本轮正文是否**按段交付**(小智 AI:同一轮 A、B、C 各一条,与设备屏一致)。
     *
     * true 时流水线把本轮**第一条**正文气泡当作**第 1 段**记账(日志 `App 气泡:新段(第 1 段)追加`);
     * 后续的每一段经 [chatMulti] 的 [onBodyCorrection] 以 [BodyDelivery.isNewSegment] = true 到达
     * (**追加**一条新气泡),同一段变完整则 false(**就地替换该段那一条**)。
     * 默认 false(OpenClaw / Hermes / 自定义 OpenAI 兼容 / Echo 仍是「一轮一条正文」)。
     */
    val deliversSegmentedBodies: Boolean get() = false

    /**
     * 是否由网关**自己**提供设备朗读音频(小智 AI:音频随会话下行的 opus 直通给设备)。
     *
     * true 时流水线**不做**本地合成 —— 既不下发本地合成的 `TTS_OPUS`,也不回退手机朗读:
     * 音频已经由网关侧的音色给出,再本地合成一遍就是两种声音叠着播。
     * 默认 false(OpenClaw / Hermes / 自定义 OpenAI 兼容 / Echo 仍是「文本进 → 本地合成」)。
     */
    val providesDeviceTtsAudio: Boolean get() = false

    /**
     * 是否处于「等待网关授权(设备未在网关被批准)」状态。
     *
     * 语义:这【不是】配置错误,而是可自动恢复的状态 —— 保持重连/重试循环,批准后下一次重试即恢复。
     * 设置页用它决定「继续等」还是「报错让用户改配置」。默认 false,只有做设备配对的实现
     * (OpenClaw 的 ed25519 设备配对)需要重写。
     */
    val isAwaitingPairing: Boolean get() = false

    /** 释放连接与线程资源;可重复调用。 */
    fun close()
}

/**
 * 保存前校验用的显式网关配置草稿:直接来自设置页输入框,不经过 SharedPreferences。
 *
 * 有了它,「保存」按钮就能先用草稿值建一个适配器探活,成功才落盘,
 * 失败时不会把无效草稿写进设置、也不需要再回滚。
 */
/**
 * 一轮网关对话的结果:本轮全部回复正文(body)+ 完整回传流(raw)+ 可读原因。
 *
 * @param messages 按到达顺序的全部正文(body);成功但网关没给正文时为空
 * @param error 失败/超时/中断的可读原因;完整成功时为 null
 * @param raw 本轮**全部**下行信息条目(状态/生命周期/步骤/工具/工具输出/正文/终局/用量),
 *   按到达顺序,一条不丢;只给 App 调试视图用,**设备屏与 TTS 只吃 [messages]**。
 *   非 OpenClaw 通道(单条回复)恒为空。
 */
data class ChatReply(
    val messages: List<String>,
    val error: String? = null,
    val raw: List<RawEntry> = emptyList(),
) {
    /** 完全没有可上屏的回复(失败或空回复)。 */
    val isEmpty: Boolean get() = messages.isEmpty()
}

/**
 * 网关交付的一条**助手正文**(逐段交付 / 历史补正),由 `chatMulti` 的 `onBodyCorrection` 回调。
 *
 * 背景:`onBodyCorrection` 旧签名只有字符串 —— 调用方只能把它当「替换当前正文气泡」处理,于是小智的
 * 多段正文(A、B、C)在 App 里被逐条覆盖,最后只剩最后一段(真机现象)。装配器
 * ([com.shinku.aipassport.openclaw.stt.XiaozhiReplyText])是唯一知道段界的地方,所以把段号与
 * 「新段 / 本段更新」一起交付到这里,由 UI 决定**追加**还是**就地替换那一段**。
 *
 * @param text 该条正文(已清洗,非空)
 * @param isNewSegment true = **新的一段**:调用方**追加**一条新的助手气泡;
 *   false = **本段更新**:调用方**就地替换该段**那条气泡(不新增、不动其它段)。
 * @param segmentOrdinal 段号(1 起,与小智设备屏「第 N 段」同一套编号);
 *   0 = 不分段的整轮正文补正(OpenClaw 历史补正,调用方沿用旧语义处理)
 */
data class BodyDelivery(
    val text: String,
    val isNewSegment: Boolean,
    val segmentOrdinal: Int = 0,
)

sealed interface GatewayDraft {
    /** OpenClaw:WS connect + Ed25519 鉴权。 */
    data class OpenClaw(val config: OpenClawConfig) : GatewayDraft

    /** Hermes:GET /health 探活(Bearer key)。 */
    data class Hermes(val config: HermesConfig) : GatewayDraft

    /** 自定义 OpenAI 兼容:优先 GET {basePath}/models,404/405 时退化到一次最小 chat/completions。 */
    data class OpenAi(val config: OpenAiConfig) : GatewayDraft

    /** 本地回显:恒通过。 */
    data object Echo : GatewayDraft

    /**
     * 小智 AI:没有可填的连接参数(ws/OTA 地址与 token 写死在
     * [com.shinku.aipassport.openclaw.stt.XiaozhiSettings],会话由识别通道提供),
     * 因此保存时**不做网络探活、直接落盘**(见 `SettingsFragment.saveSettings`)。
     * 保留显式草稿类型,保证这里的 `when` 不会把新类型默默归到 OpenClaw。
     */
    data object Xiaozhi : GatewayDraft
}

/**
 * 按设置里的网关类型构造对应适配器。
 *
 * 这里是唯一知道「有哪些网关实现」的地方:VoicePipeline 只依赖 [GatewayAdapter]。
 */
object GatewayFactory {

    /**
     * @param context 用于构造 OpenClaw 的设备身份与读取设置
     * @param settings 用户在设置页保存的网关配置
     * @param onStatus 状态文案回调(仅 OpenClaw 鉴权/配对过程会用到)
     * @param xiaozhiSession 小智 AI 网关要**共用**的会话(即识别通道那个 [XiaozhiLlmSource] 实例)。
     *   只有语音桥服务持有它;其余调用方(App 内探活 / 文本对话页)不传,
     *   此时小智网关会给出「只在设备语音链路里工作」的可读原因,而不是空等一个超时。
     */
    fun create(
        context: Context,
        settings: GatewaySettings,
        onStatus: (String) -> Unit = {},
        xiaozhiSession: XiaozhiLlmSource? = null,
    ): GatewayAdapter = when (settings.type) {
        GatewaySettings.TYPE_HERMES -> HermesGateway(
            config = settings.hermesConfig(defaultConversation(context)),
            // 历史由 ConversationStore 提供(客户端模式:每次带完整 messages)。
            historyProvider = { conversationHistory() },
            onStatus = onStatus,
        )

        GatewaySettings.TYPE_ECHO -> EchoGateway()

        // 小智 AI:不另建连接,复用识别通道那条会话去等 `llm` 正文(见 XiaozhiGateway)。
        GatewaySettings.TYPE_XIAOZHI -> XiaozhiGateway(source = xiaozhiSession)

        GatewaySettings.TYPE_OPENAI -> OpenAiCompatibleGateway(
            config = settings.openAiConfig(),
            // 历史由 ConversationStore 提供(客户端模式:每次带完整 messages,按 maxHistory 截断)
            historyProvider = { conversationHistory() },
            onStatus = onStatus,
        )

        else -> OpenClawGatewayRegistry.acquire(context, OpenClawConfig.of(settings), onStatus)
    }

    /**
     * 当前设置会构造出的适配器配置快照(判断保存成功后是否需要重载用,见 [needsGatewayReload])。
     *
     * 必须与 [create] 用同一套归一化规则(都走各通道的 Config 构造):
     * 否则「快照看起来变了」而适配器其实没变(或反之),会造成无谓重连或漏重载。
     */
    fun configSnapshot(context: Context, settings: GatewaySettings): GatewayConfigSnapshot =
        when (settings.type) {
            GatewaySettings.TYPE_HERMES ->
                GatewayConfigSnapshot.Hermes(
                    settings.hermesConfig(defaultConversation(context)),
                )

            GatewaySettings.TYPE_ECHO -> GatewayConfigSnapshot.Echo

            GatewaySettings.TYPE_XIAOZHI -> GatewayConfigSnapshot.Xiaozhi

            GatewaySettings.TYPE_OPENAI ->
                GatewayConfigSnapshot.OpenAi(settings.openAiConfig())

            else -> GatewayConfigSnapshot.OpenClaw(OpenClawConfig.of(settings))
        }

    /**
     * 用显式草稿配置构造适配器(保存前校验用)。
     * 只读草稿,不读写 SharedPreferences,因此校验失败不会留下任何脏配置。
     *
     * OpenClaw 这里【不】走 [OpenClawGatewayRegistry]:草稿只是试连一下,
     * 不能把正在用的共享连接换成草稿配置的地址;用完由调用方 close(),引用计数从 1 归零真正断开。
     *
     * @param context OpenClaw 需要它构造设备身份(signing key 仍从本机持久化存储读)
     * @param draft 设置页输入框的当前值
     */
    fun createFromDraft(
        context: Context,
        draft: GatewayDraft,
        onStatus: (String) -> Unit = {},
    ): GatewayAdapter = when (draft) {
        is GatewayDraft.Hermes -> HermesGateway(
            config = draft.config.copy(
                conversation = draft.config.conversation.ifBlank { defaultConversation(context) },
            ),
            historyProvider = { conversationHistory() },
            onStatus = onStatus,
        )

        GatewayDraft.Echo -> EchoGateway()

        // 小智 AI 的适配器必须**共用**识别通道那条会话,而保存校验这里拿不到它(草稿只用于探活)。
        // 因此 source = null:若真被 connect() 会立刻返回可读原因,不会挂死;
        // 设置页对 TYPE_XIAOZHI 不走探活闸门(直接落盘),所以正常不会走到 connect()。
        GatewayDraft.Xiaozhi -> XiaozhiGateway(source = null)

        is GatewayDraft.OpenAi -> OpenAiCompatibleGateway(
            config = draft.config,
            historyProvider = { conversationHistory() },
            onStatus = onStatus,
        )

        is GatewayDraft.OpenClaw -> OpenClawGateway(context, draft.config, onStatus)
    }

    /**
     * Hermes 服务端会话名:优先用设备名/deviceId,让服务端按这个 key 串历史。
     * 设备名取 BLE 侧记住的最近设备地址(无则退回机型),去掉分隔符后加固定前缀,
     * 保证同一台机器 + 同一台设备每次生成同一个会话名。
     */
    fun defaultConversation(context: Context): String {
        val addr = try {
            context.getSharedPreferences("ble_central", Context.MODE_PRIVATE)
                .getString("last_device_addr", "") ?: ""
        } catch (_: Exception) {
            ""
        }
        val raw = addr.ifBlank { Build.MODEL ?: "android" }
        val token = raw.filter { it.isLetterOrDigit() }.takeIf { it.isNotBlank() } ?: "android"
        return "ai-passport-$token"
    }

    /**
     * 取共享对话历史(ConversationStore)并转成 role→text 列表。
     *  - "agent" → "assistant"(OpenAI 角色名)
     *  - 过滤 UI 占位消息("…"、空串),它们不是真实对话内容
     *
     * 这里不截断:历史上限由各网关自己决定(见 [OpenAiCompat.buildClientMessages] 的 maxHistory),
     * Hermes 固定 20 条,自定义 OpenAI 兼容通道用用户配置的条数,避免两处上限互相覆盖。
     */
    fun conversationHistory(): List<Pair<String, String>> =
        ConversationStore.messages.value
            .asSequence()
            .mapNotNull { m ->
                val text = m.text
                val trimmed = text.trim()
                if (trimmed.isEmpty() || trimmed == "…") return@mapNotNull null
                (if (m.role == "agent") "assistant" else "user") to text
            }
            .toList()
}
