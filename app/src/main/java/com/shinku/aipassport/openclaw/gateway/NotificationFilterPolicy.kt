package com.shinku.aipassport.openclaw.gateway

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 通知过滤策略(本地展示层,不改网关数据)。
 *
 * 按通知来源 App 名过滤:白名单模式(仅显示列出的 App)或黑名单模式(排除列出的 App)。
 * 过滤设置存 SharedPreferences;为空时不过滤(显示全部)。
 */
class NotificationFilterPolicy(context: Context) {

    private val prefs = context.getSharedPreferences("notif_filter", Context.MODE_PRIVATE)
    private val gson = Gson()

    enum class Mode { OFF, WHITELIST, BLACKLIST }

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, "OFF") ?: "OFF") }
            .getOrDefault(Mode.OFF)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** 参与过滤的 App 名列表(白/黑名单共用)。 */
    var appList: List<String>
        get() = try {
            gson.fromJson(
                prefs.getString(KEY_APPS, "[]"),
                object : TypeToken<List<String>>() {}.type,
            ) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        set(value) = prefs.edit().putString(KEY_APPS, gson.toJson(value)).apply()

    /**
     * 判断某条通知是否应显示。appName 为空时:
     *  - OFF → 显示
     *  - WHITELIST → 不显示(空来源无法匹配白名单)
     *  - BLACKLIST → 显示(空来源不在黑名单)
     */
    fun shouldShow(appName: String): Boolean {
        return when (mode) {
            Mode.OFF -> true
            Mode.WHITELIST -> appList.any { it.equals(appName, ignoreCase = true) }
            Mode.BLACKLIST -> !appList.any { it.equals(appName, ignoreCase = true) }
        }
    }

    /** 把一个 appName 加入列表(去重)。 */
    fun addApp(name: String) {
        val cur = appList.toMutableList()
        if (cur.none { it.equals(name, ignoreCase = true) }) {
            cur.add(name.trim())
            appList = cur
        }
    }

    /** 从列表移除一个 appName。 */
    fun removeApp(name: String) {
        appList = appList.filterNot { it.equals(name, ignoreCase = true) }
    }

    companion object {
        private const val KEY_MODE = "filter_mode"
        private const val KEY_APPS = "filter_apps"
    }
}
