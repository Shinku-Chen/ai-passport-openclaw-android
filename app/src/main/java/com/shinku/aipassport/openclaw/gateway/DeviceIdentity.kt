package com.shinku.aipassport.openclaw.gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * OpenClaw 网关设备身份:ed25519 密钥对 + 持久化。
 *
 * 网关 connect 鉴权要求 device 携带 ed25519 签名(见 gateway protocol):
 *   payload = v2|<deviceId>|<clientId>|<clientMode>|<role>|<scopes>|<signedAtMs>|<token>|<nonce>
 *   signature = ed25519.sign(payload)
 * 关键(实测+官方文档):device.id 必须是【公钥指纹】= SHA-256(原始32字节公钥)的 hex(64位小写);
 * publicKey 必须是【Base64url(withoutPadding) 编码的原始32字节公钥】。之前用自定义 device_id +
 * 标准 Base64 编码,导致网关 DEVICE_AUTH_DEVICE_ID_MISMATCH(device.id 与公钥指纹对不上)。
 * 密钥对首次生成后存 SharedPreferences,deviceToken 在配对成功后由网关下发并持久化。
 */
class DeviceIdentity(context: Context) {

    private val prefs = context.getSharedPreferences("gateway_device", Context.MODE_PRIVATE)

    private var cachedPrivate: Ed25519PrivateKeyParameters? = null

    init {
        // 构造时即确保私钥 seed 生成并持久化(deviceId/公钥由同一私钥派生,跨重启稳定)。
        privateKey()
        Log.i("DeviceIdentity", "身份就绪 私钥已持久化")
    }

    /** 网关在 connect 成功后下发的 deviceToken(首次配对后非空)。 */
    var deviceToken: String
        get() = prefs.getString(KEY_DEVICE_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()
        }

    /** device.id = 公钥指纹(SHA-256(原始32字节公钥) 的 hex,64位小写)。 */
    val deviceId: String by lazy {
        val pub = privateKey().generatePublicKey().encoded
        MessageDigest.getInstance("SHA-256").digest(pub)
            .joinToString("") { "%02x".format(it) }
    }

    /** publicKey = Base64url(withoutPadding) 编码的原始32字节公钥(网关要求格式)。 */
    val publicKeyBase64: String by lazy {
        val pub = privateKey().generatePublicKey().encoded
        Base64.encodeToString(pub, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** 对字符串做 ed25519 签名,返回 base64(标准无填充,网关接受)。 */
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

    private fun newKey(): Ed25519PrivateKeyParameters {
        val seed = ByteArray(32)
        java.security.SecureRandom().nextBytes(seed)
        return Ed25519PrivateKeyParameters(seed, 0)
    }

    companion object {
        // device_id 不再持久化为字符串,而是由私钥公钥派生指纹(动态计算)。
        private const val KEY_PRIVATE_SEED = "private_seed"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }
}
