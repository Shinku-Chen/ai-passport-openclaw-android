package com.shinku.aipassport.openclaw.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScanPick] 的 JVM 单测。
 *
 * 现场有多台同名 `Passport-*` 时，只按名字前缀连会连到别人那台 → 对方连接槽已满 → 10 秒超时 →
 * 反复重试（用户体感「重启后自动连接特别慢」；关掉旁边那台立刻就好）。所以记住地址后必须**只连那一台**。
 */
class ScanPickTest {

    private val mine = "4C:11:AE:30:B9:7A"

    @Test
    fun connects_only_to_the_remembered_device() {
        assertTrue(ScanPick.shouldConnect(mine, mine))
    }

    @Test
    fun ignores_another_device_with_the_same_name_prefix() {
        // 旁边那台（别人的、备用机）—— 连上去只会等 10 秒超时
        assertFalse(ScanPick.shouldConnect(mine, "4C:11:AE:30:00:01"))
    }

    @Test
    fun address_comparison_ignores_case() {
        assertTrue(ScanPick.shouldConnect(mine.lowercase(), mine))
        assertTrue(ScanPick.shouldConnect(mine, mine.lowercase()))
    }

    @Test
    fun without_a_remembered_device_any_match_is_accepted() {
        // 首次配对 / 刚点过「忘记设备」：不能把配对流程挡掉
        assertTrue(ScanPick.shouldConnect(null, "AA:BB:CC:DD:EE:FF"))
        assertTrue(ScanPick.shouldConnect("", "AA:BB:CC:DD:EE:FF"))
        assertTrue(ScanPick.shouldConnect("   ", "AA:BB:CC:DD:EE:FF"))
    }
}
