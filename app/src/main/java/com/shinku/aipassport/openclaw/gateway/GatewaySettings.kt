package com.shinku.aipassport.openclaw.gateway

import android.content.Context

import com.shinku.aipassport.openclaw.tts.TtsEngines

/**
 * 网关设置在 App 内配置(SharedPreferences),不写死。
 *
 * 支持四类网关(见 docs/gateway-adapters.md):
 *  - `openclaw`:自建 OpenClaw 网关(WebSocket + Ed25519 设备鉴权)
 *  - `hermes`:Hermes 的 OpenAI 兼容 HTTP API server(Bearer key)
 *  - `openai`:自定义 OpenAI 兼容 HTTP 服务(`POST {请求路径}`,每次带历史)
 *  - `echo`:本地回显(无网关联调用)
 *
 * 各套配置用不同 key 前缀分别持久化(OpenClaw 沿用历史 `gateway_*` 键,Hermes 用 `hermes_*`,
 * 自定义 OpenAI 兼容用 `openai_*`),因此切换类型不会丢配置。token/API key 是运行时 secret,
 * 只存本机,绝不写进任何提交的代码/构建文件。
 */
class GatewaySettings(context: Context) {

    private val prefs =
        context.getSharedPreferences("gateway_settings", Context.MODE_PRIVATE)

    // ---- 网关类型 ----

    /** 当前生效的网关类型:openclaw | hermes | openai | echo。非法值退回 openclaw。 */
    var type: String
        get() = prefs.getString(KEY_TYPE, TYPE_OPENCLAW)?.takeIf { it in ALL_TYPES } ?: TYPE_OPENCLAW
        set(value) = prefs.edit().putString(KEY_TYPE, value.lowercase().takeIf { it in ALL_TYPES } ?: TYPE_OPENCLAW).apply()

    // ---- OpenClaw(WebSocket)----

    var host: String
        // 默认空:不再预填任何测试域名,由用户在设置页填写。
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var port: String
        get() = prefs.getString(KEY_PORT, DEFAULT_OPENCLAW_PORT) ?: DEFAULT_OPENCLAW_PORT
        set(value) = prefs.edit().putString(KEY_PORT, value.trim()).apply()

    var useTls: Boolean
        get() = prefs.getBoolean(KEY_USE_TLS, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_TLS, value).apply()

    var token: String
        // 默认空:token 是 secret,绝不预填/入库。
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /**
     * 仅调试用:允许自签证书(默认关闭)。
     *
     * 风险:开启后信任所有证书并跳过主机名校验,中间人可无感截获 token 与对话内容;
     * 只适用于内网自签 wss 联调,公网环境必须保持关闭。与 Hermes 的同名开关语义一致。
     */
    var openclawAllowInsecureTls: Boolean
        get() = prefs.getBoolean(KEY_OPENCLAW_ALLOW_INSECURE_TLS, false)
        set(value) = prefs.edit().putBoolean(KEY_OPENCLAW_ALLOW_INSECURE_TLS, value).apply()

    /** WebSocket 对话路径(OpenClaw 网关,可配置)。 */
    var wsPath: String
        get() = prefs.getString(KEY_WS_PATH, DEFAULT_WS_PATH) ?: DEFAULT_WS_PATH
        set(value) {
            val v = value.trim().ifBlank { DEFAULT_WS_PATH }
            prefs.edit().putString(KEY_WS_PATH, if (v.startsWith("/")) v else "/$v").apply()
        }

    /**
     * 等待网关最终回复的上限(秒,默认 180)。可在设置页改,越界值由 [OpenClawConfig] 夹紧到 15..900。
     */
    var openclawReplyTimeoutSeconds: Long
        get() = prefs.getLong(
            KEY_OPENCLAW_REPLY_TIMEOUT_SECONDS,
            GatewayConfig.DEFAULT_REPLY_TIMEOUT_SECONDS,
        )
        set(value) = prefs.edit().putLong(KEY_OPENCLAW_REPLY_TIMEOUT_SECONDS, value).apply()

    /**
     * 对讲机自己的会话名(sessionKey = agent:main:<这个名字>,默认 passport)。
     * 与 PC 控制台/微信/飞书分开,避免共用主会话导致上下文膨胀(实测 23 万 tokens)与话题串线。
     */
    var openclawSessionName: String
        get() = prefs.getString(KEY_OPENCLAW_SESSION_NAME, GatewayConfig.DEFAULT_SESSION_NAME)
            ?: GatewayConfig.DEFAULT_SESSION_NAME
        set(value) {
            val v = value.trim().ifBlank { GatewayConfig.DEFAULT_SESSION_NAME }
            prefs.edit().putString(KEY_OPENCLAW_SESSION_NAME, v).apply()
        }

    /** OpenClaw 的域名+端口是否已配置。 */
    fun isConfigured(): Boolean = host.isNotBlank() && port.isNotBlank()

    // ---- 语音附加提示词(与网关类型无关,所有通道共用)----

    /**
     * 语音输入末尾自动附加的提示词(「回复约束提示词」)。
     *
     * 只作用于语音输入(见 [VoicePrompt.compose]):文字输入不追加。默认值为
     * [DEFAULT_VOICE_PROMPT_SUFFIX];用户可清空(空串 = 不追加任何内容),因此这里【不】做
     * 「空则退回默认」的兜底,与业务上可关闭该行为一致。
     */
    var voicePromptSuffix: String
        get() = prefs.getString(KEY_VOICE_PROMPT_SUFFIX, DEFAULT_VOICE_PROMPT_SUFFIX)
            ?: DEFAULT_VOICE_PROMPT_SUFFIX
        set(value) = prefs.edit().putString(KEY_VOICE_PROMPT_SUFFIX, value.trim()).apply()

    // ---- App 调试展示(与网关类型无关)----

    /**
     * App 对话列表是否展示**完整回传流(raw)**。
     *
     * 默认 **true**(调试用):把一轮里状态/生命周期/步骤/工具/工具输出/正文/终局/用量
     * 全部按到达顺序展示(非正文条目弱化小字)。
     * 关掉后 App 也只显示 body(设备屏/TTS 用的正确正文),适合正式演示。
     *
     * 这是纯 App 展示开关,与网关连接无关,因此设置页**切换即落盘**(不走「保存网关设置」的
     * 校验-落盘闸门;校验失败也不会把它回滚)。
     */
    var showRawStream: Boolean
        get() = prefs.getBoolean(KEY_SHOW_RAW_STREAM, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_RAW_STREAM, value).apply()

    /**
     * 开机自动启动：重启后由 [com.shinku.aipassport.openclaw.service.BootReceiver] 拉起前台服务。
     *
     * 默认开（这个应用本身就是常连设备）。能否真的生效还取决于系统是否放行开机广播：
     * 小米/HyperOS 需要用户在本应用的「自启动」里打开，属 OEM 白名单，App 无法代替。
     */
    var bootAutoStart: Boolean
        get() = prefs.getBoolean(KEY_BOOT_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_AUTO_START, value).apply()

    // ---- 设备朗读(下行 TTS:手机合成 → Opus → 设备播放回复)----

    /**
     * 是否把网关回复合成成音频并**下发设备播放**(设备朗读回复)。
     *
     * 默认 **true(开)**:固件 TTS 播放通路已在真机验收(播放栈/LVGL 池两个崩溃点已修),
     * 因此新装即开;设备若不支持(`hello.caps` 无 `tts_opus`)由 App 回退手机朗读。
     * 与网关连接无关,因此设置页**切换即落盘**(不走「保存网关设置」的校验-落盘闸门,也不参与网关探活)。
     */
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    /**
     * 设备朗读的合成引擎:`android`(系统 `TextToSpeech`,M1 唯一实现)/ `http`(骨架,服务选型待确认)。
     * 非法值退回 `android`(见 [TtsEngines.normalize])。与网关无关,**切换即落盘**。
     */
    var ttsEngine: String
        get() = TtsEngines.normalize(prefs.getString(KEY_TTS_ENGINE, TtsEngines.ANDROID))
        set(value) = prefs.edit().putString(KEY_TTS_ENGINE, TtsEngines.normalize(value)).apply()

    // ---- Hermes(OpenAI 兼容 HTTP)----

    var hermesHost: String
        get() = prefs.getString(KEY_HERMES_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HERMES_HOST, value.trim()).apply()

    var hermesPort: String
        get() = prefs.getString(KEY_HERMES_PORT, DEFAULT_HERMES_PORT) ?: DEFAULT_HERMES_PORT
        set(value) = prefs.edit().putString(KEY_HERMES_PORT, value.trim()).apply()

    var hermesUseTls: Boolean
        get() = prefs.getBoolean(KEY_HERMES_USE_TLS, false)
        set(value) = prefs.edit().putBoolean(KEY_HERMES_USE_TLS, value).apply()

    /** 仅调试用:允许自签证书(默认关闭)。 */
    var hermesAllowInsecureTls: Boolean
        get() = prefs.getBoolean(KEY_HERMES_ALLOW_INSECURE_TLS, false)
        set(value) = prefs.edit().putBoolean(KEY_HERMES_ALLOW_INSECURE_TLS, value).apply()

    /** 基址路径前缀(反代场景可配),如 `/hermes`;空串 = 根路径。 */
    var hermesBasePath: String
        get() = prefs.getString(KEY_HERMES_BASE_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HERMES_BASE_PATH, value.trim()).apply()

    /** API_SERVER_KEY(Bearer token),只存本机。 */
    var hermesToken: String
        get() = prefs.getString(KEY_HERMES_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HERMES_TOKEN, value.trim()).apply()

    /** 请求体里的 model 字段(仅展示用途,真正模型由服务端配置决定)。 */
    var hermesModel: String
        get() = prefs.getString(KEY_HERMES_MODEL, DEFAULT_HERMES_MODEL) ?: DEFAULT_HERMES_MODEL
        set(value) = prefs.edit().putString(KEY_HERMES_MODEL, value.trim().ifBlank { DEFAULT_HERMES_MODEL }).apply()

    /** 服务端会话名(留空时由 GatewayFactory 用设备名/deviceId 兜底)。 */
    var hermesConversation: String
        get() = prefs.getString(KEY_HERMES_CONVERSATION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HERMES_CONVERSATION, value.trim()).apply()

    /** true = 服务端按 conversation 名管历史;false = 客户端每次带完整 messages。 */
    var hermesUseServerSideConversation: Boolean
        get() = prefs.getBoolean(KEY_HERMES_SERVER_SIDE_CONV, false)
        set(value) = prefs.edit().putBoolean(KEY_HERMES_SERVER_SIDE_CONV, value).apply()

    /** true = SSE 流式取回复;false = 一次性取 choices[0].message.content。 */
    var hermesStream: Boolean
        get() = prefs.getBoolean(KEY_HERMES_STREAM, false)
        set(value) = prefs.edit().putBoolean(KEY_HERMES_STREAM, value).apply()

    /** Hermes 的域名+端口是否已配置。 */
    fun isHermesConfigured(): Boolean = hermesHost.isNotBlank() && hermesPort.isNotBlank()

    // ---- 自定义 OpenAI 兼容(HTTP,Bearer key,每次带历史)----

    var openaiHost: String
        get() = prefs.getString(KEY_OPENAI_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_HOST, value.trim()).apply()

    var openaiPort: String
        get() = prefs.getString(KEY_OPENAI_PORT, DEFAULT_OPENAI_PORT) ?: DEFAULT_OPENAI_PORT
        set(value) = prefs.edit().putString(KEY_OPENAI_PORT, value.trim()).apply()

    var openaiUseTls: Boolean
        get() = prefs.getBoolean(KEY_OPENAI_USE_TLS, false)
        set(value) = prefs.edit().putBoolean(KEY_OPENAI_USE_TLS, value).apply()

    /** 仅调试用:允许自签证书(默认关闭,仅作用于本通道)。 */
    var openaiAllowInsecureTls: Boolean
        get() = prefs.getBoolean(KEY_OPENAI_ALLOW_INSECURE_TLS, false)
        set(value) = prefs.edit().putBoolean(KEY_OPENAI_ALLOW_INSECURE_TLS, value).apply()

    /**
     * 请求路径(默认 `/v1`,解析为 `/v1/chat/completions`);可直接填完整端点路径
     * (如 `/openai/v1/chat/completions`),解析规则见 [OpenAiPath]。prefs key 仍为 `openai_base_path`。
     */
    var openaiBasePath: String
        get() = prefs.getString(KEY_OPENAI_BASE_PATH, DEFAULT_OPENAI_BASE_PATH) ?: DEFAULT_OPENAI_BASE_PATH
        set(value) = prefs.edit().putString(KEY_OPENAI_BASE_PATH, value.trim()).apply()

    /** Bearer API Key(明文输入框、只存本机;本地服务不校验时可留空)。 */
    var openaiApiKey: String
        get() = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_API_KEY, value.trim()).apply()

    /** 模型名(必填:服务端按它选模型)。 */
    var openaiModel: String
        get() = prefs.getString(KEY_OPENAI_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_MODEL, value.trim()).apply()

    /** 可选 system 提示;留空则请求里不带 system 消息。 */
    var openaiSystemPrompt: String
        get() = prefs.getString(KEY_OPENAI_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_SYSTEM_PROMPT, value.trim()).apply()

    /** 单次请求携带的历史消息上限(默认 20 条)。 */
    var openaiMaxHistory: Int
        get() = prefs.getInt(KEY_OPENAI_MAX_HISTORY, DEFAULT_OPENAI_MAX_HISTORY)
        set(value) = prefs.edit().putInt(KEY_OPENAI_MAX_HISTORY, value).apply()

    /** true = SSE 流式取回复;false = 一次性取 choices[0].message.content。 */
    var openaiStream: Boolean
        get() = prefs.getBoolean(KEY_OPENAI_STREAM, false)
        set(value) = prefs.edit().putBoolean(KEY_OPENAI_STREAM, value).apply()

    /** 自定义 OpenAI 兼容通道的域名+端口+模型名是否已配置(model 必填)。 */
    fun isOpenAiConfigured(): Boolean =
        openaiHost.isNotBlank() && openaiPort.isNotBlank() && openaiModel.isNotBlank()

    /** 组装 [OpenAiConfig](调用 [openAiConfigOf] 统一归一化规则,与草稿校验完全一致)。 */
    fun openAiConfig(): OpenAiConfig = openAiConfigOf(
        host = openaiHost,
        port = openaiPort,
        useTls = openaiUseTls,
        allowInsecureTls = openaiAllowInsecureTls,
        basePath = openaiBasePath,
        apiKey = openaiApiKey,
        model = openaiModel,
        systemPrompt = openaiSystemPrompt,
        maxHistory = openaiMaxHistory.toString(),
        stream = openaiStream,
    )

    /**
     * 组装 [HermesConfig](调用 [hermesConfigOf] 统一归一化规则,与草稿校验完全一致)。
     * @param defaultConversation 会话名留空时的兜底值(设备名/deviceId)
     */
    fun hermesConfig(defaultConversation: String = ""): HermesConfig = hermesConfigOf(
        host = hermesHost,
        port = hermesPort,
        useTls = hermesUseTls,
        allowInsecureTls = hermesAllowInsecureTls,
        basePath = hermesBasePath,
        token = hermesToken,
        model = hermesModel,
        conversation = hermesConversation,
        useServerSideConversation = hermesUseServerSideConversation,
        stream = hermesStream,
        defaultConversation = defaultConversation,
    )

    // ---- 保存 ----

    /**
     * 保存 OpenClaw 一套配置;未传的字段保持不变。
     *
     * @param replyTimeoutSeconds 回复等待上限(秒)的输入框原值;空/非法保持原值,
     *   合法则夹紧到 [GatewayConfig.MIN_REPLY_TIMEOUT_SECONDS]..[GatewayConfig.MAX_REPLY_TIMEOUT_SECONDS]
     */
    fun save(
        host: String,
        port: String,
        useTls: Boolean,
        allowInsecureTls: Boolean,
        token: String,
        wsPath: String? = null,
        replyTimeoutSeconds: String? = null,
        sessionName: String? = null,
        voicePromptSuffix: String? = null,
    ) {
        prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putString(KEY_PORT, port.trim())
            .putBoolean(KEY_USE_TLS, useTls)
            .putBoolean(KEY_OPENCLAW_ALLOW_INSECURE_TLS, allowInsecureTls)
            .putString(KEY_TOKEN, token.trim())
            .apply()
        if (wsPath != null) this.wsPath = wsPath
        replyTimeoutSeconds
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(
                GatewayConfig.MIN_REPLY_TIMEOUT_SECONDS,
                GatewayConfig.MAX_REPLY_TIMEOUT_SECONDS,
            )
            ?.let { this.openclawReplyTimeoutSeconds = it }
        sessionName?.let { this.openclawSessionName = it }
        // 语音附加提示词与网关类型无关,不传时保持原值(沿用 setter 的 trim 归一化)
        voicePromptSuffix?.let { this.voicePromptSuffix = it }
    }

    /** 保存 Hermes 一套配置。 */
    fun saveHermes(
        host: String,
        port: String,
        useTls: Boolean,
        allowInsecureTls: Boolean,
        basePath: String,
        token: String,
        model: String,
        conversation: String,
        useServerSideConversation: Boolean,
        stream: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_HERMES_HOST, host.trim())
            .putString(KEY_HERMES_PORT, port.trim())
            .putBoolean(KEY_HERMES_USE_TLS, useTls)
            .putBoolean(KEY_HERMES_ALLOW_INSECURE_TLS, allowInsecureTls)
            .putString(KEY_HERMES_BASE_PATH, basePath.trim())
            .putString(KEY_HERMES_TOKEN, token.trim())
            .putString(KEY_HERMES_MODEL, model.trim().ifBlank { DEFAULT_HERMES_MODEL })
            .putString(KEY_HERMES_CONVERSATION, conversation.trim())
            .putBoolean(KEY_HERMES_SERVER_SIDE_CONV, useServerSideConversation)
            .putBoolean(KEY_HERMES_STREAM, stream)
            .apply()
    }

    /** 保存自定义 OpenAI 兼容一套配置。 */
    fun saveOpenAi(
        host: String,
        port: String,
        useTls: Boolean,
        allowInsecureTls: Boolean,
        basePath: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        maxHistory: String,
        stream: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_OPENAI_HOST, host.trim())
            .putString(KEY_OPENAI_PORT, port.trim())
            .putBoolean(KEY_OPENAI_USE_TLS, useTls)
            .putBoolean(KEY_OPENAI_ALLOW_INSECURE_TLS, allowInsecureTls)
            .putString(KEY_OPENAI_BASE_PATH, basePath.trim())
            .putString(KEY_OPENAI_API_KEY, apiKey.trim())
            .putString(KEY_OPENAI_MODEL, model.trim())
            .putString(KEY_OPENAI_SYSTEM_PROMPT, systemPrompt.trim())
            .putInt(
                KEY_OPENAI_MAX_HISTORY,
                maxHistory.trim().toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_OPENAI_MAX_HISTORY,
            )
            .putBoolean(KEY_OPENAI_STREAM, stream)
            .apply()
    }

    companion object {
        /**
         * 从原始表单值组装 [HermesConfig]:设置页保存校验的草稿也走这里,
         * 保证「探活用的配置」与「落盘后实际使用的配置」逐字一致。
         * 端口非法(空/非数字/越界)退回默认 8642;会话名留空时用 [defaultConversation]。
         */
        fun hermesConfigOf(
            host: String,
            port: String,
            useTls: Boolean,
            allowInsecureTls: Boolean,
            basePath: String,
            token: String,
            model: String,
            conversation: String,
            useServerSideConversation: Boolean,
            stream: Boolean,
            defaultConversation: String = "",
        ): HermesConfig = HermesConfig(
            host = host.trim(),
            port = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_HERMES_PORT_INT,
            useTls = useTls,
            allowInsecureTls = allowInsecureTls,
            basePath = basePath.trim(),
            token = token.trim(),
            model = model.trim().ifBlank { DEFAULT_HERMES_MODEL },
            conversation = conversation.trim().ifBlank { defaultConversation },
            useServerSideConversation = useServerSideConversation,
            stream = stream,
        )

        /**
         * 从原始表单值组装 [OpenAiConfig]:设置页保存校验的草稿也走这里,
         * 保证「探活用的配置」与「落盘后实际使用的配置」逐字一致。
         * 端口非法退回默认;[DEFAULT_OPENAI_MAX_HISTORY] 为历史条数上限。
         */
        fun openAiConfigOf(
            host: String,
            port: String,
            useTls: Boolean,
            allowInsecureTls: Boolean,
            basePath: String,
            apiKey: String,
            model: String,
            systemPrompt: String,
            maxHistory: String,
            stream: Boolean,
        ): OpenAiConfig = OpenAiConfig(
            host = host.trim(),
            port = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_OPENAI_PORT_INT,
            useTls = useTls,
            allowInsecureTls = allowInsecureTls,
            basePath = basePath.trim().ifBlank { DEFAULT_OPENAI_BASE_PATH },
            apiKey = apiKey.trim(),
            model = model.trim(),
            systemPrompt = systemPrompt.trim(),
            maxHistory = maxHistory.trim().toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_OPENAI_MAX_HISTORY,
            stream = stream,
        )

        /** 网关类型标识。 */
        const val TYPE_OPENCLAW = "openclaw"
        const val TYPE_HERMES = "hermes"
        const val TYPE_OPENAI = "openai"
        const val TYPE_ECHO = "echo"

        val ALL_TYPES = listOf(TYPE_OPENCLAW, TYPE_HERMES, TYPE_OPENAI, TYPE_ECHO)

        const val DEFAULT_OPENCLAW_PORT = "8035"
        const val DEFAULT_HERMES_PORT = "8642"
        const val DEFAULT_HERMES_PORT_INT = 8642
        const val DEFAULT_HERMES_MODEL = "hermes-agent"
        const val DEFAULT_OPENAI_PORT = "8080"
        const val DEFAULT_OPENAI_PORT_INT = 8080
        const val DEFAULT_OPENAI_BASE_PATH = "/v1"
        const val DEFAULT_OPENAI_MAX_HISTORY = 20
        const val DEFAULT_WS_PATH = "/message/messages/ws"

        private const val KEY_TYPE = "gateway_type"

        // OpenClaw 键(沿用历史命名,保证既有配置不丢)
        private const val KEY_HOST = "gateway_host"
        private const val KEY_PORT = "gateway_port"
        private const val KEY_USE_TLS = "gateway_use_tls"
        private const val KEY_TOKEN = "gateway_token"
        private const val KEY_WS_PATH = "gateway_ws_path"

        // Hermes 键(独立前缀)
        private const val KEY_HERMES_HOST = "hermes_host"
        private const val KEY_HERMES_PORT = "hermes_port"
        private const val KEY_HERMES_USE_TLS = "hermes_use_tls"
        private const val KEY_HERMES_ALLOW_INSECURE_TLS = "hermes_allow_insecure_tls"
        private const val KEY_HERMES_BASE_PATH = "hermes_base_path"
        private const val KEY_HERMES_TOKEN = "hermes_token"
        private const val KEY_HERMES_MODEL = "hermes_model"
        private const val KEY_HERMES_CONVERSATION = "hermes_conversation"
        private const val KEY_HERMES_SERVER_SIDE_CONV = "hermes_use_server_side_conversation"
        private const val KEY_HERMES_STREAM = "hermes_stream"

        // 自定义 OpenAI 兼容键(独立前缀)
        private const val KEY_OPENAI_HOST = "openai_host"
        private const val KEY_OPENAI_PORT = "openai_port"
        private const val KEY_OPENAI_USE_TLS = "openai_use_tls"
        private const val KEY_OPENAI_ALLOW_INSECURE_TLS = "openai_allow_insecure_tls"
        private const val KEY_OPENAI_BASE_PATH = "openai_base_path"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_OPENAI_SYSTEM_PROMPT = "openai_system_prompt"
        private const val KEY_OPENAI_MAX_HISTORY = "openai_max_history"
        private const val KEY_OPENAI_STREAM = "openai_stream"

        // OpenClaw 的调试开关(与 token 同组,仍用历史 gateway_* 前缀)
        private const val KEY_OPENCLAW_ALLOW_INSECURE_TLS = "gateway_allow_insecure_tls"
        private const val KEY_OPENCLAW_REPLY_TIMEOUT_SECONDS = "gateway_reply_timeout_seconds"
        private const val KEY_OPENCLAW_SESSION_NAME = "gateway_session_name"

        // 语音附加提示词(与网关类型无关;存同一份 gateway_settings prefs)
        private const val KEY_VOICE_PROMPT_SUFFIX = "voice_prompt_suffix"

        // App 调试展示开关(与网关类型无关;存同一份 gateway_settings prefs)
        private const val KEY_SHOW_RAW_STREAM = "show_raw_stream"

        /** 开机自启开关（默认开）。 */
        private const val KEY_BOOT_AUTO_START = "boot_auto_start"

        // 设备朗读(下行 TTS)开关与引擎(与网关类型无关)
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_TTS_ENGINE = "tts_engine"
    }
}
