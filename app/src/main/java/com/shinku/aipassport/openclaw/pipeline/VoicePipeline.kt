package com.shinku.aipassport.openclaw.pipeline

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.shinku.aipassport.openclaw.gateway.ChatReply
import com.shinku.aipassport.openclaw.gateway.GatewayAdapter
import com.shinku.aipassport.openclaw.gateway.OpenClawGateway
import com.shinku.aipassport.openclaw.gateway.RawEntry
import com.shinku.aipassport.openclaw.gateway.RawLabel
import com.shinku.aipassport.openclaw.gateway.VoicePrompt
import com.shinku.aipassport.openclaw.protocol.VbFrame
import com.shinku.aipassport.openclaw.protocol.VbFrameData
import com.shinku.aipassport.openclaw.protocol.VersionCompat
import com.shinku.aipassport.openclaw.stt.SttEngine
import com.shinku.aipassport.openclaw.tts.DeviceTtsSession
import com.shinku.aipassport.openclaw.tts.TtsEngine
import com.shinku.aipassport.openclaw.tts.TtsPlaybackReport
import com.shinku.aipassport.openclaw.tts.parseTtsPlaybackReport
import com.shinku.aipassport.openclaw.tts.ttsPlaybackLogLine
import com.shinku.aipassport.openclaw.ui.BodySource
import com.shinku.aipassport.openclaw.ui.ConversationStore
import com.shinku.aipassport.openclaw.ui.bodyFlag
import com.shinku.aipassport.openclaw.ui.bodySourceOf
import com.shinku.aipassport.openclaw.ui.replyDisplayEntries
import com.shinku.aipassport.openclaw.ui.statusTalkFlag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 状态话术 body 缓发时最多等多久历史补正判定。
 *
 * 必须 > [OpenClawGateway.POST_TERMINAL_GRACE_MS](`chat.history` 查询就被这个窗包住),
 * 再加 1s 余量:保证「补正已到」永远优先于「补发缓存的流式 body」,
 * 而「宽限窗结束仍未定论」一定能把缓存补发给设备(设备不能空着)。
 */
private const val HELD_BODY_WAIT_MS = OpenClawGateway.POST_TERMINAL_GRACE_MS + 1_000L

/**
 * 本轮正文(body)在 App 里的记账(见 [VoicePipeline] 的 `resolveTurnBody` / `replaceBodyBubbles`)。
 *
 * 每轮 `turn_start` 重建一个;[turn] 是本轮编号,补正/超时结算时用它确认「还是同一轮」
 * (barge 后旧轮一律丢弃)。字段只在 [VoicePipeline] 内部使用。
 *
 * @param turn 本轮编号(与 `VoicePipeline.turnId` 相等)
 */
private class TurnBodies(val turn: Int) {
    /** 已写入 App 的正文(body)气泡:id → 写入时的文本(补正时就地替换;多余气泡降级用)。 */
    val bubbles = mutableListOf<Pair<Long, String>>()

    /** 缓发等历史判定的状态话术正文(未下发设备;通常 0 或 1 条)。 */
    val held = mutableListOf<String>()

    /** 正文是否已结算(历史补正到达 / 宽限窗超时),保证同轮只结算一次。 */
    var resolved = false
}

/**
 * 语音流水线编排:
 *
 *  AUDIO 帧 --STT--> 文本 --网关--> 回复 --TTS--> 播放 --合成结束--> TEXT 帧回传固件上屏
 *
 * 音频帧(v1 协议)首字节都是 [SEQ:1B](1 字节回绕,只用于丢帧统计),这里先剥掉 SEQ
 * 再交给识别端:Opus 帧原样转给 [SttEngine.feedOpus](不再本地解码/重编码),
 * PCM 兜底帧走 [SttEngine.feedPcm]。每轮 turn_start 重置 SEQ 统计,丢帧率打进日志/状态面板。
 *
 * 由固件 EVENT(turn_start / turn_end)驱动一段对话的起止:
 *  - turn_start:打断任何进行中的 TTS/识别(即 barge,用户再按 PTT 立即打断),
 *    开始新一轮 STT;STT **真的就绪**后向设备下发 `turn_ready`,设备把「按下即红」变绿
 *    (见 [onRecordingReady] / [TurnReadyGate])。小智通道常驻预热([prewarm]+
 *    `endTurn` 后保留热连接)后,按下时可直接 `listen.start` → 就绪回调毫秒级到达。
 *  - AUDIO:喂 STT。
 *  - turn_end:STT 出文本 → 网关回复 → TTS 播放;TTS 正常结束才把文本回传设备。
 *
 * [sendText] 由 Service 注入,负责把 TEXT 帧经 BLE 发给固件。
 */
class VoicePipeline(
    private val scope: CoroutineScope,
    private val stt: SttEngine,
    gateway: GatewayAdapter,
    private val tts: TtsEngine,
    /**
     * 设备朗读(下行 TTS:M1 = 手机合成 PCM → Opus → `TYPE_TTS_OPUS` 帧)的编排。
     *
     * 默认 [DeviceTtsSession.disabled](未接线 = 不下发);服务侧按设置项 `tts_enabled`
     * (默认关)与 `tts_engine` 接进来。本流水线只调它的三个点:
     *  - 每轮 `turn_start` → [DeviceTtsSession.onTurnStart](无条件 `tts_abort`,掐掉设备上仍在播的旧回复);
     *  - 网关回复上屏（`'A'` 文本帧）**之后** → [DeviceTtsSession.onReply](异步,可被 barge 取消);
     *  - `turn_start` 之外的中断(断开/销毁) → [DeviceTtsSession.onCancel]。
     * 设备回报 `tts_playback_done` / `tts_playback_aborted` → [DeviceTtsSession.onDeviceEvent]。
     */
    private val deviceTts: DeviceTtsSession = DeviceTtsSession.disabled(),
    /** 回传 TEXT 帧给固件上屏。role: 'U'=用户语音识别文本,'A'=网关回复。 */
    private val sendText: (role: Char, text: String) -> Unit,
    /**
     * 向设备下发一条 EVENT 帧(JSON);返回 false = 设备未连接/未初始化(调用方只记日志)。
     * 目前只用于 [TURN_READY_EVENT_JSON](录音/识别真的就绪,设备侧「按下即红、就绪变绿」)。
     */
    private val sendEvent: (String) -> Boolean = { false },
    private val onState: (String) -> Unit,

    /**
     * 设备信息(固件版本等)补齐时回调一次:设备页要显示固件版本,而版本是随设备 hello
     * 才到的(晚于链路就绪),所以需要这一次通知去刷新广播。
     */
    private val onDeviceInfo: () -> Unit = {},
    private val clearPendingWrites: () -> Unit = {},
    /**
     * 语音输入末尾自动附加的提示词(设置页可改,见 [VoicePrompt]);
     * 只影响【发给网关】的文本,【不上屏】到设备。文字输入不追加。
     */
    voicePromptSuffix: String = "",
    /**
     * App 对话列表是否展示**完整回传流(raw)**;默认 true(调试用)。
     * 关掉后 App 也只展示 body(正确正文),与设备屏/TTS 一致,适合正式演示。
     * 读设置里「App 显示完整回传流(调试)」开关,每次回复时取値(不缓存,改开关即时生效)。
     */
    private val showRawStream: () -> Boolean = { true },
    /**
     * 「设备朗读回复（TTS）」开关(设置项 `tts_enabled`,默认**关**)。
     *
     * 打开后:网关回复**上屏之后**会被念出来 —— 优先让**设备**念(需要设备在 hello 里报
     * `caps:["tts_opus"]`),设备播不了才退回**手机自己念**;关闭时两边都不出声。
     */
    private val ttsEnabled: () -> Boolean = { false },
    /** 本 App 的 `versionName`(服务构造时从 PackageManager 读):用于固件/App 版本比对。 */
    private val appVersion: String = "",
) {
    private val tag = "VoicePipeline"
    private val gson = Gson()

    /** 设备 hello 的 caps 里表示「支持下行朗读」的能力名(逐字与固件一致)。 */
    private val CAP_TTS_OPUS = "tts_opus"

    /** 本次连接是否已经检查过设备 hello 的版本(断开时重置,避免每次重连重复提示)。 */
    private var helloChecked = false

    /**
     * 设备是否声明支持下行朗读(设备 hello 的 `caps` 里有 `tts_opus`)。
     *
     * 固件**只在 Opus 解码器就绪时**才报这个能力:没报就说明这台设备播不了 —— 此时绝不能往设备推
     * TTS 音频(白推一堆帧,设备还会回 `tts_playback_aborted`),应改为让手机自己念([speakReply])。
     */
    @Volatile
    private var deviceTtsCapable = false

    /**
     * 设备上报的固件版本（`hello.fw`）；未上报为 null。
     * 「固件有新版本」的提醒要用它（见 UpdateCheck）：设备自己不上网，只能由 App 代勞。
     */
    @Volatile
    var deviceFirmwareVersion: String? = null
        private set

    /**
     * 设备是否在 hello 里声明了 `tts_opus`(支持下行朗读)。
     *
     * 小智 TTS 直通也据此门控(见 `XiaozhiTtsRelay`):设备没报这个能力就**绝不能**发 0x06
     * 音频帧 —— 固件会把未知类型当错位处理,连带丢掉它后面一帧。
     */
    val deviceTtsSupported: Boolean get() = deviceTtsCapable

    /**
     * 处理设备的 hello:核对固件/App 版本是否配套。
     *
     * 版本号按【大版本 X.Y 相同即配套】的约定比对（[VersionCompat]）：App 可以发小版本
     * （1.11 → 1.11.1），那不是不匹配；只有大版本不同才提示。不一致时向用户提示两处：
     *  - App 侧:走 [onState] 上抛,状态卡显示可读原因;
     *  - 设备侧:回一条文字气泡(`sendText`),设备屏也能看到。
     * 信息不足(老固件不上报 fw/proto)不提示;每次连接只提示一次。
     */
    private fun onDeviceHello(obj: JsonObject?) {
        if (helloChecked) return
        helloChecked = true
        val fw = obj?.get("fw")?.takeIf { it.isJsonPrimitive }?.asString
        deviceFirmwareVersion = fw?.trim()?.takeIf { it.isNotEmpty() }
        // 固件版本到齐了:通知服务重发一次状态,设备页才能显示出版本号
        onDeviceInfo()
        val proto = obj?.get("proto")?.takeIf { it.isJsonPrimitive }?.asInt
        // 设备能力:hello 的 caps 里有没有 tts_opus(固件只在解码器就绪时报,见协议文档)
        val caps = obj?.get("caps")?.takeIf { it.isJsonArray }?.asJsonArray
        deviceTtsCapable = caps?.any { it.isJsonPrimitive && it.asString == CAP_TTS_OPUS } == true
        Log.i(
            tag,
            "设备能力: 下行朗读=${if (deviceTtsCapable) "支持(tts_opus)" else "不支持"}" +
                " caps=${caps ?: "未上报"}",
        )
        val notice = VersionCompat.check(appVersion, fw, proto)
        if (notice == null) {
            Log.i(tag, "版本一致:App $appVersion / 固件 ${fw ?: "未上报"}")
            return
        }
        Log.w(tag, "版本不配套: $notice")
        onState(notice)
        sendText('A', "版本提示：$notice")
    }

    /**
     * 当前使用的网关适配器。设置页保存后由 [updateGateway] 换成新配置重建的实例
     * (不重启服务、不动 BLE 链路)。读取发生在 IO 协程,故标 @Volatile。
     */
    @Volatile
    var gateway: GatewayAdapter = gateway
        private set

    /** 当前生效的语音附加提示词;随设置保存实时更新。 */
    @Volatile
    private var voicePromptSuffix: String = voicePromptSuffix

    /** 一轮结束后回到的状态文案:与 VoiceBridgeService 的「已就绪」用同一句,便于状态卡/圆点判定一致。 */
    private val readyText = "已就绪,长按设备 OK 说话"

    /** 每次 turn_start 自增,用于判定结果是否已过期(barge)。TTS 回调在 binder 线程,需 volatile。 */
    @Volatile
    private var turnId = 0

    /** 本轮正文记账(每轮重建);补正/超时结算时用它就地替换 App 正文气泡、补发缓发的 body。 */
    @Volatile
    private var turnBodies = TurnBodies(0)

    /**
     * `turn_ready`(设备侧「按下即红、就绪变绿」)的去重闩:同一轮只放行一次,
     * barge 后的旧轮就绪回调一律丢弃(见 [TurnReadyGate])。
     */
    private val turnReady = TurnReadyGate()

    @Volatile
    private var turnActive = false

    // ---- 音频 SEQ 丢帧统计(每轮重置)----

    /** 上一帧 SEQ;-1 表示本轮还没收到过音频帧。 */
    private var lastSeq = -1

    /** 本轮收到的音频帧数(含重复/乱序帧)。 */
    private var seqReceived = 0

    /** 本轮按 SEQ 缺口推算的丢帧数。 */
    private var seqLost = 0

    /**
     * 预热识别通道(后台静默,不阻塞主线程):小智会先建一条「热连接」(WS 握手 + hello),
     * 下一轮按下时直接 `listen.start`,设备侧 `turn_ready` 毫秒级到达(目标 < 300ms)。
     * 服务启动与 BLE 链路就绪时各调一次;失败只记日志,不影响「按下才连」。
     */
    fun prewarm() {
        scope.launch(Dispatchers.Default) { stt.prewarm() }
    }

    /** 识别通道是否已预热可用（通知栏「语音」状态用）。 */
    fun recognizerWarm(): Boolean = stt.isWarmReady()

    /**
     * 设置保存后由服务调用:把网关适配器换成按新配置重建的实例。
     * 不触碰 BLE/STT/TTS;在途的一轮对话仍会走旧适配器自然结束(下一轮用新实例)。
     */
    fun updateGateway(newGateway: GatewayAdapter) {
        gateway = newGateway
    }

    /**
     * 设置保存后由服务调用:更新语音附加提示词。
     * 与连接无关,因此即使网关配置未变也要同步(否则改了提示词要重启 App 才生效)。
     */
    fun updateVoicePromptSuffix(suffix: String) {
        voicePromptSuffix = suffix
    }

    /** 从帧重组器来的完整帧。 */
    fun handleFrame(frame: VbFrameData) {
        // 调试:打印吐出的每帧 type + payload 前缀(定位帧重组错位)
        val dbg = frame.payload.take(24).joinToString(" ") { "%02x".format(it) }
        Log.i(tag, "帧 type=${frame.type} flags=${frame.flags} len=${frame.payload.size}: $dbg")
        when (frame.type) {
            VbFrame.TYPE_AUDIO -> handleAudio(frame.payload, opus = false)
            VbFrame.TYPE_OPUS -> handleAudio(frame.payload, opus = true)   // v1 默认上行:Opus 原样转发
            VbFrame.TYPE_EVENT -> handleEvent(frame.payload)
            VbFrame.TYPE_TEXT -> Log.d(tag, "设备回传文本(忽略): ${String(frame.payload, Charsets.UTF_8)}")
            VbFrame.TYPE_CONTROL -> Unit
            else -> Unit
        }
    }

    /** BLE 断开:终止一切在途状态,并让识别引擎立刻关闭常驻热连接(设备没了,留着 socket 无用)。 */
    fun onDisconnected() {
        turnId++
        turnActive = false
        tts.stop()
        deviceTts.onCancel()
        stt.onLinkDown()
        helloChecked = false   // 新一次连接:版本提示重新计一次
        deviceTtsCapable = false   // 能力要重新等这台设备的 hello(可能换了一台设备)
    }

    /** 服务销毁:停止 TTS 并释放识别引擎。 */
    fun shutdown() {
        turnId++
        turnActive = false
        tts.stop()
        deviceTts.onCancel()
        stt.release()
    }

    // ---- 帧处理 ----

    /**
     * 音频帧处理。v1 协议音频载荷首字节是 [SEQ],必须剥掉再交给识别端:
     *  - Opus([opus]=true):整包原样交给 [SttEngine.feedOpus](小智直接上送,不再解码重编码);
     *  - PCM 兜底([opus]=false):剥掉 SEQ 后喂 [SttEngine.feedPcm]
     *    (旧实现把 SEQ 当成第一个采样点,会引入一次直流脉冲并且在多字节对齐上错位)。
     */
    private fun handleAudio(payload: ByteArray, opus: Boolean) {
        if (!turnActive) return
        if (payload.isEmpty()) return
        trackSeq(payload[0].toInt() and 0xFF)
        val audio = payload.copyOfRange(1, payload.size)
        if (audio.isEmpty()) return
        if (opus) stt.feedOpus(audio) else stt.feedPcm(audio)
    }

    /**
     * 记录一个 SEQ:与上一帧比对算缺口(1 字节回绕)。
     * 语义与固件 `oc_seq_push` 一致:重复帧(seq 相同)既不计收也不计丢,
     * 否则回绕公式会把重复帧当成 255 个丢包。
     */
    private fun trackSeq(seq: Int) {
        if (seq == lastSeq) return
        if (lastSeq >= 0) {
            val gap = (seq - lastSeq - 1 + 256) % 256
            if (gap > 0) seqLost += gap
        }
        lastSeq = seq
        seqReceived++
    }

    /** 本轮 SEQ 统计:打进日志,并在有丢帧时把丢帧率发到状态面板。 */
    private fun reportSeqLoss() {
        val total = seqReceived + seqLost
        if (total == 0) {
            Log.i(tag, "本轮音频 SEQ 统计:无音频帧")
            return
        }
        val pct = seqLost * 100.0 / total
        val text = "%.1f".format(pct)
        Log.i(tag, "本轮音频 SEQ 统计:收 ${seqReceived} 帧,丢 ${seqLost} 帧,丢帧率 ${text}%")
        if (seqLost > 0) onState("音频丢帧 ${text}%")
    }

    private fun handleEvent(payload: ByteArray) {
        val str = String(payload, Charsets.UTF_8)
        val obj = try {
            gson.fromJson(str, JsonObject::class.java)
        } catch (e: Exception) {
            Log.e(tag, "EVENT 解析失败", e)
            return
        }
        val ev = obj?.get("ev")?.takeIf { it.isJsonPrimitive }?.asString
            ?: str.trim().trim('"').takeIf { it.isNotEmpty() }   // 兼容非 JSON 的纯字符串事件
        when {
            ev == "turn_start" -> onTurnStart()
            ev == "turn_end" -> onTurnEnd()
            // 设备撤销本轮(短按 OK 只唤醒屏幕):固件按下就开轮(保证识别零延迟),
            // 但短于此门限松手会改成 turn_cancel —— 这里静默丢弃,绝不回「无语音」。
            ev == "turn_cancel" -> onTurnCancel()
            // 设备的 hello:带上固件版本与协议版本 —— 与本 App 比对(同一版本号成对发布),
            // 不一致时双侧提示用户更新(只提示、不阻断对话)。
            ev == "hello" -> onDeviceHello(obj)
            // 设备侧 TTS 播放回报(见 docs/wire-protocol.md 的 TTS 下行):只记日志并与
            // 本地「已发送」对账(设备可能因队列溢出/解码失败丢掉一些包)。
            ev != null && ev in TtsPlaybackReport.ALL -> onTtsPlayback(ev, obj)
            else -> Unit
        }
    }

    /**
     * 设备回报一段 TTS 播放结束:`tts_playback_done` / `tts_playback_aborted`。
     *
     * 设备是唯一知道「实际听到多少」的一方,所以这里只做日志与对账(「已发送」与「已播放」分开看),
     * 不把回报当逐帧信用:流控只靠实时节奏(见 `TtsFlowControl`)。
     */
    private fun onTtsPlayback(ev: String, obj: JsonObject?) {
        val report = obj?.let { parseTtsPlaybackReport(it) } ?: TtsPlaybackReport(ev)
        Log.i(tag, ttsPlaybackLogLine(report))
        deviceTts.onDeviceEvent(report)
    }

    // ---- turn 起止 ----

    private fun onTurnStart() {
        // barge:打断正在进行的 TTS / 识别 / 网关等待。
        // 若上一轮网关回复还在收集中(activeCollector 被旧轮占用),先中断它,释放单例
        // 收集器,避免新一轮 chat.send 与其冲突导致发送失败/回复错配。
        turnId++
        turnBodies = TurnBodies(turnId)
        turnActive = true
        // 每轮重置 SEQ 统计(SEQ 是 1 字节回绕,跨轮累计没有意义)
        lastSeq = -1
        seqReceived = 0
        seqLost = 0
        tts.stop()
        // 设备朗读也归零:设备可能仍在播上一轮回复(≤2s 缓冲),必须立刻发 tts_abort。
        // 这一步与设置开关无关(关着时 App 本就没推过音频,abort 是幂等操作)。
        deviceTts.onTurnStart()
        stt.barge()
        gateway.interrupt()
        clearPendingWrites()
        // 录音/识别真的就绪后才回 [onRecordingReady]:设备那时才把「按下即红」变绿。
        // 小智引擎的就绪点 = listen.start 已发出:复用热连接时按下即发(可能**同步**回调,
        // 毫秒级就绪);热连接不可用则现连现握手(hello 回包后回调,与未预热时一致)。
        // 注意:barge() 只打断本轮识别、**保留热连接**,所以这里紧接着的 startTurn 仍能直接复用。
        val myTurn = turnId
        stt.startTurn { onRecordingReady(myTurn) }
        onState("录音中…")
    }

    /**
     * 设备撤销本轮(固件 `{"ev":"turn_cancel"}`,短按 OK 只唤醒屏幕)。
     *
     * 与 barge 走同一条「作废旧轮」路径:`turnId++` 让仍在飞行中的 `endTurn` 结果失效,
     * `stt.barge()` 丢掉本轮音频。**不发任何 TEXT 气泡**(旧行为会回一条「无语音」),
     * 状态卡几秒后回到就绪。
     */
    private fun onTurnCancel() {
        turnId++
        turnBodies = TurnBodies(turnId)
        turnActive = false
        stt.barge()
        Log.i(tag, "设备取消本轮(短按唤醒):丢弃音频与识别结果,不发气泡")
        publishReadyAfter(turnId, 500L)
    }

    /**
     * 录音/识别**真的开始**之后的回调(`SttEngine.startTurn(onReady)`):
     * 向设备下发一条 EVENT `{"ev":"turn_ready"}` —— 设备据此把「按下即红(准备中)」变绿,
     * 表示「现在说话一定能被识别」。
     *
     * 三条约束:
     *  - **不提前发**:不在收到 `turn_start` 的瞬间无条件发(会话还没建立),也不等识别结果发;
     *    复用常驻热连接时会话按下即建,因此回调可以很快(毫秒级)甚至同步到达 —— 那正是绿光及时的原因;
     *  - **同轮只发一次**:barge/重连可能让同一轮的就绪回调重复或迟到,由 [TurnReadyGate] 按轮号去重,
     *    旧轮一律丢弃(不会把上一轮的绿灯发到新一轮);
     *  - **设备没连就不发**:只记 DEBUG 日志;设备侧有 800ms 兜底超时,不会一直红屏。
     *
     * 回调可能来自 BLE/Binder/WS 线程:只调线程安全的发送口,不碰 UI。
     */
    private fun onRecordingReady(myTurn: Int) {
        if (!turnReady.arm(myTurn)) {
            Log.d(tag, "忽略重复/旧轮的录音就绪(turn=$myTurn)")
            return
        }
        if (sendEvent(TURN_READY_EVENT_JSON)) {
            Log.i(tag, "已通知设备: turn_ready(录音就绪)")
        } else {
            Log.d(tag, "设备未连接,未下发 turn_ready(等设备侧 800ms 兜底)")
        }
    }

    private fun onTurnEnd() {
        if (!turnActive) return
        turnActive = false
        // 本轮音频已发完:先结算丢帧率(日志 + 有丢帧时上状态面板),再做识别/网关
        reportSeqLoss()
        // 必须发布「识别中…」:否则状态卡会一直停在「录音中…」(松手后看起来像还在录)
        onState("识别中…")
        val myTurn = turnId
        scope.launch {
            val text = stt.endTurn()
            if (myTurn != turnId) return@launch   // 已被新一轮打断
            if (text.isNullOrBlank()) {
                Log.d(tag, "未识别到语音")
                onState("未识别到语音")
                // 反馈硬件:未识别到语音 → 设备屏显示"无语音"
                ConversationStore.add("agent", "无语音", ConversationStore.SOURCE_VOICE)
                sendText('A', "无语音")
                publishReadyAfter(myTurn, 3000L)
                return@launch
            }
            Log.i(tag, "识别结果: $text")
            // 发给【网关】的文本 = STT 原文 + 语音附加提示词(仅语音;见 VoicePrompt)。
            // 附加提示【不上屏】设备:设备屏只显示 STT 原文。
            val suffix = voicePromptSuffix
            val outgoing = VoicePrompt.compose(text, suffix, isVoice = true)
            val appliedSuffix = suffix.trim().takeIf { it.isNotEmpty() }
            // 识别结果仅在对话列表 + 设备屏展示,不污染顶部状态栏(状态栏只显示流程状态)
            // 对话里用户气泡存 STT 原文,附加提示另存一行弱化小字(与实际发给网关的内容对应)
            ConversationStore.add(
                "user", text, ConversationStore.SOURCE_VOICE, appliedSuffix = appliedSuffix,
            )
            // 识别文本先回传设备屏(role='U' 用户),让用户立即看到自己说的内容
            sendText('U', text)

            // 网关请求可能因网络/超时/异常失败;必须兜底回传 role='A',否则设备卡"接收中"。
            // 等待期间网关的进度事件会把状态卡改成「网关工作中: <阶段>」。
            onState("等待网关…")
            val reply = try {
                // 一轮可能有多条回复(答案 + 后续状态消息),用 chatMulti 全部拿到。
                // 终局宽限窗内迟到的帧会经回调增量补进 App 列表;这里【不等】宽限窗,
                // body 到手就返回并下发设备/TTS(用户对「回复慢」敏感)。
                // 同一个窗内网关还会查一次 chat.history:流式帧只推最后一条 assistant 消息
                // (实测常常是「已回复完毕…」这类状态话术),真正的答案由补正回调补发。
                gateway.chatMulti(
                    outgoing,
                    onRawUpdate = { added -> publishRawUpdatesToApp(myTurn, added) },
                    // 历史补正到达即结算本轮正文:就地替换 App 正文气泡([来自历史])并把
                    // 真答案补发一帧 'A' 给设备;若流式正文是缓发的状态话术,则直接丢弃它。
                    onBodyCorrection = { corrected -> resolveTurnBody(myTurn, corrected) },
                )
            } catch (e: Exception) {
                Log.e(tag, "gateway.chatMulti 异常", e)
                ChatReply(emptyList(), null)
            }
            if (myTurn != turnId) return@launch
            val supportsCorrection = gateway.supportsBodyCorrection
            // body 到手后才开始计时:状态话术 body 缓发等历史补正判定(宽限窗内);
            // 等不到补正就按「历史无更好正文」补发缓存的流式 body(设备不能空着)。
            // 只有支持历史补正的通道才需要等这一窗(不支持的通道前面就已经下发,绝不白等)。
            if (supportsCorrection) {
                scope.launch {
                    delay(HELD_BODY_WAIT_MS)
                    resolveTurnBody(myTurn, corrected = null)
                }
            }
            val replyError = reply.error?.trim()?.takeIf { it.isNotEmpty() }
                ?: gateway.lastError?.trim()?.takeIf { it.isNotEmpty() }
            if (reply.messages.isEmpty() && replyError != null) {
                // 失败原因优先用网关给出的可读描述(连接中断/超时/未配对/无权限…)。
                // 不再只写一句「(网关无回复)」——那会把「连接断了」显示得和「网关就是不回」一样,
                // 用户看不出是连接问题。只有网关没给原因时才退回默认文案。
                val msg = replyError
                Log.w(tag, "网关无回复: $msg")
                onState(msg)                      // 状态卡先显示原因,让用户看出是连接问题
                ConversationStore.add("agent", msg, ConversationStore.SOURCE_VOICE)
                sendText('A', msg)
                publishReadyAfter(myTurn, 6000L)  // 原因看几秒后回到就绪,不停在这个状态
                return@launch
            }
            if (reply.messages.isEmpty()) {
                // 拿到了 final 但文本为空 —— 这才是真正的「空回复」(不是连接失败)
                Log.w(tag, "网关空回复(收到 final 但文本为空)")
                sendText('A', "(网关空回复)")
                if (showRawStream() && reply.raw.isNotEmpty()) {
                    // raw 模式:body 为空但回传流里有内容(例:正文落在区间外)→ App 仍完整展示
                    publishReplyToApp(reply, turnBodiesOf(myTurn))
                } else {
                    ConversationStore.add("agent", "(网关空回复)", ConversationStore.SOURCE_VOICE)
                }
                publishReadyAfter(myTurn, 3000L)
                return@launch
            }
            // 本轮网关可能推多条 assistant 消息:每条各占一个设备气泡,顺序与到达一致。
            // body / raw 双路:**设备屏与 TTS 只用 body**;App 侧按设置里的
            // 「App 显示完整回传流(调试)」开关决定展示 raw(全部条目)还是 body(仅正文)。
            // 真机现象:旧实现只保留最后一条,后面的状态消息会把答案冲掉。
            Log.i(
                tag,
                "网关回复 body ${reply.messages.size} 条 / raw ${reply.raw.size} 条: " +
                    reply.messages.joinToString(" | ") { it.take(30) },
            )
            // 状态话术 body **缓发**:命中「疑似状态话术」且通道支持历史补正时先不发设备
            // (仍进 App 调试流,[流式] + [疑似状态话术] 标记),等历史补正/宽限窗判定后再决定
            // 是「只发真答案」还是「补发缓存的流式 body」(判定见 [bodyDispatch])。
            // 非状态话术的 body 行为完全不变:立即下发,时延不增加。
            val bodies = turnBodiesOf(myTurn)
            reply.messages.forEach { message ->
                val action = bodyDispatch(
                    streamedBody = message,
                    correctedBody = null,
                    correctedAvailable = false,
                    historyCorrectionSupported = supportsCorrection,
                )
                if (action is BodyAction.Hold) {
                    bodies?.let { b -> synchronized(b) { b.held += message } }
                    Log.i(tag, "状态话术 body 暂缓下发,等历史补正判定")
                } else {
                    sendText('A', if (message.isBlank()) "(网关空回复)" else message)
                }
            }
            publishReplyToApp(reply, bodies)
            // 超时/中断但已收到部分消息:消息照常上屏,TTS 照常读,原因只上状态卡
            val leaveReadyDelayMs = if (replyError != null) {
                Log.w(tag, "网关未完整回复(已上屏 ${reply.messages.size} 条): $replyError")
                onState(replyError)
                6000L
            } else {
                0L
            }
            val spoken = reply.messages.filter { it.isNotBlank() }.joinToString("\n")
            // 回复朗读:设备优先(设备报 tts_opus 才推)、否则手机念;开关关闭时两边都不出声
            if (spoken.isNotEmpty()) speakReply(spoken)
            publishReadyAfter(myTurn, leaveReadyDelayMs)
        }
    }

    /**
     * 把本轮网关回传写进 App 对话列表(设备屏 / TTS 不经过这里)。
     *
     * 「App 显示完整回传流(调试)」开 → **body 正文正常气泡(带 [流式] 来源标记)在前**,
     * raw 全部条目(状态/步骤/工具输出/正文全文… **一律弱化小字 + 标签**)按到达顺序跟在后;
     * 关 → 只写 body(正确正文)。「疑似状态话术」标记由 [replyDisplayEntries] 加上,
     * 不改文本、不删条目。
     *
     * @param bodies 本轮正文记账:正文气泡 id 记进去,历史补正到达时就地替换(不新增气泡)
     */
    private fun publishReplyToApp(reply: ChatReply, bodies: TurnBodies?) {
        // 排查用:把 raw 条目的 kind/seq 打出来(真机验证 raw 是否按 seq 升序、网关是否多轮重试)
        if (Log.isLoggable(tag, Log.DEBUG)) {
            reply.raw.forEach { e ->
                Log.d(tag, "raw 条目 kind=${e.kind} seq=${e.seq} label=${e.label} 文本长度=${e.text.length}")
            }
        }
        val display = replyDisplayEntries(
            reply.messages,
            reply.raw,
            showRawStream(),
            bodySourceOf(corrected = false),
        )
        display.forEach { entry ->
            // 弱化小字条目允许空文本;正文(body)空文本仍显示「(网关空回复)」
            val shown = if (entry.label == null && entry.text.isBlank()) "(网关空回复)" else entry.text
            val id = ConversationStore.add(
                "agent",
                shown,
                ConversationStore.SOURCE_VOICE,
                label = entry.label,
                flag = entry.flag,
            )
            // label == null 的才是正文气泡(正常气泡);补正时就地替换用
            if (entry.label == null) {
                bodies?.let { b -> synchronized(b) { b.bubbles += id to shown } }
            }
        }
    }

    /**
     * 终局宽限窗内到达的 raw **增量**条目(只给 App 调试视图)。
     *
     * 来源有二:窗内迟到的网关帧,以及 `chat.history` 补进来的**本轮历史条目**
     * (标签 `正文(历史) · 全文` / `工具(历史) · …` / `工具输出(历史) · 全文`,
     * 追加在流式条目之后)。
     *
     * 规则与首批 [publishReplyToApp] 一致:raw 条目**一律弱化小字 + 标签**、
     * 命中疑似状态话术只加标记;「App 显示完整回传流(调试)」关闭时增量没有 body 可展示,
     * 于是直接不上屏(仍会打进 DEBUG 日志)。
     *
     * 设备屏与 TTS **完全不走这里**(只吃 body);真正需要补发给设备的正文走
     * [resolveTurnBody](它与本开关无关)。
     * 回调可能在网关线程执行:只用线程安全的 [ConversationStore],不碰 UI/Context。
     * 已开新一轮(barge)时丢弃:迟到条目属于旧轮,不能混进新一轮的对话列表。
     */
    private fun publishRawUpdatesToApp(myTurn: Int, added: List<RawEntry>) {
        if (added.isEmpty() || myTurn != turnId) return
        if (Log.isLoggable(tag, Log.DEBUG)) {
            added.forEach { e ->
                Log.d(tag, "raw 增量条目 kind=${e.kind} seq=${e.seq} label=${e.label} 文本长度=${e.text.length}")
            }
        }
        replyDisplayEntries(emptyList(), added, showRawStream()).forEach { entry ->
            // 弱化小字条目允许空文本;正文(body)空文本仍显示「(网关空回复)」
            val shown = if (entry.label == null && entry.text.isBlank()) "(网关空回复)" else entry.text
            ConversationStore.add(
                "agent",
                shown,
                ConversationStore.SOURCE_VOICE,
                label = entry.label,
                flag = entry.flag,
            )
        }
    }

    /**
     * 取本轮的正文记账;已不是当前轮(barge / 断开后 `turnId` 已自增,或 [TurnBodies.turn] 已换轮)时返回 null。
     */
    private fun turnBodiesOf(myTurn: Int): TurnBodies? =
        if (myTurn != turnId) null else turnBodies.takeIf { it.turn == myTurn }

    /**
     * 本轮正文的**最终结算**(历史补正到达 / 宽限窗超时都会走这里;同轮只结算一次)。
     *
     * 真机现象:一轮里的真实答案与后续状态话术是**两条** assistant 消息,流式 `chat delta/final`
     * 只推最后一条(状态话术)—— 设备屏与 App 因此只看到「已经回复完毕…」。网关在终局宽限窗内
     * 查回历史后回调真答案,这里据此结算:
     *
     *  - [corrected] 非空 → 历史补正到位:给设备**补发一帧 `'A'`**(只发真答案),并把本轮
     *    App 正文气泡**就地替换**成补正后的正文(`[来自历史]` 标记,不新增气泡);
     *    若流式正文是缓发的状态话术,则直接丢弃它(设备屏只有真答案)。
     *  - [corrected] 为空 → 历史没有更好的正文:把缓发的状态话术**补发给设备**(设备不能空着)。
     *
     * 重复保护:只有「历史正文与已下发正文不同」才会带着非空 [corrected] 来到这里
     * (网关侧已按文本去重),因此不会重复上屏、也不会重复朗读(本回调不重新触发 TTS)。
     * 可能在网关线程执行:只用线程安全的 [ConversationStore] 与注入的 [sendText],不碰 UI/Context。
     * 已开新一轮(barge)时丢弃:补正/补发属于旧轮。
     */
    private fun resolveTurnBody(myTurn: Int, corrected: String?) {
        val text = corrected?.trim()?.takeIf { it.isNotEmpty() }
        val bodies = turnBodiesOf(myTurn)
        if (bodies == null) {
            if (text != null) Log.i(tag, "丢弃旧轮的历史补正正文(${text.length} 字)")
            return
        }
        val held: List<String>
        val firstId: Long?
        val extraBubbles: List<Pair<Long, String>>
        synchronized(bodies) {
            if (bodies.resolved) return
            bodies.resolved = true
            held = bodies.held.toList()
            firstId = bodies.bubbles.firstOrNull()?.first
            extraBubbles = bodies.bubbles.drop(1)
        }
        // 历史判定已完成:统一由纯函数 [bodyDispatch] 决定「只发真答案」还是「补发缓存的流式 body」。
        // 带走的是【缓发的状态话术】(没有缓发时是非状态话术正文,它早已下发 → 本步不做事)。
        val action = bodyDispatch(
            streamedBody = held.joinToString("\n"),
            correctedBody = text,
            correctedAvailable = true,
            historyCorrectionSupported = true,
        )
        when (action) {
            is BodyAction.ReplaceWithCorrected -> {
                if (held.isEmpty()) {
                    Log.i(tag, "历史补正: 补发正文到设备屏与 App(${action.text.length} 字)")
                } else {
                    Log.i(tag, "历史补正到位,丢弃缓发的状态话术(${held.sumOf { it.length }} 字)")
                }
                sendText('A', action.text)
                // 补正后的正文刚刚上屏(设备屏的真答案):朗读也改读它。
                // 服务侧会先中止上一段(若流式正文已开始播),只保证「最终正文」被完整读出。
                speakReply(action.text)
                replaceBodyBubbles(firstId, extraBubbles, action.text, bodySourceOf(corrected = true))
            }

            is BodyAction.SendHeld -> {
                Log.i(tag, "历史无更好正文,补发缓发的状态话术(${held.sumOf { it.length }} 字)")
                held.forEach { sendText('A', if (it.isBlank()) "(网关空回复)" else it) }
            }

            // 没有补正、也没有缓发的状态话术:正文早已以 [流式] 下发,无需动作。
            else -> Unit
        }
    }

    /**
     * 用历史补正后的正文替换本轮已写入 App 的**正文气泡**(就地替换,不新增气泡)。
     *
     * 第一条替换为补正正文([来自历史] 标记,与「疑似状态话术」标记可同时存在);
     * 同一轮若还有其它正文气泡,降级成弱化小字(`正文 · 全文` 标签),
     * 保证「同一轮只有一个正常正文气泡」。本轮还没有正文气泡(例:body 为空但历史有答案)
     * → 新增一个正常气泡。
     */
    private fun replaceBodyBubbles(
        firstId: Long?,
        extraBubbles: List<Pair<Long, String>>,
        corrected: String,
        source: BodySource,
    ) {
        val flag = bodyFlag(corrected, source)
        if (firstId == null) {
            ConversationStore.add("agent", corrected, ConversationStore.SOURCE_VOICE, flag = flag)
            return
        }
        ConversationStore.replaceById(firstId, corrected, label = null, flag = flag)
        extraBubbles.forEach { (id, text) ->
            ConversationStore.replaceById(id, text, label = RawLabel.ASSISTANT, flag = statusTalkFlag(text))
        }
    }

    /**
     * 回到「就绪」状态,避免状态卡停在「录音中…/等待网关…」。
     * delayMs > 0 时先把原因/提示留一会儿再回到就绪;期间若已开新一轮(myTurn != turnId),
     * 就不再覆盖新状态(否则会把新一轮的「录音中…」冲掉)。
     */
    private fun publishReadyAfter(myTurn: Int, delayMs: Long) {
        if (delayMs <= 0L) {
            if (myTurn == turnId) onState(readyText)
            return
        }
        scope.launch {
            delay(delayMs)
            if (myTurn == turnId) onState(readyText)
        }
    }

    /**
     * 把一句回复念出来。
     *
     * 分派规则(「设备朗读回复」开关打开时才有声音,见构造参数 [ttsEnabled]):
     *  - 网关自带设备朗读音频(小智 AI,见 [GatewayAdapter.providesDeviceTtsAudio]) → 什么都不做,
     *    音频由 TTS 直通下发(不再本地合成,也不回退手机朗读);
     *  - 设备在 hello 里报了 `tts_opus` → 下发**设备朗读**(手机合成 PCM → Opus → BLE → 设备放);
     *  - 设备播不了(老固件未上报/设备未连接) → 退回**手机自己念**([speak]);
     *  - 开关关闭(默认) → 什么都不做。
     *
     * 为什么不再“两边都念”:手机外放与设备外放同时出声重复且吵闹;而且原来的本地朗读是**无门槛**的,
     * 在没装 TTS 引擎的手机上默默无声,一旦换了有引擎的手机就会**突然开口** ✗(真机实测)。
     */
    private fun speakReply(text: String) {
        if (text.isBlank()) return
        // 小智 AI:本轮音频已由网关侧（小智音色）随会话下行、原样直通给设备（见 `XiaozhiTtsRelay`）。
        // 本地再合成一遍就是两种声音叠着播，也可能让设备收到两段无 bracket 的 TTS_OPUS。
        if (gateway.providesDeviceTtsAudio) return
        if (!ttsEnabled()) return
        if (deviceTtsCapable) {
            deviceTts.onReply(text)
        } else {
            Log.i(tag, "设备不支持下行朗读(未报 tts_opus),改用手机朗读")
            speak(text)
        }
    }

    private fun speak(reply: String) {
        val myTurn = turnId
        tts.speak(reply, object : TtsEngine.Listener {
            // 回复已在 onTurnEnd sendText('A', reply) 立即回传设备;TTS 只负责播声音,
            // 不再重复回传,避免设备收到重复"助手"消息。onDone/onInterrupted 仅日志。
            override fun onDone(text: String) {
                Log.i(tag, "TTS 结束: $text")
            }
            override fun onInterrupted(text: String) {
                Log.d(tag, "TTS 未完成: $text")
            }
        })
    }
}
