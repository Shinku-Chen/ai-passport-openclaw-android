package com.shinku.aipassport.openclaw.ble

/**
 * 扫到广播后「该不该连它」的判定（纯逻辑，JVM 单测可覆盖）。
 *
 * 背景（真机现场）：现场同时有多台**同名** `Passport-*` 设备在广播时，只按名字前缀连会随到随连 ——
 * 一旦连上别人那台，就撞上设备端「同时只允许 1 条连接」（`CONFIG_BT_NIMBLE_MAX_CONNECTIONS=1`）
 * 的限制：那台多半已被它自己的手机占着，于是只能干等 10 秒连接超时，再扫描、再连错……
 * 用户体感就是「设备重启后自动重连特别慢」，而**把旁边那台关掉立刻就好**（真机实测）。
 *
 * 规则：
 *  - 记住过设备地址 → **只连这一台**，其它同名设备一律忽略（继续扫描，不打扰用户）；
 *  - 没记住（首次配对、或刚点过「忘记设备」）→ 任何匹配设备都可以连，不挡配对流程。
 *
 * 只在这里做判定，真正的连接/重连策略仍归 [LinkRetryPolicy] / [ScanRetry]。
 */
object ScanPick {

    /**
     * @param rememberedAddress 上次连过并记住的设备地址（null/空 = 还没记住）
     * @param foundAddress 本次扫描到的设备地址
     */
    fun shouldConnect(rememberedAddress: String?, foundAddress: String): Boolean {
        if (rememberedAddress.isNullOrBlank()) return true
        return rememberedAddress.equals(foundAddress, ignoreCase = true)
    }
}
