package com.shinku.aipassport.openclaw.stt

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 小智 **OTA(绑定)请求**的**唯一**字段来源 + 版本号解析 + 响应结构取证。
 *
 * 为什么单独成文件:OTA 请求里的 `name` / `version` / `User-Agent` 语义同源(「本端是谁、什么版本」),
 * 之前散在 [XiaozhiActivator] 里写死(`board.name = "Passport"`、`application.version = "0.1.0"`、
 * `User-Agent: lancelot/passport-0.1.0`),既不是当前版本号、也容易几处各写一份改漏。
 *
 * 字段与官方固件逐字对齐(`xiaozhi-esp32` 的 `Board::GetSystemInfoJson()` + `SystemInfo::GetUserAgent()`):
 *  - `application.name` / `application.version`:固件上报的是工程名与固件版本(App 侧叫 `ai-passport` +
 *    当前版本号 —— 小智云按这两项登记/展示设备);
 *  - `board.type` / `board.name` / `board.ip` / `board.mac`:机型、名称、IP、MAC;
 *  - `User-Agent`:`<机型>/<名称>-<版本>`,与 YAML/日志里看到的固件 UA 同一形状。
 *
 * 版本号优先级(见 [version]):**设备固件版本**(设备 hello 的 `fw`,如 `1.13`)→ **App versionName**
 * → [FALLBACK_VERSION](两者都取不到时的**显式**回退,不再是写死的 `0.1.0`)。
 *
 * 本对象不依赖 Android,可直接 JVM 单测(见 `XiaozhiOtaRequestTest`)。
 */
object XiaozhiOtaRequest {

    /** 上报的设备/应用名(作者要求:绑定请求里的 `name` 必须是 `ai-passport`)。 */
    const val APP_NAME = "ai-passport"

    /** 小智云登记这台设备的机型(`board.type`,与识别通道/绑定用的是同一台设备)。 */
    const val BOARD_TYPE = "lancelot"

    /**
     * 版本号都取不到时的**显式**回退值。
     *
     * 用 `0.0.0` 而不是留空/写死 `0.1.0`:小智云按版本号做固件比对,空串会让服务端解析异常,
     * 而 `0.1.0` 会把「不知道版本」伪装成一个真实版本。`0.0.0` 明确表示「比任何版本都旧」。
     */
    const val FALLBACK_VERSION = "0.0.0"

    /** 本端不是设备本体(手机侧发起 OTA),IP 无意义但字段不能缺(与固件同形状)。 */
    private const val BOARD_IP = "127.0.0.1"

    /** 无固件镜像可散列(手机侧没有 elf),与既有实现一致用占位值。 */
    private const val ELF_SHA256 = "0000000000000000"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * OTA 上报的版本号(唯一解析点):
     *  1. [deviceFirmwareVersion] —— 设备 hello 的 `fw`(如 `1.13`),设备真的在跑什么版本;
     *  2. [appVersionName] —— App 的 `versionName`(设备没上报时退化到本端版本);
     *  3. 都没有 → [FALLBACK_VERSION]。
     *
     * 空白/`null` 一律视为「没拿到」,不会把空串当版本号上报。
     */
    fun version(deviceFirmwareVersion: String?, appVersionName: String?): String =
        deviceFirmwareVersion?.trim()?.takeIf { it.isNotEmpty() }
            ?: appVersionName?.trim()?.takeIf { it.isNotEmpty() }
            ?: FALLBACK_VERSION

    /** `User-Agent`,如 `lancelot/ai-passport-1.13`(与 [version] 同源,不再各写一份)。 */
    fun userAgent(version: String): String = "$BOARD_TYPE/$APP_NAME-$version"

    /** OTA 请求体(与固件 `GetSystemInfoJson` 同字段名/同层级)。 */
    fun body(mac: String, version: String): JsonObject {
        val application = JsonObject().apply {
            addProperty("name", APP_NAME)
            addProperty("version", version)
            addProperty("elf_sha256", ELF_SHA256)
        }
        val board = JsonObject().apply {
            addProperty("type", BOARD_TYPE)
            addProperty("name", APP_NAME)
            addProperty("ip", BOARD_IP)
            addProperty("mac", mac)
        }
        return JsonObject().apply {
            add("application", application)
            add("board", board)
        }
    }

    /**
     * 一次 OTA(查激活状态)请求:请求体 + 握手头都由这里产出,保证「字段只有一处」。
     *
     * `Activation-Version: 1` = 无 serial_number 的 v1 激活流程(与固件 `Ota::SetupHttp` 一致,
     * 也与 `ota/activate` 轮询用的版本号一致)。
     */
    fun request(baseUrl: String, mac: String, clientId: String, version: String): Request =
        Request.Builder()
            .url(baseUrl)
            .addHeader("Device-Id", mac)
            .addHeader("Client-Id", clientId)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", userAgent(version))
            .addHeader("Accept-Language", "zh-CN")
            .addHeader("Activation-Version", "1")
            .post(body(mac, version).toString().toRequestBody(JSON))
            .build()

    // ---- 取证:OTA 响应结构与密钥字段的可读描述 ----

    /**
     * 把 OTA 响应压成**键名 + 结构**的可读摘要(取证用):嵌套对象/数组逐层展开,
     * 字符串值默认只打「长度 + 前 4 位」,只有 [PLAIN_VALUE_KEYS] 里的非敏感字段打明文。
     *
     * 为什么要它:绑定后识别失败时要能一眼看出云端这次**到底下发了什么**(有没有 `activation`、
     * 有没有 `websocket.token` / `mqtt`),而不是靠猜;同时 `websocket.token` / `password` 这类
     * 凭据**绝不**进日志正文。
     */
    fun summarize(json: JsonObject): String = buildString { appendObject(this, json, 0) }

    /** 密钥/凭据类字符串的日志描述:只给长度与前 4 位(明文永不出现在日志里)。 */
    fun describeSecret(value: String?): String {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return "未下发"
        return "len=${v.length}, 前4位=${v.take(4)}"
    }

    /** 这些键的值可能写进日志明文(结构取证要看的非敏感字段)。 */
    private val PLAIN_VALUE_KEYS = setOf("url", "version", "type", "name", "endpoint")

    private fun appendObject(sb: StringBuilder, obj: JsonObject, depth: Int) {
        val pad = "  ".repeat(depth)
        obj.entrySet().forEach { (key, value) ->
            when {
                value.isJsonObject -> {
                    sb.append(pad).append(key).append(":{\n")
                    appendObject(sb, value.asJsonObject, depth + 1)
                    sb.append(pad).append("}\n")
                }

                value.isJsonArray -> {
                    val array = value.asJsonArray
                    sb.append(pad).append(key).append(":[数组 ").append(array.size()).append(" 项]\n")
                    // 数组元素结构一致,只展开第一项的形状
                    array.firstOrNull()?.let { first ->
                        if (first.isJsonObject) {
                            appendObject(sb, first.asJsonObject, depth + 1)
                        } else {
                            sb.append(pad).append("  ").append(describe(key, first)).append('\n')
                        }
                    }
                }

                else -> sb.append(pad).append(key).append('=').append(describe(key, value)).append('\n')
            }
        }
    }

    private fun describe(key: String, value: JsonElement): String = when {
        value.isJsonNull -> "null"
        !value.isJsonPrimitive -> value.toString()
        value.asJsonPrimitive.isString -> {
            val s = value.asString
            if (key in PLAIN_VALUE_KEYS) s else describeSecret(s)
        }
        // 数字/布尔:本身不是凭据,原样打
        else -> value.asString
    }
}
