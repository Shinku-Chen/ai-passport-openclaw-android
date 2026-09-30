# 文档索引

本目录存放 App 侧的开发文档。权威契约在固件仓库，这里是与实现同步的说明。

| 文档 | 内容 |
| --- | --- |
| [wire-protocol.md](wire-protocol.md) | 设备 ⇄ 手机线协议（帧格式、握手、音频上行、TTS 下行、文本分片、重连与配对）以及 App 侧改动清单 |
| [gateway-adapters.md](gateway-adapters.md) | 网关抽象层：OpenClaw（WS + Ed25519）与 Hermes（OpenAI 兼容 API server）的接入方式、设置页字段、测试方案，以及识别通道常驻预热（热连接，§8） |

固件侧权威规范：`ai-passport` 仓库 `docs/development/engineering/intercom-wire-protocol.md`（英文默认）与 `.zh_CN.md`。
