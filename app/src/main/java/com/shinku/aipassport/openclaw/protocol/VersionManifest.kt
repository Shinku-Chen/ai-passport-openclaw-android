package com.shinku.aipassport.openclaw.protocol

import com.google.gson.Gson

/**
 * 远端版本清单（App 仓根目录 `versions.json`，由发版 CI 自动维护）。
 *
 * 一个文件同时描述「App 最新版」和「固件最新版」，因为**只有 App 会去检查**：
 *  ```
 *  { "schema": 1, "app": { "version": "1.11.1", "url": "…", "notes": "…" },
 *    "firmware": { "version": "1.11", "tag": "v1.11-intercom", "url": "…", "notes": "…" } }
 *  ```
 * 字段全部可选：解析失败或缺字段时当作「没有信息」，绝不因此提示用户。
 */
data class VersionManifest(
    val schema: Int = 1,
    val updated: String = "",
    val app: Entry? = null,
    val firmware: Entry? = null,
) {
    data class Entry(
        val version: String = "",
        val versionCode: Int = 0,
        val tag: String = "",
        val url: String = "",
        val notes: String = "",
        val minSupported: String = "",
    )

    companion object {
        private val gson = Gson()

        /** 解析清单；不是 JSON / 两个入口都缺 → null（调用方静默处理）。 */
        fun parse(json: String): VersionManifest? =
            runCatching { gson.fromJson(json, VersionManifest::class.java) }
                .getOrNull()
                ?.takeIf { it.app != null || it.firmware != null }
    }
}

/** 更新检查的结论（纯数据，便于单测与 UI 渲染）。 */
data class UpdateNotices(
    /** App 有新版本时给用户看的一句话；否则 null。 */
    val appUpdate: String? = null,
    /** 设备固件有新版本时给用户看的一句话；否则 null。 */
    val firmwareUpdate: String? = null,
    /** 点「更新」该打开的地址（App 优先，其次固件）。 */
    val url: String = "",
) {
    val hasAny: Boolean get() = appUpdate != null || firmwareUpdate != null
}

/**
 * 版本更新判定（纯逻辑，JVM 单测覆盖）。
 *
 * 规则（用户定）：
 *  - **App 允许小版本**（`1.11` → `1.11.1`）：远端**比本机新**才提示；
 *  - **固件是大版本**：只要远端固件比**设备当前固件**新就提示更新（固件与 App 大版本是否配套
 *    由 [VersionCompat.check] 另行提示，两者互补：一个说「有新版」，一个说「不配套」）；
 *  - 信息不足（清单缺失、设备没上报固件版本）→ 一律不提示。
 */
object UpdateCheck {

    fun notices(
        appVersion: String,
        deviceFirmware: String?,
        manifest: VersionManifest?,
    ): UpdateNotices {
        if (manifest == null) return UpdateNotices()

        val appEntry = manifest.app
        val appNotice = appEntry?.version
            ?.takeIf { it.isNotBlank() && VersionCompat.compare(it, appVersion) > 0 }
            ?.let { newer ->
                "App 有新版本 $newer（当前 $appVersion）" + notesSuffix(appEntry.notes)
            }

        val fwEntry = manifest.firmware
        val deviceFw = deviceFirmware?.trim().orEmpty()
        val fwNotice = when {
            fwEntry == null || fwEntry.version.isBlank() -> null
            deviceFw.isEmpty() -> null                       // 设备没上报:信息不足,不猜
            VersionCompat.compare(fwEntry.version, deviceFw) <= 0 -> null   // 已是最新
            else -> "设备固件有新版本 ${fwEntry.version}（当前 $deviceFw）" + notesSuffix(fwEntry.notes)
        }

        val url = when {
            appNotice != null && !appEntry?.url.isNullOrBlank() -> appEntry!!.url
            fwNotice != null && !fwEntry?.url.isNullOrBlank() -> fwEntry!!.url
            else -> ""
        }
        return UpdateNotices(appNotice, fwNotice, url)
    }

    private fun notesSuffix(notes: String): String =
        if (notes.isBlank()) "" else "：${notes.trim().take(120)}"

    /**
     * 落盘的 App 提示还能不能直接用：只有「检查时的 App 版本」与当前版本一致才能展示。
     *
     * 为什么：提示文案里已经写死了当时的版本号（`App 有新版本 1.12（当前 1.11）`），
     * App 装上 1.12 后这句话就变成错的（真机反馈：装好新版，顶部还挂着「当前 1.11」）。
     * 版本一变就不用旧结论，等重查（[dueForCheck] 会因此立刻拉一次）。
     */
    fun usableAppNotice(notice: String, checkedAppVersion: String, appVersion: String): String? =
        notice.takeIf { it.isNotBlank() && checkedAppVersion.trim() == appVersion.trim() }

    /**
     * 现在该重新检查版本吗：距上次超过 [intervalMs]，或者 App 换了版本。
     *
     * `checkedAt == 0`（从没查过）算「该查」；没记过版本（老缓存）时按新版本处理，
     * 让它重建一次结论，避免一直挂着旧文案。
     */
    fun dueForCheck(
        checkedAt: Long,
        checkedAppVersion: String,
        appVersion: String,
        now: Long,
        intervalMs: Long,
    ): Boolean = now - checkedAt >= intervalMs || checkedAppVersion.trim() != appVersion.trim()
}
