package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.content.SharedPreferences

/**
 * 小智**绑定得到的识别通道凭据**(OTA 响应里的 `websocket` 段:`url` + `token`)。
 *
 * 为什么需要它:自动绑定(OTA)成功后,小智云会给**这台设备**下发一份专属的 WS 凭据 —— 识别通道必须
 * 带它握手。真机现象对应关系:匿名通道(全零 MAC)用共享占位 token 能识别,而换成真 MAC 后云端要的是
 * 「绑定得到的那份凭据」;旧实现把 OTA 响应里的凭据丢掉、握手仍用占位 token,于是长按 OK 说话
 * 拿不到 `stt` —— App 回「无语音」。
 *
 * token 是运行时 secret:只存本机 SharedPreferences,**绝不入库、绝不写进日志正文**
 * (日志只打长度与前 4 位,见 [XiaozhiOtaRequest.describeSecret])。
 */
data class XiaozhiCredential(val url: String, val token: String) {

    /**
     * 小智 WS 握手的 `Authorization` 头取值。
     *
     * 规则与官方固件逐字一致(`xiaozhi-esp32` 的 `WebsocketProtocol::OpenAudioChannel`):token 里没有空格
     * 就补 `Bearer ` 前缀;已经写成 `Bearer xx` 这类形式的原样使用。服务端也按这个前缀解析
     * (`xiaozhi-esp32-server` 的 `websocket_server.py` `_handle_auth` 里 `token[7:]`)。
     */
    fun authorizationValue(): String = if (token.contains(' ')) token else "Bearer $token"

    companion object {
        /**
         * 从 OTA 响应的 `websocket` 段取凭据;**不完整**(url 或 token 为空)时返回 `null`。
         *
         * 为什么不落半份凭据:只有 url 没有 token 拿不去握手(且**绝不能**用占位 token 硬撞),
         * 当作「尚未取得凭据」明确提示用户重新绑定,比留一份用不了的记录更好排查。
         */
        fun fromOta(wsUrl: String?, wsToken: String?): XiaozhiCredential? {
            val url = wsUrl?.trim().orEmpty()
            val token = wsToken?.trim().orEmpty()
            if (url.isEmpty() || token.isEmpty()) return null
            return XiaozhiCredential(url, token)
        }
    }
}

/** 一次建链实际要用的识别通道参数(**URL + Authorization**),或不可建链的可读原因。 */
sealed interface XiaozhiLinkAuth {

    /**
     * 可建链。
     *
     * @param url 本次握手的地址
     * @param authorization `Authorization` 头取值;`null` = 不带这个头
     */
    data class Ok(val url: String, val authorization: String?) : XiaozhiLinkAuth

    /** 不可建链:[reason] 是给用户看的可读原因(调用方据此显示状态文案与下一步)。 */
    data class Unavailable(val reason: String) : XiaozhiLinkAuth
}

/**
 * 「识别通道用哪份鉴权」的**唯一**决策点(纯逻辑,可 JVM 单测)。
 *
 * 两条路必须清楚分开:
 *  - **非小智网关**(OpenClaw / Hermes / 自定义 OpenAI 兼容 / 回显)→ 全零匿名 MAC + **占位 token**
 *    **原样**当 `Authorization` 用:匿名识别通道是实测能跑通的形态,行为与改动前逐字一致;
 *  - **小智 AI** → 用该设备**绑定得到的凭据**(URL + token,`Bearer` 形态);没凭据就**不建链**、
 *    给可读原因([MISSING_CREDENTIAL_REASON]),**绝不**退回去用占位 token 硬撞云端。
 */
object XiaozhiCredentialGate {

    /**
     * 小智模式但本机没有该设备的凭据:识别通道**不建链**,并把原因与下一步摆给用户。
     *
     * 为什么必须明确提示而不是硬撞:真 MAC 的握手用占位 token 会被云端拒绝(整轮识别静默失败,
     * 用户只看到「无语音」),看起来像麦克风/网络坏了 —— 实际是「这台设备还没绑到本机」。
     */
    const val MISSING_CREDENTIAL_REASON =
        "尚未取得小智凭据，请先绑定：打开「设置 → 网关设置」选「小智 AI」并点「保存网关设置」" +
            "（或在「高级 → 小智识别」点「激活小智设备」）完成绑定后重试。"

    /**
     * 「云端已激活但这次 OTA 没下发识别凭据」的提示:本地拿不到 token 就没法识别,
     * 只能让云端重新走一遍绑定把凭据发下来。
     */
    const val MISSING_CREDENTIAL_NOTICE =
        "云端已激活，但本次 OTA 没有下发识别凭据（websocket.token）：" +
            "请在 xiaozhi.me 删除该设备后重新绑定一次。"

    /**
     * 解析本次建链的鉴权。
     *
     * @param gatewayType 当前生效的网关类型(见 [XiaozhiIdentity.isXiaozhi]);非小智一律走匿名通道
     * @param credential 该设备绑定得到的凭据(没绑定/换设备 → null)
     * @param defaultUrl 匿名通道地址(写死的官方地址,见 [XiaozhiSettings.serverUrl])
     * @param placeholderToken 匿名通道的占位 token([XiaozhiSettings.token] 实参原样传进来,不做改写)
     */
    fun linkAuth(
        gatewayType: String?,
        credential: XiaozhiCredential?,
        defaultUrl: String,
        placeholderToken: String,
    ): XiaozhiLinkAuth {
        if (!XiaozhiIdentity.isXiaozhi(gatewayType)) {
            // 匿名通道:URL 与占位 token 都原样(改动前就是这个值、这个位置),识别必须照旧可用。
            return XiaozhiLinkAuth.Ok(
                defaultUrl,
                placeholderToken.ifBlank { XiaozhiSettings.ANONYMOUS_PLACEHOLDER_TOKEN },
            )
        }
        val cred = credential ?: return XiaozhiLinkAuth.Unavailable(MISSING_CREDENTIAL_REASON)
        return XiaozhiLinkAuth.Ok(cred.url, cred.authorizationValue())
    }
}

/** 凭据存储的最小键值面(便于 JVM 单测用内存实现覆盖「存/取」,不依赖 Android)。 */
interface XiaozhiKeyValueStore {
    fun getString(key: String): String?
    /** 写值;`value == null` = 删除该键。 */
    fun put(key: String, value: String?)
}

/** [XiaozhiKeyValueStore] 的 SharedPreferences 实现(App 侧唯一实现)。 */
class SharedPrefsXiaozhiStore(private val prefs: SharedPreferences) : XiaozhiKeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }
}

/**
 * 本机保存的「绑定得到的小智识别凭据」(与既有 `xiaozhi_*` 存储同一风格,但**独立**一份 prefs:
 * 凭据与 [XiaozhiBinding.boundMac] 那个「本地提示」是两件事,混在一处会让「已激活」提示与实际可用性打架)。
 *
 * 只服务**一台**设备(小智链路本来就是「一台已连接设备」):整份凭据带自己的 MAC 一起存,
 * [get] 只有在 MAC 与当前设备一致时才返回 —— 换了设备拿不到旧凭据,调用方据此提示重新绑定,
 * 不会拿另一台设备的 token 去握手。
 */
class XiaozhiCredentialStore(private val kv: XiaozhiKeyValueStore) {

    constructor(context: Context) : this(
        SharedPrefsXiaozhiStore(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        ),
    )

    /** 取该设备(MAC)的凭据;没存过 / 存的是别的设备 / 存的不完整 → null。 */
    fun get(mac: String): XiaozhiCredential? {
        val want = mac.trim()
        if (want.isEmpty()) return null
        val stored = kv.getString(KEY_MAC)?.trim().orEmpty()
        if (!stored.equals(want, ignoreCase = true)) return null
        return XiaozhiCredential.fromOta(kv.getString(KEY_URL), kv.getString(KEY_TOKEN))
    }

    /** 存下该设备的凭据;MAC 为空或凭据不完整(url/token 有空)时**不落盘**(不覆盖已有凭据)。 */
    fun save(mac: String, credential: XiaozhiCredential) {
        val want = mac.trim()
        if (want.isEmpty()) return
        if (credential.url.isBlank() || credential.token.isBlank()) return
        kv.put(KEY_MAC, want)
        kv.put(KEY_URL, credential.url)
        kv.put(KEY_TOKEN, credential.token)
    }

    /** 清掉本机凭据(换设备/退出时用;识别通道下一次建链会给出「请先绑定」)。 */
    fun clear() {
        kv.put(KEY_MAC, null)
        kv.put(KEY_URL, null)
        kv.put(KEY_TOKEN, null)
    }

    companion object {
        /** 独立 prefs 文件名(键名清晰,便于排查本机存了什么)。 */
        const val PREFS = "xiaozhi_credential"

        /** 凭据所属设备的 Device-Id(MAC)。 */
        const val KEY_MAC = "cred_mac"

        /** OTA 下发的识别通道地址。 */
        const val KEY_URL = "cred_ws_url"

        /** OTA 下发的识别通道 token(运行时 secret,只存本机)。 */
        const val KEY_TOKEN = "cred_ws_token"
    }
}
