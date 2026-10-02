package com.shinku.aipassport.openclaw.service

import android.content.Context
import android.util.Log
import com.shinku.aipassport.openclaw.protocol.UpdateCheck
import com.shinku.aipassport.openclaw.protocol.UpdateNotices
import com.shinku.aipassport.openclaw.protocol.VersionManifest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 版本更新检查（联网部分）。判定逻辑在 [UpdateCheck]（纯逻辑、有单测），这里只管
 * 「拿到版本清单 → 缓存 → 少打扰」。
 *
 * 数据源是 App 仓根目录的 `versions.json`（发版 CI 自动维护），三级回退：
 *  1. `cdn.jsdelivr.net/gh/...@main/versions.json` —— 国内有节点，首选；
 *  2. `raw.githubusercontent.com/...` —— 备用；
 *  3. GitHub Releases API（App 与固件各查一次，只读 tag）—— 最后兜底。
 *
 * 说明（为什么不用 GitHub API 当主源）：国内手机到 `api.github.com` 常超时，
 * 且未认证额度按出口 IP 算（60/h），多设备共用一条出口会互相挤。
 *
 * 缓存策略：一天自动查一次；用户手点「检查更新」时给 URL 加时间戳绕过 CDN 缓存，
 * 保证立刻能看到刚发布的版本。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val PREF = "update_check"
    private const val KEY_JSON = "manifest_json"
    private const val KEY_AT = "checked_at"
    private const val KEY_SOURCE = "source"
    private const val KEY_NOTICE_APP = "notice_app"
    private const val KEY_NOTICE_FW = "notice_fw"
    private const val KEY_NOTICE_URL = "notice_url"

    private const val APP_SLUG = "Shinku-Chen/ai-passport-openclaw-android"
    private const val FW_SLUG = "Shinku-Chen/ai-passport"

    /** 自动检查间隔：一天一次就够，别天天催。 */
    const val INTERVAL_MS = 24L * 60L * 60L * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 一次检查的结论（给 UI 渲染）。 */
    data class State(val notices: UpdateNotices, val checkedAt: Long, val source: String)

    /** UI 随时可读的结论文本（落盘；设置页/状态卡不必依赖广播）。 */
    data class Notices(
        val appNotice: String = "",
        val firmwareNotice: String = "",
        val url: String = "",
        val checkedAt: Long = 0L,
    )

    /** 把结论落盘，供 UI 渲染（没有新版时也存，UI 才能说「已是最新」）。 */
    fun persistNotices(context: Context, state: State) {
        prefs(context).edit()
            .putString(KEY_NOTICE_APP, state.notices.appUpdate.orEmpty())
            .putString(KEY_NOTICE_FW, state.notices.firmwareUpdate.orEmpty())
            .putString(KEY_NOTICE_URL, state.notices.url)
            .apply()
    }

    /** 读上次落盘的结论（不动网）。 */
    fun cachedNotices(context: Context): Notices {
        val p = prefs(context)
        return Notices(
            appNotice = p.getString(KEY_NOTICE_APP, "").orEmpty(),
            firmwareNotice = p.getString(KEY_NOTICE_FW, "").orEmpty(),
            url = p.getString(KEY_NOTICE_URL, "").orEmpty(),
            checkedAt = p.getLong(KEY_AT, 0L),
        )
    }

    fun checkedAt(context: Context): Long = prefs(context).getLong(KEY_AT, 0L)

    /** 距上次检查是否已过 24h。 */
    fun dueForCheck(context: Context): Boolean =
        System.currentTimeMillis() - checkedAt(context) >= INTERVAL_MS

    /** 读缓存（不动网）：设置页/状态卡打开时立刻能显示上次结论。 */
    fun cached(context: Context, appVersion: String, deviceFirmware: String?): State? {
        val p = prefs(context)
        val json = p.getString(KEY_JSON, null)?.takeIf { it.isNotBlank() } ?: return null
        val manifest = VersionManifest.parse(json) ?: return null
        return State(
            notices = UpdateCheck.notices(appVersion, deviceFirmware, manifest),
            checkedAt = p.getLong(KEY_AT, 0L),
            source = p.getString(KEY_SOURCE, "").orEmpty(),
        )
    }

    /**
     * 联网检查一次并落缓存。
     *
     * @param manual 用户手点：给 URL 加时间戳绕过 CDN 缓存（立刻看到刚发的版本）
     * @return 成功时的结论；网络/解析失败一律返回 null（调用方静默处理，别打扰用户）
     */
    fun refresh(context: Context, appVersion: String, deviceFirmware: String?, manual: Boolean): State? {
        val fetched = fetch(manual) ?: return null
        val manifest = VersionManifest.parse(fetched.first) ?: return null
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString(KEY_JSON, fetched.first)
            .putLong(KEY_AT, now)
            .putString(KEY_SOURCE, fetched.second)
            .apply()
        Log.i(
            TAG,
            "版本清单已更新(来源 ${fetched.second}):app=${manifest.app?.version ?: "-"} " +
                "firmware=${manifest.firmware?.version ?: "-"}",
        )
        val state = State(UpdateCheck.notices(appVersion, deviceFirmware, manifest), now, fetched.second)
        persistNotices(context, state)
        return state
    }

    /** 依次尝试三个来源；返回 (JSON, 来源名)。全部失败返回 null。 */
    private fun fetch(manual: Boolean): Pair<String, String>? {
        val bust = if (manual) "?t=${System.currentTimeMillis()}" else ""
        val candidates = listOf(
            "https://cdn.jsdelivr.net/gh/$APP_SLUG@main/versions.json$bust" to "jsdelivr",
            "https://raw.githubusercontent.com/$APP_SLUG/main/versions.json$bust" to "raw",
        )
        for ((url, name) in candidates) {
            get(url)?.let { return it to name }
        }
        // 兜底:直接读两个仓库的 latest release tag,拼一份最小清单
        val appTag = get("https://api.github.com/repos/$APP_SLUG/releases/latest")
            ?.let { tagOf(it) } ?: return null
        val fwTag = get("https://api.github.com/repos/$FW_SLUG/releases/latest")?.let { tagOf(it) }
        val json = buildString {
            append("""{"schema":1,"app":{"version":"${appTag.trimStart('v')}","url":"https://github.com/$APP_SLUG/releases/latest"}""")
            // 固件 tag 形如 v1.11-intercom → 取 1.11
            val fwVersion = fwTag?.trimStart('v')?.substringBefore('-')
            if (!fwVersion.isNullOrBlank()) {
                append(""","firmware":{"version":"$fwVersion","tag":"${fwTag!!}","url":"https://github.com/$FW_SLUG/releases/latest"}""")
            }
            append("}")
        }
        return json to "github-api"
    }

    private fun tagOf(json: String): String? = runCatching {
        com.google.gson.JsonParser.parseString(json).asJsonObject.get("tag_name")?.asString
    }.getOrNull()

    private fun get(url: String): String? = try {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
            .execute()
            .use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
    } catch (e: Exception) {
        Log.d(TAG, "拉取失败(忽略):$url ${e.message}")
        null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
