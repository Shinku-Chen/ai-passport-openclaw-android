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
 * setPin + setPairingConfirmation 完成匹配;收到 PASSKEY_CONFIRMATION 时自动确认。
 */
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
    }

    private var scanner: BluetoothLeScanner? = null
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var running = false

    /** 上次连接设备地址持久化(记住设备,App 重启后自动重连)。 */
    private val prefs =
        context.getSharedPreferences("ble_central", Context.MODE_PRIVATE)

    /** 上次连接成功的设备地址(持久化,App 重启后用于自动重连)。 */
    private val lastDeviceAddr: String?
        get() = prefs.getString(KEY_LAST_DEVICE, null)

    private fun rememberDevice(addr: String) {
        prefs.edit().putString(KEY_LAST_DEVICE, addr).apply()
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
    private val scanFilter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid(BleNus.SERVICE_UUID))
        .build()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name
            // 兜底:即便广播过滤失效,也按名字前缀再过滤一次
            if (name?.startsWith(BleNus.DEVICE_NAME_PREFIX) != true) return
            Log.i(tag, "发现设备 ${device.address} $name")
            stopScan()
            connectTo(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "扫描失败 code=$errorCode")
            listener.onError("BLE 扫描失败(code=$errorCode)")
            // 扫描失败(常因 Android 限制扫描频率)不立即高频重试,退避后再扫一次。
            // 若持续失败则不自动重连,等用户在设备页再点"扫描"。
            if (running) scheduleReconnect(backoffMs = 8_000L)
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val target = targetDevice ?: return
                    if (device?.address != target.address) return
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
                    val target = targetDevice ?: return
                    if (device?.address != target.address) return
                    val variant = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_PIN
                    )
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
                    listener.onConnected()
                    ensureEncryption(g)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(tag, "已断开 status=$status")
                    gatt?.close()
                    gatt = null
                    listener.onDisconnected()
                    // 断开后不立即高频重连,退避后再扫,避免 Android 扫描限流
                    if (running) scheduleReconnect(backoffMs = 8_000L)
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
            Log.i(tag, "MTU=$mtu status=$status")
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
            if (descriptor.characteristic.uuid == BleNus.TX_UUID && status == BluetoothGatt.GATT_SUCCESS) {
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
        if (running) startScan()
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
    }

    /** 单次 ATT 写安全片长(MTU256-3≈253,留余量用 240)。 */
    private val WRITE_CHUNK = 240

    /** 把一帧字节(已含帧头+payload)写入 RX 特征。 */
    fun writeBytes(data: ByteArray) {
        val g = gatt ?: return
        val service = g.getService(BleNus.SERVICE_UUID) ?: return
        val rx = service.getCharacteristic(BleNus.RX_UUID) ?: return
        bleHandler.post {
            if (data.size <= WRITE_CHUNK) {
                try { writeOne(rx, data) }
                catch (e: Exception) { Log.e(tag, "写 RX 失败", e) }
            } else {
                // 超 MTU 单写限 → 分片串行写(固件帧重组器按 frame magic+len 重组)
                writeChunked(rx, data)
            }
        }
    }

    // 长帧分片串行写:wa 用延迟串行,避免 GATT 并发写冲突。
    private fun writeChunked(rx: BluetoothGattCharacteristic, data: ByteArray) {
        var off = 0
        val pending = data.size
        fun sendSlice() {
            if (off >= pending) return
            val end = minOf(off + WRITE_CHUNK, pending)
            val slice = data.copyOfRange(off, end)
            off = end
            try { writeOne(rx, slice) } catch (e: Exception) { Log.e(tag, "写 RX 分片失败", e); return }
            if (off < pending) {
                bleHandler.postDelayed({ sendSlice() }, 30)   // 30ms 间隔发下一片
            }
        }
        sendSlice()
    }

    private fun writeOne(rx: BluetoothGattCharacteristic, data: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            gatt?.writeCharacteristic(rx, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            rx.value = data
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(rx)
        }
    }

    // ---- 内部 ----

    private fun startScan() {
        val s = scanner ?: return
        Log.i(tag, "开始扫描 ${BleNus.DEVICE_NAME_PREFIX}*")
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
     * 广播在系统回调线程,必须 post 到主线程弹 UI;确认成功后 setPin + setPairingConfirmation。
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
                            device.setPin(pin.toByteArray())
                            device.setPairingConfirmation(true)
                        } catch (e: Exception) {
                            Log.e(tag, "setPin/setPairingConfirmation 失败", e)
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
        targetDevice = device
        rememberDevice(device.address)   // 记住设备地址,App 重启后可自动重连
        listener.onConnecting()
        bleHandler.post {
            Log.i(tag, "连接 ${device.address}")
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
        val service = g.getService(BleNus.SERVICE_UUID)
        val tx = service?.getCharacteristic(BleNus.TX_UUID)
        val rx = service?.getCharacteristic(BleNus.RX_UUID)
        if (service == null || tx == null || rx == null) {
            listener.onError("未找到 NUS 服务/特征")
            g.disconnect()
            return
        }
        try {
            @Suppress("DEPRECATION")
            g.requestMtu(BleNus.REQUEST_MTU)
        } catch (e: Exception) {
            Log.w(tag, "requestMtu 失败:${e.message}")
        }
        g.setCharacteristicNotification(tx, true)
        val cccd = tx.getDescriptor(BleNus.CCCD_UUID)
        if (cccd == null) {
            listener.onError("未找到 CCCD")
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(cccd)
    }

    private fun scheduleReconnect(backoffMs: Long = 2_000L) {
        bleHandler.removeCallbacksAndMessages(null)
        bleHandler.postDelayed({ if (running) startScan() }, backoffMs)
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
