package com.shinku.aipassport.openclaw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.gson.JsonObject
import com.shinku.aipassport.openclaw.AppActivity
import com.shinku.aipassport.openclaw.MainActivity
import com.shinku.aipassport.openclaw.R
import com.shinku.aipassport.openclaw.ble.BleCentral
import com.shinku.aipassport.openclaw.gateway.AWAITING_PAIRING_PREFIX
import com.shinku.aipassport.openclaw.gateway.GatewayAdapter
import com.shinku.aipassport.openclaw.gateway.GatewayConfigSnapshot
import com.shinku.aipassport.openclaw.gateway.GatewayFactory
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import com.shinku.aipassport.openclaw.gateway.OpenClawGateway
import com.shinku.aipassport.openclaw.gateway.OpenClawGatewayRegistry
import com.shinku.aipassport.openclaw.gateway.ReconnectBackoff
import com.shinku.aipassport.openclaw.gateway.isUnknownMethod
import com.shinku.aipassport.openclaw.protocol.VersionCompat
import com.shinku.aipassport.openclaw.gateway.needsGatewayReload
import com.shinku.aipassport.openclaw.pipeline.VoicePipeline
import com.shinku.aipassport.openclaw.ui.ConversationStore
import com.shinku.aipassport.openclaw.protocol.VbFrame
import com.shinku.aipassport.openclaw.protocol.VbFrameReassembler
import com.shinku.aipassport.openclaw.protocol.splitTextPayload
import com.shinku.aipassport.openclaw.protocol.vbEncodeFrame
import com.shinku.aipassport.openclaw.stt.SttFactory
import com.shinku.aipassport.openclaw.tts.AndroidTtsEngine
import com.shinku.aipassport.openclaw.tts.DeviceTtsDownlink
import com.shinku.aipassport.openclaw.tts.DeviceTtsEngine
import com.shinku.aipassport.openclaw.tts.DeviceTtsSession
import com.shinku.aipassport.openclaw.tts.HttpTtsEngine
import com.shinku.aipassport.openclaw.tts.TtsControl
import com.shinku.aipassport.openclaw.tts.TtsEngine
import com.shinku.aipassport.openclaw.tts.TtsEngines
import com.shinku.aipassport.openclaw.tts.TtsFlowControl
import com.shinku.aipassport.openclaw.tts.TtsFraming
import com.shinku.aipassport.openclaw.tts.TtsPlaybackReport
import com.shinku.aipassport.openclaw.tts.TtsPushPlan
import com.shinku.aipassport.openclaw.tts.buildTtsPushPlan
import com.shinku.aipassport.openclaw.tts.ttsPlaybackLogLine
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shinku.aipassport.openclaw.protocol.VbTtsOpusPayload
import com.shinku.aipassport.openclaw.protocol.vbEncodeTtsOpusFrame

/**
 * 前台服务:持有 BLE 中央端 + 帧重组 + 语音流水线。
 *
 * 数据流:
 *  固件 --notify--> BleCentral --字节--> VbFrameReassembler --帧--> VoicePipeline
 *  VoicePipeline --sendText--> BleCentral --写 RX--> 固件(TEXT 帧上屏)
 */
class VoiceBridgeService : Service() {

    companion object {
        private const val TAG = "VoiceBridgeService"
        private const val CHANNEL_ID = "voice_bridge"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.shinku.aipassport.openclaw.action.START"
        const val ACTION_STOP = "com.shinku.aipassport.openclaw.action.STOP"

        /** 设备页:重新扫描/连接 */
        const val ACTION_SCAN = "com.shinku.aipassport.openclaw.action.SCAN"
        /** 设备页:断开当前设备 */
        const val ACTION_DISCONNECT = "com.shinku.aipassport.openclaw.action.DISCONNECT"

        /** 忘记设备:断开 + 清掉记住的地址(并尽力解除系统绑定),之后需重新配对。 */
        const val ACTION_FORGET_DEVICE = "com.shinku.aipassport.openclaw.action.FORGET_DEVICE"
        /** 设备页:请求重发一次当前状态(新进页面立即拿到真实状态,不必等下一次变化) */
        const val ACTION_REQUEST_STATUS = "com.shinku.aipassport.openclaw.action.REQUEST_STATUS"

        /** 设置页保存成功后:按新设置重建网关适配器并重连(不重启服务、不断 BLE 链路) */
        const val ACTION_RELOAD_SETTINGS =
            "com.shinku.aipassport.openclaw.action.RELOAD_SETTINGS"

        /** 状态广播(供 UI 展示) */
        const val ACTION_STATUS = "com.shinku.aipassport.openclaw.action.STATUS"

        /**
         * 常驻通知被用户划掉时重新挂上（后台保活服务的通知不该因为误划而消失）。
         *
         * Android 14 起前台服务的通知允许被用户划掉（系统行为，App 无法直接禁止），
         * 所以用 setDeleteIntent 监听“被划掉”事件，服务还在跑就重新上报一次前台通知。
         */
        const val ACTION_REPOST_NOTIFICATION = "com.shinku.aipassport.openclaw.action.REPOST_NOTIFICATION"
        const val EXTRA_STATUS = "status"

        /** 当前(或最近一次)连接设备的名称(广播名,如 Passport-1234);断开后保留。 */
        const val EXTRA_DEVICE_NAME = "device_name"
        /** 当前(或最近一次)连接设备的 MAC;断开后保留,供设备页一键重连。 */
        const val EXTRA_DEVICE_ADDR = "device_addr"
        /** BLE 链路状态(只由 BLE 回调写,不掺网关状态):正在扫描/正在连接/已连接/已加密/已就绪/未连接。 */
        const val EXTRA_DEVICE_STATE = "device_state"

        /** 链路状态取值(设备页卡片与状态行直接用这几个词)。 */
        const val LINK_SCANNING = "正在扫描"
        const val LINK_CONNECTING = "正在连接"
        const val LINK_CONNECTED = "已连接"
        const val LINK_ENCRYPTED = "已加密"
        const val LINK_READY = "已就绪"
        const val LINK_DISCONNECTED = "未连接"

        /** 同一网关状态最短重发间隔(ms):避免进度事件多时刷屏设备屏与通知。 */
        private const val GATEWAY_STATE_MIN_INTERVAL_MS = 3_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceBridgeService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceBridgeService::class.java))
        }

        /**
         * 设置页保存成功后调用:让运行中的服务按新设置重建网关适配器并重连。
         *
         * 修的是这个 bug:服务只在 [startBridge] 时读一次设置,原来只有网关类型变化才重启服务,
         * 于是只改 host/端口/token 时保存成功、prefs 也写了,运行中的服务却还在用旧配置
         * (真机表现:顶部横幅一直「网关未配置,请在 App 设置中填写」)。
         */
        fun reloadSettings(context: Context) {
            context.startService(
                Intent(context, VoiceBridgeService::class.java).setAction(ACTION_RELOAD_SETTINGS),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var ble: BleCentral
    private lateinit var reassembler: VbFrameReassembler
    private lateinit var gateway: GatewayAdapter

    /**
     * 运行中适配器对应的配置快照:保存成功后据此判断是否需要重建(见 [needsGatewayReload])。
     * null = 还没建过适配器。
     */
    @Volatile
    private var appliedGatewaySnapshot: GatewayConfigSnapshot? = null

    private lateinit var tts: TtsEngine
    private lateinit var pipeline: VoicePipeline

    /**
     * 网关设置(服务生命周期内共用一份):网关重建与**设备朗读开关/引擎**都从这里实时读,
     * 因此设置页改完即时生效(无需重启服务)。
     */
    private lateinit var settings: GatewaySettings

    /** 设备朗读编排(关/开+引擎的门都在这里,见 [DeviceTtsSession])。 */
    private lateinit var deviceTts: DeviceTtsSession

    /** 设备朗读的下行通路实现(合成 → Opus → `TYPE_TTS_OPUS` 帧)。 */
    private lateinit var deviceTtsPush: DeviceTtsPush

    @Volatile
    private var initialized = false

    /**
     * 网关状态回调。
     *
     * 共享实例只有一个 onStatus,这里多播给状态卡/通知,并顺便把「网关工作中/连接中断」
     * 转成设备侧已有的 gateway 通道([forwardGatewayState])。
     * 在 onDestroy 里要注销,否则回调会继续持有已停止的服务实例。
     */
    private val gatewayStatusListener: (String) -> Unit = { status ->
        publishStatus(status)
        forwardGatewayState(status)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireKeepAliveLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台服务被系统杀死后 START_STICKY 重启(intent 为 null)也要拉起桥
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            // 通知被划掉 → 重新挂上（服务还在跑才会收到这个 intent）
            ACTION_REPOST_NOTIFICATION -> startForegroundCompat()
            ACTION_SCAN -> {
                startBridge()
                if (::ble.isInitialized) {
                    // 先把链路状态置为「正在扫描」:网关状态广播很多,不单独记链路状态的话
                    // 设备页会在一秒内被网关文案刷成「未连接」。
                    publishLinkStatus(LINK_SCANNING, "正在扫描设备…")
                    userDisconnected = false   // 用户重新发起:恢复自动重连
                    ble.rescan()
                }
            }
            ACTION_DISCONNECT -> {
                // 用户主动断开:标记后连接监控不再自动重连,否则设备会被立刻接回来,
                // 设备页列表里就一直显示"已连接"(真机反馈)。重新扫描/重连会清掉这个标记。
                userDisconnected = true
                if (::ble.isInitialized) ble.stop()
                publishLinkStatus(LINK_DISCONNECTED, "已断开")
            }
            ACTION_FORGET_DEVICE -> {
                // 忘记设备:与断开一样不再自动重连,此外清掉记住的地址(下次必须重新扫描配对)
                userDisconnected = true
                if (::ble.isInitialized) ble.forgetDevice()
                publishLinkStatus(LINK_DISCONNECTED, "已忘记设备,等待重新配对")
            }
            // 设备页刚进来时问一次当前状态:广播只在状态变化时发,不能指望它刚好发生。
            // 同时走 startBridge(),保证服务未跑时也能被这次请求正常拉起(不靠后台 startService)。
            ACTION_REQUEST_STATUS -> {
                startBridge()
                publishStatus(lastStatusText ?: "未连接")
            }
            // 设置页保存成功:重建适配器并重连(不重启服务、不断 BLE)。
            // 只改 host/端口/token 时原来不会重启服务,运行中的适配器仍在用旧配置。
            ACTION_RELOAD_SETTINGS -> if (!initialized) startBridge() else reloadGatewaySettings()
            else -> startBridge()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        if (::ble.isInitialized) ble.stop()
        if (::gateway.isInitialized) gateway.close()
        if (::tts.isInitialized) tts.shutdown()
        if (::pipeline.isInitialized) pipeline.shutdown()
        releaseKeepAliveLocks()
        releaseTtsEngine()
        initialized = false
        OpenClawGatewayRegistry.removeListener(gatewayStatusListener)
        stopForegroundCompat()
        super.onDestroy()
    }

    // ---- 初始化 ----

    private fun startBridge() {
        if (initialized) return
        initialized = true
        startForegroundCompat()

        // 共享对话历史:硬件语音也要写入同一列表,供对话 Tab 实时展示
        ConversationStore.init(this)

        // 网关适配器由设置里的类型决定(openclaw / hermes / echo),流水线只依赖 GatewayAdapter 接口。
        // OpenClaw 走 [OpenClawGatewayRegistry]:与顶部探针/概览页/对话页复用同一条 WS,
        // 不再各自 new 一个实例把对方连接顶掉。
        val settings = GatewaySettings(this)
        this.settings = settings
        gateway = GatewayFactory.create(this, settings, gatewayStatusListener)
        appliedGatewaySnapshot = GatewayFactory.configSnapshot(this, settings)
        // 把生效的关键参数打出来(排查「为什么等了这么久/怎么这么快就失败」时一眼能看到)。
        Log.i(TAG, "网关配置: type=${settings.type} 回复等待上限=${settings.openclawReplyTimeoutSeconds}s")
        tts = TtsEngine(this)
        // 设备朗读(M1:手机合成 PCM → Opus → 逐帧下发):通路实现 + 开关/引擎门。
        // 开关默认关(设置页「设备朗读回复（TTS）」),引擎默认系统 TTS。
        deviceTtsPush = DeviceTtsPush()
        deviceTts = DeviceTtsSession(enabled = { this.settings.ttsEnabled }, downlink = deviceTtsPush)
        Log.i(TAG, "设备朗读(TTS): enabled=${settings.ttsEnabled} engine=${settings.ttsEngine}")

        // 帧重组 → 帧回调 → 流水线
        reassembler = VbFrameReassembler { frame -> pipeline.handleFrame(frame) }

        pipeline = VoicePipeline(
            scope = scope,
            stt = SttFactory.create(this) { partial ->
                // 流式识别中间结果:不实时回传设备屏。小智识别会把多条 partial 连续回调,
                // 若每条都 sendTextFrame('U') 上屏,设备会被海量 partial 淹没、状态卡在"接收中",
                // 且覆盖最终的完整回复。识别文本最终由 onTurnEnd 的 sendText('U', text) 一次回传。
                Log.d(TAG, "识别中间结果(不上屏): $partial")
            },
            gateway = gateway,
            tts = tts,
            deviceTts = deviceTts,
            sendText = { role, text -> sendTextFrame(role, text) },
            // 录音/识别真的就绪 → 下发 EVENT {"ev":"turn_ready"}(设备侧「按下即红、就绪变绿」)
            sendEvent = { json -> sendEventFrame(json) },
            appVersion = appVersionName(),
            onState = { status ->
                // 流水线状态也上状态卡;同时把与网关有关的那几条(已就绪/中断原因)同步给设备:
                // 否则一轮跑完后设备会停在「网关 工作中」,直到下一次状态变化才纠正。
                publishStatus(status)
                forwardGatewayState(status)
            },
            clearPendingWrites = { ble.clearPendingWrites() },
            // 语音附加提示词:仅语音输入时追加到发给网关的文本末尾(见 VoicePrompt);上屏仍是 STT 原文
            voicePromptSuffix = settings.voicePromptSuffix,
            // App 是否展示完整回传流(raw):读设置里「App 显示完整回传流(调试)」,
            // 每次回复时取値(同一份 SharedPreferences,设置页切换即时生效);关掉后只显示 body
            showRawStream = { settings.showRawStream },
        )

        ble = BleCentral(
            context = this,
            // 配对输入框弹在 App 前台 Activity 上(Service 无 window token 弹不了对话框)
            pairingDialogContext = { AppActivity.current() },
            listener = object : BleCentral.Listener {
                override fun onConnecting() = publishLinkStatus(LINK_CONNECTING, "正在连接设备…")
                override fun onConnected() {
                    // 记录“已连接但尚未加密”的起点:手机刚解除配对、设备侧还留着旧绑定时，
                    // 没有任何一方会再发起 createBond，靠这个时间戳让看门狗重建连接。
                    waitingEncryptSinceMs = System.currentTimeMillis()
                    publishLinkStatus(LINK_CONNECTED, "已连接,等待加密")
                }
                override fun onEncrypted() {
                    waitingEncryptSinceMs = 0L
                    encryptStuckCycles = 0
                    publishLinkStatus(LINK_ENCRYPTED, "已加密")
                }
                override fun onReady() {
                    publishLinkStatus(LINK_READY, "已就绪,长按设备 OK 说话")
                    // 向设备上报本 App 版本(设备据此检查固件/App 是否配套,不一致时设备屏会提示)
                    sendDeviceHello()
                    // 识别通道常驻预热:链路一就绪就先建一条小智热连接(后台静默、不影响 UI),
                    // 用户按下 OK 时就能直接 listen.start → turn_ready 毫秒级到达(修「按下后要等准备中」)。
                    if (::pipeline.isInitialized) pipeline.prewarm()
                    // 下发当前时间给设备(设备无网络时钟,靠 App 同步;右上角显示 HH:MM)。
                    // 延迟稍等,确保 MTU 协商完成(否则超长写入被 Android 拒)。
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        sendTimeSync()
                        // 链路就绪后立即同步一次网关状态:否则设备左上角会一直显示「网关 未知」,
                        // 直到下一次真正的状态变化(用户会以为坏了)。
                        syncGatewayStateToDevice()
                    }, 800)
                }
                // 帧处理统一投递到主线程 scope:保证帧重组 + 流水线在单线程上串行,
                // STT(startTurn)/EVENT 起停/识别顺序确定,不因 BLE 回调线程而竞态。
                override fun onBytesReceived(bytes: ByteArray) {
                    // 调试:打印收到的 BLE 字节前 20 + 交给帧重组(定位帧边界错乱)
                    if (bytes.isNotEmpty()) {
                        val dbg = bytes.take(24).joinToString(" ") { "%02x".format(it) }
                        Log.i(TAG, "BLE RX ${bytes.size}B: $dbg")
                    }
                    scope.launch { reassembler.push(bytes) }
                }
                override fun onDisconnected() {
                    scope.launch {
                        pipeline.onDisconnected()
                        publishLinkStatus(LINK_DISCONNECTED, "已断开,自动重连…")
                    }
                }
                override fun onError(message: String) = publishStatus("错误: $message")
            },
        )

        pipeline.prewarm()
        ble.start()
        startConnectionMonitor()
    }

    /**
     * 重载网关配置(设置页保存成功后由 [ACTION_RELOAD_SETTINGS] 触发)。
     *
     * 行为:
     *  - 语音附加提示词总是同步给流水线(只影响上行文本,与连接无关);
     *  - 网关配置快照变了才真正重建适配器并重连(OpenClaw 由注册表按配置指纹换连接);
     *  - 不重启服务、不碰 BLE 链路,已连接的设备与正在跑的一轮对话不受影响。
     *
     * 日志只打 type/host/port —— **绝不打印 token**。
     */
    private fun reloadGatewaySettings() {
        if (!::gateway.isInitialized) return
        val settings = GatewaySettings(this)
        // 语音附加提示:与连接无关,每次保存都同步(否则改了提示词要重启 App 才生效)
        if (::pipeline.isInitialized) pipeline.updateVoicePromptSuffix(settings.voicePromptSuffix)

        val next = GatewayFactory.configSnapshot(this, settings)
        if (!needsGatewayReload(appliedGatewaySnapshot, next)) {
            Log.i(TAG, "网关设置已保存: 网关配置未变化,保留现有适配器")
            return
        }
        val old = gateway
        val fresh = GatewayFactory.create(this, settings, gatewayStatusListener)
        gateway = fresh
        appliedGatewaySnapshot = next
        if (::pipeline.isInitialized) pipeline.updateGateway(fresh)
        // OpenClaw 旧实例已由注册表按配置指纹 shutdown;其余通道需显式关闭旧连接。
        // (若注册表因会话名归一化后指纹不变而返回同一实例,这里只是减一次引用,不会断开正在用的连接)
        old.close()
        val (host, port) = endpointOf(settings)
        Log.i(TAG, "网关配置已重载: type=${settings.type} host=$host port=$port")
        // 重建后立即重连一次;失败也不打紧,连接监控的退避循环会继续重试
        scope.launch {
            try {
                // 重载/重连**开始**:先清掉上一次的错误,并把监控的「已播报原因」置空。
                // 于是下面拼出的状态文案只可能是【本次尝试】的原因 —— 不会再把上一次的旧原因
                // (真机案例:概览页问了一个本网关没有的方法 → `unknown method: usage`)
                // 拼成「网关配置已重载,正在重连… — unknown method: usage」从而看着像「网关不可达」。
                fresh.clearLastError()
                reportedGatewayError = null
                if (fresh.connect()) {
                    gatewayBackoff.reset()
                    publishGatewayStatus("网关配置已重载,连接就绪")
                } else {
                    val reason = fresh.lastError?.takeIf { it.isNotBlank() }
                    publishGatewayStatus("网关配置已重载,正在重连…" + (reason?.let { " — $it" } ?: ""))
                }
            } catch (e: Exception) {
                Log.w(TAG, "重载后连接网关失败: ${e.message}")
            }
        }
    }

    /** 当前类型的 host/port(仅日志用;不含 token)。 */
    private fun endpointOf(settings: GatewaySettings): Pair<String, String> = when (settings.type) {
        GatewaySettings.TYPE_HERMES -> settings.hermesHost to settings.hermesPort
        GatewaySettings.TYPE_OPENAI -> settings.openaiHost to settings.openaiPort
        GatewaySettings.TYPE_ECHO -> "-" to "-"
        else -> settings.host to settings.port
    }

    // ---- 连接监控:定时检测设备(BLE)+ 小智 连接,断则及时重连 ----

    private var monitorJob: kotlinx.coroutines.Job? = null

    /** 已播报过的网关错误(只在原因变化时重新播报,避免每 6s 刷屏)。 */
    @Volatile
    private var reportedGatewayError: String? = null

    /**
     * 最近一次重连失败的原因。
     *
     * 重连**开始**时会清掉适配器的旧错误(保证状态文案只含本次尝试的原因);万一某个实现
     * `connect()` 失败却什么都没写,就用它兜底 —— 重连循环绝不能因为「没有原因」而停掉。
     */
    @Volatile
    private var lastReconnectReason: String? = null

    /** 网关重连是否在途(避免多个重连叠在一起)。 */
    @Volatile
    private var gatewayReconnectInFlight = false

    /** 网关重连退避:2s→4s→8s→16s→30s 封顶;恢复后 [ReconnectBackoff.reset]。 */
    private val gatewayBackoff = ReconnectBackoff()

    /**
     * 转发给设备屏的网关状态缓存(按 state+detail 节流)。
     * 设备侧已有 `{"cmd":"gateway","state":...,"detail":...}` 通道,这里直接复用。
     */
    @Volatile
    private var lastGatewayState: String? = null

    @Volatile
    private var lastGatewayDetail: String? = null

    @Volatile
    private var lastGatewayStateAtMs: Long = 0L

    /**
     * 用户是否主动点过「断开设备」。
     *
     * 为 true 时连接监控不再自动重连(否则断开一秒后又连上,列表里一直显示已连接);
     * 重新扫描(ACTION_SCAN)会置回 false。只在内存中,App 重启后恢复正常自动重连。
     */
    @Volatile
    private var userDisconnected = false

    /** 「已连接但尚未加密」的起点时刻(0 = 不处于该状态):配对卡住时靠它识别并重建连接。 */
    @Volatile
    private var waitingEncryptSinceMs = 0L

    /** 加密看门狗阀值:超过它仍未加密就重建连接(真机：解除配对后用旧连接重配会永远停在「等待加密」)。 */
    private val encryptTimeoutMs = 12_000L

    /** 连续多少次看门狗重建后仍未加密:达到上限就改成给用户可操作的提示。 */
    @Volatile
    private var encryptStuckCycles = 0

    private fun startConnectionMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            try {
                while (true) {
                    kotlinx.coroutines.delay(6_000)
                    try {
                        // 设备(BLE)连接检测:若断,触发重连(自动重连上次设备)。
                        // 用户刚点过「断开设备」时不在这里重连:那是有意断开,不该被监控接回来。
                        if (!ble.isConnected() && !userDisconnected) {
                            Log.w(TAG, "连接监控: 设备 BLE 已断开,触发重连")
                            publishLinkStatus(LINK_DISCONNECTED, "设备断开,自动重连…")
                            ble.rescan()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "连接监控异常", e)
                    }
                    try {
                        // 加密看门狗:连上了却一直不加密。
                        // 典型场景:手机侧解除了配对，但连接还在(设备侧还留着旧绑定) ——
                        // 两边都不会再发起 createBond，界面就永远停在「等待加密」。
                        // 处理:重建一次连接，让 ensureEncryption() 重新走一遍(该弹配对码时就弹)。
                        val stuckMs = if (waitingEncryptSinceMs > 0L) {
                            System.currentTimeMillis() - waitingEncryptSinceMs
                        } else {
                            0L
                        }
                        if (!userDisconnected && ble.isConnected() && stuckMs > encryptTimeoutMs) {
                            encryptStuckCycles++
                            if (encryptStuckCycles >= 3) {
                                // 重建两次仍未加密:多半是手机侧与设备侧的配对状态对不上(典型：手机刚从
                                // 系统蓝牙里取消了配对),重建连接解决不了 —— 把该做的事直接告诉用户。
                                Log.w(TAG, "连接监控: 连续 ${encryptStuckCycles} 次重建仍未加密,提示用户处理配对")
                                publishLinkStatus(
                                    LINK_DISCONNECTED,
                                    "配对卡住了：请到系统蓝牙里取消配对本设备，再回设备页点「扫描并连接」",
                                )
                            } else {
                                Log.w(TAG, "连接监控: 已连接 ${stuckMs}ms 仍未加密,重建连接以重新配对")
                                publishLinkStatus(LINK_CONNECTED, "等待加密超时,重新建立连接…")
                            }
                            waitingEncryptSinceMs = System.currentTimeMillis()   // 下一轮再检查
                            ble.rescan()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "加密看门狗异常", e)
                    }
                    try {
                        // 识别通道保活:热连接会被服务端/中间设备静默回收(真机实测 `小智 WS 失败 code=null`,
                        // 无错误码),App 不自知则用户按下只能现场握手 →「等好久才变绿」。
                        // 每 6 秒巡检一次:已经热着时 prewarm() 内部直接返回,不会重复建连。
                        if (ble.isConnected() && ::pipeline.isInitialized) pipeline.prewarm()
                    } catch (e: Exception) {
                        Log.w(TAG, "识别通道预热巡检异常:${e.message}")
                    }
                    try {
                        monitorGateway()
                    } catch (e: Exception) {
                        Log.e(TAG, "网关连接监控异常", e)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 服务停止时取消监控循环
            }
        }
    }

    /**
     * 网关连接监控:断开/鉴权失败时把【具体原因】([GatewayAdapter.lastError])播到顶部状态卡,
     * 并驱动一次重连;原因变化时才重新播报。
     *
     * 重连语义(修正「每 ~6s 一次 WS 失败」):
     *  - 只在确实断开(isReady()==false 且有 lastError)时才排队重连,健康 socket 一律不碰;
     *  - 每次重连前等一个指数退避(2s→4s→8s→16s→30s 封顶),不再固定每 6s 新建一条 socket;
     *  - 每次重连都打一行带【原因】与退避时长的日志,方便从 logcat 看出为什么又建了连接。
     *
     * 不改变任何网关协议行为:重连仍用适配器自己的 connect()。
     */
    /**
     * 网关相关状态的统一出口:同时上状态卡/通知,并同步给设备。
     * 注意:监控路径原先只调 publishStatus,结果设备侧收不到「已恢复/重连中」,
     * 会一直停在上一状态(甚至「未知」)——必须走这一条。
     */
    private fun publishGatewayStatus(status: String) {
        publishStatus(status)
        forwardGatewayState(status)
    }

    /** 「未就绪且无错误」时主动探活的节流时刻。 */
    private var lastGatewayProbeAtMs = 0L

    /**
     * 网关未就绪、且还没有失败原因时的主动探活。
     *
     * OpenAI 兼容与 Hermes 适配器是“一问才会连”:不主动发请求就永远不会 ready，
     * 设备屏/状态卡就会一直停在「网关 连接中」（真机反馈的“设备网关状态更新不及时”）。
     * 这里每 10 秒最多探一次，探通就立即播报并同步到设备。
     */
    private fun maybeProbeGateway() {
        val now = System.currentTimeMillis()
        if (now - lastGatewayProbeAtMs < 10_000L) return
        lastGatewayProbeAtMs = now
        scope.launch {
            try {
                if (gateway.connect()) {
                    Log.i(TAG, "网关探活成功,状态置为就绪")
                    reportedGatewayError = null
                    syncGatewayStateToDevice(force = true)
                } else {
                    Log.i(TAG, "网关探活未就绪:${gateway.lastError ?: "无原因"}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "网关探活异常:${e.message}")
            }
        }
    }

    private fun monitorGateway() {
        if (!::gateway.isInitialized) return
        if (gateway.isReady()) {
            gatewayBackoff.reset()
            lastReconnectReason = null
            if (reportedGatewayError != null) {
                reportedGatewayError = null
                publishGatewayStatus("网关已恢复连接")
            }
            // 真机反馈「网关就绪状态刷新不及时」：上面只在“曾经报过错”时才播报，
            // 首次由 connecting → ready 时什么都不发，设备屏就停在「网关 连接中」。
            // 这里每轮（6 秒）同步一次，重复内容由 publishGatewayState 的 3 秒去抖拦掉。
            syncGatewayStateToDevice(force = false)
            return
        }
        // 还没有失败原因(如尚未首次对话)时不再直接 return —— 那会让 OpenAI 兼容/Hermes 这类
        // “不主动请求就不会 ready”的网关永远停在 connecting，设备屏一直显示「网关 连接中」。
        // 改为主动探活(10 秒节流)，探通就立刻播报就绪并同步到设备。
        val reason = gateway.lastError ?: lastReconnectReason
        if (reason == null) {
            maybeProbeGateway()
            return
        }
        // 双保险:「网关不认识这个方法」与连接无关(能力缺失),不播报、也不驱动重连。
        // 只拦这一个原因 —— 权限不足之类的仍要重试/播报(重连循环绝不能停在那上面)。
        if (isUnknownMethod(null, reason)) return
        if (reason != reportedGatewayError) {
            reportedGatewayError = reason
            publishGatewayStatus("$reason — 正在重连…")
        }
        // 未就绪时也保持同步:重连原因/详情会变，设备屏不能停在旧文案上（同一内容由 3 秒去抖拦掉）。
        syncGatewayStateToDevice(force = false)
        if (gatewayReconnectInFlight) return
        val delayMs = gatewayBackoff.nextDelayMs()
        Log.i(TAG, "网关重连排队:${delayMs}ms 后第 ${gatewayBackoff.attempts} 次重试,原因: $reason")
        gatewayReconnectInFlight = true
        scope.launch {
            try {
                kotlinx.coroutines.delay(delayMs)
                // 本次尝试开始:清掉上一次的错误 → 之后读到的 lastError 一定是本次尝试的原因
                gateway.clearLastError()
                if (gateway.connect()) {
                    gatewayBackoff.reset()
                    lastReconnectReason = null
                    Log.i(TAG, "网关重连成功")
                } else {
                    val fresh = gateway.lastError
                    if (fresh == null) lastReconnectReason = reason
                    Log.w(TAG, "网关重连失败: ${fresh ?: reason}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "网关重连异常: ${e.message}")
            } finally {
                gatewayReconnectInFlight = false
            }
        }
    }

    // ---- 数据出口 ----

    private fun sendTextFrame(role: Char, text: String) {
        // TEXT 帧 payload = [role:1B]['U'=用户识别/'A'=网关回复] + UTF-8 文本。
        // 固件据此区分并入对话历史,供 UP/DOWN 翻页。
        val body = text.toByteArray(Charsets.UTF_8)
        // 固件 TEXT 帧上限 VB_FRAME_TEXT_MAX(2048,见固件 voice_bridge_frame.h)。
        // 长回复按此分片,首片 FLAG_FIRST、中间 FLAG_MORE、末片 FLAG_LAST,固件端合并成一条显示。
        val bodyMax = VbFrame.TEXT_PAYLOAD_MAX - 1   // payload = role(1B) + body
        if (body.isEmpty()) {
            // 空文本:发一条空 frame(带 LAST)
            val payload = ByteArray(1).also { it[0] = role.code.toByte() }
            val frame = vbEncodeFrame(VbFrame.TYPE_TEXT, VbFrame.FLAG_LAST, payload)
            Log.i(TAG, "sendTextFrame role=$role(空) 分片0/1")
            ble.writeBytes(frame)
            return
        }
        // 分片回退到 UTF-8 边界(见 splitTextPayload),不切坏汉字/emoji
        val chunks = splitTextPayload(body, bodyMax)
        val totalChunks = chunks.size
        chunks.forEachIndexed { chunkIdx, chunk ->
            val payload = ByteArray(chunk.size + 1)
            payload[0] = role.code.toByte()
            System.arraycopy(chunk, 0, payload, 1, chunk.size)
            val flags = when {
                totalChunks == 1 -> VbFrame.FLAG_LAST            // 单帧即完整
                chunkIdx == 0 -> VbFrame.FLAG_FIRST              // 首片
                chunkIdx == totalChunks - 1 -> VbFrame.FLAG_LAST // 末片
                else -> VbFrame.FLAG_MORE                        // 中间片
            }
            val frame = vbEncodeFrame(VbFrame.TYPE_TEXT, flags, payload)
            Log.i(TAG, "sendTextFrame role=$role text=${text.take(30)} 分片$chunkIdx/$totalChunks 字节=${frame.size} flags=$flags")
            ble.writeBytes(frame)
        }
    }

    // 下发当前时间给设备:CONTROL 帧 {"ev":"time","epoch":<秒>}。
    private fun sendTimeSync() {
        val json = "{\"ev\":\"time\",\"epoch\":${System.currentTimeMillis() / 1000}}"
        val payload = json.toByteArray(Charsets.UTF_8)
        val frame = vbEncodeFrame(VbFrame.TYPE_CONTROL, 0, payload)
        ble.writeBytes(frame)
    }

    /**
     * 向设备下发一条 **EVENT 帧**(JSON):与设备→App 的事件帧同格式
     * (`[A5][5A][TYPE_EVENT][FLAGS][LEN][JSON]`,见 `docs/wire-protocol.md`),只是方向反过来。
     *
     * 目前只用于 `{"ev":"turn_ready"}`:App **真的开始录音/识别**之后才发(见
     * `VoicePipeline.onRecordingReady`),设备据此把「按下即红(准备中)」变绿(可以说话)。
     * 设备未连接/未初始化时丢弃并记 DEBUG —— 不假装发成功,设备侧另有 2.5s 兜底超时。
     *
     * @return 是否已交给 BLE 写队列(false = 设备未连接,没发)
     */
    private fun sendEventFrame(json: String): Boolean {
        if (!::ble.isInitialized || !ble.isConnected()) {
            Log.d(TAG, "设备未连接,丢弃事件帧: $json")
            return false
        }
        val frame = vbEncodeFrame(VbFrame.TYPE_EVENT, 0, json.toByteArray(Charsets.UTF_8))
        Log.i(TAG, "下发事件帧给设备: $json")
        ble.writeBytes(frame)
        return true
    }

    /**
     * 把网关进度同步到设备屏,复用设备侧已有的 `{"cmd":"gateway","state":…,"detail":…}` 通道。
     *
     * 只转发三类,其余 UI 状态(已加密/录音中…)不占这个通道:
     *  - `网关工作中: <phase/工具>` → state=working(设备屏可显示「网关工作中: preparing_context」)
     *  - 连接中断/重连中 → state=connecting
     *  - 恢复连接 → state=idle
     * 同一 state+detail 在 [GATEWAY_STATE_MIN_INTERVAL_MS] 内不重复下发,避免刷屏设备。
     */
    /**
     * 把状态文案映射成设备侧 gateway 通道的状态词(与固件共用四个词)。
     * 返回 null 表示这条文案与网关无关,不下发。
     */
    private fun gatewayStateOf(status: String): Pair<String, String>? {
        val s = status.trim()
        return when {
            // 「等待网关授权」:状态词固定 connecting,授权信息全在 detail(设备屏底部提示行)。
            // 必须单独列一条:这句话里没有任何「连接/重连/中断」类关键词,不单列会被当无关文案丢掉。
            s.startsWith(AWAITING_PAIRING_PREFIX) -> "connecting" to s

            s.startsWith(OpenClawGateway.PROGRESS_PREFIX) ->
                "working" to s.removePrefix(OpenClawGateway.PROGRESS_PREFIX).trim().let { "工作中: $it" }

            s.contains("工作中") -> "working" to s
            // ready:链接就绪/网关恢复都算可用
            s.contains("就绪") || s.contains("已恢复") || s.contains("健康") -> "ready" to ""
            // offline:看词表就能分出失败,原因原样带过去
            s.contains("中断") || s.contains("错误") || s.contains("失败") ||
                s.contains("不可达") || s.contains("断开") -> "offline" to s
            // connecting:其余与连接过程有关的(含等待授权/待配对)
            s.contains("连接") || s.contains("重连") || s.contains("加密") ||
                s.contains("授权") || s.contains("配对") -> "connecting" to s

            else -> null
        }
    }

    /**
     * 把网关状态下发给设备(去抖)。state 只用四个词,detail 是人话(设备显示在底部提示行)。
     */
    private fun forwardGatewayState(status: String, force: Boolean = false) {
        val mapped = gatewayStateOf(status) ?: return
        publishGatewayState(mapped.first, mapped.second, force)
    }

    /**
     * 首次同步用:不带状态文案,直接按适配器当前情况推导 —— 否则设备会一直显示「未知」。
     *
     * @param force true = 忽略去抖立即下发;监控循环里用 false,靠 3 秒去抖变成“最多每轮一次”的心跳式同步。
     */
    private fun syncGatewayStateToDevice(force: Boolean = true) {
        val (state, detail) = if (::gateway.isInitialized && gateway.isReady()) {
            "ready" to ""
        } else {
            "connecting" to (if (::gateway.isInitialized) gateway.lastError ?: "正在连接网关…" else "正在连接网关…")
        }
        publishGatewayState(state, detail, force = force)
    }

    private fun publishGatewayState(state: String, detail: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && state == lastGatewayState && detail == lastGatewayDetail &&
            now - lastGatewayStateAtMs < GATEWAY_STATE_MIN_INTERVAL_MS
        ) {
            return
        }
        lastGatewayState = state
        lastGatewayDetail = detail
        lastGatewayStateAtMs = now
        // 通知栏的「网关」行跟着状态变（内容未变时 Android 会去重，不会刷屏）
        if (::ble.isInitialized) updateNotification(lastStatusText ?: "")
        if (!::ble.isInitialized) return
        val json = JsonObject().apply {
            addProperty("cmd", "gateway")
            addProperty("state", state)
            addProperty("detail", detail)
        }.toString()
        val frame = vbEncodeFrame(VbFrame.TYPE_CONTROL, 0, json.toByteArray(Charsets.UTF_8))
        ble.writeBytes(frame)
        Log.i(TAG, "下发网关状态给设备: $json")
    }

    // ---- 设备朗读(下行 TTS:手机合成 → Opus → TYPE_TTS_OPUS 帧)----
    //
    // 与上面的 TEXT / EVENT / CONTROL 下发并列,但方向相反:这里是【把回复变成音频】推到设备播放。
    // 协议(见 docs/wire-protocol.md 的 TTS 下行):
    //   {"ev":"tts_start"} → 逐帧 TYPE_TTS_OPUS(0x06,每帧一个 60ms Opus 包,领先 ≤2s)→ {"ev":"tts_stop"}
    //   被 barge(新一轮 turn_start)/取消时立即 {"ev":"tts_abort"} 并停止推送。

    /** 已创建的下行 TTS 合成引擎(与 [ttsEngineId] 配套;换引擎时重建并释放旧实例)。 */
    @Volatile
    private var ttsEngineInstance: DeviceTtsEngine? = null

    /** [ttsEngineInstance] 对应的 `tts_engine` 设置值。 */
    @Volatile
    private var ttsEngineId: String? = null

    /**
     * 按设置项 `tts_engine` 取合成引擎(实例缓存:`AndroidTtsEngine` 的 `TextToSpeech`
     * 初始化很贵,不能每次朗读都重建)。
     */
    private fun ttsEngine(): DeviceTtsEngine {
        val id = settings.ttsEngine
        val cached = ttsEngineInstance
        if (cached != null && ttsEngineId == id) return cached
        (cached as? AndroidTtsEngine)?.shutdown()
        val fresh: DeviceTtsEngine = when (id) {
            TtsEngines.HTTP -> HttpTtsEngine(baseUrl = "", model = "", voice = "", apiKey = "")
            else -> AndroidTtsEngine(this)
        }
        ttsEngineId = id
        ttsEngineInstance = fresh
        Log.i(TAG, "设备朗读引擎: $id")
        return fresh
    }

    /** 服务销毁:释放合成引擎(系统 TTS 要 shutdown,否则泄漏绑定)。 */
    private fun releaseTtsEngine() {
        (ttsEngineInstance as? AndroidTtsEngine)?.shutdown()
        ttsEngineInstance = null
        ttsEngineId = null
    }

    /**
     * 用 libopus 把一整段 PCM 编成 60ms 一帧的下行 Opus 包(整轮复用一个编码器实例)。
     *
     * 分帧/尾帧补零/超限丢弃规则全在纯函数 [buildTtsPushPlan]/[TtsFraming] 里(有 JVM 单测);
     * 这里只负责调 libopus JNI。采样率只接受 16k/24k(见 [TtsFraming.supportedRateKhz])。
     */
    private fun encodeTtsPlanWithOpus(pcm: ByteArray, rateHz: Int): TtsPushPlan? {
        val enc = Opus()
        return try {
            val rate = if (rateHz == 24_000) Constants.SampleRate._24000() else Constants.SampleRate._16000()
            val ret = enc.encoderInit(rate, Constants.Channels.mono(), Constants.Application.audio())
            if (ret != 0) {
                Log.e(TAG, "TTS Opus 编码器初始化失败 ret=$ret rate=${rateHz}Hz")
                return null
            }
            buildTtsPushPlan(pcm, rateHz) { frame, samples ->
                runCatching { enc.encode(frame, Constants.FrameSize._custom(samples)) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS Opus 编码失败", e)
            null
        } finally {
            runCatching { enc.encoderRelease() }
        }
    }

    /**
     * 下发一条 `CONTROL` 帧(`{"ev":…}` 形状;`tts_start` / `tts_stop` / `tts_abort` 都走这里)。
     *
     * 设备未连接/未初始化时丢弃并记 DEBUG,**不假装发成功**;返回 false 让调用方放弃本轮下发
     * (否则音频帧没了 bracket 会被设备当错位处理)。
     */
    private fun sendControlJson(json: String): Boolean {
        if (!::ble.isInitialized || !ble.isConnected()) {
            Log.d(TAG, "设备未连接,丢弃控制帧: $json")
            return false
        }
        val frame = vbEncodeFrame(VbFrame.TYPE_CONTROL, 0, json.toByteArray(Charsets.UTF_8))
        Log.i(TAG, "下发控制帧给设备: $json")
        ble.writeBytes(frame)
        return true
    }

    /**
     * 设备朗读的下行通路实现(手机→设备)。
     *
     * 三条不变式:
     *  1. **不阻塞**:合成/编码/节流都在协程里,调用方(流水线)只发一个「下一次」;
     *  2. **可取消**:每次 [speak]/[abort] 都自增 [pushId],在途循环在推下一帧前比对编号后退出
     *     (barge 后不会再把旧回复的音频推给设备);
     *  3. **复用现有 TX 队列**:帧只走 [BleCentral.writeBytes](同一个串行写队列 + ATT 确认),
     *     不另开线程猛灌;节奏由 [TtsFlowControl] 算(领先设备 ≤2s,目标 800ms)。
     */
    private inner class DeviceTtsPush : DeviceTtsDownlink {

        /** 在途下发编号:每次 speak/abort 自增,旧编号的循环下一帧前退出(跨线程读写)。 */
        @Volatile
        private var pushId = 0

        @Volatile
        private var pushJob: Job? = null

        /** 最近一次实际推给设备的音频帧数 / 丢弃帧数(与设备回报对账用)。 */
        @Volatile
        private var lastFrames = 0

        @Volatile
        private var lastDropped = 0

        override fun speak(text: String) {
            val replacing = pushJob?.isActive == true
            val id = ++pushId
            pushJob?.cancel()
            // 上一段还在推:先让设备丢掉旧队列,否则旧回复的残音会与新一段叠在一起
            // (典型场景:流式正文已开始播,历史补正的真答案到了)
            if (replacing) sendControlJson(TtsControl.ABORT_JSON)
            pushJob = scope.launch {
                try {
                    pushAudio(text, id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 合成/编码/写入的异常只能记日志:不能让一条朗读失败影响上屏与下一轮对话
                    Log.e(TAG, "设备朗读(TTS)下发失败", e)
                }
            }
        }

        override fun abort() {
            val active = pushJob?.isActive == true
            pushId++
            pushJob?.cancel()
            pushJob = null
            // 无论有没有在途下发都要发:设备最多缓存 ~2s 音频,上一轮 tts_stop 之后它可能还在播。
            sendControlJson(TtsControl.ABORT_JSON)
            if (active) Log.i(TAG, "设备朗读已中止(tts_abort)")
        }

        override fun onPlaybackReport(report: TtsPlaybackReport) {
            // 设备是唯一知道「实际听到多少」的一方:把它的统计与本地「已发送」放在同一行对账
            Log.i(
                TAG,
                "${ttsPlaybackLogLine(report)} — 本地已发送 frames=$lastFrames dropped=$lastDropped",
            )
            if (report.ev == TtsPlaybackReport.ABORTED) {
                lastFrames = 0
                lastDropped = 0
            }
        }

        /** 一轮下发的完整流程;每一步前都检查 [pushId],确保被 barge 后立刻停手。 */
        private suspend fun pushAudio(text: String, id: Int) {
            if (!::ble.isInitialized || !ble.isConnected()) {
                Log.d(TAG, "设备未连接,跳过设备朗读")
                return
            }
            val engine = ttsEngine()
            if (!engine.prepare()) {
                Log.w(TAG, "设备朗读(TTS)不可用: ${engine.lastError ?: "未知原因"}")
                return
            }
            if (id != pushId) return
            // 合成(WAV 读写)与 Opus 编码都是阻塞活,放 Default 线程,别占主线程/流水线
            val plan = withContext(Dispatchers.Default) {
                val pcm = engine.synthesize(text, TtsFraming.PREFERRED_RATE_HZ)
                    ?: return@withContext null
                val rateHz = engine.lastSampleRateHz.takeIf { it > 0 } ?: TtsFraming.PREFERRED_RATE_HZ
                if (TtsFraming.supportedRateKhz(rateHz) == 0) {
                    // M1 不重采样:非 16k/24k 无法编成设备认的帧(帧头只有 rate_khz 16/24)
                    Log.w(TAG, "TTS 输出 ${rateHz}Hz 不是 16k/24k:M1 不重采样,本轮不下发设备朗读")
                    return@withContext null
                }
                val built = encodeTtsPlanWithOpus(pcm, rateHz)
                if (built != null) {
                    Log.i(
                        TAG,
                        "TTS 已编码: rate=${built.rateKhz}kHz frame=${built.frameMs}ms " +
                            "帧数=${built.packets.size} 丢弃=${built.dropped} 文本=${text.length}字",
                    )
                }
                built
            }
            if (plan == null) {
                if (id == pushId) {
                    Log.w(TAG, "设备朗读(TTS)未产出音频: ${engine.lastError ?: "未知原因"}")
                }
                return
            }
            if (id != pushId) return
            if (plan.packets.isEmpty()) {
                Log.w(TAG, "设备朗读(TTS)没有可下发的帧(丢弃 ${plan.dropped})")
                return
            }
            // 先声明一段开始,再推音频:设备据此进入播放态(停采集、保持背光)
            if (!sendControlJson(TtsControl.START_JSON)) return
            val startedAtMs = System.currentTimeMillis()
            var sent = 0
            var seq = 0
            for (packet in plan.packets) {
                if (id != pushId) {
                    Log.i(TAG, "设备朗读被打断: 已发 $sent/${plan.packets.size} 帧")
                    return
                }
                val wait = TtsFlowControl.waitMs(sent, System.currentTimeMillis() - startedAtMs)
                if (wait > 0) delay(wait)
                if (id != pushId) {
                    Log.i(TAG, "设备朗读被打断: 已发 $sent/${plan.packets.size} 帧")
                    return
                }
                ble.writeBytes(vbEncodeTtsOpusFrame(seq, plan.rateKhz, plan.frameMs, packet))
                seq = VbTtsOpusPayload.nextSeq(seq)
                sent++
            }
            lastFrames = sent
            lastDropped = plan.dropped
            sendControlJson(TtsControl.STOP_JSON)
            Log.i(
                TAG,
                "设备朗读下发完成: frames=$sent dropped=${plan.dropped} " +
                    "rate=${plan.rateKhz}kHz 耗时 ${System.currentTimeMillis() - startedAtMs}ms",
            )
        }
    }

    // ---- 状态 ----

    /** 最近一次广播的状态文案;供设备页进入时主动查询([ACTION_REQUEST_STATUS])。 */
    @Volatile
    private var lastStatusText: String? = null

    /** 最近一次连接设备的名称(广播名);断开后保留。 */
    @Volatile
    private var lastDeviceName: String? = null

    /** 最近一次连接设备的 MAC;断开后保留,供设备页展示与一键重连。 */
    @Volatile
    private var lastDeviceAddr: String? = null

    /**
     * BLE 链路状态:只由 BLE 回调与扫描/断开动作写。
     * 不能从状态文案推导——链路就绪后仍有大量网关状态文案(如「网关连接已断开…」),
     * 把其中的「断开」当成 BLE 断开,会让设备页卡片时隐时现。
     */
    @Volatile
    private var deviceLinkState: String = LINK_DISCONNECTED

    /** 记下链路状态再广播,让设备页拿到与网关文案无关的链路事实。 */
    private fun publishLinkStatus(linkState: String, text: String) {
        deviceLinkState = linkState
        publishStatus(text)
    }

    /**
     * 读取并缓存当前连接设备的地址+名称。
     * [BleCentral] 只暴露地址,名称从系统蓝牙名缓存读出;取不到时只影响显示(连接不受影响)。
     * 断开后**不**清空:设备页仍能显示上一次的设备,方便直接重连。
     */
    private fun refreshConnectedDevice() {
        val addr = if (::ble.isInitialized) ble.lastConnectedAddr() else null
        if (addr.isNullOrBlank()) return
        val changed = addr != lastDeviceAddr
        lastDeviceAddr = addr
        // 名称只在换设备或还没取到时查一次(BluetoothDevice.name 可能触发远端名请求,别每条状态都问)
        if (changed || lastDeviceName.isNullOrBlank()) {
            lastDeviceName = bluetoothDeviceName(addr)
        }
    }

    /** 读系统缓存里的设备蓝牙名;无权限/地址失效时返回 null,不影响其它流程。 */
    private fun bluetoothDeviceName(addr: String): String? = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(addr)?.name
    } catch (e: Exception) {
        Log.w(TAG, "读取设备名失败: ${e.message}")
        null
    }

    private fun publishStatus(status: String) {
        Log.i(TAG, status)
        lastStatusText = status
        refreshConnectedDevice()
        // 广播可能从 BLE 回调线程发出;通知/广播本身线程安全,无需切线程
        updateNotification(status)
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, status)
                // 设备名/地址随每条状态带出:设备页据此显示真实设备,而不是写死的占位卡片
                .putExtra(EXTRA_DEVICE_NAME, lastDeviceName)
                .putExtra(EXTRA_DEVICE_ADDR, lastDeviceAddr)
                .putExtra(EXTRA_DEVICE_STATE, deviceLinkState)
        )
    }

    /**
     * 向设备上报本 App 的版本(hello)。
     *
     * 设备侧拿它做两件事:① 检查固件/App 是否配套(同一版本号成对发布,不一致时设备屏提示更新 App);
     * ② 显示在设备信息页(长按 UP → 设备信息)。每次链路就绪后发一次即可。
     */
    private fun sendDeviceHello() {
        if (!::ble.isInitialized) return
        val json = JsonObject().apply {
            addProperty("cmd", "hello")
            addProperty("proto", VersionCompat.PROTO_VERSION)
            addProperty("app", appVersionName())
        }.toString()
        Log.i(TAG, "向设备上报 App 版本:${appVersionName()}(协议 v${VersionCompat.PROTO_VERSION})")
        ble.writeBytes(vbEncodeFrame(VbFrame.TYPE_CONTROL, 0, json.toByteArray(Charsets.UTF_8)))
    }

    /** 本 App 的 versionName(运行时读取,不依赖 BuildConfig —— AGP 8 默认不生成它)。 */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        Log.w(TAG, "读取 App 版本号失败:${e.message}")
        "0"
    }

    // ---- 息屏保活 ----

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * 拿两把锁,保证**息屏后连接不断**。
     *
     * 小智语音通道是长连接(已带 20 秒 ping),但手机黑屏后会做两件事把长连接搞死:
     *  ① CPU 休眠 —— 协程/定时器不再跑,ping 发不出去,对端判死连接;
     *  ② Wi-Fi 进入省电模式 —— TCP 长时间空闲被中间设备/路由器回收。
     * 所以这里保持 PARTIAL_WAKE_LOCK(不强亮屏、只让 CPU 能跑) +
     * WifiLock(WIFI_MODE_FULL_HIGH_PERF,防止 Wi-Fi 省电断流)。两把锁随服务生命周期,
     * onDestroy 释放;前台服务本来就在跑,所以额外的耗电有限(实测场景本来就是常连的设备)。
     *
     * 注:MIUI/HyperOS 等系统还要求把应用设为"无限制/允许后台活动"(电池优化豁免),
     * 那是系统侧设置, App 侧只能引导用户去开(见设置页提示)。
     */
    private fun acquireKeepAliveLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:voice-bridge")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "保活:PARTIAL_WAKE_LOCK 已获取")
        } catch (e: Exception) {
            Log.w(TAG, "获取 WakeLock 失败(息屏后连接可能被系统挂起):${e.message}")
        }
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:wifi-bridge")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "保活:WifiLock(HIGH_PERF) 已获取")
        } catch (e: Exception) {
            Log.w(TAG, "获取 WifiLock 失败(息屏后 Wi-Fi 可能省电断流):${e.message}")
        }
    }

    private fun releaseKeepAliveLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
        Log.i(TAG, "保活锁已释放")
    }

    // ---- 前台通知 ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "语音对讲桥",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "保持与 AI Passport 设备的 BLE 连接与语音流水线" }
        nm.createNotificationChannel(channel)
    }

    /**
     * 常驻通知：三行状态（设备 / 网关 / 语音）。
     *
     * 折叠时一行摘要，展开（BigTextStyle）三行 —— 锁屏/息屏时也能一眼看到链路、网关、
     * 语音通道各自是否活着（也是排查"息屏掉连接"最直接的窗口）。
     */
    private fun notification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val device = lastStatusText ?: status
        val gateway = gatewayNotificationLine()
        val voice = BridgeStatusText.voiceLine(
            linkReady = deviceLinkState == LINK_READY || deviceLinkState == LINK_ENCRYPTED,
            warmReady = if (::pipeline.isInitialized) pipeline.recognizerWarm() else false,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Passport 语音桥")
            .setContentText(BridgeStatusText.summary(device, gateway, voice))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(BridgeStatusText.detail(device, gateway, voice))
            )
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentIntent(pi)
            .setOngoing(true)
            // 被划掉也重新挂上:后台保活通知消失会让用户误以为服务停了(也确实是排查窗口)
            .setDeleteIntent(
                PendingIntent.getService(
                    this, 1,
                    Intent(this, VoiceBridgeService::class.java)
                        .setAction(ACTION_REPOST_NOTIFICATION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
    }

    /** 通知里的网关状态行（四态词转中文；类型由状态卡与设备页展示，通知里不重复）。 */
    private fun gatewayNotificationLine(): String = when (lastGatewayState) {
        "ready" -> "就绪"
        "connecting" -> "连接中"
        "working" -> "工作中"
        "offline" -> "离线"
        else -> "未知"
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notification("启动中…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIF_ID, notification("启动中…"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "前台通知启动失败(不影响主流程)", e)
            try { startForeground(NOTIF_ID, notification("启动中…")) } catch (_: Exception) {}
        }
    }

    private fun updateNotification(status: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, notification(status))
        } catch (e: Exception) {
            Log.w(TAG, "更新通知失败", e)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
