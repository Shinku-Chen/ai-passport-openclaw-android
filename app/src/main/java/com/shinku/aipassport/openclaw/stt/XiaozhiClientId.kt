package com.shinku.aipassport.openclaw.stt

import android.content.Context
import java.util.UUID

/**
 * 小智 `Client-Id` 的**唯一**生成与持久化点。
 *
 * 为什么必须持久化(真机问题):小智云按 **(client_id, device_id)** 这一对来签发/校验会话 ——
 * 开源服务端就是明证(`core/auth.py`: `AuthManager.generate_token(client_id, username)` 把两者拼进
 * 签名内容,握手时再用**当前请求里的那两个值**重算并比对;`websocket_server.py::_handle_auth` 取
 * `client-id` 头)。旧实现每次 App 启动都 `UUID.randomUUID()`,于是同一台已注册真设备
 * **OTA 用一个 client_id、识别 WS 用另一个** → 服务端「设备已登记,但这份 client_id 对不上」→
 * 升级 101 通过后立刻被切断(实测关闭码 1005、无 reason);而游客通道(全零匿名 MAC)压根没有登记记录,
 * 所以随机 client_id 也能用 —— 这正是「匿名能跑、真设备被拒」的分叉点。
 *
 * 官方固件的做法(`xiaozhi-esp32`,本对象与之对齐):
 *  - `main/boards/common/board.cc::Board::Board()`:`uuid_ = settings.GetString("uuid")`,
 *    为空才 `GenerateUuid()` 并 `SetString("uuid", uuid_)` **落盘**(NVS,`board` 命名空间);
 *  - `GenerateUuid()`:16 字节硬件随机数,按 RFC 4122 置版本位 `0x40`(v4)与变体位 `0x80`,
 *    格式化成 36 字符小写 **UUID v4**(`8-4-4-4-12`);
 *  - 同一份 uuid 同时用于 OTA(`ota.cc::SetupHttp` 的 `Client-Id` 头 + `board.cc::GetSystemInfoJson`
 *    的 `uuid` 字段)与识别 WS(`websocket_protocol.cc::OpenAudioChannel` 的 `Client-Id` 头)。
 * 也就是说:官方固件**一次生成、持久化、处处复用**,并且**跟设备身份(MAC/uuid)绑定**。
 *
 * 本对象把同一套语义搬到 App:按 [XiaozhiIdentity] 解析出来的 **Device-Id** 存一份 Client-Id,
 * OTA 请求、`ota/activate` 轮询、识别 WS 握手**共用同一个值**;App 重启 / 重连 / 重新绑定都复用;
 * 只有**设备身份变了**(换设备、或在小智真 MAC 与全零匿名之间切换)才换新值 —— 旧的 client_id
 * 属于旧身份,继续用会再次对不上。
 *
 * 本对象不依赖 Android(store 是 [XiaozhiKeyValueStore]),可在 JVM 单测里直接覆盖。
 */
object XiaozhiClientId {

    /** 独立 prefs 文件名:与凭据(`xiaozhi_credential`)分开,便于排查「本机存了什么」。 */
    const val PREFS = "xiaozhi_client_id"

    /** 本机这份 Client-Id **属于哪个设备身份**(Device-Id,即 [XiaozhiIdentity] 解析出来的值)。 */
    const val KEY_DEVICE_ID = "client_id_device_id"

    /** 本机当前生效的 Client-Id(UUID v4 的 36 字符小写形式)。 */
    const val KEY_CLIENT_ID = "client_id_uuid"

    /** 未注入持久化 store 时(单测/直连会话)的进程内兜底:仍然「一次生成、进程内复用」。 */
    private val processStore: XiaozhiKeyValueStore = InMemoryStore()

    /**
     * 取**这份身份**的 Client-Id:存过且身份一致 → 原样返回;首次 / 身份变了 → 生成一个新的 UUID v4
     * 并**落盘**(连同它所属的 Device-Id 一起存,便于本机排查与「换身份就换值」的判定)。
     *
     * 为什么按身份存而不是全局一份:小智云按 (client_id, device_id) 配对;识别通道在小智 AI
     * (真 MAC)与非小智网关(全零匿名)之间切换时,Device-Id 会变 —— 拿旧身份的 client_id 去握手
     * 与「换一台设备」是同一类错误。
     *
     * 线程安全([Synchronized]):OTA 请求与 WS 建链可能同时走到这里(预热 + 保存),必须只有一份值 ——
     * 否则两条路径各自生成一个,又回到「OTA 一个、WS 一个」。
     *
     * @param deviceId 小智侧 Device-Id([XiaozhiIdentity.resolve] 的结果:小智 AI = 设备真 MAC,
     *   其余网关 = 全零匿名)。**空串**不该走到这里(会话层取不到 Device-Id 时直接不建链);
     *   真被调到只生成、**不落盘**,免得拿空身份覆盖掉真设备的记录。
     */
    @Synchronized
    fun forDevice(deviceId: String, store: XiaozhiKeyValueStore): String {
        val want = deviceId.trim()
        if (want.isEmpty()) return newUuid()
        val storedDevice = store.getString(KEY_DEVICE_ID)?.trim().orEmpty()
        val storedId = store.getString(KEY_CLIENT_ID)?.trim().orEmpty()
        // MAC 大小写不敏感(归一化前后都算同一台设备),避免「大小写不同 = 又换一份 client_id」。
        if (storedId.isNotEmpty() && storedDevice.equals(want, ignoreCase = true)) return storedId
        val fresh = newUuid()
        store.put(KEY_DEVICE_ID, want)
        store.put(KEY_CLIENT_ID, fresh)
        return fresh
    }

    /**
     * 生产入口:Client-Id 落在独立 prefs [PREFS] 里,跨 App 重启 / 重连 / 重新绑定都复用同一个值。
     *
     * @param deviceId 同 [forDevice] 的 Device-Id
     */
    fun forDevice(context: Context, deviceId: String): String =
        forDevice(
            deviceId,
            SharedPrefsXiaozhiStore(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            ),
        )

    /**
     * 未注入持久化 store 时的兜底(进程内稳定):单测与直接构造会话的调用方用。
     * 生产链路([SttFactory] → [XiaozhiStt] → [XiaozhiSession]、[XiaozhiActivator])一律用持久化入口。
     */
    fun forDevice(deviceId: String): String = forDevice(deviceId, processStore)

    /** UUID v4 字符串(与官方固件 `Board::GenerateUuid()` 同格式:`java.util.UUID.randomUUID()` 即 v4)。 */
    private fun newUuid(): String = UUID.randomUUID().toString()

    /**
     * 日志用的 Client-Id 描述:**值脱敏**(长度 + 前 8 位),OTA 那一行与 WS 那一行都用它,
     * 于是「两处是不是同一个值」用眼睛就能比出来,而完整值不进日志。
     */
    fun describe(clientId: String?): String {
        val v = clientId?.trim().orEmpty()
        if (v.isEmpty()) return "未生成"
        return "len=${v.length},前8位=${v.take(8)}"
    }

    /** 单测/直连会话用的内存实现(与 [SharedPrefsXiaozhiStore] 同一契约)。 */
    private class InMemoryStore : XiaozhiKeyValueStore {
        private val map = HashMap<String, String>()

        override fun getString(key: String): String? = map[key]

        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }
}
