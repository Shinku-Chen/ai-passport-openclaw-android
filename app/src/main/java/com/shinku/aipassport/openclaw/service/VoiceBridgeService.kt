package com.shinku.aipassport.openclaw.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 前台 Service:持有 BLE client + 语音流水线,保持设备连接与 OpenClaw 网关会话。
 * 骨架占位,后续由 worker 实现:BLE GATT central / STT(Vosk) / OpenClaw gateway / pipeline。
 */
class VoiceBridgeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: 启动前台通知 + 初始化 BLE/流水线
        return START_STICKY
    }
}
