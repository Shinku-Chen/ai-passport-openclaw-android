package com.shinku.aipassport.openclaw.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 小智 Device-Id 归一化:小智云按 Device-Id 登记/绑定设备,OTA 与识别 WS 必须用**同一个值、同一种格式**,
 * 所以这里把「已连接设备的蓝牙 MAC → 小智 mac 字段」的规则钉死。
 *
 * 覆盖点:
 *  - 既有的 BLE 地址格式(大写冒号分隔)原样通过;
 *  - 小写/无分隔的写法归一化成同一种格式(不产生第二种写法);
 *  - 空、非 MAC、长度不对的一律返回 null —— 调用方据此**停手**,而不是拿手机侧标识顶上。
 */
class XiaozhiDeviceIdTest {

    @Test
    fun `标准 BLE 地址原样通过`() {
        assertEquals("4c:11:ae:30:b9:7a", XiaozhiDeviceId.formatAddress("4c:11:ae:30:b9:7a"))
    }

    @Test
    fun `小写地址归一化为小写`() {
        assertEquals("4c:11:ae:30:b9:7a", XiaozhiDeviceId.formatAddress("4c:11:ae:30:b9:7a"))
    }

    @Test
    fun `无分隔的 12 位 hex 补上冒号`() {
        assertEquals("4c:11:ae:30:b9:7a", XiaozhiDeviceId.formatAddress("4c11ae30b97a"))
        assertEquals("4c:11:ae:30:b9:7a", XiaozhiDeviceId.formatAddress("4C11AE30B97A"))
    }

    @Test
    fun `首尾空白被忽略`() {
        assertEquals("4c:11:ae:30:b9:7a", XiaozhiDeviceId.formatAddress("  4c:11:ae:30:b9:7a\n"))
    }

    @Test
    fun `同一地址的不同写法得到同一个 Device-Id`() {
        val colon = XiaozhiDeviceId.formatAddress("4c:11:ae:30:b9:7a")
        val bare = XiaozhiDeviceId.formatAddress("4c11ae30b97a")
        assertEquals(colon, bare)
    }

    @Test
    fun `取不到设备地址时返回 null 而不是手机侧标识`() {
        assertNull(XiaozhiDeviceId.formatAddress(null))
        assertNull(XiaozhiDeviceId.formatAddress(""))
        assertNull(XiaozhiDeviceId.formatAddress("   "))
        // 全零匿名通道不再被接受:它不是「已连接设备」的地址,用它就是猜
        assertNull(XiaozhiDeviceId.formatAddress("00:00:00:00:00"))
    }

    @Test
    fun `非 MAC 文本与位数不对的地址一律非法`() {
        assertNull(XiaozhiDeviceId.formatAddress("Passport-1234"))
        assertNull(XiaozhiDeviceId.formatAddress("4C:11:AE:30:B9"))
        assertNull(XiaozhiDeviceId.formatAddress("4c:11:ae:30:b9:7a:00"))
        assertNull(XiaozhiDeviceId.formatAddress("4C-11-AE-30-B9-7A"))
        assertNull(XiaozhiDeviceId.formatAddress("ZZ:11:AE:30:B9:7A"))
        assertNull(XiaozhiDeviceId.formatAddress("device-1a2b3c4d5e6f"))
    }

    @Test
    fun uppercase_and_mixed_case_inputs_normalize_to_lowercase() {
        // 云端很可能按字符串把设备登记在册 → 我们统一发小写(与官方固件 SystemInfo::GetMacAddress 一致)
        assertEquals("4c:11:ae:30:b9:7a", XiaozhiDeviceId.formatAddress("4C:11:AE:30:B9:7A"))
        assertEquals("4c:11:ae:30:b9:7a", XiaozhiDeviceId.formatAddress("4c:11:AE:30:b9:7A"))
        assertEquals("4c:11:ae:30:b9:7a", XiaozhiDeviceId.formatAddress("4C11AE30B97A"))
    }
}
