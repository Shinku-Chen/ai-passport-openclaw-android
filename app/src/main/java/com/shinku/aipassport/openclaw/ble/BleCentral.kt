package com.shinku.aipassport.openclaw.ble

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * BLE 中央端:扫描 Passport-* 设备 → 连接 → 加密配对(固定 passkey)→ 发现 NUS →
 * 订阅 TX 通知 → 把帧字节写入 RX。所有 GATT 操作在专用 HandlerThread 上串行执行。
 *
 * 配对:固件为 LE SC + MITM + bonding,IO 能力 DISP_ONLY 且自动注入固定 passkey
 * (固件 voice_bridge.c static_passkey)。中央端收到 PIN 输入请求时自动填入该 passkey,
 * 收到 PASSKEY_CONFIRMATION 时自动确认。
 */
@SuppressLint("MissingPermission")
class BleCentral(
    private val context: Context,
    private val staticPasskey: String,
    private val listener: Listener,
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

    private var scanner: BluetoothLeScanner? = null
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var running = false

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
            if (running) scheduleReconnect()
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
                        BluetoothDevice.PAIRING_VARIANT_PIN -> {
                            device.setPin(staticPasskey.toByteArray())
                            device.setPairingConfirmation(true)
                        }
                        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT -> {
                            device.setPairingConfirmation(true)
                        }
                        // PAIRING_VARIANT_PASSKEY / DISPLAY_PASSKEY:对端展示 passkey,
                        // 本机无需输入(固件侧自动注入固定值)。
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
                    if (running) scheduleReconnect()
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
        startScan()
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

    /** 把一帧字节(已含帧头+payload)写入 RX 特征。 */
    fun writeBytes(data: ByteArray) {
        val g = gatt ?: return
        val service = g.getService(BleNus.SERVICE_UUID) ?: return
        val rx = service.getCharacteristic(BleNus.RX_UUID) ?: return
        bleHandler.post {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(rx, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    rx.value = data
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(rx)
                }
            } catch (e: Exception) {
                Log.e(tag, "写 RX 失败", e)
            }
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
        listener.onConnecting()
        bleHandler.post {
            Log.i(tag, "连接 ${device.address}")
            gatt = device.connectGatt(
                context, false, gattCallback, BluetoothDevice.TRANSPORT_LE, bleHandler
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

    private fun scheduleReconnect() {
        bleHandler.removeCallbacksAndMessages(null)
        bleHandler.postDelayed({ if (running) startScan() }, 2000L)
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
