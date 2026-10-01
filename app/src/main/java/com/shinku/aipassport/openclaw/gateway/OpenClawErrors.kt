package com.shinku.aipassport.openclaw.gateway

/**
 * OpenClaw 网关错误 → 状态的可读映射。
 *
 * 纯函数、无 Android 依赖:Kotlin/JVM 单测可直接覆盖「NOT_PAIRED / INVALID_REQUEST+device /
 * missing scope / EHOSTUNREACH / 超时 / HTTP 401·403·404」以及
 * 「等待网关授权不被当成致命错误」这条语义(见 `OpenClawErrorsTest`)。
 *
 * 设备端(gateway 通道)只能显示四个状态词 `ready|connecting|working|offline`,
 * 因此「等待网关授权」这类信息【全部】放进 [GatewayStatus.detail](中文、可读),
 * 状态词保持 [GatewayStatus.STATE_CONNECTING]:设备屏底部提示行与 App 状态卡都直接显示 detail。
 *
 * 为什么把 deviceId 放进文案:网关对每台设备做 ed25519 设备配对(首次连接会被回
 * `NOT_PAIRED: pairing required: device is not approved yet`),用户必须在网关主机上批准【这台】设备。
 * 只写「鉴权失败/网关断开」用户不知道要做什么、也不知道在等哪台设备。
 */

/**
 * 网关状态:四个状态词之一 + 中文可读详情。
 *
 * @param state 四个状态词之一(见 [Companion]),与固件 `gateway` 通道共用词表
 * @param detail 中文可读详情:设备屏底部提示行与 App 状态卡直接显示
 * @param awaitingPairing 是否「等待网关授权(设备未配对/未批准)」:设置页据此自动重试而不是报配置错
 * @param recoverable 是否可恢复:true = 保持重连/重试循环(等待授权属于此类);false = 需用户改配置
 */
data class GatewayStatus(
    val state: String,
    val detail: String,
    val awaitingPairing: Boolean = false,
    val recoverable: Boolean = true,
) {
    /** 致命失败 = 重连也不会成功(等待授权【不】是致命失败,必须保持重试)。 */
    val fatal: Boolean get() = !recoverable

    companion object {
        const val STATE_READY = "ready"
        const val STATE_CONNECTING = "connecting"
        const val STATE_WORKING = "working"
        const val STATE_OFFLINE = "offline"

        /** 四态词表:映射结果必须落在其中(设备端只认这四个词)。 */
        val ALL_STATES = listOf(STATE_READY, STATE_CONNECTING, STATE_WORKING, STATE_OFFLINE)
    }
}

/** 「等待网关授权」文案前缀:设置页日志与设备屏据此识别等待授权(而不是配置错误)。 */
const val AWAITING_PAIRING_PREFIX = "等待网关授权："

/** 默认下一步提示:设备必须在 OpenClaw 控制台/CLI 被批准。 */
const val AWAITING_PAIRING_HINT = "请在 OpenClaw 控制台批准本设备"

/** HTTP 状态码提取:框架给的是 "HTTP 401(网关拒绝握手…)" 这样的可读文本。 */
private val HTTP_STATUS = Regex("""HTTP\s*(\d{3})""", RegexOption.IGNORE_CASE)

/** 网关错误码/文案是否表示「设备未配对/未批准」(需人工授权,可恢复)。 */
fun isPairingRequired(code: String?, message: String?): Boolean {
    val c = code?.trim().orEmpty()
    val lower = message?.trim()?.lowercase().orEmpty()
    return c.equals("NOT_PAIRED", ignoreCase = true) ||
        c.equals("PAIRING_REQUIRED", ignoreCase = true) ||
        // 新设备刚加上时,不同网关版本会换别的说法/错误码:下面这些同样是「等用户批准」,
        // 必须走同一个可恢复的授权流程,不能当配置错或网关故障报给用户。
        c.equals("DEVICE_NOT_APPROVED", ignoreCase = true) ||
        c.equals("DEVICE_REQUIRED", ignoreCase = true) ||
        c.equals("NOT_REGISTERED", ignoreCase = true) ||
        lower.contains("not approved") ||
        lower.contains("pairing required") ||
        lower.contains("not paired") ||
        lower.contains("unrecognized device") ||
        lower.contains("unknown device") ||
        lower.contains("device not registered") ||
        lower.contains("device is not registered") ||
        lower.contains("approve") && lower.contains("device")
}

/** 网关回复是否只是「这条查询没权限」(链路本身可用,不是配对问题)。 */
fun isMissingScope(message: String?): Boolean =
    message?.contains("missing scope", ignoreCase = true) == true

/**
 * 网关回复是否是「这台网关不认识这个方法」(实测 `usage` / `usage.get` / `system.usage` / `stats*` /
 * `metrics` / `cost` / `skills` 在部分 OpenClaw 上都是这个错误)。
 *
 * 语义:这是**能力缺失**,不是链路故障 —— 连接与鉴权都好好的,只是这个 RPC 不存在。
 * 因此它绝不能把网关状态推到 `offline`,也绝不能写进 `lastError` 去污染「正在重连…」这类状态文案。
 */
fun isUnknownMethod(code: String?, message: String?): Boolean {
    val c = code?.trim().orEmpty().lowercase()
    val m = message?.trim().orEmpty().lowercase()
    return m.contains("unknown method") ||
        m.contains("method not found") ||
        m.contains("no such method") ||
        c == "method_not_found" ||
        m.contains("未知方法") || m.contains("不支持的方法")
}

/**
 * 辅助查询失败是否「良性」(不该被当成网关故障)。
 *
 * 良性 = 路径/查询本身的问题:① 网关不认识这个方法;② 当前 token 没这个 scope。
 * 两者都说明**链路是通的**,只是这条查询拿不到数据。
 *
 * 关键错误(`NOT_PAIRED` 等待授权、`token mismatch`、传输层/超时/HTTP 错误)一律返回 false:
 * 那些确实代表网关不可用,必须写进 `lastError` 并驱动重连。
 *
 * 注意:本函数回答的是「**辅助查询**的失败要不要当故障」(日志分类/展示决策用);
 * **关键操作**的写入门槛看 [shouldWriteGatewayError] —— 权限不足虽然链路可用,
 * 但它意味着这次关键操作真的做不成,仍要把原因写进 `lastError` 让用户看到。
 */
fun isBenignQueryError(code: String?, message: String?): Boolean =
    isUnknownMethod(code, message) || isMissingScope(message)

/**
 * 一次 RPC 失败是否允许写 `lastError` / 影响网关状态。
 *
 * - 辅助查询(`critical = false`,概览/用量/技能/会话/定时任务等)一律**不写**:
 *   失败只经 `RpcResult` 返回给调用方,网关状态与状态文案完全不受影响;
 * - 关键操作(`critical = true`,`connect` 握手 / `chat.send` / 重连本身)**除「网关不认识这个方法」
 *   外都写**:连接失败、等待授权、token 失效、权限不足都必须让用户看到。
 *   `unknown method` 单列是因为它无论如何都不是链路故障(能力缺失),而其它原因都要能解释
 *   「为什么这次操作没成」—— 一个关键的 `chat.send` / `connect` 失败却什么原因都不留,
 *   `ensureConnected()` 只能报一个「连接超时」,反而把用户引向错误的方向。
 *
 * 真机 bug 的根因就在这里:概览页问了一个这台网关没有的方法(`usage`),错误被写进 `lastError`,
 * 随后被「网关配置已重载,正在重连…」拼上去,看起来就成了**网关不可达**(其实连接一直正常)。
 */
fun shouldWriteGatewayError(critical: Boolean, code: String?, message: String?): Boolean =
    critical && !isUnknownMethod(code, message)

/**
 * 辅助查询失败时的网关状态词:连接没坏,状态只由连接决定。
 * 已连接 → `ready`;尚未连上 → `connecting`(由重连流程推进),**绝不**报 `offline`。
 */
fun benignQueryState(isConnected: Boolean): String =
    if (isConnected) GatewayStatus.STATE_READY else GatewayStatus.STATE_CONNECTING

/**
 * 概览页一个分区的查询失败展示决策。
 *
 * @param text 展示文案:「该网关不支持用量查询」这类友好文案,或可读的失败原因
 * @param hideCard 是否隐藏/禁用该分区卡片(网关不支持 / 无权限时为 true:反复显示一条报错没有意义)
 */
data class QueryUnavailable(val text: String, val hideCard: Boolean)

/**
 * 概览页分区查询失败 → 展示决策(纯函数,JVM 可测)。
 *
 * - 网关不认识该方法 → 「该网关不支持<分区>查询」+ **隐藏卡片**;
 * - 无权限(missing scope) → 「当前 token 无权读取<分区>（需网关 admin 权限）」+ **隐藏卡片**;
 * - 其余(连接断开/超时等)→ 保留卡片并显示可读原因:这是真的连不上,用户需要看到。
 *
 * @param section 分区名的中文短名(如「用量」「技能」),直接拼进文案
 */
fun queryUnavailable(section: String, code: String?, message: String?): QueryUnavailable = when {
    isUnknownMethod(code, message) ->
        QueryUnavailable("该网关不支持${section}查询", hideCard = true)

    isMissingScope(message) ->
        QueryUnavailable("当前 token 无权读取${section}（需网关 admin 权限）", hideCard = true)

    else -> QueryUnavailable(
        "不可用: ${message?.trim()?.takeIf { it.isNotBlank() } ?: "网关未响应"}",
        hideCard = false,
    )
}

/**
 * 构造「等待网关授权」状态。
 *
 * @param deviceIdShort deviceId 前 8 位(完整值写日志,不进设备屏文案)
 * @param hint 下一步提示,默认 [AWAITING_PAIRING_HINT]
 */
fun awaitingPairingStatus(
    deviceIdShort: String,
    hint: String = AWAITING_PAIRING_HINT,
): GatewayStatus = GatewayStatus(
    state = GatewayStatus.STATE_CONNECTING,
    detail = "$AWAITING_PAIRING_PREFIX$hint (deviceId ${shortDeviceId(deviceIdShort)}…)",
    awaitingPairing = true,
    recoverable = true,
)

/** 网关错误码/文案是否表示「token 不匹配/未授权」(换 token 后旧值失效就走这条)。 */
fun isTokenMismatch(code: String?, message: String?): Boolean {
    val msg = message?.trim().orEmpty().lowercase()
    val c = code?.trim().orEmpty().lowercase()
    return msg.contains("token mismatch") ||
        msg.contains("unauthorized") ||
        (msg.contains("token") && msg.contains("invalid")) ||
        c == "unauthorized"
}

/** deviceId 前 8 位(传入完整 deviceId 时也安全;不足 8 位则原样返回)。 */
fun shortDeviceId(deviceId: String?): String = deviceId?.trim()?.take(8).orEmpty()

/**
 * 把网关错误(错误码 + 可读信息)映射成 [GatewayStatus]。
 *
 * 判定顺序:设备配对(等待授权) → 权限不足 → HTTP 状态 → 网络/超时 → 兜底。
 * 只有「等待授权」会把 [GatewayStatus.awaitingPairing] 置 true;
 * 除配对/权限外的一切错误都算 offline 且 recoverable(重连循环继续跑)。
 *
 * @param code 网关 error.code(如 `NOT_PAIRED` / `INVALID_REQUEST`),或已拼好的 `HTTP 401` 之类文本
 * @param message 网关 error.message 或本地异常描述(会做大小写不敏感的关键词匹配)
 * @param deviceIdShort 本机 deviceId 前 8 位(用于提示用户批准哪台设备)
 */
fun mapRpcError(code: String?, message: String?, deviceIdShort: String): GatewayStatus {
    val msg = message?.trim().orEmpty()
    val lower = msg.lowercase()
    val short = shortDeviceId(deviceIdShort)
    val httpCode = HTTP_STATUS.find(code?.trim().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        ?: HTTP_STATUS.find(msg)?.groupValues?.get(1)?.toIntOrNull()

    return when {
        // 0) token 不匹配/未授权:换 token 后旧值失效就走这条。
        //    必须先于「未配对」判定:网关对这两种情况的文案里都可能带 device/gateway 词。
        isTokenMismatch(code, msg) -> GatewayStatus(
            state = GatewayStatus.STATE_OFFLINE,
            detail = "网关 token 不匹配:旧 token 已失效,请在设置页更新 Token 后保存",
        )

        // 1) 设备未配对/未批准:独立的「等待授权」状态,可恢复(批准后下一次重试自动恢复)
        isPairingRequired(code, msg) -> awaitingPairingStatus(short)

        // 2) 网关把未配对设备报成 INVALID_REQUEST:同样是要人去批准的等待授权
        code?.trim().equals("INVALID_REQUEST", ignoreCase = true) && lower.contains("device") ->
            awaitingPairingStatus(
                short,
                "$AWAITING_PAIRING_HINT 或执行 openclaw devices approve",
            )

        // 3) 权限不足:链路可用,只是这条查询没 scope
        isMissingScope(msg) -> GatewayStatus(
            state = GatewayStatus.STATE_READY,
            detail = "该数据需网关 admin 权限,当前 token 无权读取",
            recoverable = true,
        )

        // 3.1) 网关不认识这个方法:同样是「链路可用」,绝不能落到兜底的 offline
        //      (真机 bug:概览页问 `usage` 得到 unknown method,状态卡就成了「网关不可达」)
        isUnknownMethod(code, msg) -> GatewayStatus(
            state = GatewayStatus.STATE_READY,
            detail = "该网关不支持该方法:${msg.ifBlank { code?.trim().orEmpty() }}",
            recoverable = true,
        )

        // 4) HTTP 层:401/403 鉴权、404 路径/服务、5xx 服务端
        httpCode != null -> when (httpCode) {
            401, 403 -> GatewayStatus(
                state = GatewayStatus.STATE_OFFLINE,
                detail = "网关鉴权失败(HTTP $httpCode):token 无效或权限不足,请在设置里更新 token",
            )

            404 -> GatewayStatus(
                state = GatewayStatus.STATE_OFFLINE,
                detail = "网关路径或服务不对(HTTP 404):请确认 wsPath/端口与网关服务已启用",
            )

            in 500..599 -> GatewayStatus(
                state = GatewayStatus.STATE_OFFLINE,
                detail = "网关服务端错误(HTTP $httpCode):请稍后重试或查看网关日志",
            )

            else -> GatewayStatus(
                state = GatewayStatus.STATE_OFFLINE,
                detail = "网关返回 HTTP $httpCode:${msg.ifBlank { "请检查网关配置" }}",
            )
        }

        // 5) 网络层:主机不可达 / 连接被拒
        lower.contains("ehostunreach") || lower.contains("no route to host") ||
            lower.contains("unreachable") || lower.contains("connectexception") ||
            lower.contains("连接被拒绝") || lower.contains("不可达") -> GatewayStatus(
            state = GatewayStatus.STATE_OFFLINE,
            detail = "网关路由不可达:请检查 Host/端口与网络(Tailscale 是否已就绪)${if (msg.isBlank()) "" else " — $msg"}",
        )

        // 6) 超时:连接/读取超时,网络恢复后自动重连
        lower.contains("timeout") || lower.contains("超时") ||
            code?.contains("TIMEOUT", ignoreCase = true) == true -> GatewayStatus(
            state = GatewayStatus.STATE_OFFLINE,
            detail = "连接网关超时:网络或 Tailscale 未就绪,将自动重连${if (msg.isBlank()) "" else " — $msg"}",
        )

        // 7) 兜底:可读信息原样透出,空则给一句人话(仍然可重连)
        else -> GatewayStatus(
            state = GatewayStatus.STATE_OFFLINE,
            detail = msg.ifBlank { code?.trim().orEmpty().ifBlank { "网关错误(未知原因)" } },
        )
    }
}
