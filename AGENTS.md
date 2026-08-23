# AGENTS.md

给 AI agent(Claude Code / Codex / Cursor 等)读的仓库说明。

## 项目概述

`ai-passport-openclaw-android` 是 FoloToy AI Passport(ESP32-C3) 语音对讲设备的安卓中央端 App。
它作为 BLE 中央端连接固件(采集端)，接收固件麦克风 PCM，经本地 STT 识别为文本，送到用户自建的
OpenClaw 网关做 Agent/LLM 推理，网关回复经系统 TTS 合成，并把文本回传固件上屏；同时提供
网关控制台(对话界面/状态/概览)、设备管理与通知同步视图。本端不做模型推理，只做采集、识别、
传输与展示。

## 目录结构

```
app/                                    安卓应用模块
  src/main/AndroidManifest.xml          权限、前台 connectedDevice Service、MainActivity
  src/main/java/com/shinku/aipassport/openclaw/
    MainActivity.kt                     网关设置入口 + 权限申请 + 启停服务
    ble/BleNus.kt                       NUS UUID 常量(与固件 voice_bridge_ble.h 逐字对齐) + 广播名前缀
    ble/BleCentral.kt                   GATT central:扫描/连接/加密配对/服务发现/订阅/写入
    protocol/VbFrame.kt                 线协议(与固件 voice_bridge_frame.h 一致):帧编解码/重组
    gateway/GatewayConfig.kt            网关域名/端口/token 读取口(来自 App 设置)
    gateway/GatewaySettings.kt          网关设置的 SharedPreferences 存取
    gateway/GatewayClient.kt            OpenClaw WS RPC:connect(Ed25519 鉴权)+ chat.send + 事件流收集
    gateway/DeviceIdentity.kt           Ed25519 设备身份生成/签名/持久化 + deviceToken
    pipeline/VoicePipeline.kt           流水线编排:BLE AUDIO→STT→网关→TTS→TEXT 回传 + barge
    stt/Stt.kt                          语音识别抽象
    stt/SpeechRecognizerStt.kt          系统 SpeechRecognizer 实现(当前唯一 STT 引擎)
    tts/TtsEngine.kt                    系统 TextToSpeech 合成封装
    service/VoiceBridgeService.kt       前台 Service:装配 BLE/STT/网关/TTS/流水线
  src/main/res/                         strings / themes / 前台通知图标 / activity_main 布局
流程:
  固件(BLE 采集+PCM) → BleCentral(AUDIO 帧) → VbFrameReassembler(重组) → VoicePipeline
    → SpeechRecognizerStt(STT 出文本) → GatewayClient(chat.send → 回复) → TtsEngine(合成)
    → 回传 TEXT 帧给固件上屏;barge 在流水线内实现(再按 PTT 打断 TTS)
```

## 构建与验证

```bash
./gradlew assembleDebug        # 构建 Debug APK(需 Android SDK/JDK)
./gradlew lint                 # 静态检查
# 未见自动化单测;BLE/STT/网关/TTS 均为硬件或外部依赖,需真机验证
```

改动后至少跑 `./gradlew assembleDebug`(能快速构建时)。BLE 配对、STT 识别、网关 chat、
TTS 合成依赖真机与自建 OpenClaw 网关,需在真机上验证;首次 connect 需网关主机
`openclaw devices approve` 批准本设备 Ed25519 身份。

## 代码约定

- Kotlin,包名 `com.shinku.aipassport.openclaw`,命名遵循 Kotlin 惯例。
- 线协议 `VbFrame.kt` 与固件 `voice_bridge_frame.h` 必须逐字一致;改协议两边同改。
- BLE 回调(后台线程)只做轻量派发;STT/TTS/网关调用在协程里,不阻塞 BLE 线程。
- 网关 token 是运行时 secret:绝不写进提交的代码;域名/端口可提交(BuildConfig/设置读),token 从 App 内设置读。
- 注释解释函数职责、请求参数语义与使用边界。
- 代码里禁止出现 'yoooclaw' 字样(用户硬约束,功能可参考但字面必须清码)。

## 提交规范

- commit 标题:`type(scope): 简述`,`type` ∈ `feat/fix/docs/refactor/perf/test/chore/build/ci`,简述用祈使句、≤50 字符、结尾不加句号、默认中文(技术术语保留英文)。
- 一个 commit 只做一件事,message 描述最终 diff,不叙述调试过程。
- commit 后在同一轮内 `git push`(本仓库主线即 origin/main)。
- push 前检查仓库根是否同时存在 `AGENTS.md` 和 `CLAUDE.md`,缺失时先补齐。
- 任何实际文件变更都同步记录到 `CHANGELOG.md`。
