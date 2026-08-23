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
 * 关键:设备 MAC(Device-Id) + 标准 UUID(Client-Id) 是 OTA 握手必需;无 serial_number 时
 * 走 v1 激活(body={}),有 SN 走 v2(body= {algorithm,serial_number,challenge,hmac})。
 *
 * 激活成功后持久化 activated=true + websocket url/token,供 XiaozhiStt 连接使用。
 */
class XiaozhiActivator(
    private val context: Context,
    private val deviceMac: String,
    private val otaUrl: String,   // 默认 https://api.tenclass.net/xiaozhi/ota/
) {

    private val tag = "XiaozhiActivator"
    private val gson = Gson()

    private companion object {
        /** 小智 Client-Id(写死,与网页端注册的设备一致,保持稳定不变)。 */
        const val CLIENT_ID = "1dd91545-082a-454e-a131-1c8251375c9c"
        /** 小智 Device-Id(全零 MAC,服务器放行的匿名通道;真实 MAC 当前被 op=8 拒)。 */
        const val DEVICE_MAC = "00:00:00:00:00:00"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 激活结果。 */
    data class ActivationResult(
        val activated: Boolean,       // 已激活(可连 websocket 识别)
        val code: String? = null,     // 绑定码(未激活时给用户去网页绑定)
        val challenge: String? = null,
        val wsUrl: String? = null,    // websocket url(OTA 下发)
        val wsToken: String? = null,  // websocket token(OTA 下发)
        val message: String? = null,  // 服务器提示(如 "xiaozhi.me\n147063")
        val detail: String? = null,   // 失败原因等
    )

    /**
     * 一次完整激活检查:
     *  1. POST ota/ 拉配置 → 若含 activation(code+challenge)则未激活,返回 code 供用户绑定;
     *     若不含 activation 则已激活,返回 wsUrl/token。
     *  2. 若未激活,调用 [pollActivate] 轮询直到用户在网页绑定完成(服务器 200)。
     *
     * 这是阻塞式(挂起)直到激活完成或超时。设置页调它并展示 code。
     */
    suspend fun activateAndPoll(onCodeReady: (code: String, message: String) -> Unit): ActivationResult =
        withContext(Dispatchers.IO) {
            // Client-Id 写死为固定值(与网页端注册的设备一致),保持稳定不变。
            val clientId = CLIENT_ID

            // 1. OTA 拉取(Device-Id 用全零 MAC 匿名通道)
            val ota = postOta(otaUrl, DEVICE_MAC, clientId) ?: return@withContext ActivationResult(false, detail = "OTA 请求失败")
            val activation = ota.getAsJsonObject("activation")
            val wsUrl = ota.getAsJsonObject("websocket")?.get("url")?.asString
            val wsToken = ota.getAsJsonObject("websocket")?.get("token")?.asString

            if (activation == null || !activation.has("challenge")) {
                // 无 activation = 设备已授权,直接可用
                Log.i(tag, "OTA 无激活数据,设备已激活 ws=$wsUrl")
                return@withContext ActivationResult(true, wsUrl = wsUrl, wsToken = wsToken)
            }

            val code = activation.get("code")?.asString
            val challenge = activation.get("challenge")?.asString
            val message = activation.get("message")?.asString
            Log.i(tag, "需激活 code=$code message=$message")
            // 通知 UI:显示绑定码,让用户去 xiaozhi.me 绑定
            onCodeReady(code ?: "", message ?: "")

            // 2. 轮询 activate 直到 200(用户绑定完成后)
            val ok = pollActivate(otaUrl, DEVICE_MAC, clientId, challenge ?: "")
            return@withContext ActivationResult(
                activated = ok,
                code = code,
                challenge = challenge,
                wsUrl = wsUrl,
                wsToken = wsToken,
                message = message,
                detail = if (ok) null else "激活超时:请确认已在 xiaozhi.me 输入绑定码 $code",
            )
        }

    /** 轮询 ota/activate,直到服务器 200(用户已在网页绑定)或超时。 */
    private suspend fun pollActivate(otaUrl: String, mac: String, clientId: String, challenge: String): Boolean {
        val activateUrl = "${otaUrl.trimEnd('/')}/activate"
        // 无 serial_number → v1 激活,body={}(小智固件 GetActivationPayload 无SN返回{})
        val payload = JsonObject()
        // 若想走 v2,可构造 {algorithm,serial_number,challenge,hmac};这里先按 v1 简化。
        val body = payload.toString().toRequestBody(JSON)

        val maxMillis = 60_000L   // 最多等 60s(用户绑定时间)
        val interval = 3_000L     // 每 3s 轮询一次
        val headers = mapOf(
            "Activation-Version" to "1",   // 无 SN v1
            "Device-Id" to mac,
            "Client-Id" to clientId,
            "Content-Type" to "application/json",
        )
        var elapsed = 0L
        while (elapsed < maxMillis) {
            val status = withTimeoutOrNull(10_000) {
                postStatus(activateUrl, body, headers)
            } ?: -1
            Log.i(tag, "activate 轮询 status=$status")
            if (status == 200) {
                Log.i(tag, "设备激活成功! 用户已绑定")
                return true
            }
            // 202 = 仍在等用户绑定;其它 = 继续等
            delay(interval)
            elapsed += interval
        }
        return false
    }

    // ---- 底层请求 ----

    private fun postOta(baseUrl: String, mac: String, clientId: String): JsonObject? {
        val board = JsonObject().apply {
            addProperty("type", "lancelot")
            addProperty("name", "Passport")
            addProperty("ip", "127.0.0.1")
            addProperty("mac", mac)
        }
        val app = JsonObject().apply {
            addProperty("version", "0.1.0")
            addProperty("elf_sha256", "0000000000000000")
        }
        val body = JsonObject().apply {
            add("application", app)
            add("board", board)
        }
        val req = Request.Builder()
            .url(baseUrl)
            .addHeader("Device-Id", mac)
            .addHeader("Client-Id", clientId)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "lancelot/passport-0.1.0")
            .addHeader("Accept-Language", "zh-CN")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    JsonParser.parseString(resp.body?.string() ?: "").asJsonObject
                } else null
            }
        } catch (e: Exception) {
            Log.e(tag, "OTA 失败", e)
            null
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

    /** HMAC-SHA256 签名(challenge),供 v2 激活用(有 SN 时)。 */
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
