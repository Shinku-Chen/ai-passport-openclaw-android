package com.shinku.aipassport.openclaw.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.widget.EditText
import androidx.core.content.ContextCompat

/**
 * BLE 中央端:扫描 Passport-* 设备 → 连接 → 加密配对(用户输入固件屏幕随机密码)→ 发现 NUS →
 * 订阅 TX 通知 → 把帧字节写入 RX。所有 GATT 操作在专用 HandlerThread 上串行执行。
 *
 * 配对:固件为 LE SC + MITM + bonding,配对时生成随机 6 位密码并在小屏显示。中央端收到
 * PIN / PASSKEY / DISPLAY_PASSKEY 输入请求时弹出输入框,用户按固件屏幕显示的密码输入后
 * **只调 setPin**(输入类变体不能再调 setPairingConfirmation,否则配对会卡在加密阶段);
 * 只有 PASSKEY_CONFIRMATION(两侧显示同一数字需确认)才自动 setPairingConfirmation(true)。
 */
// 重连/超时策略见 [LinkRetryPolicy](纯逻辑,已单测):前几次直连记住的地址(快路),
// 连续失败后退避 + 扫描(慢路)。

@SuppressLint("MissingPermission")
class BleCentral(
    private val context: Context,
    private val listener: Listener,
    /**
     * 配对输入框宿主:返回可弹 AlertDialog 的 Activity 上下文。
     * Service 上下文中窗口 token 缺失,AlertDialog 无法弹出;由宿主(如 AppActivity)
     * 提供当前前台 Activity,App 不在前台时返回 null(跳过弹框,配对超时)。
     */
    private val pairingDialogContext: () -> Context? = { null },
) {

    interface Listener {
        fun onConnecting()
        fun onConnected()
        fun onEncrypted()
        /** 服务已发现 + TX 已订阅,可收发帧 */
        fun onReady()
        fun onBytesReceived(bytes: ByteArray)
        fun onDisconnected()
        fun onError(message: String)
    }

    private val tag = "BleCentral"
    private val bleHandlerThread = HandlerThread("ble-central").apply { start() }
    private val bleHandler = Handler(bleHandlerThread.looper)

    /** 弹配对输入框必须 post 到主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 固件屏幕展示的配对码固定为 6 位数字 */
    private val pairingPinRegex = Regex("\\d{6}")

    /** 正在展示的配对码输入框;配对请求可能重发,避免对话框堆叠 */
    private var pairingDialog: AlertDialog? = null

    companion object {
        /**
         * EXTRA_PAIRING_VARIANT 取值(@hide 常量,需自行定义,值与 AOSP
         * android.bluetooth.BluetoothDevice 一致):
         *  PIN=0 / PASSKEY=1 / PASSKEY_CONFIRMATION=2 / CONSENT=3 /
         *  DISPLAY_PASSKEY=4 / DISPLAY_PIN=5。公开常量仅 PIN 与 PASSKEY_CONFIRMATION。
         */
        private const val PAIRING_VARIANT_PASSKEY = 1
        private const val PAIRING_VARIANT_DISPLAY_PASSKEY = 4

        /** 上次连接成功设备的地址(SharedPreferences key,用于启动自动重连)。 */
        private const val KEY_LAST_DEVICE = "last_device_addr"

        /** BLE 状态 prefs 文件名(实例与静态读法共用,保证看到同一个地址)。 */
        private const val PREFS = "ble_central"

        /**
         * 上次连接设备的地址(**不持有 [BleCentral] 实例的调用方用**,如设置页的「小智识别」
         * 与 STT 工厂):设备页/激活页都用这一个来源,避免各自去读 prefs 键名而写错。
         *
         * 与实例方法 [lastConnectedAddr] 读同一份 prefs;返回 null 表示从未连过设备。
         */
        fun lastConnectedAddr(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_DEVICE, null)
    }

    private var scanner: BluetoothLeScanner? = null
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var running = false

    /** 物理链路是否已连上(用于连接超时判定:`gatt != null` 只代表"发起过连接")。 */
    private var linkUp = false

    /** 连续"直连记住的地址"失败次数:少量快速重试后改为退避 + 扫描。 */
    private var directRetryCount = 0

    /** 连接超时任务(连上或掉线时取消)。 */
    private var connectTimeout: Runnable? = null

    /**
     * 单飞保护:已有连接在进行(或已连上)时,忽略重复的连接请求。
     *
     * 真机回归:rescan() 与“启动后自动重连”会几乎同时发起连接,
     * 同一个设备上出现两个 BluetoothGatt 客户端、两条 onConnectionStateChange 回调,
     * 加密/配对状态机被搅乱 —— App 就一直停在「已连接,等待加密」。
     */
    private var connectInFlight = false

    /** code=1(SCAN_FAILED_ALREADY_STARTED) 连续次数:扫到设备后重置,见 [ScanRetry]。 */
    private var alreadyStartedAttempts = 0

    /** 上次连接设备地址持久化(记住设备,App 重启后自动重连)。 */
    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 上次连接成功的设备地址(持久化,App 重启后用于自动重连)。 */
    private val lastDeviceAddr: String?
        get() = prefs.getString(KEY_LAST_DEVICE, null)

    /** 供外部(如小智激活)读取上次连接的设备蓝牙 MAC。 */
    fun lastConnectedAddr(): String? = lastDeviceAddr

    /** BLE 是否当前已连接(供连接监控器定时检测)。 */
    fun isConnected(): Boolean = gatt != null

    private fun rememberDevice(addr: String) {
        prefs.edit().putString(KEY_LAST_DEVICE, addr).apply()
    }

    /**
     * 当前设备档案（UUID / 广播名前缀 / MTU / 配对方式 / 帧格式 / 音频参数）。
     *
     * 取 [DeviceProfiles.default]（目前等于唯一的 AI Passport 档案），行为与重构前一致；
     * 以后支持多设备时改为“按扫描结果 / 用户选择”切换该属性即可，扫描与连接逻辑不用改。
     */
    private val profile: DeviceProfile = DeviceProfiles.default

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
    private val scanFilter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid(profile.serviceUuid))
        .build()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name
            // 兜底:即便广播过滤失效,也按名字前缀再过滤一次(前缀与大小写敏感规则来自设备档案)
            if (!profile.matchesName(name)) return
            // 只认上次连过的那台:现场常有多台同名 Passport-* 同时在广播(别人的、备用机)。
            // 只按名字前缀连会随到随连 —— 连到别人那台后对方唯一的连接槽已满,只能干等 10s 超时,
            // 再扫描再连错,用户体感就是“设备重启后自动连接特别慢”(真机:关掉旁边那台立刻就好)。
            // “忘记设备”会清掉记住的地址 → 那种情况下什么都能连,不影响配对/换机。
            if (!ScanPick.shouldConnect(lastDeviceAddr, device.address)) {
                Log.i(tag, "发现同名设备 ${device.address} $name:不是上次那台($lastDeviceAddr),忽略")
                return
            }
            Log.i(tag, "发现设备 ${device.address} $name")
            alreadyStartedAttempts = 0   // 扫到了:重置 code=1 计数
            stopScan()
            connectTo(device)
        }

        override fun onScanFailed(errorCode: Int) {
            val attemptsBefore = alreadyStartedAttempts
            if (errorCode == ScanRetry.ERROR_ALREADY_STARTED) alreadyStartedAttempts++
            Log.e(tag, "扫描失败 code=$errorCode(已连续 ALREADY_STARTED $alreadyStartedAttempts 次)")
            // code=1(SCAN_FAILED_ALREADY_STARTED)不是致命错误:通常是上一轮扫描没停就再 startScan。
            // 旧实现不看错误码一律退避 8s 再 startScan → 永远 code=1 自锁,必须重启 App 才能恢复。
            val decision = ScanRetry.decide(errorCode, attemptsBefore)
            if (ScanRetry.shouldSurfaceToUser(errorCode, alreadyStartedAttempts)) {
                listener.onError("BLE 扫描失败(code=$errorCode)")
            }
            if (decision.needsStopScan) stopScan()
            val delay = decision.retryDelayMs ?: return
            if (running) scheduleReconnect(backoffMs = delay)
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val target = targetDevice
                    Log.i(tag, "绑定状态变化 ${device?.address} state=${device?.bondState} target=${target?.address}")
                    if (device == null || target == null) return
                    if (device.address != target.address) return
                    when (device.bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            Log.i(tag, "配对完成")
                            listener.onEncrypted()
                            val g = gatt ?: return
                            bleHandler.post {
                                if (g.services.isNullOrEmpty()) g.discoverServices()
                                else requestMtuAndSubscribe(g)
                            }
                        }
                        BluetoothDevice.BOND_NONE -> listener.onError("配对失败")
                        else -> Unit
                    }
                }
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val variant = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_PIN
                    )
                    // 先记日志再判断:这对“配对没弹框、一直等加密”是唯一的现场证据。
                    Log.i(tag, "收到配对请求 ${device?.address} variant=$variant target=${targetDevice?.address}")
                    if (device == null) return
                    // 真机回归:以前是 `targetDevice ?: return`,一旦 target 暂时为空(例如刚 rescan 过)
                    // 配对广播就被静默丢弃 —— 手机不会弹输入框,设备也等不到加密,两边一起卡住。
                    // 配对请求是系统广播,这里只拦“别的设备”,target 为空时直接采纳该设备。
                    val target = targetDevice
                    if (target != null && device.address != target.address) return
                    if (target == null) targetDevice = device
                    when (variant) {
                        // 固件无键盘,由中央端输入其小屏显示的 6 位随机密码
                        BluetoothDevice.PAIRING_VARIANT_PIN,
                        PAIRING_VARIANT_PASSKEY,
                        PAIRING_VARIANT_DISPLAY_PASSKEY,
                        -> requestPairingPin(device, showHint = variant != BluetoothDevice.PAIRING_VARIANT_PIN)

                        // 两侧显示相同 6 位码(中央端/对端各自动确认),无需人工输入
                        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> {
                            device.setPairingConfirmation(true)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(tag, "已连接 status=$status")
                    linkUp = true
                    connectInFlight = false
                    cancelConnectTimeout()
                    directRetryCount = 0
                    listener.onConnected()
                    ensureEncryption(g)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(tag, "已断开 status=$status")
                    // 断开(含设备重启、直连超时)统一走重连策略,不再固定退避 8 秒 + 重新扫描。
                    onLinkDown()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("服务发现失败 status=$status")
                return
            }
            requestMtuAndSubscribe(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // 记下**实际协商到**的 MTU:写分片长度按它动态取(见 [WriteChunking]),不再写死 240。
            // 协商失败(非 SUCCESS)时不猜这个值 —— 置空 = 回退 [WriteChunking.FALLBACK_CHUNK]。
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS && mtu > 0) mtu else null
            Log.i(tag, "MTU=$mtu status=$status → 单次 ATT 写片长=${WriteChunking.chunkSize(negotiatedMtu)}")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val bytes = characteristic.value ?: return
            // 值缓冲会被复用,必须拷贝后再交上层
            listener.onBytesReceived(bytes.copyOf())
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.characteristic.uuid == profile.txUuid && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(tag, "TX 已订阅")
                listener.onReady()
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("CCCD 写入失败 status=$status")
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            bleHandler.post { finishWriteCallback(status) }
            if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
                status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
            ) {
                // 加密态过期/丢失 → 重新触发配对
                Log.w(tag, "写入需要加密,重新配对")
                targetDevice?.createBond()
            }
        }
    }

    fun start() {
        if (running) return
        if (!hasBlePermissions()) {
            listener.onError("缺少 BLE 权限")
            return
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("蓝牙未开启")
            return
        }
        this.adapter = adapter
        scanner = adapter.bluetoothLeScanner
        running = true
        registerPairingReceiver()
        // 不再自动扫描:在用户于设备页点击"扫描"(ACTION_SCAN -> rescan)时才启动,
        // 避免 App 启动即高频扫描被 Android 拒绝(扫描失败 code=1 死循环)。
        // 若记住过设备(App 重启),直接按地址重连,保证"退出重启后仍连接"。
        val last = lastDeviceAddr
        if (last != null) {
            Log.i(tag, "自动重连上次设备 $last")
            try {
                connectTo(adapter.getRemoteDevice(last))
            } catch (e: Exception) {
                Log.e(tag, "自动重连失败(地址不存在?): $last", e)
            }
        }
    }

    /** 设备页触发:重新开始扫描(断开当前连接,重新发现)。 */
    fun rescan() {
        bleHandler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        targetDevice = null
        connectInFlight = false   // 手动重扫:丢掉上一次的单飞状态,避免永久拦住新连接
        // 旧实现只有 `if (running) startScan()`:
        // 用户点过「断开设备」后 BleCentral.stop() 已把 running 置 false,
        // 于是「断开 → 再点扫描」是空操作,只能重启 App 才恢复(真机复现过)。
        // 这里补回:没在跑就重新 start()(它会重新拿 scanner/权限检查),然后才扫描。
        if (!running) {
            Log.i(tag, "rescan(): 链路未运行,先重新初始化")
            start()
        }
        if (running) {
            startScan()
        } else {
            listener.onError("蓝牙未就绪,无法扫描")
        }
    }

    /**
     * 忘记设备:停链路、清掉记住的地址，并尽力解除系统绑定。
     *
     * Android 不给普通 App 直接取消配对的 API（`removeBond` 是 @SystemApi），这里反射尝试一次，
     * 失败也不影响功能 —— 界面会同时告诉用户去系统蓝牙里取消配对（或直接在设备上长按 UP → 重新配对）。
     */
    fun forgetDevice() {
        val addr = lastDeviceAddr ?: targetDevice?.address
        stop()
        prefs.edit().remove(KEY_LAST_DEVICE).apply()
        if (addr == null) return
        try {
            val dev = adapter?.getRemoteDevice(addr) ?: return
            if (dev.bondState != BluetoothDevice.BOND_NONE) {
                val ok = dev.javaClass.getMethod("removeBond").invoke(dev) as? Boolean ?: false
                Log.i(tag, "忘记设备:$addr removeBond=$ok")
            }
        } catch (e: Exception) {
            Log.w(tag, "removeBond 不可用(需用户到系统蓝牙里取消配对):${e.message}")
        }
    }

    fun stop() {
        running = false
        bleHandler.removeCallbacksAndMessages(null)
        stopScan()
        try {
            context.unregisterReceiver(bondReceiver)
        } catch (_: IllegalArgumentException) {
            // 未注册过,忽略
        }
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        targetDevice = null
        connectInFlight = false
    }

    /**
     * Idle power-save flag: true = LOW_POWER connection interval requested.
     *
     * Why (power saving, 2026-10): keeping CONNECTION_PRIORITY_HIGH (~11-15ms) while idle wakes the
     * device 60+ times per second, so it can never enter light sleep. Idle -> LOW_POWER (wider
     * interval + slave latency), any activity -> back to HIGH. Only connection parameters change:
     * data path, write mode and flow control are untouched.
     */
    @Volatile
    private var idleLowPower = false

    /** Switch idle/performance connection parameters. Returns true when the state changed. */
    fun setIdleLowPower(lowPower: Boolean): Boolean {
        if (lowPower == idleLowPower) return false
        idleLowPower = lowPower
        val g = gatt ?: return true
        // 空闲用 BALANCED 而不是 LOW_POWER:真机反馈「第一句语音的前半部分丢失 + 有延迟」——
        // LOW_POWER(很宽的间隔 + 允许从机延迟)会让一段音频突发的**前几帧**既慢又容易丢;
        // BALANCED(间隔约 30ms)依旧比 11-15ms 省得多,但突发不会丢头。
        val prio = if (lowPower) BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        else BluetoothGatt.CONNECTION_PRIORITY_HIGH
        runCatching {
            g.requestConnectionPriority(prio)
            Log.i(tag, "conn params: " + (if (lowPower) "idle low power" else "high performance"))
        }
        return true
    }

    @Volatile

    /**
     * 协商到的 ATT MTU(null = 还没协商到或协商失败)。
     * 写分片长度由它算:`min(mtu − 3, 253)`,协商失败回退 240(见 [WriteChunking])。
     */

    private var negotiatedMtu: Int? = null

    /**
     * 单块写入等待 onCharacteristicWrite 回调的上限。
     * 原来 500ms 在设备忙(渲染长文本/音频)或手机射频排队时会误判超时,
     * 实测长回复(1271B,6 块)会在第一块就报「写回调超时 status=257」而整帧丢弃。
     */
    private val WRITE_CALLBACK_TIMEOUT_MS = 2000L

    /** 单块重试次数:丢回调/设备忙很常见,重试一次比把整条回复丢掉合理。 */
    private val MAX_CHUNK_RETRIES = 1

    /** 写入调用失败(非 GATT 回调失败)的同一块最大重试次数与间隔。 */
    private val MAX_CALL_RETRIES = 3
    private val CALL_RETRY_DELAY_MS = 120L
    private var chunkRetries = 0

    /**
     * `writeCharacteristic()` **调用本身** 失败的短退避重试计数(同一块)。
     *
     * 真机实测:按下 OK 那一瞬间的第一枪常因设备忙/协议栈抖动而失败,
     * 旧实现【直接丢整帧】→ `turn_ready` 要等上层约 4 秒重发才到设备 → 「等好久才变绿」。
     * 这里改成同一块短退避重试,超限才丢整帧。
     */
    private var callFailRetries = 0

    /** 所有 App→设备逻辑帧共用串行队列,避免长回复分片交错。 */
    private val writeQueue = ArrayDeque<ByteArray>()

    /**
     * 已**送达**设备的下行逻辑帧数(以 GATT 写回调为准,不是“已入队”)。
     *
     * 下行 TTS 的流控靠它算“在途帧数”:入队快、送达慢时手机队列会积压,
     * 只看入队数会把领先量算高,设备侧其实早已空库→播放一卡一卡(真机实测)。
     */
    @Volatile
    private var deliveredFrames: Long = 0

    /** 已送达设备的逻辑帧数快照(TTS 流控用)。 */
    fun deliveredFrameCount(): Long = deliveredFrames

    /**
     * 批量写模式:**打开后用 `WRITE_NO_RESPONSE`,关闭时恢复带响应写**。
     *
     * 只是「写类型选择」的开关,不能保证送达:2026-10 真机 A/B(同一手机 + 同一固件)表明
     * **本机/本固件组合上无响应写会被静默丢弃** —— 下行音频帧走无响应写时,App 侧报「写入成功
     * N 帧」而设备侧 `TTS` 计数恒为 0、`RX 缓冲满` 计数也为 0(不是 ring 溢出,是根本没到)。
     * 因此小智直通已固定为带响应写(`VoiceBridgeService.drainXiaozhiTts`,不再打开这个开关);
     * 本地合成那条路仍打开(**待真机复核:可能同样静默丢帧**)。
     *
     * 旧注释里「带响应写是串行的,一次连接事件只能推一个包 → 仅 11 帧/秒」已被实测推翻:
     * 带响应写实测 207 帧 / 11.2s ≈ **18.5 帧/秒**,高于 16kHz/60ms 实时所需的 16.7 帧/秒。
     */
    @Volatile
    private var bulkWrite: Boolean = false

    /** 开启/关闭批量写模式(下行朗读开始/结束时调,幂等)。 */
    fun setBulkWrite(enabled: Boolean) {
        if (bulkWrite == enabled) return
        bulkWrite = enabled
        Log.i(tag, "批量写模式: $enabled")
    }

    /**
     * 当前写模式(只读,仅用于日志取证):true = 音频帧用的无响应写(Write Command)。
     *
     * 为什么要给外面看:正文分片与音频帧同处一条串行写队列,若文字还没写出去就切到了无响应写,
     * 那些**文本**帧也会走 Write Command —— 而 Write Command 不支持 Long Write 分片,
     * 实际 MTU 不够时会被对端静默丢弃(真机可能出现「时间顺序对、但设备屏没有字」)。
     * 这一行(与 `writeBytes 入队/逻辑帧完成` 两行)能把这种形态区分开。
     */
    fun isBulkWrite(): Boolean = bulkWrite
    private var currentWrite: ByteArray? = null
    private var currentOffset = 0
    private var writeInProgress = false
    private var writeGeneration = 0L
    private var pendingWriteContinuation: ((Int) -> Unit)? = null
    private var pendingWriteTimeout: Runnable? = null

    /** 把一帧完整字节加入队列,同一帧的所有切片连续发送。 */
    fun writeBytes(data: ByteArray) {
        bleHandler.post {
            if (gatt == null) {
                Log.w(tag, "writeBytes: gatt 为 null,丢弃")
                return@post
            }
            writeQueue.addLast(data.copyOf())
            Log.i(tag, "writeBytes 入队 字节=${data.size} 队列=${writeQueue.size}")
            pumpWriteQueue()
        }
    }

    /** 回复中重新 PTT 时丢弃旧的未发送帧,避免旧 A 分片占用 GATT。 */
    fun clearPendingWrites() {
        bleHandler.post {
            writeQueue.clear()
            currentWrite = null
            currentOffset = 0
            writeInProgress = false
            pendingWriteContinuation = null
            pendingWriteTimeout?.let { bleHandler.removeCallbacks(it) }
            pendingWriteTimeout = null
            writeGeneration++
            Log.i(tag, "清理 BLE 待写队列 generation=$writeGeneration")
        }
    }

    /** 由 GATT 写回调推进当前逻辑帧。 */
    private fun finishWriteCallback(status: Int) {
        pendingWriteTimeout?.let { bleHandler.removeCallbacks(it) }
        pendingWriteTimeout = null
        val continuation = pendingWriteContinuation
        pendingWriteContinuation = null
        continuation?.invoke(status)
    }

    /** 按完整逻辑帧串行切片,每片等待 onCharacteristicWrite 回调。 */
    private fun pumpWriteQueue() {
        if (writeInProgress) return
        if (currentWrite == null) {
            currentWrite = writeQueue.removeFirstOrNull() ?: return
            currentOffset = 0
            chunkRetries = 0
            callFailRetries = 0
        }
        val g = gatt ?: run { currentWrite = null; return }
        val service = g.getService(profile.serviceUuid) ?: run { currentWrite = null; return }
        val rx = service.getCharacteristic(profile.rxUuid) ?: run { currentWrite = null; return }
        val frame = currentWrite ?: return
        if (currentOffset >= frame.size) {
            Log.i(tag, "writeBytes 逻辑帧完成 字节=${frame.size}")
            deliveredFrames++   // 以 GATT 写回调为准的“已送达”计数(TTS 流控用它算在途量)
            currentWrite = null
            pumpWriteQueue()
            return
        }

        // 片长按协商到的 MTU 动态取(协商失败回退 240):小帧因此可能一次 ATT 写就发完。
        // 每片都重新取值:帧跨片写入期间 MTU 变了也只是下一片跟着变,offset 记账不受影响。
        val end = minOf(currentOffset + WriteChunking.chunkSize(negotiatedMtu), frame.size)
        val slice = frame.copyOfRange(currentOffset, end)
        val offset = currentOffset
        if (!writeOne(rx, slice)) {
            // 调用本身失败:同一块短退避重试(不前进 offset→ 下次还是这块);超限才丢整帧。
            callFailRetries++
            if (callFailRetries <= MAX_CALL_RETRIES) {
                Log.w(
                    tag,
                    "GATT 写入调用失败 offset=$offset/${frame.size}," +
                        "${CALL_RETRY_DELAY_MS}ms 后重试第 $callFailRetries 次",
                )
                bleHandler.postDelayed({ pumpWriteQueue() }, CALL_RETRY_DELAY_MS)
            } else {
                Log.e(
                    tag,
                    "GATT 写入调用失败 offset=$offset/${frame.size}(已重试 $callFailRetries 次,丢弃本帧)",
                )
                callFailRetries = 0
                currentWrite = null
                currentOffset = 0
                pumpWriteQueue()
            }
            return
        }
        currentOffset = end
        callFailRetries = 0
        writeInProgress = true
        pendingWriteContinuation = { status ->
            writeInProgress = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                chunkRetries = 0
                pumpWriteQueue()
            } else if (chunkRetries < MAX_CHUNK_RETRIES) {
                // 单块失败先重试同一块:设备忙/回调丢失时,重试比丢整帧合理。
                chunkRetries++
                Log.w(tag, "GATT 写 status=$status,重试第 $chunkRetries 次 offset=$offset/${frame.size}")
                currentOffset = offset
                pumpWriteQueue()
            } else {
                Log.e(tag, "GATT 写失败 status=$status offset=$offset/${frame.size}(已重试 $chunkRetries 次)")
                chunkRetries = 0
                currentWrite = null
                currentOffset = 0
                pumpWriteQueue()
            }
        }
        val timeout = Runnable {
            if (writeInProgress) {
                Log.e(tag, "GATT 写回调超时 offset=$offset/${frame.size}")
                finishWriteCallback(BluetoothGatt.GATT_FAILURE)
            }
        }
        pendingWriteTimeout = timeout
        bleHandler.postDelayed(timeout, WRITE_CALLBACK_TIMEOUT_MS)
    }

    private fun writeOne(rx: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        return try {
            // 下行朗读是批量实时流:用 WRITE_NO_RESPONSE(固件特征已声明支持)才够快;
            // 控制/文本帧仍用带响应写(要确认到位)。
            val type = if (bulkWrite) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            if (Build.VERSION.SDK_INT >= 33) {
                gatt?.writeCharacteristic(rx, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                rx.value = data
                @Suppress("DEPRECATION")
                rx.writeType = type
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(rx) == true
            }
        } catch (e: Exception) {
            Log.e(tag, "writeCharacteristic 异常", e)
            false
        }
    }

    // ---- 内部 ----

    private fun startScan() {
        val s = scanner ?: return
        // 先停掉可能残留的扫描:否则 Android 会回 SCAN_FAILED_ALREADY_STARTED(code=1),
        // 旧实现就是在这里自锁的(rescan() 直接 startScan,上一次一直没被停)。
        stopScan()
        Log.i(tag, "开始扫描 ${profile.namePrefixes.joinToString("/")}*")
        try {
            s.startScan(listOf(scanFilter), scanSettings, scanCallback)
        } catch (e: Exception) {
            listener.onError("启动扫描失败:${e.message}")
        }
    }

    private fun stopScan() {
        val s = scanner ?: return
        try {
            s.stopScan(scanCallback)
        } catch (_: Exception) {
            // 已停止,忽略
        }
    }

    /**
     * 配对请求:弹输入框让用户输入固件小屏显示的 6 位密码。
     * 广播在系统回调线程,必须 post 到主线程弹 UI;确认后**只提交 setPin**——
     * 输入类变体不需要也不能附带确认,确认只属于 PASSKEY_CONFIRMATION 变体。
     */
    private fun requestPairingPin(device: BluetoothDevice, showHint: Boolean) {
        Log.i(tag, "配对请求:需用户输入 6 位密码(showHint=$showHint)")
        mainHandler.post {
            val host = pairingDialogContext() ?: run {
                // App 不在前台,弹不了输入框;等配对超时或下次配对请求再弹
                Log.w(tag, "无可用 Activity 上下文,跳过配对弹框")
                return@post
            }
            showPairingPinDialog(host, device, showHint)
        }
    }

    /** 主线程弹 AlertDialog 输入框;输入非法(非 6 位数字)提示重输,不关闭对话框。 */
    private fun showPairingPinDialog(host: Context, device: BluetoothDevice, showHint: Boolean) {
        if (pairingDialog?.isShowing == true) return // 配对请求可能重发,避免对话框堆叠
        val input = EditText(host).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = "6 位数字密码"
        }
        val inputContainer = android.widget.FrameLayout(host).apply {
            val pad = (resources.displayMetrics.density * 16).toInt()
            setPadding(pad, pad, pad, pad)
            addView(input)
        }
        val title = if (showHint) "请在设备屏幕上查看密码并输入" else "请输入配对密码"
        // 确定按钮先不绑定:用 setOnShowListener 延后挂接,输入非法时保持对话框
        val dialog = AlertDialog.Builder(host)
            .setTitle(title)
            .setView(inputContainer)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消") { _, _ -> pairingDialog = null }
            .setOnCancelListener { pairingDialog = null }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text?.toString()?.trim() ?: ""
                if (pairingPinRegex.matches(pin)) {
                    pairingDialog = null
                    dialog.dismiss()
                    bleHandler.post {
                        try {
                            // 真机回归:这里同时调 setPairingConfirmation(true) 会让配对卡在加密阶段——
                            // App 一直停在「已连接,等待加密」,设备屏上的配对码面板也不消失。
                            // 输入类变体(PIN / PASSKEY / DISPLAY_PASSKEY)只提交 setPin 即可;
                            // 需要确认的是 PASSKEY_CONFIRMATION,已在广播接收器里单独处理。
                            device.setPin(pin.toByteArray())
                        } catch (e: Exception) {
                            Log.e(tag, "setPin 失败", e)
                            listener.onError("配对码提交失败:${e.message}")
                        }
                    }
                } else {
                    // 输入无效(非 6 位数字)→ 保持对话框,提示重输
                    input.error = "请输入 6 位数字密码"
                }
            }
        }
        pairingDialog = dialog
        dialog.show()
    }

    private fun registerPairingReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // 配对/绑定广播来自系统进程,NOT_EXPORTED 仍能收到,且不被第三方应用冒充
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(bondReceiver, filter)
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        // 单飞:同一设备已在连接/已连上时不再发起第二次 GATT 连接(见 connectInFlight 注释)。
        if (connectInFlight && targetDevice?.address == device.address) {
            Log.i(tag, "忽略重复连接请求 ${device.address}(已有连接在进行)")
            return
        }
        targetDevice = device
        rememberDevice(device.address)   // 记住设备地址,App 重启后可自动重连
        listener.onConnecting()
        bleHandler.post {
            Log.i(tag, "连接 ${device.address}")
            linkUp = false
            connectInFlight = true
            scheduleConnectTimeout(device.address)
            gatt = device.connectGatt(
                context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    private fun ensureEncryption(g: BluetoothGatt) {
        val device = g.device
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                listener.onEncrypted()
                if (g.services.isNullOrEmpty()) g.discoverServices()
                else requestMtuAndSubscribe(g)
            }
            BluetoothDevice.BOND_BONDING -> Unit // 等 bond 广播
            else -> {
                if (!device.createBond()) {
                    // 已绑定或绑定被拒,仍尝试发现服务;加密写入失败会再触发配对
                    Log.w(tag, "createBond 未启动,直接发现服务")
                    g.discoverServices()
                }
            }
        }
    }

    private fun requestMtuAndSubscribe(g: BluetoothGatt) {
        val service = g.getService(profile.serviceUuid)
        val tx = service?.getCharacteristic(profile.txUuid)
        val rx = service?.getCharacteristic(profile.rxUuid)
        if (service == null || tx == null || rx == null) {
            listener.onError("未找到 NUS 服务/特征")
            g.disconnect()
            return
        }
        // 请求高优先级连接间隔(每次 ATT 写/notify 都要等一个连接事件,间隔越短下行吞吐越高;
        // CONNECTION_PRIORITY_HIGH 会把间隔压到 ~11–15ms,下行/上行都受益)。
        // 注意旧注释把下行朗读的低吞吐归因于这里的「默认连接间隔」并写下了「11 帧/秒」这个数字 ——
        // 该结论已被 2026-10 真机 A/B 推翻(带响应写实测 ≈18.5 帧/秒,真正的断点在选择无响应写,
        // 见 [setBulkWrite]);这条请求保留,因为它仍然给下行留余量。
        try {
            val ok = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            Log.i(tag, "请求高优先级连接间隔: $ok")
        } catch (e: Exception) {
            Log.w(tag, "请求连接优先级失败:${e.message}")
        }
        // 片长以**本次连接**协商到的 MTU 为准:先清空上一次连接的值 —— 在 onMtuChanged 回调
        // 到达之前按回退片长(240)发,绝不沿用旧连接的值(换设备/重连后 MTU 可能不同)。
        negotiatedMtu = null
        try {
            @Suppress("DEPRECATION")
            g.requestMtu(profile.requestMtu)
        } catch (e: Exception) {
            Log.w(tag, "requestMtu 失败:${e.message}")
        }
        g.setCharacteristicNotification(tx, true)
        val cccd = tx.getDescriptor(profile.cccdUuid)
        if (cccd == null) {
            listener.onError("未找到 CCCD")
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(cccd)
    }

    /**
     * 重连调度。
     *
     * [direct] = true 时先直连上次记住的地址(1~2 秒级,设备刚重启完就能接上);
     * 连续失败 [DIRECT_RETRY_LIMIT] 次后改为"退避 + 扫描",兜住设备地址变化或需重新配对的情况。
     */
    private fun scheduleReconnect(backoffMs: Long = 2_000L, direct: Boolean = false) {
        bleHandler.removeCallbacksAndMessages(null)
        val addr = lastDeviceAddr
        val tryDirect = direct && addr != null
        bleHandler.postDelayed({
            if (!running) return@postDelayed
            val dev = if (tryDirect) {
                try {
                    adapter?.getRemoteDevice(addr!!)
                } catch (e: Exception) {
                    Log.w(tag, "直连地址无效(${e.message}),改为扫描")
                    null
                }
            } else {
                null
            }
            if (dev != null) {
                Log.i(tag, "重连:直连上次设备 $addr(已用直连重试 $directRetryCount 次)")
                connectTo(dev)
            } else {
                startScan()
            }
        }, backoffMs)
    }

    /**
     * 连接超时兜底:到点仍未连上就当作掉线,交给 [onLinkDown] 重连。
     *
     * 没有它时,设备关机重启期间发起的那次直连会一直挂着(Android 不报错也不超时),
     * 用户看上去就是"重新连接特别慢"。
     */
    private fun scheduleConnectTimeout(address: String) {
        cancelConnectTimeout()
        val r = Runnable {
            if (running && !linkUp) {
                Log.w(tag, "连接超时(${LinkRetryPolicy.CONNECT_TIMEOUT_MS}ms):$address")
                onLinkDown()
            }
        }
        connectTimeout = r
        bleHandler.postDelayed(r, LinkRetryPolicy.CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() {
        connectTimeout?.let { bleHandler.removeCallbacks(it) }
        connectTimeout = null
    }

    /**
     * 链路掉线(或直连超时)的统一入口:清理 gatt、通知上层,然后按"快路→慢路"重连。
     *
     * 快路:1.5 秒后直连上次地址 —— 设备关机重启后只等它把广播/连接能力拉起来;
     * 慢路:连续直连失败后 8 秒退避 + 扫描 —— 处理设备换了地址、或绑定被清除需重新配对的情况。
     */
    private fun onLinkDown() {
        linkUp = false
        connectInFlight = false
        cancelConnectTimeout()
        gatt?.close()
        gatt = null
        listener.onDisconnected()
        if (!running) return
        val d = LinkRetryPolicy.decide(directRetryCount)
        if (d.direct) directRetryCount++ else directRetryCount = 0
        scheduleReconnect(backoffMs = d.delayMs, direct = d.direct)
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
