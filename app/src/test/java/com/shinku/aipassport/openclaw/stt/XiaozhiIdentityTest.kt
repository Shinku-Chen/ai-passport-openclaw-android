package com.shinku.aipassport.openclaw.stt

import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「小智侧标识(Device-Id)按当前网关类型解析」这条规则的纯逻辑覆盖(不依赖 Android/网络)。
 *
 * 规则(作者要求):
 *  - 网关类型 == 小智 AI → 用**已连接设备的真实蓝牙 MAC**(取不到就失败并给可读原因,不回落匿名);
 *  - 网关类型 != 小智 AI → 用全零匿名标识(即使设备已连接),因为那些模式只把小智当识别引擎;
 *  - 同一台设备在类型切换后解析结果随之改变(所以不能在服务启动时把标识定死)。
 *
 * 以及保存侧最容易踩的回归点:小智绑定闸门**只**对「小智 AI」生效。
 */
class XiaozhiIdentityTest {

    private val xiaozhi = "xiaozhi"
    private val mac = "4C:11:AE:30:B9:7A"

    private fun deviceIdOf(type: String?, address: String?): String =
        XiaozhiIdentity.resolve(type, address).deviceId

    @Test
    fun `小智网关用已连接设备的真实 MAC`() {
        val r = XiaozhiIdentity.resolve(xiaozhi, mac)
        assertTrue(r is XiaozhiIdentity.Resolution.DeviceMac)
        assertEquals(mac, r.deviceId)
        assertTrue("有真 MAC 就该能建链", r.canLink)
        assertNull(r.reason)
    }

    @Test
    fun `小智网关归一化地址格式(与 OTA 同一套写法)`() {
        // 冒号大小写 / 无分隔的 12 位 hex / 首尾空白 → 同一个小智 Device-Id
        assertEquals(mac, deviceIdOf(xiaozhi, "4c:11:ae:30:b9:7a"))
        assertEquals(mac, deviceIdOf(xiaozhi, "4C:11:AE:30:B9:7A"))
        assertEquals(mac, deviceIdOf(xiaozhi, "4c11ae30b97a"))
        assertEquals(mac, deviceIdOf(xiaozhi, "  4C:11:AE:30:B9:7A\n"))
        // 且与归一化工具的输出完全一致(只有一处格式实现)
        assertEquals(XiaozhiDeviceId.formatAddress("4c11ae30b97a"), deviceIdOf(xiaozhi, "4c11ae30b97a"))
    }

    @Test
    fun `小智网关取不到设备地址时失败且不回落匿名`() {
        for (bad in listOf(null, "", "   ", "Passport-1234", "4C:11:AE:30:B9", "00:00:00:00:00")) {
            val r = XiaozhiIdentity.resolve(xiaozhi, bad)
            assertTrue("$bad 应判为不可建链", r is XiaozhiIdentity.Resolution.Unavailable)
            assertFalse(r.canLink)
            assertNotEquals("绝不能把匿名标识当作小智模式的 Device-Id", XiaozhiIdentity.ANONYMOUS_DEVICE_ID, r.deviceId)
            assertTrue(r.reason.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `非小智网关一律用全零匿名标识(即使设备已连接)`() {
        for (type in listOf("openclaw", "hermes", "openai", "echo", "custom-openai", "", null)) {
            val r = XiaozhiIdentity.resolve(type, mac)
            assertTrue("$type 应是匿名标识", r is XiaozhiIdentity.Resolution.Anonymous)
            assertEquals(XiaozhiIdentity.ANONYMOUS_DEVICE_ID, r.deviceId)
            assertTrue("识别必须继续可用:匿名标识也能建链", r.canLink)
            assertNull(r.reason)
        }
    }

    @Test
    fun `非小智网关没连设备也能建链`() {
        val r = XiaozhiIdentity.resolve("openclaw", null)
        assertTrue(r.canLink)
        assertEquals("00:00:00:00:00:00", r.deviceId)
    }

    @Test
    fun `类型切换后解析结果随之改变`() {
        // 同一台设备、同一个地址:类型决定标识(所以标识必须在每次建链时解析,不能启动时定死)
        assertEquals(XiaozhiIdentity.ANONYMOUS_DEVICE_ID, deviceIdOf("openclaw", mac))
        assertEquals(mac, deviceIdOf(xiaozhi, mac))
        assertEquals(XiaozhiIdentity.ANONYMOUS_DEVICE_ID, deviceIdOf("hermes", mac))
        // 切到小智但设备没连 → 从「可建链的匿名」变成「失败」,而不是继续用匿名
        assertFalse(XiaozhiIdentity.resolve(xiaozhi, null).canLink)
    }

    @Test
    fun `网关类型字面量与设置里的常量一致`() {
        assertEquals(GatewaySettings.TYPE_XIAOZHI, XiaozhiIdentity.GATEWAY_XIAOZHI)
        for (t in GatewaySettings.ALL_TYPES) {
            val anonymous = XiaozhiIdentity.resolve(t, mac) is XiaozhiIdentity.Resolution.Anonymous
            assertEquals(
                "只有小智 AI 该用真 MAC,其余类型都该走匿名标识:$t",
                t != GatewaySettings.TYPE_XIAOZHI,
                anonymous,
            )
        }
    }

    @Test
    fun `绑定闸门只对当前小智 AI 类型生效`() {
        for (type in listOf("openclaw", "hermes", "openai", "echo")) {
            // 设备已连接 → 非小智类型不得去查云端/触发绑定
            assertEquals(
                XiaozhiBindGate.BeforeSave.NotXiaozhi,
                XiaozhiBindGate.beforeSave(type, mac),
            )
            // 设备没连 → 非小智类型也**不得**被拦(保存必须照常走通用校验-落盘)
            assertEquals(
                XiaozhiBindGate.BeforeSave.NotXiaozhi,
                XiaozhiBindGate.beforeSave(type, null),
            )
        }
        // 小智 AI:没设备 → 拦;有设备 → **一律**先查云端(本地 bound_mac 不参与判断)
        assertEquals(XiaozhiBindGate.BeforeSave.NoDevice, XiaozhiBindGate.beforeSave(xiaozhi, null))
        assertEquals(XiaozhiBindGate.BeforeSave.QueryCloud(mac), XiaozhiBindGate.beforeSave(xiaozhi, mac))
    }

    @Test
    fun `非小智网关不做设备绑定的说明可读`() {
        for (type in listOf("openclaw", "hermes", "openai", "echo")) {
            val reason = XiaozhiIdentity.bindingNotApplicableReason(type)
            assertTrue(reason.contains(type))
            assertTrue(reason.contains(XiaozhiIdentity.ANONYMOUS_DEVICE_ID))
        }
    }
}
