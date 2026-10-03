package com.shinku.aipassport.openclaw.stt

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
 *  - `version`(顶层):**系统信息 JSON 的格式版本**,固件恒发 `2`;
 *  - `language` / `mac_address` / `uuid`:语言、设备 MAC、软件生成的客户端 UUID(uuid 与 `Client-Id`
 *    头**同一个值**,见 [XiaozhiClientId]);
 *  - `application.name` / `application.version`:固件上报的是工程名与固件版本(App 侧叫 `ai-passport` +
 *    当前版本号 —— 小智云按这两项登记/展示设备);
 *  - `board.type` / `board.name` / `board.ip` / `board.mac`:机型、名称、IP、MAC;
 *  - `User-Agent`:固件是 `BOARD_NAME "/" 固件版本`(如 `lancelot/1.13`),本端同形;
 *    固件里 `BOARD_NAME` 就是板型(见 `main/CMakeLists.txt`:未单独指定时 `BOARD_NAME = BOARD_TYPE`)。
 *
 * 已知**未补齐**的固件字段(手机侧没有对应事实,官方服务端也不依赖它们做鉴权):`flash_size` /
 * `minimum_free_heap_size` / `chip_model_name` / `chip_info` / `partition_table` / `ota` / `display`。
 * 完整差异清单见 `CHANGELOG.md` 与对应提交说明(结论与依据:官方 `board.cc` + `system_info.cc`)。
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

    /** 顶层系统信息格式版本(固件 `GetSystemInfoJson` 恒发 `2`)。 */
    private const val SYSTEM_INFO_VERSION = 2

    /** 上报/协商语言:请求体 `language` 与 `Accept-Language` 头**同值**(固件用 `Lang::CODE`,`zh-CN` 构建下即此值)。 */
    const val LANGUAGE = "zh-CN"

    /**
     * `User-Agent`,如 `lancelot/1.13`(与 [version] 同源,不再各写一份)。
     *
     * 与官方固件逐字同形:`SystemInfo::GetUserAgent()` = `BOARD_NAME "/" 固件版本`。
     * 旧实现写成 `lancelot/ai-passport-1.13`,把工程名塞进了版本位,上报的版本号会被读成
     * `ai-passport-1.13` —— 与 `application.version` 自相矛盾,故按固件形状改回。
     */
    fun userAgent(version: String): String = "$BOARD_TYPE/$version"

    /**
     * OTA 请求体(与固件 `GetSystemInfoJson` 同字段名/同层级)。
     *
     * `uuid` 与 `Client-Id` 头**必须是同一个值**([clientId] 由调用方从**唯一**来源
     * [XiaozhiClientId] 取):固件就是在两处用同一个 `Board::GetUuid()`。
     *
     * @param clientId 本端 Client-Id([XiaozhiClientId.forDevice] 的结果)
     */
    fun body(mac: String, version: String, clientId: String): JsonObject {
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
            addProperty("version", SYSTEM_INFO_VERSION)
            addProperty("language", LANGUAGE)
            addProperty("mac_address", mac)
            addProperty("uuid", clientId)
            add("application", application)
            add("board", board)
        }
    }

    /**
     * 一次 OTA(查激活状态)请求:请求体 + 握手头都由这里产出,保证「字段只有一处」。
     *
     * `Activation-Version: 1` = 无 serial_number 的 v1 激活流程(与固件 `Ota::SetupHttp` 一致,
     * 也与 `ota/activate` 轮询用的版本号一致)。
     *
     * @param clientId 本端 Client-Id(**唯一**来源 [XiaozhiClientId]):同时进 `Client-Id` 头与请求体
     *   `uuid` 字段 —— 与识别 WS 握手用的是同一个值,否则服务端按 (client_id, device_id) 校验会对不上。
     */
    fun request(baseUrl: String, mac: String, clientId: String, version: String): Request =
        Request.Builder()
            .url(baseUrl)
            .addHeader("Device-Id", mac)
            .addHeader("Client-Id", clientId)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", userAgent(version))
            .addHeader("Accept-Language", LANGUAGE)
            .addHeader("Activation-Version", "1")
            .post(body(mac, version, clientId).toString().toRequestBody(JSON))
            .build()

    // ---- 取证:OTA 响应结构与密钥字段的可读描述 ----

    /**
     * **上行报文**(我们发给云端的 JSON,如客户端 `hello`)的日志文本:照**原文**打印,
     * 只把密钥类字段([SECRET_KEYS])的值换成 [describeSecret] 形态。
     *
     * 为什么要有它:真机上「升级通过 → 发 hello → 立刻被 1005 切断」这种问题,必须能一眼看到
     * **我们到底发了什么**;而报文将来若加了凭据字段,也绝不能明文进日志 —— 所以规则是
     * 「原文照打 + 密钥字段脱敏」,而不是整体摘要(摘要会把 `transport`/`format` 这类关键字段也糊掉)。
     * 不修改传入对象(内部先拷一份)。
     */
    fun describeOutgoing(json: JsonObject): String {
        val copy = try {
            JsonParser.parseString(json.toString()).asJsonObject
        } catch (e: Exception) {
            // 解析不出来(理论上不会)也不能把日志搞崩:退回原文 —— 它已经是我们要发的字节。
            return json.toString()
        }
        redactSecrets(copy)
        return copy.toString()
    }

    /** 这些键(**不分大小写**、逐层递归)的值不写进日志明文。 */
    private val SECRET_KEYS = setOf("token", "password", "authorization", "challenge", "secret", "key")

    private fun redactSecrets(obj: JsonObject) {
        // 先取键快照:替换已存在键的值不是结构性修改,但快照最稳。
        for (key in obj.keySet().toList()) {
            val value = obj.get(key) ?: continue
            when {
                value.isJsonObject -> redactSecrets(value.asJsonObject)
                value.isJsonArray -> value.asJsonArray.forEach { el ->
                    if (el.isJsonObject) redactSecrets(el.asJsonObject)
                }
                value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                    key.lowercase() in SECRET_KEYS -> obj.addProperty(key, describeSecret(value.asString))
                else -> Unit
            }
        }
    }

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
