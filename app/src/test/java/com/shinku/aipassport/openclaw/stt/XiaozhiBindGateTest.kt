package com.shinku.aipassport.openclaw.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「保存网关设置」里小智绑定闸门的决策。
 *
 * 小智云按 Device-Id(= 已连接设备的蓝牙 MAC)登记/绑定设备,所以保存「小智 AI」时必须先过这道闸门:
 *  - 没连接设备 → 拦截保存(不回退手机侧标识);
 *  - 已绑定当前设备 → 直接放行(不重复绑定);
 *  - 未绑定/换了设备 → 先绑定,且**绑定失败/超时绝不落盘**。
 *
 * 同时钉住最容易踩的回归点:**这道闸门只对网关类型「小智 AI」生效** —— 保存其它网关时既不能触发
 * 小智绑定,也不能因为「设备未连接」被拦下(它们只把小智当识别引擎,见 [XiaozhiIdentity])。
 */
class XiaozhiBindGateTest {

    private val xiaozhi = XiaozhiIdentity.GATEWAY_XIAOZHI
    private val macA = "4C:11:AE:30:B9:7A"
    private val macB = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `没连接设备时拦截保存`() {
        assertEquals(
            XiaozhiBindGate.BeforeSave.NoDevice,
            XiaozhiBindGate.beforeSave(xiaozhi, null, macA),
        )
        assertEquals(
            XiaozhiBindGate.BeforeSave.NoDevice,
            XiaozhiBindGate.beforeSave(xiaozhi, "", macA),
        )
        assertEquals(
            XiaozhiBindGate.BeforeSave.NoDevice,
            XiaozhiBindGate.beforeSave(xiaozhi, "   ", macA),
        )
        // 即使之前绑定过别的设备,现在没设备也不放行
        assertEquals(
            XiaozhiBindGate.BeforeSave.NoDevice,
            XiaozhiBindGate.beforeSave(xiaozhi, null, null),
        )
    }

    @Test
    fun `已绑定当前设备时直接放行`() {
        assertEquals(
            XiaozhiBindGate.BeforeSave.AlreadyBound(macA),
            XiaozhiBindGate.beforeSave(xiaozhi, macA, macA),
        )
    }

    @Test
    fun `绑定记录大小写不同也算同一台设备`() {
        assertEquals(
            XiaozhiBindGate.BeforeSave.AlreadyBound(macA),
            XiaozhiBindGate.beforeSave(xiaozhi, "4c:11:ae:30:b9:7a", macA),
        )
        assertEquals(
            XiaozhiBindGate.BeforeSave.AlreadyBound(macA),
            XiaozhiBindGate.beforeSave(xiaozhi, macA, " 4c:11:ae:30:b9:7a "),
        )
    }

    @Test
    fun `从未绑定时要求先绑定`() {
        val decision = XiaozhiBindGate.beforeSave(xiaozhi, macA, null)
        assertTrue(decision is XiaozhiBindGate.BeforeSave.NeedBind)
        assertEquals(macA, (decision as XiaozhiBindGate.BeforeSave.NeedBind).mac)
    }

    @Test
    fun `换了设备 MAC 时要重新绑定`() {
        val decision = XiaozhiBindGate.beforeSave(xiaozhi, macB, macA)
        assertTrue(decision is XiaozhiBindGate.BeforeSave.NeedBind)
        assertEquals(macB, (decision as XiaozhiBindGate.BeforeSave.NeedBind).mac)
    }

    @Test
    fun `非小智网关一律放行且不触发绑定`() {
        // 无论设备是否在线、是否绑定过任何设备,非小智类型都直接落到通用校验-落盘闸门
        for (type in listOf("openclaw", "hermes", "openai", "echo", "")) {
            assertEquals(
                "类型 $type 不应被小智绑定闸门拦住",
                XiaozhiBindGate.BeforeSave.NotXiaozhi,
                XiaozhiBindGate.beforeSave(type, macA, null),
            )
            assertEquals(
                "类型 $type 且没连设备时也不能被拦(这是最容易踩的回归点)",
                XiaozhiBindGate.BeforeSave.NotXiaozhi,
                XiaozhiBindGate.beforeSave(type, null, null),
            )
            assertEquals(
                XiaozhiBindGate.BeforeSave.NotXiaozhi,
                XiaozhiBindGate.beforeSave(type, macA, macA),
            )
        }
    }

    @Test
    fun `绑定成功才落盘`() {
        assertEquals(XiaozhiBindGate.AfterBind.Persist, XiaozhiBindGate.afterBind(true))
    }

    @Test
    fun `绑定失败或超时不落盘`() {
        assertEquals(XiaozhiBindGate.AfterBind.KeepOldConfig, XiaozhiBindGate.afterBind(false))
    }
}
