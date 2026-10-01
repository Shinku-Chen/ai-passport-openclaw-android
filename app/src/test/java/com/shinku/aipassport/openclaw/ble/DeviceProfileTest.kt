package com.shinku.aipassport.openclaw.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeviceProfile] / [DeviceProfiles] 的 JVM 单测（纯逻辑，不依赖 Android 与真机）。
 *
 * 核心回归：**重构不得改变行为**。AI Passport 档案里的 UUID、广播名前缀、请求 MTU 必须与
 * 重构前 [BleNus] 里的字面值逐一相同，名字匹配规则（大小写敏感的前缀匹配）也必须保持，
 * 否则真机上会从"能扫到并连上"退化成"扫不到"或"连了别的设备"。
 */
class DeviceProfileTest {

    @Test
    fun passport_profile_keeps_the_original_literals() {
        val p = DeviceProfiles.PASSPORT
        assertEquals("passport", p.id)
        assertEquals(BleNus.SERVICE_UUID, p.serviceUuid)
        assertEquals(BleNus.RX_UUID, p.rxUuid)
        assertEquals(BleNus.TX_UUID, p.txUuid)
        assertEquals(BleNus.CCCD_UUID, p.cccdUuid)
        assertEquals(BleNus.REQUEST_MTU, p.requestMtu)
        assertEquals(listOf(BleNus.DEVICE_NAME_PREFIX), p.namePrefixes)
        // 默认档案就是唯一档案，扫描/连接流程取的都是它。
        assertSame(DeviceProfiles.PASSPORT, DeviceProfiles.default)
        assertEquals(listOf(DeviceProfiles.PASSPORT), DeviceProfiles.all)
    }

    @Test
    fun passport_profile_describes_the_firmware_contract() {
        val p = DeviceProfiles.PASSPORT
        assertEquals("AI Passport", p.displayName)
        assertEquals(PairingMode.PASSPORT_SCREEN_CODE, p.pairing)
        assertEquals(Framing.PASSPORT_A5, p.framing)
        assertEquals(AudioSpec.Codec.OPUS, p.audio.codec)
        assertEquals(16_000, p.audio.sampleRateHz)
        assertEquals(60, p.audio.frameMs)
        assertTrue(p.capabilities.hasDisplay)
        assertEquals(3, p.capabilities.buttonCount)
        assertEquals(2047, p.capabilities.maxTextBytes)
    }

    @Test
    fun name_matching_is_case_sensitive_prefix_matching() {
        val p = DeviceProfiles.PASSPORT
        assertTrue("固件广播名形如 Passport-A1B2", p.matchesName("Passport-A1B2"))
        assertTrue("前缀后接什么都算命中", p.matchesName("Passport-"))
        assertFalse("大小写不同不算命中（与重构前 startsWith 行为一致）", p.matchesName("passport-a1b2"))
        assertFalse("别的品牌名不算命中", p.matchesName("SomeOtherDevice"))
        assertFalse("没有广播名时不算命中", p.matchesName(null))
    }

    @Test
    fun empty_prefix_list_means_no_name_filter() {
        val generic = DeviceProfiles.PASSPORT.copy(namePrefixes = emptyList())
        assertTrue(generic.matchesName("Whatever"))
        assertTrue("不做名字过滤时连 null 也算通过（只依赖服务 UUID 过滤）", generic.matchesName(null))
    }

    @Test
    fun detect_picks_the_owning_profile() {
        assertSame(DeviceProfiles.PASSPORT, DeviceProfiles.detect("Passport-B97A"))
        assertNull("未知设备不猜档案", DeviceProfiles.detect("Random-1234"))
        assertNull(DeviceProfiles.detect(null))
    }

    @Test
    fun byId_round_trips_and_misses_cleanly() {
        assertSame(DeviceProfiles.PASSPORT, DeviceProfiles.byId("passport"))
        assertNull(DeviceProfiles.byId("no-such-id"))
    }

    @Test
    fun every_profile_is_self_consistent() {
        assertTrue("目录不能为空", DeviceProfiles.all.isNotEmpty())
        assertEquals(
            "档案标识必须唯一（偏好键与日志都依赖它）",
            DeviceProfiles.all.size,
            DeviceProfiles.all.map { it.id }.toSet().size,
        )
        DeviceProfiles.all.forEach { p ->
            assertTrue("${p.id}: MTU 需为正数", p.requestMtu > 0)
            assertTrue("${p.id}: 采样率需为正数", p.audio.sampleRateHz > 0)
            assertTrue("${p.id}: 帧长需为正数", p.audio.frameMs > 0)
            assertTrue("${p.id}: 文本上限需为正数", p.capabilities.maxTextBytes > 0)
            assertTrue("${p.id}: 按键数不能为负", p.capabilities.buttonCount >= 0)
            assertTrue("${p.id}: 前缀不能是空串（会匹配到所有设备）", p.namePrefixes.none { it.isEmpty() })
        }
    }
}
