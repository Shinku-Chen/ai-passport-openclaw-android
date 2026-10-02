package com.shinku.aipassport.openclaw.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「关键 / 辅助 RPC 的错误隔离」的 JVM 单测(纯函数,无 Android 依赖)。
 *
 * 真机 bug(用户原话:「Hermes 切换为 openclaw,网关不可达,重启 app 后正常」)的根因:
 * 概览页对一台**不认识 `usage`** 的网关发了一次辅助查询 → 错误被写进网关的 `lastError`
 * → 随后被「网关配置已重载,正在重连… ｜ unknown method: usage」拼出来,看起来就是网关不可达
 * (连接其实一直正常,重启 App 后那条旧文案没了,所以「重启后正常」)。
 *
 * 覆盖:
 *  - 错误分类:[unknown_method_is_benign] / [critical_errors_are_not_benign]
 *  - 关键性判定:[only_critical_non_benign_errors_may_write_gateway_error]（辅助查询一律不写 lastError）、
 *    [benign_for_queries_is_not_the_same_as_never_write]（「对查询良性」≠「不写 lastError」）
 *  - 状态词:[benign_query_state_never_offline](辅助查询失败不致命,状态只由连接决定)
 *  - 概览页文案:[unsupported_method_hides_card] / [missing_scope_hides_card] /
 *    [connection_failure_keeps_card]
 *  - 会话内记忆:[remember_only_applies_to_same_gateway] / [forget_on_new_gateway_and_clear]
 */
class QuerySupportTest {

    // ---- 错误分类:辅助(良性)vs 关键 ----

    /** 网关不认识这个方法(`unknown method: usage` 等)→ 辅助查询的良性失败。 */
    @Test
    fun unknown_method_is_benign() {
        assertTrue(isUnknownMethod(null, "unknown method: usage"))
        assertTrue(isUnknownMethod("INVALID_REQUEST", "unknown method: usage.get"))
        assertTrue(isUnknownMethod(null, "Method not found: stats"))
        assertTrue(isUnknownMethod("METHOD_NOT_FOUND", null))
        assertTrue(isBenignQueryError("INVALID_REQUEST", "unknown method: usage"))
    }

    /** 无权限(missing scope):链路是通的,只是这条查询读不到 → 同样良性。 */
    @Test
    fun missing_scope_is_benign() {
        assertTrue(isBenignQueryError("INVALID_REQUEST", "missing scope: operator.admin"))
        assertTrue(isMissingScope("missing scope: operator.admin"))
    }

    /** NOT_PAIRED / token 不匹配 / 传输层 / 超时 / HTTP 错误 → 关键(必须写 lastError 并重连)。 */
    @Test
    fun critical_errors_are_not_benign() {
        val critical = listOf(
            "NOT_PAIRED" to "pairing required: device is not approved yet",
            "INVALID_REQUEST" to "unauthorized: gateway token mismatch",
            null to "连接被拒绝:域名/端口不可达",
            "SocketTimeout" to "connect timed out",
            null to "No route to host (EHOSTUNREACH)",
            "HTTP 401" to "网关拒绝握手",
            null to null,
        )
        critical.forEach { (code, message) ->
            assertFalse(
                "必须算关键错误: code=$code message=$message",
                isBenignQueryError(code, message),
            )
            assertTrue(
                "关键错误必须允许写 lastError: code=$code message=$message",
                shouldWriteGatewayError(critical = true, code = code, message = message),
            )
        }
    }

    // ---- 关键性:只有关键操作的真实故障才允许写 lastError ----

    /** 辅助查询(critical=false)一律不写 lastError —— 不管错误是良性还是连接层故障。 */
    @Test
    fun only_critical_non_benign_errors_may_write_gateway_error() {
        assertFalse(
            "辅助查询失败不得改动 lastError",
            shouldWriteGatewayError(critical = false, code = null, message = "unknown method: usage"),
        )
        assertFalse(
            "辅助查询失败不得改动 lastError(即便原因是连接层)",
            shouldWriteGatewayError(critical = false, code = null, message = "连接被拒绝:域名/端口不可达"),
        )
        assertTrue(
            "关键操作 + 真实故障 → 必须写",
            shouldWriteGatewayError(critical = true, code = null, message = "连接被拒绝:域名/端口不可达"),
        )
        assertTrue(
            "关键操作 + 等待授权 → 必须写",
            shouldWriteGatewayError(critical = true, code = "NOT_PAIRED", message = "pairing required"),
        )
        assertTrue(
            "关键操作 + token 失效 → 必须写",
            shouldWriteGatewayError(critical = true, code = null, message = "unauthorized: gateway token mismatch"),
        )
        assertTrue(
            "关键操作 + 权限不足 → 也要写(它说明这次操作真的做不成)",
            shouldWriteGatewayError(
                critical = true,
                code = "INVALID_REQUEST",
                message = "missing scope: operator.admin",
            ),
        )
        assertFalse(
            "关键操作命中良性错误(网关不认识该方法)也不写",
            shouldWriteGatewayError(critical = true, code = null, message = "unknown method: run"),
        )
    }

    /**
     * 两个判定回答的是不同问题,不能混用:
     *  - `isBenignQueryError` = 「**辅助查询**的失败要不要当故障」(能力/权限都算良性);
     *  - `shouldWriteGatewayError` = 「能不能写 lastError」(关键操作里除 unknown method 外都写)。
     * 因此「权限不足」对查询是良性的,对关键操作却必须写进 lastError。
     */
    @Test
    fun benign_for_queries_is_not_the_same_as_never_write() {
        val code = "INVALID_REQUEST"
        val message = "missing scope: operator.admin"
        assertTrue(isBenignQueryError(code, message))
        assertFalse("辅助查询一律不写", shouldWriteGatewayError(false, code, message))
        assertTrue("但关键操作遇到权限不足仍要写", shouldWriteGatewayError(true, code, message))
    }

    /** 辅助查询失败不致命:状态只由连接决定(已连接 → ready;未连上 → connecting),绝不 offline。 */
    @Test
    fun benign_query_state_never_offline() {
        assertEquals(GatewayStatus.STATE_READY, benignQueryState(isConnected = true))
        assertEquals(GatewayStatus.STATE_CONNECTING, benignQueryState(isConnected = false))
        assertTrue(benignQueryState(isConnected = true) != GatewayStatus.STATE_OFFLINE)
        assertTrue(benignQueryState(isConnected = false) != GatewayStatus.STATE_OFFLINE)
        // 与错误映射一致:unknown method / missing scope 都不该被映射成 offline
        assertTrue(
            mapRpcError("INVALID_REQUEST", "unknown method: usage", "1a2b3c4d").state != GatewayStatus.STATE_OFFLINE,
        )
        assertTrue(
            mapRpcError("INVALID_REQUEST", "missing scope: operator.admin", "1a2b3c4d").state !=
                GatewayStatus.STATE_OFFLINE,
        )
    }

    // ---- 概览页文案 ----

    /** 网关不支持 → 友好文案 + 隐藏卡片(而不是一句「不可用: unknown method: usage」)。 */
    @Test
    fun unsupported_method_hides_card() {
        val hint = queryUnavailable("用量", null, "unknown method: usage")
        assertEquals("该网关不支持用量查询", hint.text)
        assertTrue("不支持的分区必须隐藏", hint.hideCard)

        val skills = queryUnavailable("技能", "INVALID_REQUEST", "unknown method: skills")
        assertEquals("该网关不支持技能查询", skills.text)
        assertTrue(skills.hideCard)
    }

    /** 无权限 → 友好文案 + 隐藏卡片(与「不支持」分开措辞,便于用户知道是 token 的问题)。 */
    @Test
    fun missing_scope_hides_card() {
        val hint = queryUnavailable("用量", "INVALID_REQUEST", "missing scope: operator.admin")
        assertEquals("当前 token 无权读取用量（需网关 admin 权限）", hint.text)
        assertTrue(hint.hideCard)
    }

    /** 连接层失败(真的连不上)→ **不隐藏**卡片,把可读原因显示出来。 */
    @Test
    fun connection_failure_keeps_card() {
        val hint = queryUnavailable("用量", null, "网关未连接或鉴权失败")
        assertFalse("连不上不是「不支援」,卡片要留着解释原因", hint.hideCard)
        assertTrue(hint.text.contains("网关未连接或鉴权失败"))

        val empty = queryUnavailable("会话", null, null)
        assertFalse(empty.hideCard)
        assertEquals("不可用: 网关未响应", empty.text)
    }

    // ---- 「这台网关不支持」的会话内记忆 ----

    /** 记住「不支持」只对该配置指纹生效;换网关(指纹变)必须重新探测。 */
    @Test
    fun remember_only_applies_to_same_gateway() {
        val support = QuerySupport()
        assertFalse("还没探测过 → 需要请求", support.isUnsupported("gw-a", "usage"))
        support.rememberUnsupported("gw-a", "usage")
        assertTrue(support.isUnsupported("gw-a", "usage"))
        assertFalse("其它方法不受影响", support.isUnsupported("gw-a", "skills"))
        assertFalse("另一台网关(指纹不同)必须重新探测", support.isUnsupported("gw-b", "usage"))
    }

    /** 指纹变化会作废旧记忆;clear() 全清(重新探测)。 */
    @Test
    fun forget_on_new_gateway_and_clear() {
        val support = QuerySupport()
        support.rememberUnsupported("gw-a", "usage")
        // 换配置后再换回来:旧结论已作废(缓存语义,不算错误)
        assertFalse(support.isUnsupported("gw-b", "usage"))
        support.rememberUnsupported("gw-a", "usage")
        assertTrue(support.isUnsupported("gw-a", "usage"))
        support.clear()
        assertFalse("clear 后必须重新探测", support.isUnsupported("gw-a", "usage"))
    }
}
