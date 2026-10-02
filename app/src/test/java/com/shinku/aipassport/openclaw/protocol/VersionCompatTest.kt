package com.shinku.aipassport.openclaw.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VersionCompat] 的 JVM 单测（纯逻辑，不依赖 Android 与真机）。
 *
 * 关键语义：**不一致只提示、不阻断**；信息不足（老固件不上报 `fw`/`proto`）时不乱报，
 * 否则每次连接都弹一条假告警。
 *
 * 本版新增的关键语义：**配套只看大版本 `X.Y`** —— App 发小版本（1.11 → 1.11.1）
 * 不能被当成版本不匹配（历史坑：整串相等比较会误报）。
 */
class VersionCompatTest {

    @Test
    fun matching_versions_produce_no_notice() {
        assertNull(VersionCompat.check("1.9.0", "1.9.0", VersionCompat.MIN_PROTO_VERSION))
    }

    @Test
    fun different_versions_produce_a_readable_notice() {
        val notice = VersionCompat.check("1.9.0", "1.8.0", VersionCompat.MIN_PROTO_VERSION)
        assertTrue("必须提示两者版本号: $notice", notice!!.contains("1.8.0") && notice.contains("1.9.0"))
        assertTrue("要告诉用户怎么做", notice.contains("更新"))
    }

    @Test
    fun missing_firmware_version_stays_quiet() {
        assertNull("老固件不上报 fw 时不该猜", VersionCompat.check("1.9.0", null, null))
        assertNull(VersionCompat.check("1.9.0", "   ", null))
    }

    @Test
    fun protocol_below_minimum_is_a_notice() {
        val notice = VersionCompat.check("1.9.0", "1.9.0", 0)
        assertTrue("协议太旧必须提示: $notice", notice!!.contains("协议"))
        assertTrue(notice.contains("更新固件"))
    }

    @Test
    fun protocol_not_reported_is_not_a_notice() {
        assertNull(VersionCompat.check("1.9.0", "1.9.0", null))
    }

    @Test
    fun same_version_helper_treats_unknown_as_same() {
        assertTrue(VersionCompat.sameVersion("1.9.0", null))
        assertTrue(VersionCompat.sameVersion("1.9.0", "1.9.0"))
        assertFalse(VersionCompat.sameVersion("1.9.0", "1.8.0"))
    }

    @Test
    fun protocol_constants_are_consistent() {
        assertTrue(VersionCompat.MIN_PROTO_VERSION <= VersionCompat.PROTO_VERSION)
        assertEquals(1, VersionCompat.PROTO_VERSION)
    }

    // ---- 大版本语义(App 可发小版本) ----

    @Test
    fun app_minor_version_is_not_a_mismatch() {
        // App 1.11.1 / 1.11.2 配固件 1.11 → 配套,不提示
        assertNull(VersionCompat.check("1.11.1", "1.11", VersionCompat.MIN_PROTO_VERSION))
        assertNull(VersionCompat.check("1.11.2", "1.11", VersionCompat.MIN_PROTO_VERSION))
        assertTrue(VersionCompat.sameVersion("1.11.2", "1.11"))
        // 反向:设备报了三段(App 侧少见)也算配套
        assertNull(VersionCompat.check("1.11", "1.11.3", VersionCompat.MIN_PROTO_VERSION))
    }

    @Test
    fun different_major_versions_still_warn() {
        assertNotNull(VersionCompat.check("1.12", "1.11", VersionCompat.MIN_PROTO_VERSION))
        assertNotNull(VersionCompat.check("1.11.2", "1.10", VersionCompat.MIN_PROTO_VERSION))
        assertFalse(VersionCompat.sameVersion("1.11.2", "1.10"))
    }

    @Test
    fun major_extracts_first_two_segments() {
        assertEquals("1.11", VersionCompat.major("1.11.2"))
        assertEquals("1.11", VersionCompat.major("1.11"))
        assertEquals("1.11", VersionCompat.major("  1.11.0  "))
        assertEquals("1", VersionCompat.major("1"))
        assertEquals("", VersionCompat.major("   "))
        assertEquals("1.x", VersionCompat.major("1.x.3"))
    }

    @Test
    fun compare_is_numeric_per_segment() {
        assertTrue(VersionCompat.compare("1.11.2", "1.11") > 0)
        assertTrue(VersionCompat.compare("1.11", "1.9.0") > 0)   // 11 > 9,不是字典序
        assertEquals(0, VersionCompat.compare("1.11", "1.11.0"))
        assertTrue(VersionCompat.compare("1.10", "1.11") < 0)
        assertEquals(0, VersionCompat.compare("1.11.2", "1.11.2"))
    }
}
