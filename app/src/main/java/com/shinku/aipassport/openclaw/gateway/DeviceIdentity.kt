package com.shinku.aipassport.openclaw.gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * OpenClaw 网关设备身份:ed25519 密钥对 + 持久化。
 *
 * 网关 connect 鉴权要求 device 携带 ed25519 签名(见 gateway-CWCQz7bR.js 的 le()):
 *   payload = v2|<deviceId>|<clientId>|<clientMode>|<role>|<scopes>|<signedAtMs>|<token>|<nonce>
 *   signature = ed25519.sign(payload)
 * 密钥对首次生成后存 SharedPreferences,deviceToken 在配对成功后由网关下发并持久化。
 */
class DeviceIdentity(context: Context) {

    private val prefs = context.getSharedPreferences("gateway_device", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: createDeviceId()

    private var cachedPrivate: Ed25519PrivateKeyParameters? = null

    init {
        // 构造时即确保完整身份生成并持久化:device_id + 私钥 seed 绑定。
        // 否则 App 重启后 privateKey() 重新生成新私钥 -> 公钥变化 -> 网关卡 approve 的设备指纹对不上。
        // 用 deviceId 兜底确保 seed 已写回(apply 是异步,这里顺带触发一次)。
        privateKey()
        Log.i("DeviceIdentity", "身份就绪 device_id=$deviceId 私钥已持久化")
    }

    /** 网关在 connect 成功后下发的 deviceToken(首次配对后非空)。 */
    var deviceToken: String
        get() = prefs.getString(KEY_DEVICE_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()
        }

    /** 返回 base64 公钥,并缓存私钥用于签名。 */
    val publicKeyBase64: String by lazy {
        val priv = privateKey()
        val pub = priv.generatePublicKey()
        Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
    }

    /** 对字符串做 ed25519 签名,返回 base64。 */
    fun sign(payload: String): String {
        val priv = privateKey()
        val signer = Ed25519Signer()
        signer.init(true, priv)
        val data = payload.toByteArray(StandardCharsets.UTF_8)
        signer.update(data, 0, data.size)
        return Base64.encodeToString(signer.generateSignature(), Base64.NO_WRAP)
    }

    private fun privateKey(): Ed25519PrivateKeyParameters {
        cachedPrivate?.let { return it }
        val cached = prefs.getString(KEY_PRIVATE_SEED, null)
        val key = if (cached != null) {
            val seed = Base64.decode(cached, Base64.NO_WRAP)
            Ed25519PrivateKeyParameters(seed, 0)
        } else {
            val k = newKey()
            val seed = k.getEncoded()
            // commit() 同步写入,确保 seed 立即落盘(apply 异步,App 重启前可能未写)。
            prefs.edit().putString(KEY_PRIVATE_SEED, Base64.encodeToString(seed, Base64.NO_WRAP)).commit()
            k
        }
        cachedPrivate = key
        return key
    }

    private fun createDeviceId(): String {
        // 设备 id 由 app 侧生成,格式满足网关 /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/
        val id = "ai-passport-android-${UUID.randomUUID().toString().take(8)}"
        // commit() 同步写入,确保 device_id 立即落盘稳定(与后续 seed 绑定)。
        prefs.edit().putString(KEY_DEVICE_ID, id).commit()
        Log.i("DeviceIdentity", "生成设备 id(已持久化): $id")
        return id
    }

    private fun newKey(): Ed25519PrivateKeyParameters {
        val seed = ByteArray(32)
        java.security.SecureRandom().nextBytes(seed)
        return Ed25519PrivateKeyParameters(seed, 0)
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PRIVATE_SEED = "private_seed"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }
}
