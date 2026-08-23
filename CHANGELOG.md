# Changelog

## Unreleased

- 恢复 Vosk 离线 STT(设备麦克风 BLE PCM):VoskStt 消费固件 BLE 送来的 int16 mono 16k PCM,
  Model/Recognizer/sampleRate=16000;SttFactory 探测模型(Vosk 优先,模型缺失降级系统 SpeechRecognizer)。
  修正 Vosk 构建错误:Model(path) 构造、acceptWaveForm(pcm, size)。
- 真机调试修复(Redmi 9):GatewayApi `Request.Builder().url()` 移进 try 块(启动即崩溃的主因);
  BleCentral 移除不存在的 PAIRING_VARIANT_CONSENT、connectGatt 改 4 参;
  GatewayClient 补 import launch、sendChat/collectReply/requestSync 标 suspend。
- 配对改为用户输入:BLE 配对请求(PIN/PASSKEY/DISPLAY_PASSKEY)弹输入框,用户输入固件
  小屏显示的随机 6 位密码后 setPin+setPairingConfirmation 匹配,不再自动注入固定 passkey;
  输入框经 AppActivity 挂到前台 Activity(Service 无 window token),输入校验 6 位数字。
- 初始化项目文档:新增 `AGENTS.md`、`CLAUDE.md`、`CHANGELOG.md`。
- 实现语音对讲桥安卓中央端:BleCentral(BLE GATT central)、VoicePipeline(流水线编排+barge)、
  SpeechRecognizerStt(系统 STT)、TtsEngine(系统 TTS)、GatewayClient(OpenClaw WS 对话,
  Ed25519 设备鉴权+chat)、GatewaySettings/MainActivity(网关域名/token 设置)、
  VoiceBridgeService(前台编排)。设备需在网关主机 `openclaw devices approve` 配对后方可对话。
