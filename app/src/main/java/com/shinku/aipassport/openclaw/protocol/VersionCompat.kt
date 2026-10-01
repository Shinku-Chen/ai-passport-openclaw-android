package com.shinku.aipassport.openclaw.protocol

/**
 * 固件 / App 版本一致性检查（纯逻辑，便于单测）。
 *
 * 背景：固件与 App 按**同一版本号**成对发布（固件 tag `vX.Y.Z-intercom` ↔ App `versionName X.Y.Z`），
 * 所以配对握手时双方互报版本（设备 `hello` 带 `fw` / `proto`，App 的 `hello` 带 `app`），
 * 一旦不一致就在**两边**提示用户更新 —— 但**只提示、不阻断**：旧版本仍能对话，
 * 只是可能缺少新功能或状态显示不对。
 */
object VersionCompat {

    /** 本 App 实现的协议版本，与固件 `OC_PROTO_VERSION` 对应。 */
    const val PROTO_VERSION = 1

    /** 低于这个协议版本就无法正常协作。 */
    const val MIN_PROTO_VERSION = 1

    /**
     * 检查设备上报的版本是否与本 App 配套。
     *
     * @param appVersion 本 App 的 `versionName`
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
        if (fw == app) return null
        return "固件 $fw 与 App $app 版本不一致，请把固件与 App 更新到同一版本"
    }

    /** 设备上报的固件是否与本 App 同版本（信息不足时返回 true，避免误报）。 */
    fun sameVersion(appVersion: String, firmwareVersion: String?): Boolean {
        val fw = firmwareVersion?.trim().orEmpty()
        return fw.isEmpty() || fw == appVersion.trim()
    }
}
