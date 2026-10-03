package com.shinku.aipassport.openclaw.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单测用的内存键值实现(与 [SharedPrefsXiaozhiStore] 同一契约,不依赖 Android)。 */
class FakeXiaozhiStore : XiaozhiKeyValueStore {
    private val map = LinkedHashMap<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    /** 供断言:本机实际存了哪些键(排查用)。 */
    fun keys(): Set<String> = map.keys.toSet()
}

/**
 * 绑定得到的识别凭据:存/取 + 「没有凭据时不建链、给可读原因」(即「无语音」那条路的修法)。
 *
 * 三条不变量:
 *  1. 凭据按设备 MAC 存取,换设备拿不到旧凭据;
 *  2. `Authorization` 与官方固件一致(`Bearer <token>`;已带 scheme 的原样);
 *  3. 非小智网关仍走匿名地址 + 占位 token(**逐字不变**),小智 AI 没有凭据时**不**回退占位 token。
 */
class XiaozhiCredentialTest {

    private val mac = "4C:11:AE:30:B9:7A"
    private val url = "wss://api.tenclass.net/xiaozhi/v1/"
    private val token = "eyJhbGciOiJIUzI1NiJ9.payload.sig"

    private fun store(kv: FakeXiaozhiStore = FakeXiaozhiStore()) =
        XiaozhiCredentialStore(kv) to kv

    @Test
    fun `凭据按设备 MAC 存取_换设备取不到`() {
        val (store, kv) = store()
        assertNull("没绑过 → 没有凭据", store.get(mac))

        store.save(mac, XiaozhiCredential(url, token))
        assertEquals(XiaozhiCredential(url, token), store.get(mac))
        assertEquals(
            "MAC 大小写不敏感(归一化前后都能取到)",
            XiaozhiCredential(url, token),
            store.get(mac.lowercase()),
        )
        assertNull("别的设备拿到 null(不拿另一台设备的 token 去握手)", store.get("AA:BB:CC:DD:EE:FF"))
        assertEquals(
            "键名清晰:凭据带自己的设备 MAC + 落盘时刻(兜底刷新的依据)",
            setOf(
                XiaozhiCredentialStore.KEY_MAC,
                XiaozhiCredentialStore.KEY_URL,
                XiaozhiCredentialStore.KEY_TOKEN,
                XiaozhiCredentialStore.KEY_SAVED_AT,
            ),
            kv.keys(),
        )

        store.clear()
        assertNull("清掉后取不到", store.get(mac))
        assertTrue("已清空", kv.keys().isEmpty())
    }

    @Test
    fun `不完整的凭据不落盘_也不覆盖已有的完整凭据`() {
        val (store, _) = store()
        store.save(mac, XiaozhiCredential(url, token))

        store.save(mac, XiaozhiCredential(url, "  "))
        assertEquals("token 为空不覆盖", XiaozhiCredential(url, token), store.get(mac))

        store.save(mac, XiaozhiCredential("", token))
        assertEquals("url 为空不覆盖", XiaozhiCredential(url, token), store.get(mac))

        val (empty, _) = store()
        empty.save(mac, XiaozhiCredential(url, ""))
        assertNull("半份凭据存不进去(状态就是「还没取得凭据」)", empty.get(mac))
    }

    @Test
    fun `凭据落盘时刻可读_没存过或时间戳缺失时为 null`() {
        val (store, _) = store()
        assertNull("没存过凭据 → 没有落盘时刻", store.savedAt(mac))

        store.save(mac, XiaozhiCredential(url, token), nowMs = 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, store.savedAt(mac))
        assertNull("别的设备拿不到", store.savedAt("AA:BB:CC:DD:EE:FF"))

        store.save(mac, XiaozhiCredential(url, "新token"), nowMs = 1_700_000_100_000L)
        assertEquals("刷新落盘会推进时间戳", 1_700_000_100_000L, store.savedAt(mac))

        // 旧版本存的凭据(没有时间戳键):读作 null(调用方按「偏旧」处理),不当作 0 而爆炸
        val kv = FakeXiaozhiStore()
        kv.put(XiaozhiCredentialStore.KEY_MAC, mac)
        kv.put(XiaozhiCredentialStore.KEY_URL, url)
        kv.put(XiaozhiCredentialStore.KEY_TOKEN, token)
        assertNull(XiaozhiCredentialStore(kv).savedAt(mac))

        store.clear()
        assertNull("清掉后连时间戳一起没了", store.savedAt(mac))
    }

    @Test
    fun `Authorization 与官方固件一致`() {
        assertEquals("Bearer $token", XiaozhiCredential(url, token).authorizationValue())
        assertEquals(
            "已带 scheme 的原样用",
            "Bearer abc.def",
            XiaozhiCredential(url, "Bearer abc.def").authorizationValue(),
        )
    }

    @Test
    fun `OTA 响应里 url 与 token 缺一不可`() {
        assertEquals(XiaozhiCredential(url, token), XiaozhiCredential.fromOta(url, token))
        assertEquals("去空白", XiaozhiCredential(url, token), XiaozhiCredential.fromOta(" $url ", " $token "))
        assertNull(XiaozhiCredential.fromOta(url, null))
        assertNull(XiaozhiCredential.fromOta(url, ""))
        assertNull(XiaozhiCredential.fromOta(null, token))
        assertNull(XiaozhiCredential.fromOta("", token))
    }

    @Test
    fun `小智 AI 没有凭据时不建链_给可读原因而不是硬撞占位 token`() {
        val auth = XiaozhiCredentialGate.linkAuth(
            gatewayType = XiaozhiIdentity.GATEWAY_XIAOZHI,
            credential = null,
            defaultUrl = url,
            placeholderToken = "test-token",
        )
        assertTrue(auth is XiaozhiLinkAuth.Unavailable)
        val reason = (auth as XiaozhiLinkAuth.Unavailable).reason
        assertEquals(XiaozhiCredentialGate.MISSING_CREDENTIAL_REASON, reason)
        assertTrue("原因里不能说 token", !reason.contains("test-token"))
        assertTrue("要提示走绑定流程", reason.contains("绑定"))
        assertNotEquals("不能用占位 token", "test-token", reason)
    }

    @Test
    fun `小智 AI 有凭据时用凭据的 url 与 Bearer token`() {
        val auth = XiaozhiCredentialGate.linkAuth(
            gatewayType = XiaozhiIdentity.GATEWAY_XIAOZHI,
            credential = XiaozhiCredential(url, token),
            defaultUrl = "wss://fallback/",
            placeholderToken = "test-token",
        )
        assertEquals(XiaozhiLinkAuth.Ok(url, "Bearer $token", refreshable = true), auth)
    }

    @Test
    fun `非小智网关仍然走匿名地址与占位 token`() {
        for (type in listOf("openclaw", "hermes", "openai", "echo", "", null)) {
            val auth = XiaozhiCredentialGate.linkAuth(
                gatewayType = type,
                credential = XiaozhiCredential(url, token),
                defaultUrl = "wss://api.tenclass.net/xiaozhi/v1/",
                placeholderToken = "test-token",
            )
            assertEquals(
                "非小智($type):行为与改动前逐字一致(占位 token 原样,不用绑定凭据)",
                XiaozhiLinkAuth.Ok("wss://api.tenclass.net/xiaozhi/v1/", "test-token"),
                auth,
            )
        }
    }

    @Test
    fun `占位 token 为空时退回同一个共享值`() {
        val auth = XiaozhiCredentialGate.linkAuth(
            gatewayType = null,
            credential = null,
            defaultUrl = url,
            placeholderToken = "",
        )
        assertEquals(
            XiaozhiLinkAuth.Ok(url, XiaozhiSettings.ANONYMOUS_PLACEHOLDER_TOKEN),
            auth,
        )
    }
}
