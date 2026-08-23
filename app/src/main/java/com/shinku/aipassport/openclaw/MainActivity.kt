package com.shinku.aipassport.openclaw

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 语音对讲桥安卓 App 主界面。
 *
 * 完整功能(BLE 连接状态/日志/设置)由 VoiceBridgeService + pipeline 驱动,
 * 此为骨架入口,后续由 worker 按计划补业务逻辑。
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: 后续版本用 viewBinding 加载布局;骨架先不绑定具体 UI。
    }
}
