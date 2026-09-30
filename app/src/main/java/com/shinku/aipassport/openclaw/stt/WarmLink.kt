package com.shinku.aipassport.openclaw.stt

/**
 * 识别通道「常驻预热」(热连接)的纯逻辑判定:不依赖 Android / OkHttp,可在 JVM 单测里覆盖。
 *
 * 背景(真机现象):设备按下 OK 到「可以说话」之间,App 必须先跟小智云端做一次 WebSocket 握手 +
 * `hello` 往返(实测 0.5–2s),这段时间设备屏一直是「准备中」(整屏红)。把识别通道**常驻预热** ——
 * 一轮结束后不关 socket、服务/BLE 就绪后先建一次 —— 下一轮按下时就能**直接 `listen.start`**,
 * `turn_ready` 毫秒级到达(目标 < 300ms)。
 *
 * 本文件只回答三个纯问题,[XiaozhiStt] 负责真正的 socket 生命周期:
 *  1. 按下时该**复用**热连接还是**重连**([decide] / [shouldReuse]);
 *  2. 复用失败(如 `listen.start` 发不出去)本轮还允不允许兜底重连一次([shouldFallbackReconnect]);
 *  3. `onReady`(→ 设备变绿)的放行条件([sessionEstablished])。
 */
object WarmLink {

    /**
     * 热连接闲置多久后主动关闭(避免长期占用 socket 与服务器会话)。
     * 90s:能覆盖「用户连续几轮对话」的间隔,又不把空闲连接长期挂着。
     */
    const val IDLE_TIMEOUT_MS: Long = 90_000L

    /**
     * 热连接状态快照(由 [XiaozhiStt] 维护)。
     *
     * @param connected WS 已 open + 服务器 `hello` 已回 + 未失败/未关闭(= 可以直接发 `listen.start`)
     * @param lastActiveAtMs 最近一次活动(建立 / 复用)的时刻(毫秒时间戳);用于闲置超时判定
     */
    data class WarmLinkState(
        val connected: Boolean,
        val lastActiveAtMs: Long,
    )

    /**
     * 按下时的通道准备决策(三态)。
     *
     * @property reason 写进日志的原因:见 `热连接不可用,重连中:<原因>`
     */
    enum class WarmDecision(val reason: String) {
        /** 热连接可用:直接 `listen.start`,毫秒级就绪(**不重连**)。 */
        REUSE("可复用"),

        /** 热连接不存在或已断开:走「按下才连」的重连路径(行为与未预热时完全一致)。 */
        RECONNECT_DISCONNECTED("已断开"),

        /** 热连接闲置超时:先关掉再重连。 */
        RECONNECT_IDLE_TIMEOUT("闲置超时"),
    }

    /**
     * 判定按下时是否复用热连接(三态)。
     *
     * - [WarmLinkState.connected] == false → [WarmDecision.RECONNECT_DISCONNECTED];
     * - 闲置时长 ≥ [idleTimeoutMs] → [WarmDecision.RECONNECT_IDLE_TIMEOUT];
     * - 其余 → [WarmDecision.REUSE]。
     *
     * [idleTimeoutMs] ≤ 0 表示**不启用**闲置超时(只要连着就复用)。
     * 时钟回拨(`nowMs < lastActiveAtMs`)按「没超时」处理,不会因系统时间跳变误杀热连接。
     */
    fun decide(
        state: WarmLinkState,
        nowMs: Long,
        idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    ): WarmDecision {
        if (!state.connected) return WarmDecision.RECONNECT_DISCONNECTED
        if (idleTimeoutMs > 0 && nowMs - state.lastActiveAtMs >= idleTimeoutMs) {
            return WarmDecision.RECONNECT_IDLE_TIMEOUT
        }
        return WarmDecision.REUSE
    }

    /** [decide] 的布尔便捷形式:true = 复用热连接(不重连)。 */
    fun shouldReuse(
        state: WarmLinkState,
        nowMs: Long,
        idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    ): Boolean = decide(state, nowMs, idleTimeoutMs) == WarmDecision.REUSE

    /**
     * 「本轮识别会话真的建立」的唯一判据:`listen.start` 是否已被 OkHttp 写队列接受。
     *
     * `onReady`(→ 设备 `turn_ready` 变绿)只能在它为 true 时回调 —— 复用热连接时 `send` 也可能
     * 返回 false(socket 刚好被对端关掉),那时**绝不能**让设备变绿(绿色 = 此时说话一定能被识别)。
     */
    fun sessionEstablished(listenStartSent: Boolean): Boolean = listenStartSent

    /**
     * 复用热连接失败(如 `listen.start` 发不出去)后,本轮还允不允许自动重连一次。
     *
     * 预热/复用出问题**绝不能让整轮识别失败**:允许一次「按下才连」的兜底重连(保底仍能识别),
     * 再失败就放弃并记日志(设备侧还有 2.5s 兜底超时,不会一直红屏)。
     *
     * @param fallbackAttemptsThisTurn 本轮已经因复用失败而重连过的次数
     */
    fun shouldFallbackReconnect(fallbackAttemptsThisTurn: Int): Boolean =
        fallbackAttemptsThisTurn < 1
}
