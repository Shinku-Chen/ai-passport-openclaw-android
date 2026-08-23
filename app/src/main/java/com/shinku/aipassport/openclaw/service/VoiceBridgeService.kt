package com.shinku.aipassport.openclaw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.shinku.aipassport.openclaw.AppActivity
import com.shinku.aipassport.openclaw.MainActivity
import com.shinku.aipassport.openclaw.R
import com.shinku.aipassport.openclaw.ble.BleCentral
import com.shinku.aipassport.openclaw.gateway.GatewayClient
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import com.shinku.aipassport.openclaw.pipeline.VoicePipeline
import com.shinku.aipassport.openclaw.ui.ConversationStore
import com.shinku.aipassport.openclaw.protocol.VbFrame
import com.shinku.aipassport.openclaw.protocol.VbFrameReassembler
import com.shinku.aipassport.openclaw.protocol.vbEncodeFrame
import com.shinku.aipassport.openclaw.stt.SttFactory
import com.shinku.aipassport.openclaw.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务:持有 BLE 中央端 + 帧重组 + 语音流水线。
 *
 * 数据流:
 *  固件 --notify--> BleCentral --字节--> VbFrameReassembler --帧--> VoicePipeline
 *  VoicePipeline --sendText--> BleCentral --写 RX--> 固件(TEXT 帧上屏)
 */
class VoiceBridgeService : Service() {

    companion object {
        private const val TAG = "VoiceBridgeService"
        private const val CHANNEL_ID = "voice_bridge"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.shinku.aipassport.openclaw.action.START"
        const val ACTION_STOP = "com.shinku.aipassport.openclaw.action.STOP"

        /** 设备页:重新扫描/连接 */
        const val ACTION_SCAN = "com.shinku.aipassport.openclaw.action.SCAN"
        /** 设备页:断开当前设备 */
        const val ACTION_DISCONNECT = "com.shinku.aipassport.openclaw.action.DISCONNECT"

        /** 状态广播(供 UI 展示) */
        const val ACTION_STATUS = "com.shinku.aipassport.openclaw.action.STATUS"
        const val EXTRA_STATUS = "status"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceBridgeService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceBridgeService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var ble: BleCentral
    private lateinit var reassembler: VbFrameReassembler
    private lateinit var gateway: GatewayClient
    private lateinit var tts: TtsEngine
    private lateinit var pipeline: VoicePipeline

    @Volatile
    private var initialized = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台服务被系统杀死后 START_STICKY 重启(intent 为 null)也要拉起桥
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_SCAN -> {
                startBridge()
                if (::ble.isInitialized) ble.rescan()
            }
            ACTION_DISCONNECT -> {
                if (::ble.isInitialized) ble.stop()
            }
            else -> startBridge()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        if (::ble.isInitialized) ble.stop()
        if (::gateway.isInitialized) gateway.close()
        if (::tts.isInitialized) tts.shutdown()
        if (::pipeline.isInitialized) pipeline.shutdown()
        initialized = false
        stopForegroundCompat()
        super.onDestroy()
    }

    // ---- 初始化 ----

    private fun startBridge() {
        if (initialized) return
        initialized = true
        startForegroundCompat()

        // 共享对话历史:硬件语音也要写入同一列表,供对话 Tab 实时展示
        ConversationStore.init(this)

        gateway = GatewayClient(this, GatewaySettings(this)) { status -> publishStatus(status) }
        tts = TtsEngine(this)

        // 帧重组 → 帧回调 → 流水线
        reassembler = VbFrameReassembler { frame -> pipeline.handleFrame(frame) }

        pipeline = VoicePipeline(
            scope = scope,
            stt = SttFactory.create(this) { partial ->
                // 流式识别中间结果:不实时回传设备屏。小智识别会把多条 partial 连续回调,
                // 若每条都 sendTextFrame('U') 上屏,设备会被海量 partial 淹没、状态卡在"接收中",
                // 且覆盖最终的完整回复。识别文本最终由 onTurnEnd 的 sendText('U', text) 一次回传。
                Log.d(TAG, "识别中间结果(不上屏): $partial")
            },
            gateway = gateway,
            tts = tts,
            sendText = { role, text -> sendTextFrame(role, text) },
            onState = { status -> publishStatus(status) },
        )

        ble = BleCentral(
            context = this,
            // 配对输入框弹在 App 前台 Activity 上(Service 无 window token 弹不了对话框)
            pairingDialogContext = { AppActivity.current() },
            listener = object : BleCentral.Listener {
                override fun onConnecting() = publishStatus("正在连接设备…")
                override fun onConnected() = publishStatus("已连接,等待加密")
                override fun onEncrypted() = publishStatus("已加密")
                override fun onReady() {
                    publishStatus("已就绪,长按设备 OK 说话")
                    // 下发当前时间给设备(设备无网络时钟,靠 App 同步;右上角显示 HH:MM)。
                    // 延迟稍等,确保 MTU 协商完成(否则超长写入被 Android 拒)。
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        sendTimeSync()
                    }, 800)
                }
                // 帧处理统一投递到主线程 scope:保证帧重组 + 流水线在单线程上串行,
                // STT(startTurn)/EVENT 起停/识别顺序确定,不因 BLE 回调线程而竞态。
                override fun onBytesReceived(bytes: ByteArray) {
                    // 调试:打印收到的 BLE 字节前 20 + 交给帧重组(定位帧边界错乱)
                    if (bytes.isNotEmpty()) {
                        val dbg = bytes.take(24).joinToString(" ") { "%02x".format(it) }
                        Log.i(TAG, "BLE RX ${bytes.size}B: $dbg")
                    }
                    scope.launch { reassembler.push(bytes) }
                }
                override fun onDisconnected() {
                    scope.launch {
                        pipeline.onDisconnected()
                        publishStatus("已断开,自动重连…")
                    }
                }
                override fun onError(message: String) = publishStatus("错误: $message")
            },
        )

        pipeline.prewarm()
        ble.start()
    }

    // ---- 数据出口 ----

    private fun sendTextFrame(role: Char, text: String) {
        // TEXT 帧 payload = [role:1B]['U'=用户识别/'A'=网关回复] + UTF-8 文本。
        // 固件据此区分并入对话历史,供 UP/DOWN 翻页。
        val body = text.toByteArray(Charsets.UTF_8)
        // 固件 TEXT 帧上限 VB_FRAME_TEXT_MAX(2048,见固件 voice_bridge_frame.h)。
        // 长回复按此分片,首片 FLAG_FIRST、中间 FLAG_MORE、末片 FLAG_LAST,固件端合并成一条显示。
        val payloadMax = 2048   // payload = role(1B) + body;固件判 payload_len,不含帧头
        val bodyMax = payloadMax - 1
        val totalChunks = if (body.isEmpty()) 1 else (body.size + bodyMax - 1) / bodyMax
        var off = 0
        var chunkIdx = 0
        if (body.isEmpty()) {
            // 空文本:发一条空 frame(带 LAST)
            val payload = ByteArray(1).also { it[0] = role.code.toByte() }
            val frame = vbEncodeFrame(VbFrame.TYPE_TEXT, VbFrame.FLAG_LAST, payload)
            Log.i(TAG, "sendTextFrame role=$role(空) 分片0/1")
            ble.writeBytes(frame)
            return
        }
        while (off < body.size) {
            val end = minOf(off + bodyMax, body.size)
            // 回退到 UTF-8 边界,避免切在多字节字符中间
            var cut = end
            while (cut > off && (body[cut - 1].toInt() and 0xC0) == 0x80) cut--
            if (cut == off) break  // 防止死循环
            val chunk = body.copyOfRange(off, cut)
            val payload = ByteArray(chunk.size + 1)
            payload[0] = role.code.toByte()
            System.arraycopy(chunk, 0, payload, 1, chunk.size)
            val flags = when {
                totalChunks == 1 -> VbFrame.FLAG_LAST            // 单帧即完整
                chunkIdx == 0 -> VbFrame.FLAG_FIRST              // 首片
                off + (cut - off) >= body.size -> VbFrame.FLAG_LAST  // 末片
                else -> VbFrame.FLAG_MORE                        // 中间片
            }
            val frame = vbEncodeFrame(VbFrame.TYPE_TEXT, flags, payload)
            Log.i(TAG, "sendTextFrame role=$role text=${text.take(30)} 分片$chunkIdx/$totalChunks 字节=${frame.size} flags=$flags")
            ble.writeBytes(frame)
            off = cut
            chunkIdx++
        }
    }

    // 下发当前时间给设备:CONTROL 帧 {"ev":"time","epoch":<秒>}。
    private fun sendTimeSync() {
        val json = "{\"ev\":\"time\",\"epoch\":${System.currentTimeMillis() / 1000}}"
        val payload = json.toByteArray(Charsets.UTF_8)
        val frame = vbEncodeFrame(VbFrame.TYPE_CONTROL, 0, payload)
        ble.writeBytes(frame)
    }

    // ---- 状态 ----

    private fun publishStatus(status: String) {
        Log.i(TAG, status)
        // 广播可能从 BLE 回调线程发出;通知/广播本身线程安全,无需切线程
        updateNotification(status)
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, status)
        )
    }

    // ---- 前台通知 ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "语音对讲桥",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "保持与 AI Passport 设备的 BLE 连接与语音流水线" }
        nm.createNotificationChannel(channel)
    }

    private fun notification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Passport 语音桥")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notification("启动中…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIF_ID, notification("启动中…"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "前台通知启动失败(不影响主流程)", e)
            try { startForeground(NOTIF_ID, notification("启动中…")) } catch (_: Exception) {}
        }
    }

    private fun updateNotification(status: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, notification(status))
        } catch (e: Exception) {
            Log.w(TAG, "更新通知失败", e)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
