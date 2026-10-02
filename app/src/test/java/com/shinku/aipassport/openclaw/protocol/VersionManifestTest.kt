package com.shinku.aipassport.openclaw.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VersionManifest] / [UpdateCheck] 的 JVM 单测。
 *
 * 要守住的三条：
 *  1. App 的小版本升级（1.11 → 1.11.1）要提示；
 *  2. 只比**更新**，不比「不同」——远端不新就不提示，别天天催；
 *  3. 信息不足（清单缺字段 / 设备没上报固件版本 / JSON 乱七八糟）→ 一律静默。
 */
class VersionManifestTest {

    private fun manifest(app: String? = null, fw: String? = null): VersionManifest =
        VersionManifest(
            app = app?.let { VersionManifest.Entry(version = it, url = "https://app.example/$it", notes = "修了点东西") },
            firmware = fw?.let { VersionManifest.Entry(version = it, url = "https://fw.example/$it", notes = "更稳了") },
        )

    // ---- 解析 ----

    @Test
    fun parses_a_full_manifest() {
        val m = VersionManifest.parse(
            """
            {"schema":1,"updated":"2026-10-02T00:00:00Z",
             "app":{"version":"1.11.1","versionCode":8,"url":"https://x/app","notes":"n","minSupported":"1.11"},
             "firmware":{"version":"1.11","tag":"v1.11-intercom","url":"https://x/fw","notes":"m"}}
            """.trimIndent(),
        )
        assertNotNull(m)
        assertEquals("1.11.1", m!!.app!!.version)
        assertEquals(8, m.app!!.versionCode)
        assertEquals("v1.11-intercom", m.firmware!!.tag)
    }

    @Test
    fun rejects_junk_and_empty_manifests() {
        assertNull(VersionManifest.parse("not json at all"))
        assertNull(VersionManifest.parse("{}"))
        assertNull(VersionManifest.parse(""))
        assertNull(VersionManifest.parse("{\"schema\":1,\"updated\":\"x\"}"))
    }

    @Test
    fun tolerates_missing_optional_fields() {
        val m = VersionManifest.parse("""{"app":{"version":"1.11.1"}}""")
        assertNotNull(m)
        assertEquals("", m!!.app!!.url)
        assertNull(m.firmware)
    }

    // ---- App 更新 ----

    @Test
    fun app_minor_update_is_reported() {
        val n = UpdateCheck.notices("1.11", "1.11", manifest(app = "1.11.1"))
        assertNotNull("1.11 → 1.11.1 必须提示", n.appUpdate)
        assertTrue(n.appUpdate!!.contains("1.11.1"))
        assertNull("固件没变就不提示", n.firmwareUpdate)
        assertEquals("https://app.example/1.11.1", n.url)
    }

    @Test
    fun same_app_version_is_quiet() {
        val n = UpdateCheck.notices("1.11.1", "1.11", manifest(app = "1.11.1"))
        assertNull(n.appUpdate)
        assertFalse(n.hasAny)
    }

    @Test
    fun older_or_equal_remote_is_quiet() {
        assertNull(UpdateCheck.notices("1.11.2", "1.11", manifest(app = "1.11.1")).appUpdate)
        assertEquals(0, VersionCompat.compare("1.11.1", "1.11.1"))
    }

    // ---- 固件更新 ----

    @Test
    fun firmware_update_is_reported_when_device_is_older() {
        val n = UpdateCheck.notices("1.11", "1.11", manifest(fw = "1.12"))
        assertNotNull("设备 1.11 而最新固件 1.12,要提示", n.firmwareUpdate)
        assertTrue(n.firmwareUpdate!!.contains("1.12"))
        assertEquals("https://fw.example/1.12", n.url)
    }

    @Test
    fun firmware_same_version_is_quiet() {
        assertNull(UpdateCheck.notices("1.11", "1.11", manifest(fw = "1.11")).firmwareUpdate)
    }

    @Test
    fun firmware_unknown_device_version_is_quiet() {
        assertNull("设备没上报固件版本时不猜", UpdateCheck.notices("1.11", null, manifest(fw = "1.12")).firmwareUpdate)
        assertNull(UpdateCheck.notices("1.11", "  ", manifest(fw = "1.12")).firmwareUpdate)
    }

    // ---- 组合与兜底 ----

    @Test
    fun both_updates_can_be_reported_and_app_url_wins() {
        val n = UpdateCheck.notices("1.11", "1.11", manifest(app = "1.11.1", fw = "1.12"))
        assertNotNull(n.appUpdate)
        assertNotNull(n.firmwareUpdate)
        assertEquals("App 的更新更优先", "https://app.example/1.11.1", n.url)
    }

    @Test
    fun null_manifest_is_silent() {
        assertFalse(UpdateCheck.notices("1.11", "1.11", null).hasAny)
    }

    @Test
    fun notes_are_truncated_and_optional() {
        val long = VersionManifest(app = VersionManifest.Entry(version = "9.9", url = "u", notes = "x".repeat(500)))
        val n = UpdateCheck.notices("1.11", null, long)
        assertTrue(n.appUpdate!!.length < 300)
    }

    // ---- 落盘结论的「版本变了就作废」（真机 bug：装上 1.12 后横幅还写「当前 1.11」） ----

    @Test
    fun stale_app_notice_is_dropped_after_the_app_version_changes() {
        val notice = "App 有新版本 1.12（当前 1.11）：修了点东西"
        assertNull(
            "提示里写着检查时的版本号，换版本后不能再展示",
            UpdateCheck.usableAppNotice(notice, checkedAppVersion = "1.11", appVersion = "1.12"),
        )
        assertEquals(
            notice,
            UpdateCheck.usableAppNotice(notice, checkedAppVersion = "1.11", appVersion = "1.11"),
        )
        assertNull("空提示本来就不展示", UpdateCheck.usableAppNotice("", "1.11", "1.11"))
        assertNull(
            "老缓存没记过版本 → 不展示（但不崩）",
            UpdateCheck.usableAppNotice(notice, checkedAppVersion = "", appVersion = "1.11"),
        )
    }

    @Test
    fun app_version_change_forces_an_immediate_recheck() {
        val day = 24L * 60L * 60L * 1000L
        val now = 1_000_000_000_000L
        assertFalse(
            "同版本、没到 24h → 不重查（别天天扰动）",
            UpdateCheck.dueForCheck(now - 1000L, "1.12", "1.12", now, day),
        )
        assertTrue(
            "App 换了版本 → 立刻重查（否则横幅会一直挂着旧版本号）",
            UpdateCheck.dueForCheck(now - 1000L, "1.11", "1.12", now, day),
        )
        assertTrue("从没查过 → 该查", UpdateCheck.dueForCheck(0L, "", "1.12", now, day))
        assertTrue("过了 24h → 该查", UpdateCheck.dueForCheck(now - day, "1.12", "1.12", now, day))
        assertTrue(
            "老缓存没记版本 → 也算该查，重查一次建立新结论",
            UpdateCheck.dueForCheck(now - 1000L, "", "1.12", now, day),
        )
    }
}
