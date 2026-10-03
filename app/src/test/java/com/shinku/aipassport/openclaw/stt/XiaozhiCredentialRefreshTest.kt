package com.shinku.aipassport.openclaw.stt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「识别凭据会过期」这条路的**纯逻辑 + 刷新实现**验证(不碰网络/Android):
 *  - 两个触发点:兜底阈值([XiaozhiCredentialRefresh.isStale])与鉴权被拒([isAuthRejection]);
 *  - 防死循环:同一次建链尝试最多刷新一次([shouldRefreshOnRejection]);
 *  - [XiaozhiCredentialRefresher]:OTA 分流(已激活才落盘)、非小智网关一次查询都不发、脱敏不落日志。
 *
 * 真链路(WS 被 401 拒 → 刷新 → 重连)见 `XiaozhiTokenRefreshTest`。
 */
class XiaozhiCredentialRefreshTest {

    private val mac = "4C:11:AE:30:B9:7A"
    private val url = "wss://api.tenclass.net/xiaozhi/v1/"
    private val token = "eyJhbGciOiJIUzI1NiJ9.payload.sig"

    // ---- 兜底阈值 ----

    @Test
    fun `凭据超过保守阈值才算偏旧_阈值短于 1 小时有效期`() {
        val savedAt = 1_700_000_000_000L
        assertFalse("刚存下 → 不刷新", XiaozhiCredentialRefresh.isStale(savedAt, savedAt))
        assertFalse(
            "49 分 59 秒 → 还在保守区间内",
            XiaozhiCredentialRefresh.isStale(savedAt, savedAt + 49 * 60_000L + 59_000L),
        )
        assertTrue(
            "满 50 分钟 → 偏旧,下一次建链前先换一份",
            XiaozhiCredentialRefresh.isStale(savedAt, savedAt + 50 * 60_000L),
        )
        assertTrue("超过 1 小时必然偏旧", XiaozhiCredentialRefresh.isStale(savedAt, savedAt + 61 * 60_000L))
        assertTrue("时间戳未知(旧版本存的凭据)→ 保守当作偏旧", XiaozhiCredentialRefresh.isStale(null, savedAt))
        assertTrue(
            "阈值必须短于服务端约 1 小时的有效期,否则等于没兜底",
            XiaozhiCredentialRefresh.STALE_AFTER_MS < 60 * 60_000L,
        )
    }

    // ---- 鉴权被拒的判定 ----

    @Test
    fun `升级被 401 或 403 拒算鉴权失败`() {
        assertTrue(XiaozhiCredentialRefresh.isAuthRejection(401, null))
        assertTrue(XiaozhiCredentialRefresh.isAuthRejection(403, null))
        assertFalse("别的 HTTP 码不是鉴权问题(别乱刷 token)", XiaozhiCredentialRefresh.isAuthRejection(400, null))
        assertFalse(XiaozhiCredentialRefresh.isAuthRejection(500, null))
        assertFalse(XiaozhiCredentialRefresh.isAuthRejection(101, null))
        assertFalse(XiaozhiCredentialRefresh.isAuthRejection(null, null))
    }

    @Test
    fun `服务端以鉴权类关闭码收掉连接也算`() {
        assertTrue(XiaozhiCredentialRefresh.isAuthRejection(null, 1008))
        assertTrue(XiaozhiCredentialRefresh.isAuthRejection(null, 4001))
        assertTrue(XiaozhiCredentialRefresh.isAuthRejection(null, 4403))
        assertFalse("正常关闭不算(否则每次断开都白查一次 OTA)", XiaozhiCredentialRefresh.isAuthRejection(null, 1000))
        assertFalse(XiaozhiCredentialRefresh.isAuthRejection(null, 1001))
        assertFalse("异常断开(网络掉线)不是鉴权问题", XiaozhiCredentialRefresh.isAuthRejection(null, 1006))
    }

    @Test
    fun `同一次尝试最多刷新一次_且匿名链路从不刷新`() {
        assertTrue("凭据链路 + 没刷过 → 刷", XiaozhiCredentialRefresh.shouldRefreshOnRejection(true, false))
        assertFalse("已经刷过一次 → 不循环,给可读原因", XiaozhiCredentialRefresh.shouldRefreshOnRejection(true, true))
        assertFalse("匿名链路(占位 token)→ 不刷新", XiaozhiCredentialRefresh.shouldRefreshOnRejection(false, false))
        assertFalse(XiaozhiCredentialRefresh.shouldRefreshOnRejection(false, true))
    }

    // ---- 刷新实现:OTA 分流 + 落盘 ----

    private class RecordingQuery(private val query: XiaozhiActivator.CloudQuery?) {
        var calls = 0
        var lastMac: String? = null
        suspend fun invoke(mac: String): XiaozhiActivator.CloudQuery {
            calls++
            lastMac = mac
            return query ?: XiaozhiActivator.CloudQuery(XiaozhiBindGate.CloudState.QueryFailed)
        }
    }

    private fun activated(wsUrl: String?, wsToken: String?) = XiaozhiActivator.CloudQuery(
        XiaozhiBindGate.CloudState.Activated,
        mac = mac,
        wsUrl = wsUrl,
        wsToken = wsToken,
    )

    @Test
    fun `已激活且有 websocket 段_落盘并返回新凭据`() {
        val kv = FakeXiaozhiStore()
        val store = XiaozhiCredentialStore(kv)
        val query = RecordingQuery(activated("wss://api.tenclass.net/xiaozhi/v2/", token))
        val refresher = XiaozhiCredentialRefresher(store, { XiaozhiIdentity.GATEWAY_XIAOZHI }, query::invoke)

        val fresh = runBlocking { refresher.refresh(mac.lowercase()) }

        assertEquals(XiaozhiCredential("wss://api.tenclass.net/xiaozhi/v2/", token), fresh)
        assertEquals("新凭据必须落盘(下一次建链/重启用它)", fresh, store.get(mac))
        assertTrue("落盘时刻已记(兜底刷新的依据)", store.savedAt(mac) != null)
        assertEquals("一次刷新 = 一次 OTA 查询", 1, query.calls)
        assertEquals("OTA 用归一化前的设备地址", mac.lowercase(), query.lastMac)
    }

    @Test
    fun `非小智网关一次 OTA 都不发`() {
        val store = XiaozhiCredentialStore(FakeXiaozhiStore())
        val query = RecordingQuery(activated(url, token))
        val refresher = XiaozhiCredentialRefresher(store, { "openclaw" }, query::invoke)

        assertNull("匿名通道没有「该设备的凭据」可换", runBlocking { refresher.refresh(mac) })
        assertEquals("0 次 OTA(行为与改动前一致)", 0, query.calls)
    }

    @Test
    fun `云端未激活_或没下发完整凭据_都不落盘`() {
        val kv = FakeXiaozhiStore()
        val store = XiaozhiCredentialStore(kv)
        store.save(mac, XiaozhiCredential(url, "老token"))

        val needBind = RecordingQuery(
            XiaozhiActivator.CloudQuery(XiaozhiBindGate.CloudState.NeedsBinding, mac = mac, code = "123456"),
        )
        assertNull(
            "未激活 → 刷新失败",
            runBlocking { XiaozhiCredentialRefresher(store, { "xiaozhi" }, needBind::invoke).refresh(mac) },
        )

        val noCreds = RecordingQuery(activated(url, null))
        assertNull(
            "已激活但没 websocket.token → 刷新失败",
            runBlocking { XiaozhiCredentialRefresher(store, { "xiaozhi" }, noCreds::invoke).refresh(mac) },
        )

        val failed = RecordingQuery(null)
        assertNull(
            "查询失败 → 刷新失败",
            runBlocking { XiaozhiCredentialRefresher(store, { "xiaozhi" }, failed::invoke).refresh(mac) },
        )

        assertEquals("失败一律不覆盖已有凭据", XiaozhiCredential(url, "老token"), store.get(mac))

        val blank = RecordingQuery(activated(url, token))
        assertNull(
            "没有设备地址 → 不查询",
            runBlocking { XiaozhiCredentialRefresher(store, { "xiaozhi" }, blank::invoke).refresh("  ") },
        )
        assertEquals("空设备地址压根不发请求", 0, blank.calls)
    }
}
