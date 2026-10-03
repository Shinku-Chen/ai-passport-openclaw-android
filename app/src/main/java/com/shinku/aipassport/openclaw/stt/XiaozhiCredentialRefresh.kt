package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.util.Log

/**
 * 「识别凭据会过期」这件事的**纯逻辑**:什么时候该换新 token、怎么判定鉴权被拒、
 * 以及用户能看懂的两个原因文案。不依赖 Android / OkHttp,可在 JVM 单测里直接覆盖。
 *
 * 背景(真机现象):小智识别 WS 的 `Authorization` 用的是 OTA 下发的 `websocket.token`,
 * 它是**按需签发、带过期**的(开源服务端实现为 JWT,`exp` 约 1 小时;官方固件每次 OTA 都会把
 * `websocket` 段覆盖写入)。旧实现把 token 存下来一直用 → 一小时后 WS 因鉴权失败连不上,
 * 表现又回到「无语音」。本对象 + [XiaozhiSession] 的两条策略把这条路补上:
 *
 *  ① **保守兜底刷新**([isStale]/[STALE_AFTER_MS]):凭据落盘超过阈值,下一次建链**之前**先换一份;
 *  ② **被拒就刷新**([isAuthRejection]/[shouldRefreshOnRejection]):WS 被 401/403 拒、
 *     或因鉴权被关闭 → 重查 OTA 换新凭据后**重连一次**。
 */
object XiaozhiCredentialRefresh {

    /**
     * 兜底刷新的**保守阈值**(毫秒):凭据落盘超过 50 分钟后,下一次建链前主动刷新一次。
     *
     * 依据(为什么只能「保守」+ 为什么是 50 分钟):
     *  - **读不出 `exp`**:token 的 payload 是**加密**的(不是普通 base64 JWT),App 无法解析过期时间,
     *    所以不能精确判断,只能按「存了多久」估;
     *  - 服务端签发的有效期约 **60 分钟** → 阈值必须**短于**它,留出 10 分钟余量吸收时钟偏差、
     *    OTA 往返与「刚刷完就到期」的边界;
     *  - 又不能太短:每次刷新都是一次真实的 OTA 请求(有额度/服务端压力),50 分钟让正常使用
     *    (多次对话)只在跨过阈值时刷新一次,而不是每轮都刷;
     *  - 这套兜底只是**补充**:真正兜住过期的是策略②「被拒就刷新」,即使云端有效期比 1 小时更短,
     *    用户侧依然无感(最多多一次 OTA 往返)。
     */
    const val STALE_AFTER_MS: Long = 50L * 60L * 1000L

    /**
     * 「凭据要刷新但没刷成」时的可读原因(进 `XiaozhiSession.unavailableReason` → 状态文案)。
     *
     * 为什么不自动重试:刷新失败多半是网络不可用 / 云端没下发凭据,继续重连只会重复失败
     * (对用户就是卡着没反应);明确告诉用户「这一轮不行 + 下一步做什么」更好。
     */
    const val REFRESH_FAILED_REASON =
        "小智识别凭据已过期，重新获取凭据失败（网络不可用或云端未下发凭据）——" +
            "请检查网络后重试；若仍不行，请在 xiaozhi.me 删除该设备后重新绑定一次。"

    /**
     * 「刷新过、但云端仍然拒绝」的可读原因:这说明不是简单的过期(多半是绑定关系在云端已失效),
     * 必须让用户重新绑定,而不是继续重连。
     */
    const val STILL_REJECTED_REASON =
        "小智识别凭据刷新后仍被云端拒绝（鉴权失败）——" +
            "请在 xiaozhi.me 删除该设备后重新绑定一次。"

    /**
     * 升级握手被拒的 HTTP 状态码(401 未认证 / 403 无权限):凭据过期或被吊销都落在这两个码上。
     */
    private val AUTH_HTTP_CODES = setOf(401, 403)

    /**
     * 服务端以「鉴权失败」类关闭码收掉连接时的码:
     *  - `1008` Policy Violation —— 小智服务端鉴权失败常用的关闭码;
     *  - `4001/4003/4401/4403` —— 应用层自定义的 401/403 变体(各服务端实现不完全一致)。
     *
     * 正常的自己关闭(1000 正常关闭、1001 离开、1006 异常断开、闲置回收)都**不算**鉴权失败,
     * 不会触发刷新(否则每次断开都要白查一次 OTA)。
     */
    private val AUTH_CLOSE_CODES = setOf(1008, 4001, 4003, 4401, 4403)

    /**
     * 这次失败是否「鉴权被拒」:HTTP 升级响应码与 WS 关闭码任一命中即算。
     *
     * @param httpCode 非 101 升级响应的状态码(OkHttp 在 `onFailure` 的 `response.code`);没有则 null
     * @param wsCloseCode 对端关闭帧的码(`onClosing`/`onClosed`);没有则 null
     */
    fun isAuthRejection(httpCode: Int?, wsCloseCode: Int?): Boolean =
        (httpCode != null && httpCode in AUTH_HTTP_CODES) ||
            (wsCloseCode != null && wsCloseCode in AUTH_CLOSE_CODES)

    /**
     * 凭据是否「偏旧」（保守兜底刷新判据）。
     *
     * `savedAtMs == null` 一律当作**偏旧**:要么本机压根没记过时间戳(升级前存的凭据),
     * 要么时间戳写坏了 —— 这时宁可多查一次 OTA,也不要拿一份可能已过期的 token 去撞。
     */
    fun isStale(savedAtMs: Long?, nowMs: Long): Boolean =
        savedAtMs == null || nowMs - savedAtMs >= STALE_AFTER_MS

    /**
     * 该不该因为这次鉴权被拒去刷新凭据。
     *
     * 两个条件缺一不可:
     *  - [refreshable] = 这条链路用的是**绑定凭据**([XiaozhiLinkAuth.Ok.refreshable]);
     *    匿名通道(占位 token)压根没有「换新凭据」这回事,行为必须与改动前一致;
     *  - [alreadyRefreshed] = false → 同一次建链尝试**最多刷新一次**,刷新后仍被拒就放弃并给可读原因,
     *    绝不为一份不该成功的凭据无限重连。
     */
    fun shouldRefreshOnRejection(refreshable: Boolean, alreadyRefreshed: Boolean): Boolean =
        refreshable && !alreadyRefreshed
}

/**
 * [XiaozhiSession] 需要的「凭据刷新」能力面(纯接口,便于 JVM 单测注入内存实现)。
 *
 * 为什么抽成接口:刷新要发网络请求(OTA)并落盘(SharedPreferences),而会话层是纯 WS 逻辑 ——
 * 由调用方([SttFactory])把它们接进来;单测里可以注入「按脚本返回新凭据」/「固定时间戳」的实现,
 * 把两条策略(兜底阈值、被拒刷新)全部覆盖在 JVM 上,不需要真机也不需要真小智云。
 */
interface XiaozhiCredentialRefreshSource {

    /**
     * 重查一次 OTA(复用 [XiaozhiActivator.queryCloud])并把新凭据落盘
     * (复用 [XiaozhiCredentialStore]),返回新凭据;失败(网络/未下发/不是小智模式) → null。
     *
     * @param deviceAddress 已归一化的设备 MAC([XiaozhiIdentity] 解析出来的那个值)
     */
    suspend fun refresh(deviceAddress: String): XiaozhiCredential?

    /** 该设备凭据的落盘时刻(毫秒);没存过/没记过 → null(调用方按「偏旧」处理)。 */
    fun savedAtMs(deviceAddress: String): Long?

    /** 时间源(单测注入固定值以验证 [XiaozhiCredentialRefresh.STALE_AFTER_MS])。 */
    fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * [XiaozhiCredentialRefreshSource] 的**生产实现**:一次刷新 = 一次 OTA 查询 + 一次凭据落盘。
 *
 * 三条边界:
 *  - **不另写一套请求**:OTA 查询复用 [XiaozhiActivator.queryCloud](请求字段/头/超时都在那里),
 *    生产构造器负责把它接进来;单测可以注入假查询覆盖分流与落盘;
 *  - **非小智网关一次 OTA 都不发**:网关类型在刷新那一刻实时读取并判定([XiaozhiIdentity.isXiaozhi]),
 *    所以从小智 AI 切回匿名通道后,这条路径立刻停止发请求(与会话层 `refreshable` 一道构成双保险);
 *  - **脱敏**:token 只打长度与前 4 位([XiaozhiOtaRequest.describeSecret]),明文永不进日志。
 */
class XiaozhiCredentialRefresher(
    private val store: XiaozhiCredentialStore,
    /**
     * 当前网关类型(实时读取,不缓存):只有「小智 AI」才刷新 —— 非小智模式把小智通道当识别引擎,
     * 用匿名标识,没有「该设备的凭据」可换。
     */
    private val gatewayType: () -> String?,
    /** 一次 OTA 查询(入参 = 归一化后的设备 MAC);生产实现见下面的次构造器。 */
    private val queryCloud: suspend (deviceMac: String) -> XiaozhiActivator.CloudQuery,
) : XiaozhiCredentialRefreshSource {

    /**
     * 生产用构造器:OTA 查询走真实的 [XiaozhiActivator.queryCloud]。
     *
     * 每次刷新**现造**一个 activator:OTA 的 Device-Id 必须是「刷新时」那台已连接设备的 MAC,
     * 不能拿服务启动时定死的值(用户可能已经换了设备)。
     */
    constructor(
        context: Context,
        /** OTA 地址(写死的官方地址,见 [XiaozhiSettings.otaUrl])。 */
        otaUrl: String,
        store: XiaozhiCredentialStore,
        gatewayType: () -> String?,
        /** 设备固件版本(hello.fw)来源;OTA 上报的 `version` 优先用它(见 [XiaozhiOtaRequest.version])。 */
        deviceFirmwareVersionProvider: () -> String? = { null },
    ) : this(
        store = store,
        gatewayType = gatewayType,
        queryCloud = { mac ->
            XiaozhiActivator(
                context = context,
                // 实时读类型;中途被切成非小智时传空串 → [XiaozhiIdentity.resolve] 判为匿名/不可绑,
                // 查询直接失败(宁可不刷,也不拿旧模式的头去查)。
                gatewayType = gatewayType().orEmpty(),
                deviceAddress = mac,
                otaUrl = otaUrl,
                deviceFirmwareVersionProvider = deviceFirmwareVersionProvider,
            ).queryCloud()
        },
    )

    override suspend fun refresh(deviceAddress: String): XiaozhiCredential? {
        val type = gatewayType()
        if (!XiaozhiIdentity.isXiaozhi(type)) {
            Log.i(TAG, "非小智网关($type):不刷新识别凭据,也不查 OTA")
            return null
        }
        val mac = deviceAddress.trim()
        if (mac.isEmpty()) {
            Log.w(TAG, "刷新凭据失败:没有设备地址(小智模式需要设备 MAC)")
            return null
        }
        val query = queryCloud(mac)
        if (query.state != XiaozhiBindGate.CloudState.Activated) {
            Log.w(TAG, "刷新凭据失败:OTA 未返回已激活 state=${query.state} detail=${query.detail ?: "无"}")
            return null
        }
        val fresh = XiaozhiCredential.fromOta(query.wsUrl, query.wsToken) ?: run {
            Log.w(TAG, "刷新凭据失败:本次 OTA 没有下发完整凭据(websocket.url/token)")
            return null
        }
        // 落盘时刻由 store 记(保守兜底刷新的依据);MAC 用 OTA 归一化后的那份。
        store.save(query.mac ?: mac, fresh)
        Log.i(
            TAG,
            "凭据已刷新并落盘:url=${fresh.url} token=${XiaozhiOtaRequest.describeSecret(fresh.token)}",
        )
        return fresh
    }

    override fun savedAtMs(deviceAddress: String): Long? = store.savedAt(deviceAddress)

    companion object {
        private const val TAG = "XiaozhiCredRefresh"
    }
}
