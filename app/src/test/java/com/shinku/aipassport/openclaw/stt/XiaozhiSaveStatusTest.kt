package com.shinku.aipassport.openclaw.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「保存网关设置」按钮 + 它下方那行绑定流程状态的**纯逻辑**。
 *
 * 真机问题(本测试钉住的回归点):点保存后按钮一直停在「查询小智云端…」且点不动 ——
 * 「查云端」没有硬超时,复位按钮的代码又只写在正常路径的最后一行。这里把两条不变量钉住:
 *  1. **任何终局**(已激活 / 绑定成功 / 失败 / 查询超时)都必须回到「保存网关设置 + 可点」;
 *     只有两个进行中阶段才置灰,且它们各有明确出口(超时/失败/成功都会回到可点);
 *  2. 那行只在**小智 AI 模式**且**有流程状态**时显示:空闲与非小智(不查云端、不绑定)整行隐藏。
 *
 * UI 侧(真实按钮/真实网络)无法在 JVM 里跑,由真机手测覆盖(见交付报告)。
 */
class XiaozhiSaveStatusTest {

    private val xiaozhi = XiaozhiIdentity.GATEWAY_XIAOZHI
    private val mac = "4C:11:AE:30:B9:7A"

    /** 终局与期望的（阶段、按钮是否可点）—— 三处测试共用同一张表。 */
    private val terminalCases = listOf(
        XiaozhiSaveStatus.Outcome.Saved(mac) to XiaozhiSaveStatus.Stage.Activated,
        XiaozhiSaveStatus.Outcome.Bound(mac) to XiaozhiSaveStatus.Stage.Bound,
        XiaozhiSaveStatus.Outcome.Failed("网络不可用") to XiaozhiSaveStatus.Stage.Failed,
        XiaozhiSaveStatus.Outcome.QueryTimeout to XiaozhiSaveStatus.Stage.QueryTimeout,
    )

    // ---- 不变量 1:任何终局都回到「保存网关设置 + 可点」 ----

    @Test
    fun `任何终局保存按钮都必须回到可点且文案为空闲态`() {
        for ((outcome, stage) in terminalCases) {
            assertEquals("终局 $outcome 的阶段不对", stage, XiaozhiSaveStatus.stageOf(outcome))
            val button = XiaozhiSaveStatus.button(stage)
            assertEquals(
                "终局 $outcome 之后按钮必须回到空闲文案(否则用户不知道还能不能再点)",
                XiaozhiSaveStatus.IDLE_BUTTON_TEXT,
                button.text,
            )
            assertTrue("终局 $outcome 之后按钮必须可点(卡死的直接原因就是这里没回去)", button.enabled)
        }
    }

    @Test
    fun `只有进行中的两个阶段置灰_且不存在亮着却写着进行中文案的错配`() {
        val busy = XiaozhiSaveStatus.Stage.entries.filter { !XiaozhiSaveStatus.button(it).enabled }
        assertEquals(
            "置灰的只能是「正在查云端 / 等待绑定」这两个防重复点击的阶段",
            setOf(XiaozhiSaveStatus.Stage.QueryingCloud, XiaozhiSaveStatus.Stage.WaitingBind),
            busy.toSet(),
        )
        for (stage in XiaozhiSaveStatus.Stage.entries) {
            val button = XiaozhiSaveStatus.button(stage)
            assertEquals(
                "isBusy 必须与按钮置灰同源(切类型时靠它决定能不能清状态行)",
                !button.enabled,
                XiaozhiSaveStatus.isBusy(stage),
            )
            if (button.enabled) {
                assertEquals(
                    "可点状态的文案必须就是空闲文案(可点却写着「正在查询…」= 界面骗人)",
                    XiaozhiSaveStatus.IDLE_BUTTON_TEXT,
                    button.text,
                )
            } else {
                assertTrue(
                    "置灰时必须说明卡在哪一步",
                    button.text.isNotBlank() && button.text != XiaozhiSaveStatus.IDLE_BUTTON_TEXT,
                )
            }
        }
    }

    // ---- 不变量 2:可见性 ----

    @Test
    fun `非小智网关整行隐藏_不查云端也不显示这一行`() {
        val others = listOf("openclaw", "hermes", "openai", "echo", "", null, "XIAOZHI")
        for (type in others) {
            for (stage in XiaozhiSaveStatus.Stage.entries) {
                val line = XiaozhiSaveStatus.line(type, stage, mac = mac, code = "123456", reason = "网络不可用")
                assertFalse(
                    "类型 $type 不该显示绑定流程状态行(小智绑定闸门只对小智 AI 生效)",
                    line.visible,
                )
                assertTrue("隐藏时不该留文案", line.text.isEmpty())
            }
        }
    }

    @Test
    fun `小智AI空闲时不占位_有流程状态才显示`() {
        assertTrue(XiaozhiSaveStatus.isXiaozhiType(xiaozhi))
        assertFalse("空闲(还没点过保存)时整行隐藏", XiaozhiSaveStatus.line(xiaozhi, XiaozhiSaveStatus.Stage.Idle).visible)
        for ((outcome, stage) in terminalCases) {
            assertTrue(
                "小智 AI 的终局 $outcome 必须留下结论",
                XiaozhiSaveStatus.line(xiaozhi, stage, mac = mac, reason = "网络不可用").visible,
            )
        }
        assertTrue(XiaozhiSaveStatus.line(xiaozhi, XiaozhiSaveStatus.Stage.QueryingCloud).visible)
    }

    // ---- 文案表 ----

    @Test
    fun `正在查询云端与需要绑定的文案`() {
        assertEquals(
            "正在查询小智云端…",
            XiaozhiSaveStatus.line(xiaozhi, XiaozhiSaveStatus.Stage.QueryingCloud).text,
        )
        val waiting = XiaozhiSaveStatus.line(
            xiaozhi, XiaozhiSaveStatus.Stage.WaitingBind, mac = mac, code = "123456",
        ).text
        assertTrue("要带绑定码", waiting.contains("123456"))
        assertTrue("要说清去哪里绑定", waiting.contains("xiaozhi.me"))
        assertTrue("要说明正在等授权(弹窗关掉后靠这行继续)", waiting.contains("等待授权"))
    }

    @Test
    fun `已激活与绑定成功的文案带设备MAC且说明已保存`() {
        val activated = XiaozhiSaveStatus.line(
            xiaozhi, XiaozhiSaveStatus.Stage.Activated, mac = mac,
        ).text
        assertEquals("已激活：$mac（已保存）", activated)

        val bound = XiaozhiSaveStatus.line(xiaozhi, XiaozhiSaveStatus.Stage.Bound, mac = mac).text
        assertTrue(bound.contains(mac))
        assertTrue(bound.contains("已保存"))
    }

    @Test
    fun `绑定失败的文案带可读原因且明确未保存`() {
        val text = XiaozhiSaveStatus.line(
            xiaozhi, XiaozhiSaveStatus.Stage.Failed, reason = "小智绑定超时:请确认已在 xiaozhi.me 输入绑定码 123456 后重试。",
        ).text
        assertTrue(text.contains("绑定失败"))
        assertTrue("必须说清没保存", text.contains("未保存"))
        assertTrue("必须说清原配置继续生效", text.contains("原配置继续生效"))
        assertTrue(text.contains("xiaozhi.me"))
    }

    @Test
    fun `失败原因多行只取首行_原因缺失也不出现空结论`() {
        val multi = XiaozhiSaveStatus.line(
            xiaozhi, XiaozhiSaveStatus.Stage.Failed, reason = "第一行原因\n第二行细节\n第三行",
        ).text
        assertTrue(multi.contains("第一行原因"))
        assertFalse("单行提示不该把后面的行也塞进来", multi.contains("第二行细节"))

        for (blank in listOf(null, "", "   ", "\n\n")) {
            val text = XiaozhiSaveStatus.line(xiaozhi, XiaozhiSaveStatus.Stage.Failed, reason = blank).text
            assertTrue("原因缺失时要有兜底文案", text.contains(XiaozhiSaveStatus.FALLBACK_FAILED_REASON))
        }
    }

    @Test
    fun `查询超时的文案可读_说明未保存且可重试`() {
        val text = XiaozhiSaveStatus.line(xiaozhi, XiaozhiSaveStatus.Stage.QueryTimeout).text
        assertEquals(XiaozhiSaveStatus.QUERY_TIMEOUT_REASON, text)
        assertTrue(text.contains("超时"))
        assertTrue(text.contains("重试"))
        assertTrue("超时不是静默:必须说清没保存", text.contains("未保存"))
    }

    @Test
    fun `查询超时常量与硬超时在十秒量级`() {
        assertTrue(
            "硬超时应在 5~15s 之间(太小会误杀慢网络,太大就是用户感觉到的卡死)",
            XiaozhiActivator.CLOUD_QUERY_TIMEOUT_MS in 5_000..15_000,
        )
    }
}
