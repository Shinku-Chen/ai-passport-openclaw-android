package com.shinku.aipassport.openclaw.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「保存网关设置 / 手动激活」里小智闸门的决策。
 *
 * 真机问题(本测试钉住的回归点):旧实现把本地 `bound_mac` 当判据 —— 只要它等于当前设备就直接放行、
 * 连云端都不查,于是用户点了保存既没绑定码也没任何提示。现在判据**永远是云端激活状态**:
 *  - 云端已激活 → 允许保存,但**必须提示**(不静默);
 *  - 云端未激活 → 发 6 位绑定码并轮询授权,成功才落盘;
 *  - 查询失败/超时/无网 → **不落盘** + 可读原因,下次保存重试;
 *  - 设备未连 → 拦截(不回退匿名标识);
 *  - 非小智类型 → 一律放行,既不查云端、不绑定,也不因「设备未连接」被拦。
 *
 * 覆盖 (云端 未激活/已激活/查询失败) × (本地记录 无/同设备/别的设备) × (网关 小智/非小智) 全组合。
 */
class XiaozhiBindGateTest {

    private val xiaozhi = XiaozhiIdentity.GATEWAY_XIAOZHI
    private val macA = "4c:11:ae:30:b9:7a"
    private val macB = "AA:BB:CC:DD:EE:FF"

    /** 本地绑定记录的三种形态:**不参与分流**的同一个输入维度。 */
    private val boundVariants = listOf(null, macA, macB, " 4c:11:ae:30:b9:7a ")

    private val allCloudStates = XiaozhiBindGate.CloudState.entries.toList()

    // ---- 决策表 ----

    @Test
    fun `决策表_云端未激活_发码并等授权`() {
        for (bound in boundVariants) {
            assertEquals(
                "本地记录 $bound 不该改变未激活的走向",
                XiaozhiBindGate.Decision.NeedBind(macA),
                XiaozhiBindGate.decide(xiaozhi, macA, XiaozhiBindGate.CloudState.NeedsBinding, bound),
            )
        }
    }

    @Test
    fun `决策表_云端已激活_允许保存但必须提示`() {
        for (bound in boundVariants) {
            val decision = XiaozhiBindGate.decide(
                xiaozhi, macA, XiaozhiBindGate.CloudState.Activated, bound,
            )
            assertTrue("本地记录 $bound 时应允许保存(给出提示)", decision is XiaozhiBindGate.Decision.Activated)
            decision as XiaozhiBindGate.Decision.Activated
            assertEquals(macA, decision.mac)
            assertTrue("提示必须说清结论", decision.notice.contains(macA))
            assertTrue(decision.notice.contains("激活"))
            assertTrue("提示必须给出换账号的下一步", decision.notice.contains("xiaozhi.me"))
        }
    }

    @Test
    fun `决策表_查询失败_不落盘且给可读原因`() {
        for (bound in boundVariants) {
            val decision = XiaozhiBindGate.decide(
                xiaozhi, macA, XiaozhiBindGate.CloudState.QueryFailed, bound,
            )
            assertTrue(decision is XiaozhiBindGate.Decision.QueryFailed)
            decision as XiaozhiBindGate.Decision.QueryFailed
            assertTrue("原因必须可读且非空", decision.reason.isNotBlank())
            assertTrue("原因必须带上是哪台设备", decision.reason.contains(macA))
        }
    }

    @Test
    fun `决策表_没查到状态也算查询失败_绝不放行`() {
        // 调用方漏查/查询被取消:当成失败处理,绝不静默落盘
        for (bound in boundVariants) {
            assertTrue(
                XiaozhiBindGate.decide(xiaozhi, macA, null, bound)
                    is XiaozhiBindGate.Decision.QueryFailed
            )
        }
    }

    @Test
    fun `决策表_非小智只要放行_与云端与本地记录完全无关`() {
        for (type in listOf("openclaw", "hermes", "openai", "echo", "")) {
            for (cloud in allCloudStates + listOf(null)) {
                for (bound in boundVariants) {
                    assertEquals(
                        "类型 $type / 云端 $cloud / 本地 $bound 不得触发任何小智处理",
                        XiaozhiBindGate.Decision.NotXiaozhi,
                        XiaozhiBindGate.decide(type, macA, cloud, bound),
                    )
                    // 设备没连也必须放行(最容易踩的回归点)
                    assertEquals(
                        XiaozhiBindGate.Decision.NotXiaozhi,
                        XiaozhiBindGate.decide(type, null, cloud, bound),
                    )
                }
            }
        }
    }

    @Test
    fun `本地绑定记录不再决定闸门走向`() {
        for (cloud in allCloudStates) {
            val variants = boundVariants.map { XiaozhiBindGate.decide(xiaozhi, macA, cloud, it).javaClass }
            assertEquals("本地记录不得改变 $cloud 的决策类型", 1, variants.toSet().size)
        }
    }

    @Test
    fun `本地记录过时只在提示里说明_不拦截`() {
        val stale = XiaozhiBindGate.decide(
            xiaozhi, macA, XiaozhiBindGate.CloudState.Activated, macB,
        ) as XiaozhiBindGate.Decision.Activated
        assertTrue(stale.notice.contains(macB))
        val same = XiaozhiBindGate.decide(
            xiaozhi, macA, XiaozhiBindGate.CloudState.Activated, macA,
        ) as XiaozhiBindGate.Decision.Activated
        assertFalse("记录一致时不必提校正", same.notice.contains("校正"))
    }

    @Test
    fun `查询失败的具体原因优先于通用文案`() {
        val decision = XiaozhiBindGate.decide(
            xiaozhi, macA, XiaozhiBindGate.CloudState.QueryFailed, null, detail = "OTA 请求失败",
        ) as XiaozhiBindGate.Decision.QueryFailed
        assertEquals("OTA 请求失败", decision.reason)
    }

    // ---- 查询前(beforeSave):只决定「要不要查云端 / 拦不拦」 ----

    @Test
    fun `小智保存一律先查云端_不看本地记录`() {
        assertEquals(
            XiaozhiBindGate.BeforeSave.QueryCloud(macA),
            XiaozhiBindGate.beforeSave(xiaozhi, macA),
        )
        // 大小写/空白归一化
        assertEquals(
            XiaozhiBindGate.BeforeSave.QueryCloud(macA),
            XiaozhiBindGate.beforeSave(xiaozhi, " 4c:11:ae:30:b9:7a "),
        )
    }

    @Test
    fun `没连接设备时拦截且不发查询`() {
        assertEquals(XiaozhiBindGate.BeforeSave.NoDevice, XiaozhiBindGate.beforeSave(xiaozhi, null))
        assertEquals(XiaozhiBindGate.BeforeSave.NoDevice, XiaozhiBindGate.beforeSave(xiaozhi, ""))
        assertEquals(XiaozhiBindGate.BeforeSave.NoDevice, XiaozhiBindGate.beforeSave(xiaozhi, "   "))
        // 非完整 MAC 也不能被当成设备
        assertEquals(XiaozhiBindGate.BeforeSave.NoDevice, XiaozhiBindGate.beforeSave(xiaozhi, "00:11:22"))
        assertEquals(
            XiaozhiBindGate.Decision.NoDevice,
            XiaozhiBindGate.decide(xiaozhi, null, XiaozhiBindGate.CloudState.Activated, macA),
        )
    }

    @Test
    fun `非小智网关不查云端_与设备在线与否无关`() {
        for (type in listOf("openclaw", "hermes", "openai", "echo", "")) {
            assertEquals(
                "类型 $type 不得被小智闸门拦住",
                XiaozhiBindGate.BeforeSave.NotXiaozhi,
                XiaozhiBindGate.beforeSave(type, macA),
            )
            assertEquals(
                "类型 $type 且没连设备时也不能被拦(这是最容易踩的回归点)",
                XiaozhiBindGate.BeforeSave.NotXiaozhi,
                XiaozhiBindGate.beforeSave(type, null),
            )
        }
    }

    // ---- 绑定之后的落盘条件 ----

    @Test
    fun `绑定成功才落盘`() {
        assertEquals(XiaozhiBindGate.AfterBind.Persist, XiaozhiBindGate.afterBind(true))
    }

    @Test
    fun `绑定失败或超时不落盘`() {
        assertEquals(XiaozhiBindGate.AfterBind.KeepOldConfig, XiaozhiBindGate.afterBind(false))
    }
}
