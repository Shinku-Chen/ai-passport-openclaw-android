package com.shinku.aipassport.openclaw.protocol

/**
 * 固件 / App 版本一致性检查（纯逻辑，便于单测）。
 *
 * 版本体系（用户定的规则）：
 *  - **固件发大版本**：`X.Y`（如 `1.10`、`1.11`），tag `vX.Y-intercom`；
 *  - **App 可以发小版本**：`X.Y` 或 `X.Y.Z`（如 `1.11`、`1.11.1`、`1.11.2`）；
 *  - **配套判定只看大版本 `X.Y`**：`App 1.11.2` 配 `固件 1.11` 就是**配套**，
 *    App 的小版本升级**不是**版本不匹配，不该提示。
 *
 * 配对握手时双方互报版本（设备 `hello` 带 `fw` / `proto`，App 的 `hello` 带 `app`），
 * 只有大版本不同才在**两边**提示 —— 且**只提示、不阻断**：旧版本仍能对话。
 *
 * 历史坑：这里原来是**整串相等**比较，于是 App 一发小版本（1.11.1）就会被判成不匹配，
 * 设备屏还会弹一条假告警 —— 所以比较必须走 [major]。
 */
object VersionCompat {

    /** 本 App 实现的协议版本，与固件 `OC_PROTO_VERSION` 对应。 */
    const val PROTO_VERSION = 1

    /** 低于这个协议版本就无法正常协作。 */
    const val MIN_PROTO_VERSION = 1

    /**
     * 取「大版本」= 前两段：`1.11.2` → `1.11`，`1.11` → `1.11`。
     * 段数不足或内容异常时原样返回（宁可比较得保守，也不要瞎猜）。
     */
    fun major(version: String): String {
        val trimmed = version.trim()
        val parts = trimmed.split('.')
        if (parts.size < 2) return trimmed
        val x = parts[0].trim()
        val y = parts[1].trim()
        return if (x.isNotEmpty() && y.isNotEmpty()) "$x.$y" else trimmed
    }

    /**
     * 数值化比较版本号：`1.11.2 > 1.11 > 1.9.0`；段数不同按 0 补齐（`1.11 == 1.11.0`）。
     * 非数字段按 0 处理（不会抛异常）。
     */
    fun compare(a: String, b: String): Int {
        val pa = a.trim().split('.')
        val pb = b.trim().split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }

    /**
     * 检查设备上报的版本是否与本 App 配套。
     *
     * @param appVersion 本 App 的 `versionName`（可能是小版本，如 `1.11.1`）
     * @param firmwareVersion 设备 `hello` 里的 `fw`（老固件可能没有 → null）
     * @param peerProto 设备 `hello` 里的 `proto`（老固件可能没有 → null）
     * @return null = 配套或信息不足（不乱提示）；否则返回一句给用户看的中文提示
     */
    fun check(appVersion: String, firmwareVersion: String?, peerProto: Int?): String? {
        // 协议版本是机器判定：低于最低要求说明功能对不上，必须提示
        if (peerProto != null && peerProto < MIN_PROTO_VERSION) {
            return "设备协议版本过旧（v$peerProto，本 App 需要 v$MIN_PROTO_VERSION 及以上），请更新固件"
        }
        // 没上报固件版本（老固件）：信息不足，不猜、不提示
        val fw = firmwareVersion?.trim().orEmpty()
        if (fw.isEmpty()) return null
        val app = appVersion.trim()
        if (major(fw) == major(app)) return null
        return "固件 $fw 与 App $app 大版本不一致（${major(fw)} ↔ ${major(app)}），" +
            "请把固件与 App 更新到同一大版本"
    }

    /**
     * 设备上报的固件是否与本 App **配套**（只看大版本；信息不足时返回 true，避免误报）。
     * 小版本差异（App `1.11.1` 对固件 `1.11`）算配套。
     */
    fun sameVersion(appVersion: String, firmwareVersion: String?): Boolean {
        val fw = firmwareVersion?.trim().orEmpty()
        return fw.isEmpty() || major(fw) == major(appVersion.trim())
    }
}
