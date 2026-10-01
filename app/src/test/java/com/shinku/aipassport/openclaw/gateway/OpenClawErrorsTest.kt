package com.shinku.aipassport.openclaw.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关错误 → 状态映射的 JVM 单测(纯函数,[mapRpcError] 无 Android 依赖)。
 *
 * 覆盖:NOT_PAIRED(错误码与「not approved」文案两条识别路径)、INVALID_REQUEST+device、
 * missing scope、EHOSTUNREACH、SocketTimeout、HTTP 401/403/404,
 * 以及「等待网关授权不被当成致命错误」这条语义(awaitingPairing=true 且 recoverable=true,
 * 状态词仍是四态里的 connecting —— 设备端只认四个词)。
 */
class OpenClawErrorsTest {

    private val short = "1a2b3c4d"

    /** 设备端只显示四个状态词,任何映射结果都必须落在词表里。 */
    @Test
    fun every_mapping_stays_inside_the_four_state_words() {
        val inputs = listOf(
            "NOT_PAIRED" to "pairing required: device is not approved yet",
            "INVALID_REQUEST" to "device not recognized",
            "INVALID_REQUEST" to "missing scope: operator.admin",
            "HTTP 401" to null,
            "HTTP 403" to "forbidden",
            "HTTP 404" to "not found",
            "HTTP 500" to "boom",
            null to "No route to host (EHOSTUNREACH)",
            null to "timeout",
            null to "什么都不是",
            null to null,
        )
        inputs.forEach { (code, message) ->
            val status = mapRpcError(code, message, short)
            assertTrue(
                "state 必须是四态之一: $status",
                status.state in GatewayStatus.ALL_STATES,
            )
            assertTrue("detail 不能为空: $status", status.detail.isNotBlank())
        }
    }

    /** 1) NOT_PAIRED(网关原文):识别为「等待授权」,状态词 connecting,detail 带 deviceId 前 8 位。 */
    @Test
    fun not_paired_code_maps_to_awaiting_pairing() {
        val status = mapRpcError("NOT_PAIRED", "pairing required: device is not approved yet", short)
        assertTrue("必须识别为等待授权", status.awaitingPairing)
        assertEquals(GatewayStatus.STATE_CONNECTING, status.state)
        assertEquals("等待网关授权：请在 OpenClaw 控制台批准本设备 (deviceId $short…)", status.detail)
    }

    /** NOT_PAIRED 也可以只靠 message 里的「not approved」识别(错误码缺失/大小写不同都算)。 */
    @Test
    fun not_approved_message_alone_is_enough() {
        assertTrue(mapRpcError(null, "device is not approved yet", short).awaitingPairing)
        assertTrue(mapRpcError(null, "Device NOT Approved", short).awaitingPairing)
        assertTrue(mapRpcError("not_paired", null, short).awaitingPairing)
    }

    /** 2) INVALID_REQUEST + device(旧代码单独分支的那种):同样是要人去批准,不是配置错。 */
    @Test
    fun invalid_request_with_device_is_pairing() {
        val status = mapRpcError("INVALID_REQUEST", "device not recognized: unknown device", short)
        assertTrue(status.awaitingPairing)
        assertEquals(GatewayStatus.STATE_CONNECTING, status.state)
        assertTrue(status.detail.startsWith(AWAITING_PAIRING_PREFIX))
        assertTrue(status.detail.contains("deviceId $short…"))
        assertTrue("应提示去哪批准", status.detail.contains("openclaw devices approve"))
    }

    /** INVALID_REQUEST 但与设备无关(如参数错)不能被误判成等待授权。 */
    @Test
    fun invalid_request_without_device_is_not_pairing() {
        val status = mapRpcError("INVALID_REQUEST", "invalid params: message is required", short)
        assertFalse(status.awaitingPairing)
    }

    /** 3) missing scope:链路可用、只是这条查询没权限 —— 不是配对,也不是连接失败。 */
    @Test
    fun missing_scope_is_readable_and_not_pairing() {
        val status = mapRpcError("INVALID_REQUEST", "missing scope: operator.admin", short)
        assertFalse("权限不足不是等待授权", status.awaitingPairing)
        assertTrue(status.detail.contains("admin"))
        assertTrue("不能骗用户说连不上", status.state != GatewayStatus.STATE_OFFLINE)
    }

    /**
     * 3.1) 网关不认识这个方法(实测 `usage` / `usage.get` / `stats*` 等):同样是「链路可用」,
     * 绝不能落到兜底的 offline —— 否则辅助查询一失败,状态卡就成了「网关不可达」。
     */
    @Test
    fun unknown_method_is_not_a_connection_failure() {
        val status = mapRpcError("INVALID_REQUEST", "unknown method: usage", short)
        assertEquals(GatewayStatus.STATE_READY, status.state)
        assertFalse(status.awaitingPairing)
        assertFalse("不能报成致命失败", status.fatal)
        assertTrue("原因应可读: ${status.detail}", status.detail.contains("unknown method"))
        assertTrue(
            "method not found 同一处理",
            mapRpcError(null, "method not found", short).state != GatewayStatus.STATE_OFFLINE,
        )
    }

    /** 4) 等待网关授权【不】是致命错误:仍可恢复,重连/重试循环必须继续跑。 */
    @Test
    fun awaiting_pairing_is_not_fatal() {
        val status = mapRpcError("NOT_PAIRED", "pairing required", short)
        assertTrue("等待授权必须可恢复", status.recoverable)
        assertFalse("等待授权不能当致命失败", status.fatal)
        // 反向确认:非配对错误同样可恢复(重连循环对两者都继续)
        assertTrue(mapRpcError(null, "timeout", short).recoverable)
    }

    /** 5) EHOSTUNREACH(主机不可达):offline + 可读原因,仍然可重连。 */
    @Test
    fun host_unreachable_is_offline_and_recoverable() {
        val status = mapRpcError("EHOSTUNREACH", "No route to host (EHOSTUNREACH)", short)
        assertEquals(GatewayStatus.STATE_OFFLINE, status.state)
        assertFalse(status.awaitingPairing)
        assertTrue(status.recoverable)
        assertTrue("原因应可读: ${status.detail}", status.detail.contains("不可达"))
    }

    /** 6) SocketTimeout:offline + 可读原因(不会被当成配对或致命失败)。 */
    @Test
    fun socket_timeout_is_offline_and_recoverable() {
        val status = mapRpcError("SocketTimeout", "connect timed out", short)
        assertEquals(GatewayStatus.STATE_OFFLINE, status.state)
        assertFalse(status.awaitingPairing)
        assertTrue(status.recoverable)
        assertTrue("原因应可读: ${status.detail}", status.detail.contains("超时"))
        assertTrue(mapRpcError(null, "连接超时:网络或 Tailscale 未就绪", short).detail.contains("超时"))
    }

    /** 7) HTTP 401/403/404:状态码进文案,offline 但可重连;401/403 不能被误判成等待授权。 */
    @Test
    fun http_status_codes_are_readable() {
        val unauthorized = mapRpcError("HTTP 401(网关拒绝握手:token 无效) 连接被拒绝:域名/端口不可达", null, short)
        assertTrue(unauthorized.detail.contains("401"))
        assertTrue("401 是 token 问题,不是等审批", !unauthorized.awaitingPairing)
        assertEquals(GatewayStatus.STATE_OFFLINE, unauthorized.state)

        val forbidden = mapRpcError(null, "HTTP 403 forbidden", short)
        assertTrue(forbidden.detail.contains("403"))

        val notFound = mapRpcError(null, "HTTP 404(WS 路径不对或服务未启用)", short)
        assertTrue(notFound.detail.contains("404"))
        assertTrue(notFound.recoverable)
    }

    /** detail 前缀常量与设备屏/设置页日志共用:改文案时两边一起改,单测盯住。 */
    @Test
    fun awaiting_pairing_detail_prefix_is_stable() {
        assertEquals("等待网关授权：", AWAITING_PAIRING_PREFIX)
        assertTrue(
            mapRpcError("NOT_PAIRED", null, short).detail.startsWith(AWAITING_PAIRING_PREFIX),
        )
    }

    /** deviceId 裁剪:完整 deviceId 也只进文案前 8 位(完整值只写日志)。 */
    @Test
    fun device_id_is_trimmed_to_eight_chars() {
        val full = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertEquals("01234567", shortDeviceId(full))
        assertTrue(mapRpcError("NOT_PAIRED", null, full).detail.contains("deviceId 01234567…"))
        assertFalse(mapRpcError("NOT_PAIRED", null, full).detail.contains(full))
    }

    /** 换 token 后旧值失效:必须报「token 不匹配」,不能被引导去批准设备。 */
    @Test
    fun token_mismatch_maps_to_update_token_not_device_approval() {
        val st = mapRpcError(
            "INVALID_REQUEST",
            "unauthorized: gateway token mismatch (open the dashboard URL and paste the token in Control UI settings)",
            "8b87594d",
        )
        assertEquals("offline", st.state)
        assertTrue("应点明 token: ${st.detail}", st.detail.contains("token"))
        assertTrue("应引导去设置页更新: ${st.detail}", st.detail.contains("设置页"))
        assertFalse("不得再引导去批准设备: ${st.detail}", st.detail.contains("approve"))
        assertFalse("token 失效不是等待授权", st.awaitingPairing)
    }

    @Test
    fun token_mismatch_detector_covers_gateway_wording() {
        assertTrue(isTokenMismatch(null, "unauthorized: gateway token mismatch"))
        assertTrue(isTokenMismatch("unauthorized", null))
        assertTrue(isTokenMismatch(null, "invalid token"))
        assertFalse(isTokenMismatch("INVALID_REQUEST", "pairing required: device is not approved yet"))
    }
}
