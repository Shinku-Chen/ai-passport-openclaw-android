# Changelog

## Unreleased

- 初始化项目文档:新增 `AGENTS.md`、`CLAUDE.md`、`CHANGELOG.md`。
- 实现语音对讲桥安卓中央端:BleCentral(BLE GATT central)、VoicePipeline(流水线编排+barge)、
  SpeechRecognizerStt(系统 STT)、TtsEngine(系统 TTS)、GatewayClient(OpenClaw WS 对话,
  Ed25519 设备鉴权+chat)、GatewaySettings/MainActivity(网关域名/token 设置)、
  VoiceBridgeService(前台编排)。设备需在网关主机 `openclaw devices approve` 配对后方可对话。
