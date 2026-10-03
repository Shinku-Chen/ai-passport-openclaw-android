package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 小智设备激活器 —— 复刻 py-xiaozhi / 小智固件的"OTA → 手持绑定码 → activate 轮询 → 授权"流程。
 *
 * 官方小智强绑定"平台账号激活":首次接入要先走 OTA 拉取,拿到【绑定码(code) + challenge】,
 * 用户去 xiaozhi.me 网页登录 → 添加设备 → 输入绑定码完成绑定;随后 App 调 ota/activate
 * (带 challenge 的签名) 轮询,服务器在用户绑定完成后返回 200 = 激活成功。激活成功才允许
 * 连 websocket 发 hello 做识别。
 *
 * 关键:设备 MAC(Device-Id) + **持久化且唯一**的 Client-Id(见 [XiaozhiClientId])是 OTA 握手必需;
 * 无 serial_number 时走 v1 激活(body={}),有 SN 走 v2(body= {algorithm,serial_number,challenge,hmac})。
 * Client-Id 绝不能每次启动随机 —— 服务端按 (client_id, device_id) 签发/校验,OTA 与识别 WS 必须是同一个值。
 *
 * 这是查/绑的**唯一实现**:[queryCloud] 查云端激活状态,[pollActivate] 在需要绑定时轮询授权。
 * 保存「小智 AI」与手动「激活小智设备」都只调这两个方法(见 `SettingsFragment`),不另写一套请求。
 *
 * @param context 当前上下文(当前实现不用到;保留是为了后续若需 Toast/日志落地不改构造)。
 * @param gatewayType 当前网关类型(见 [XiaozhiIdentity.GATEWAY_XIAOZHI]):**只有**「小智 AI」才做设备绑定,
 *   其余网关压根不绑(它们只把小智当识别引擎,Device-Id 是匿名标识)。
 * @param deviceAddress 已连接对讲设备的蓝牙地址(原始值,可为 null):小智模式下由 [XiaozhiIdentity]
 *   归一化成 Device-Id(与小智识别的 WS 握手、设置页显示**同一套规则**)。
 *   小智云按这个值登记/绑定设备,所以既不能用手机侧标识代替,取不到时也只能停手报错 ——
 *   退回全零匿名 MAC 会把两台设备登记成同一台,绑定结果对当前设备无效。
 *   非小智模式下这个值不参与绑定(压根不绑)。
 * @param deviceFirmwareVersionProvider 设备固件版本(设备 hello 的 `fw`,如 `1.13`)的来源回调:
 *   OTA 上报的 `version` **优先**用它(设备真的在跑什么版本),取不到再回退 App 的 `versionName`,
 *   都没有才用 [XiaozhiOtaRequest.FALLBACK_VERSION]。默认 = 取不到。
 *
 * [queryCloud] 返回 websocket url/token 与绑定码:绑定码用于展示引导,
 * **url/token 是识别通道的凭据**,调用方必须落盘(见 [XiaozhiCredentialStore])—— 真 MAC 的识别链路
 * 靠它握手,丢掉它就会「绑定之后反而识别不到」。
 */
class XiaozhiActivator(
    private val context: Context,
    private val gatewayType: String,
    private val deviceAddress: String?,
    private val otaUrl: String,   // 默认 https://api.tenclass.net/xiaozhi/ota/
    private val deviceFirmwareVersionProvider: () -> String? = { null },
) {

    private val tag = "XiaozhiActivator"
    private val gson = Gson()

    /**
     * 本次要用的 Client-Id(与识别 WS 握手**同一个值**,见 [XiaozhiClientId])。
     *
     * 为什么每次现取而不是构造时定死:Client-Id 按 Device-Id 存(一台设备一份),而 Device-Id 是
     * 调用时才解析的;现取还保证「OTA 与 WS 拿到的是同一个持久化值」而不是两份随机 UUID。
     */
    private fun clientIdFor(mac: String): String = XiaozhiClientId.forDevice(context, mac)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // **硬超时**:callTimeout 覆盖一次请求的全过程(连接/重定向/写请求/读响应),不像
        // connect/read/write 那样只卡某一段;设置页还会再套一层同值的 withTimeoutOrNull。
        // 修的真机 bug:没有它时「查云端」可能长时间不返回,保存按钮永远停在「查询小智云端…」。
        .callTimeout(CLOUD_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** activate 轮询请求体的 media type(OTA 查激活的请求体已移到 [XiaozhiOtaRequest])。 */
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 本 App 的 `versionName`(运行时读取,不依赖 BuildConfig —— AGP 8 默认不生成它);读不到返回 null。
     */
    private fun appVersionName(): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: Exception) {
        Log.w(tag, "读取 App 版本号失败:${e.message}")
        null
    }

    /**
     * 本次 OTA 上报的版本号(**唯一**解析点见 [XiaozhiOtaRequest.version]):
     * 设备固件版本 → App `versionName` → [XiaozhiOtaRequest.FALLBACK_VERSION]。
     */
    private fun clientVersion(): String =
        XiaozhiOtaRequest.version(deviceFirmwareVersionProvider(), appVersionName())

    /**
     * 一次 OTA 查询的结果(纯数据):云端激活状态 + 绑定码/ws 信息 + 失败原因。
     *
     * 走到哪一步由 [state] 唯一决定 —— 保存闸门([XiaozhiBindGate.decide])直接按它分流,
     * 不再从 `code == null` / `detail == null` 这种间接迹象反推。
     */
    data class CloudQuery(
        val state: XiaozhiBindGate.CloudState,
        /** 本次查询实际用的设备 Device-Id(已归一化);不可建链时为 null。 */
        val mac: String? = null,
        val code: String? = null,      // 绑定码(未激活时给用户去网页绑定)
        val challenge: String? = null, // 轮询 activate 用的 challenge
        val message: String? = null,   // 服务器提示(如 "xiaozhi.me\n147063")
        val wsUrl: String? = null,     // websocket url(OTA 下发)
        val wsToken: String? = null,   // websocket token(OTA 下发)
        val detail: String? = null,    // 失败/不可用时的可读原因
    )

    /**
     * 查一次云端激活状态(**只发 OTA,不轮询**):
     *  - OTA 响应含 `activation`(code+challenge)→ 未激活,[CloudQuery.state] = [XiaozhiBindGate.CloudState.NeedsBinding];
     *  - OTA 响应不含 `activation` → 已激活,[CloudQuery.state] = [XiaozhiBindGate.CloudState.Activated];
     *  - 请求失败/超时/无网,或标识不可用 → [XiaozhiBindGate.CloudState.QueryFailed] + 可读 `detail`。
     *
     * 这是**保存「小智 AI」与手动激活共用的唯一查询入口**:用户点了保存就必须先真的问一次云端,
     * 不能拿本地 `bound_mac` 当依据(见 [XiaozhiBindGate])。
     *
     * **硬超时**:单次 OTA 由 `callTimeout`([CLOUD_QUERY_TIMEOUT_MS])封顶,调用方**还**在协程上套一层
     * 同值的超时 —— 两层合起来保证这个方法不会无限期不返回(否则保存按钮会永远停在「查询小智云端…」)。
     */
    suspend fun queryCloud(): CloudQuery = withContext(Dispatchers.IO) {
        // 标识与识别通道是**同一套规则**([XiaozhiIdentity]):只有网关类型「小智 AI」才用已连接设备的
        // 真实 MAC 做绑定;其余网关直接停手(它们只把小智当识别引擎,拿匿名标识去绑也绑不到本机);
        // 小智模式取不到设备地址同样停手 —— 不猜、不回退。三种情况都不发任何网络请求。
        val mac = when (val identity = XiaozhiIdentity.resolve(gatewayType, deviceAddress)) {
            is XiaozhiIdentity.Resolution.DeviceMac -> identity.deviceId
            XiaozhiIdentity.Resolution.Anonymous -> return@withContext CloudQuery(
                XiaozhiBindGate.CloudState.QueryFailed,
                detail = XiaozhiIdentity.bindingNotApplicableReason(gatewayType),
            )
            is XiaozhiIdentity.Resolution.Unavailable -> return@withContext CloudQuery(
                XiaozhiBindGate.CloudState.QueryFailed,
                detail = identity.reason,
            )
        }
        // Client-Id:/**唯一**来源 [XiaozhiClientId](按 Device-Id 持久化)—— 与识别 WS 握手同一个值。
        val clientId = clientIdFor(mac)
        val ota = postOta(otaUrl, mac, clientId) ?: return@withContext CloudQuery(
            XiaozhiBindGate.CloudState.QueryFailed,
            mac = mac,
            detail = "OTA 请求失败:没连上小智服务器(网络不可用/超时/服务器无响应)。",
        )
        val activation = ota.getAsJsonObject("activation")
        val wsUrl = ota.getAsJsonObject("websocket")?.get("url")?.asString
        val wsToken = ota.getAsJsonObject("websocket")?.get("token")?.asString

        // 取证:把两类响应(未激活含 activation / 已激活无 activation)的**键名与结构**都打进日志。
        // 凭据类字段(token/password…)只打长度与前 4 位,明文绝不进日志(见 [XiaozhiOtaRequest.summarize])。
        Log.i(
            tag,
            "OTA 响应结构(${if (activation == null) "已激活:无 activation 段" else "未激活:含 activation 段"}):\n" +
                XiaozhiOtaRequest.summarize(ota),
        )
        Log.i(
            tag,
            "OTA 下发 websocket: url=${wsUrl ?: "未下发"}" +
                " token=${XiaozhiOtaRequest.describeSecret(wsToken)}",
        )

        if (activation == null || !activation.has("challenge")) {
            // 无 activation = 设备已授权,直接可用
            Log.i(tag, "OTA 无激活数据,设备已激活 ws=$wsUrl")
            return@withContext CloudQuery(
                XiaozhiBindGate.CloudState.Activated,
                mac = mac,
                wsUrl = wsUrl,
                wsToken = wsToken,
            )
        }

        val code = activation.get("code")?.asString
        val challenge = activation.get("challenge")?.asString
        val message = activation.get("message")?.asString
        Log.i(tag, "云端未激活 code=$code message=$message")
        CloudQuery(
            XiaozhiBindGate.CloudState.NeedsBinding,
            mac = mac,
            code = code,
            challenge = challenge,
            message = message,
            wsUrl = wsUrl,
            wsToken = wsToken,
        )
    }

    /**
     * 轮询 ota/activate,直到服务器 200(用户在 xiaozhi.me 绑定完成)或超时(60s)。
     *
     * **只有** [queryCloud] 返回 [XiaozhiBindGate.CloudState.NeedsBinding] 时才调用 ——
     * 已激活的设备不该被再按一遍。返回值 = 用户是否真的完成了绑定。
     */
    suspend fun pollActivate(mac: String, challenge: String): Boolean = withContext(Dispatchers.IO) {
        // 轮询用的是与这次 OTA **同一个** Client-Id(同一个来源 [XiaozhiClientId] 现取)。
        pollActivateLoop(otaUrl, mac, clientIdFor(mac), challenge)
    }

    /** 轮询 ota/activate,直到服务器 200(用户已在网页绑定)或超时。 */
    private suspend fun pollActivateLoop(otaUrl: String, mac: String, clientId: String, challenge: String): Boolean {
        val activateUrl = "${otaUrl.trimEnd('/')}/activate"
        // 无 serial_number → v1 激活,body={}(小智固件 GetActivationPayload 无SN返回{})
        val payload = JsonObject()
        // 若想走 v2,可构造 {algorithm,serial_number,challenge,hmac};这里先按 v1 简化。
        val body = payload.toString().toRequestBody(JSON)

        val maxMillis = ACTIVATE_WAIT_MS   // 最多等 60s(用户绑定时间)
        val interval = ACTIVATE_POLL_INTERVAL_MS     // 每 3s 轮询一次
        // 官方固件的激活轮询走的是**同一个** `Ota::SetupHttp()`,所以这里也带全套同样的头
        // (User-Agent / Accept-Language 之前漏了;Device-Id + Client-Id 与 OTA 请求同值)。
        val headers = mapOf(
            "Activation-Version" to "1",   // 无 SN v1
            "Device-Id" to mac,
            "Client-Id" to clientId,
            "User-Agent" to XiaozhiOtaRequest.userAgent(clientVersion()),
            "Accept-Language" to XiaozhiOtaRequest.LANGUAGE,
            "Content-Type" to "application/json",
        )
        // 用**真实墙钟**做 60s 预算(旧实现只把 interval 相加,单次请求阻塞多久都不计入,
        // 弹窗里承诺的「最多等 60 秒」会名不副实)。
        val deadlineAt = System.currentTimeMillis() + maxMillis
        while (true) {
            val status = withTimeoutOrNull(ACTIVATE_REQUEST_TIMEOUT_MS) {
                postStatus(activateUrl, body, headers)
            } ?: -1
            Log.i(tag, "activate 轮询 status=$status")
            if (status == 200) {
                Log.i(tag, "设备激活成功! 用户已绑定")
                return true
            }
            val remain = deadlineAt - System.currentTimeMillis()
            if (remain <= 0) break
            // 202 = 仍在等用户绑定;其它 = 继续等
            delay(minOf(interval, remain))
        }
        return false
    }

    companion object {
        /**
         * 单次**云端查询(OTA)**的硬超时(毫秒)。
         *
         * 两个地方共用同一个值,保证「查云端」不可能无限期不返回:
         *  - 本类 OkHttp 的 `callTimeout`(管住阻塞式的 HTTP 请求全过程);
         *  - 设置页协程上的 `withTimeoutOrNull`(管住协程侧的调度/取消)。
         * 超时后调用方**不落盘**,并给用户可读文案(见 `XiaozhiSaveStatus.QUERY_TIMEOUT_REASON`)。
         */
        const val CLOUD_QUERY_TIMEOUT_MS = 10_000L

        /** 单次 activate 轮询请求的超时(协程侧兜底;HTTP 侧由 `callTimeout` 先兜住)。 */
        private const val ACTIVATE_REQUEST_TIMEOUT_MS = 10_000L

        /** 等待用户完成网页绑定的最长时长(墙钟)。 */
        private const val ACTIVATE_WAIT_MS = 60_000L

        /** 绑定时 activate 的轮询间隔。 */
        private const val ACTIVATE_POLL_INTERVAL_MS = 3_000L
    }

    // ---- 底层请求 ----

    private fun postOta(baseUrl: String, mac: String, clientId: String): JsonObject? {
        // 请求体/User-Agent/版本号只有一处实现(见 [XiaozhiOtaRequest]):name=ai-passport、
        // version=当前版本(设备固件版本优先),不再各写一份写死的 0.1.0/Passport。
        val version = clientVersion()
        val req = XiaozhiOtaRequest.request(baseUrl, mac, clientId, version)
        Log.i(
            tag,
            "OTA 请求 device=$mac Client-Id=${XiaozhiClientId.describe(clientId)}" +
                " name=${XiaozhiOtaRequest.APP_NAME} version=$version" +
                " User-Agent=${XiaozhiOtaRequest.userAgent(version)}",
        )
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    JsonParser.parseString(text).asJsonObject
                } else {
                    // 非 200 也要留证据(旧实现直接返回 null,查不出是 401 还是 5xx);
                    // 响应体同样**不原样进日志**:能解析就只打键名/结构(凭据字段已脱敏),否则只打长度。
                    Log.w(tag, "OTA 失败 HTTP ${resp.code}: ${describeFailureBody(text)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "OTA 失败", e)
            null
        }
    }

    /**
     * 非 200 响应体的日志描述:能解析成 JSON 就给键名/结构(凭据字段已脱敏),
     * 否则只给长度 —— 失败响应的正文也不把 token 明文写进日志。
     */
    private fun describeFailureBody(text: String): String {
        if (text.isBlank()) return "(空响应体)"
        return try {
            "长度 ${text.length},\n" + XiaozhiOtaRequest.summarize(JsonParser.parseString(text).asJsonObject)
        } catch (e: Exception) {
            "非 JSON 响应体(长度 ${text.length})"
        }
    }

    private fun postStatus(url: String, body: okhttp3.RequestBody, headers: Map<String, String>): Int {
        val builder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return try {
            client.newCall(builder.build()).execute().use { it.code }
        } catch (e: Exception) {
            Log.e(tag, "activate 请求异常", e)
            -1
        }
    }

    /** HMAC-SHA256 签名(challenge),供 v2 激活用(有 SN 时)。当前未接线,保留以备 v2。 */
    @Suppress("unused")
    private fun hmacSha256(key: String, msg: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
            mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(tag, "HMAC 失败", e)
            ""
        }
    }
}
