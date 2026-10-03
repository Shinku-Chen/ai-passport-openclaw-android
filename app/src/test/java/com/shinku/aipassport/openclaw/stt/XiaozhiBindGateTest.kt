package com.shinku.aipassport.openclaw.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「保存小智 AI」的绑定闸门决策。
 *
 * 小智云按 Device-Id(= 已连接设备的蓝牙 MAC)登记/绑定设备,所以保存动作必须先过这道闸门:
 *  - 没连接设备 → 拦截保存(不回退手机侧标识);
 *  - 已绑定当前设备 → 直接放行(不重复绑定);
 *  - 未绑定/换了设备 → 先绑定,且**绑定失败/超时绝不落盘**。
 */
class XiaozhiBindGateTest {

    private val macA = "4C:11:AE:30:B9:7A"
    private val macB = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `没连接设备时拦截保存`() {
        assertEquals(
            XiaozhiBindGate.BeforeSave.NoDevice,
            XiaozhiBindGate.beforeSave(null, macA),
        )
        assertEquals(
            XiaozhiBindGate.BeforeSave.NoDevice,
            XiaozhiBindGate.beforeSave("", macA),
        )
        assertEquals(
            XiaozhiBindGate.BeforeSave.NoDevice,
            XiaozhiBindGate.beforeSave("   ", macA),
        )
        // 即使之前绑定过别的设备,现在没设备也不放行
        assertEquals(
            XiaozhiBindGate.BeforeSave.NoDevice,
            XiaozhiBindGate.beforeSave(null, null),
        )
    }

    @Test
    fun `已绑定当前设备时直接放行`() {
        assertEquals(
            XiaozhiBindGate.BeforeSave.AlreadyBound,
            XiaozhiBindGate.beforeSave(macA, macA),
        )
    }

    @Test
    fun `绑定记录大小写不同也算同一台设备`() {
        assertEquals(
            XiaozhiBindGate.BeforeSave.AlreadyBound,
            XiaozhiBindGate.beforeSave("4c:11:ae:30:b9:7a", macA),
        )
        assertEquals(
            XiaozhiBindGate.BeforeSave.AlreadyBound,
            XiaozhiBindGate.beforeSave(macA, " 4c:11:ae:30:b9:7a "),
        )
    }

    @Test
    fun `从未绑定时要求先绑定`() {
        val decision = XiaozhiBindGate.beforeSave(macA, null)
        assertTrue(decision is XiaozhiBindGate.BeforeSave.NeedBind)
        assertEquals(macA, (decision as XiaozhiBindGate.BeforeSave.NeedBind).mac)
    }

    @Test
    fun `换了设备 MAC 时要重新绑定`() {
        val decision = XiaozhiBindGate.beforeSave(macB, macA)
        assertTrue(decision is XiaozhiBindGate.BeforeSave.NeedBind)
        assertEquals(macB, (decision as XiaozhiBindGate.BeforeSave.NeedBind).mac)
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
