package com.shinku.aipassport.openclaw.ble

import java.util.UUID

/**
 * NUS( Nordic UART Service )相关 UUID —— 与固件 voice_bridge_ble.h 完全一致。
 *
 * 固件以标准 NUS 128 位 UUID 广播并对外设特征做 READ/WRITE 加密校验,
 * 中央端必须先完成配对/加密,再写 RX、订阅 TX 通知。
 */
object BleNus {
    /** 服务 UUID:6e400001-b5a3-f393-e0a9-e50e24dcca9e */
    val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** RX 特征(App→设备,WRITE + WRITE_NO_RSP + WRITE_ENC) */
    val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** TX 特征(设备→App,READ + NOTIFY + NOTIFY_INDICATE_ENC) */
    val TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** CCCD(通知开关)标准 UUID */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 固件广播名前缀 Passport-<MAC 后 4 字节>,如 "Passport-3A2B" */
    const val DEVICE_NAME_PREFIX = "Passport-"

    /** 目标 MTU:固件按 min(mtu-3, 244) 分片,247 能把单帧 notify 控制在 244B 内 */
    const val REQUEST_MTU = 247
}
