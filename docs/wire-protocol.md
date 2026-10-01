# 对讲机线协议（App 侧实现说明）

本文是 App 侧对**设备 ⇄ 手机线协议**的实现说明。契约的权威版本在固件仓库：

- `ai-passport`（Shinku-Chen fork）→ `docs/development/engineering/intercom-wire-protocol.md`（英文默认）与 `.zh_CN.md`

**同步规则**：两份仓库必须同一轮一起改。改帧格式、类型、事件字段或握手流程时，先改固件侧那份规范，再改这份实现说明与代码；不允许只有一侧更新。

---

## 1. 角色与传输

| 项 | 取值 |
| --- | --- |
| 设备 | BLE 外设，广播名 `Passport-XXXX`，单连接 |
| 手机 | BLE 中央端（`BleCentral`） |
| 服务 | NUS `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| 手机 → 设备 | `6e400002-...`（RX）：`WRITE` / `WRITE_NO_RSP` / `WRITE_ENC` |
| 设备 → 手机 | `6e400003-...`（TX）：`NOTIFY`（订阅 CCCD `00002902-...`） |
| MTU | 请求 247，notify 载荷 `min(mtu-3, 244)` |
| 加密 | LE SC + MITM + bonding；设备屏显示 6 位密码，App 弹框输入 |

约定：**上行走 notify（音频，不重传）**，**下行走 write（文本/控制，with-response 拿 ATT 确认）**。

设备侧按键：长按 OK = 按住说话，松开 = 发送；短按 OK = 设备设置页（设备信息/亮度/返回）；UP/DOWN = 浏览对话历史。**设备音量与麦克风增益由 App 主导**（`CONTROL` 的 `audio` 命令），亮度保留设备本地；`status` 按需拉取（设备背光熄灭后仍常连，故不做周期心跳）。

## 2. 帧格式

```text
[A5][5A][TYPE:1B][FLAGS:1B][LEN:2B 大端][payload]
```

| 类型 | 名称 | 方向 | payload |
| --- | --- | --- | --- |
| `0x01` | `AUDIO_PCM` | 设备→App | `[SEQ:1]` + int16 小端单声道 PCM（1024B/帧；整帧 payload = 1025B，兜底路径） |
| `0x02` | `TEXT` | App→设备 | `[role:1]` + UTF-8（≤2048B/帧） |
| `0x03` | `CONTROL` | 双向 | JSON |
| `0x04` | `EVENT` | 双向 | JSON |
| `0x05` | `AUDIO_OPUS` | 设备→App | `[SEQ:1]` + 一个 Opus 包（≤512B） |
| `0x06` | `TTS_OPUS` | **App→设备** | `[SEQ:1]` + `[rate_khz:1]` + `[frame_ms:1]` + 一个 Opus 包（≤512B） |

FLAGS：`0x01 MORE` / `0x02 FIRST` / `0x04 LAST`。

三条必须保留的规则（都是踩过的坑）：

1. magic 用于重同步，单字节失步必须能自愈。
2. 攒 payload 时若发现"非音频的合法新帧头"，丢弃半截帧切过去（固件会把 EVENT 插进 AUDIO 帧中间）。
3. 重组缓冲必须容得下最大帧（`6 + 2048`）。

## 3. 握手

1. App 连接 → 加密 → 服务发现 → 订阅 TX。
2. 设备在订阅确认后发 `EVENT {"ev":"hello","proto":1,"caps":["opus",...],...}`。
3. App 收到后回 `CONTROL {"cmd":"hello","proto":1,...}`，并据此决定上行编码（有 `opus` 用 `opus`，否则 `pcm`）。
4. 双方都完成后 UI 才显示"就绪"；App 在收到设备 hello 前不得发依赖音频的命令。

## 4. 一轮对讲

```text
设备长按 OK（屏幕立刻整屏红 = 准备中）
  → EVENT turn_start
  → 连续 AUDIO_OPUS 帧（60ms/帧，SEQ 递增）
App：首帧到达即可开始识别（startTurn）
App：**录音/识别真的就绪**后 → EVENT {"ev":"turn_ready"}（设备变绿 = 可以说话）
设备松开 OK
  → EVENT turn_end{frames,codec,dropped}
App：结束识别 → 出文本 → 网关 → 回 TEXT('U') / TEXT('A') 上屏 → TTS 播报
App：上屏之后再走 TTS 下行（0x06，见第 6 节）：{"ev":"tts_start"} → 逐帧音频 → {"ev":"tts_stop"}
设备：播完回 EVENT {"ev":"tts_playback_done",…}；被 abort/解码失败回 {"ev":"tts_playback_aborted"}
```

`turn_ready` 的实现约定（App 侧 `VoicePipeline.onRecordingReady` + `pipeline/TurnReady.kt`）：

- **方向**：App→设备，与设备→App 的事件帧**同格式**：`[A5][5A][0x04][FLAGS=0][LEN]` + `{"ev":"turn_ready"}`（`FLAGS` 固定 0）。
- **时机**：必须在**录音/识别真的开始**之后才发 —— 由 `SttEngine.startTurn(onReady)` 的就绪回调触发：
  小智云端在 `listen.start` 已发出（会话建立、服务器可收音频）时回调；Vosk 在识别器 `reset` 完成后回调；
  系统识别在 `onReadyForSpeech` 回调。**禁止**收到 `turn_start` 就无条件发，也**不等识别结果**。
  小智通道**常驻预热**（热连接）后，按下即可直接 `listen.start`，该回调毫秒级到达（目标 < 300ms）——
  这正是修「按下后要等『准备中』」的关键，详见 `docs/gateway-adapters.md` 第 8 节。
- **绿色语义**：设备变绿 = 用户此时说话一定能被识别，因此宁可晚发（设备侧另有 800ms 兜底超时）也不能提前发。
- **只发一次**：按轮去重（`TurnReadyGate` 用递增的轮号去重）；barge/断开后旧轮的迟到就绪回调一律丢弃。
- **设备没连不发**：`BleCentral.isConnected() == false` 时只记 DEBUG 日志，不假装发成功。

要点：

- **只有 `turn_end` 能结束一轮**；DTX 会让静音期间完全没有音频包，禁止用"静音"判断说话结束。
- App 开始新一轮时必须清掉未发送的旧文本分片（现有 `clearPendingWrites()`），避免旧回复在新一轮上屏。
- 丢掉一个包 = 60ms 音频，不重传；App 统计 SEQ 缺口并在状态面板显示丢帧率。

## 5. 文本与分片

- payload = `[role:1]` + UTF-8 文本；`role = 'U'` 用户识别文本、`'A'` 网关回复、`'R'` 系统提示。
- 长文本按 2048B/帧分片，**绝不切在多字节 UTF-8 序列中间**（回退到字符边界）。
- 使用 `WRITE_TYPE_DEFAULT`（with-response）发送；写失败 → 短延时重试一次 → 仍失败则在对话列表标注"未送达"。

## 6. TTS 下行（手机合成 → 设备播放）

网关回复由**手机侧合成**成音频、经 `TTS_OPUS`(0x06) 推给设备播放；设备自己**不**调语音服务
（设备无法上网），所以这条通路只依赖手机已有的网关链路。App 侧同时保留本地 `TextToSpeech` 播放
（`tts/TtsEngine.kt`），两者是独立通路。

payload（不含 6B 帧头）：

| 偏移 | 字段 | 含义 |
| --- | --- | --- |
| `0` | `SEQ` | 1 字节计数器，每帧 +1、到 256 回绕（与上行同义，只用于设备侧统计 `SEQ` 缺口） |
| `1` | `rate_khz` | `16` 或 `24` |
| `2` | `frame_ms` | 帧时长（ms），目前恒为 `60` |
| `3..` | Opus 包 | 一个完整 Opus 包，≤ `OC_TTS_OPUS_PAYLOAD_MAX`（512B） |

规则（App 侧实现见 `tts/TtsFraming.kt`、`tts/TtsFlowControl.kt`、`tts/TtsPlaybackReport.kt`）：

- 每帧**恰好一个 Opus 包**、恒 60ms：`16k → 960 samples / 1920B`，`24k → 1440 samples / 2880B`；
  payload 总长因此是 `4..515`，超出即按错位处理（同第 2 节的重同步规则），不交给解码器。
- 尾帧不足一帧时**补零（静音）成整帧**：libopus 编码器要固定帧长，补零既保证末字不被截断，
  也不给设备引入协议外的「短帧」状态；空 PCM 不发任何帧。
- `rate_khz` 由帧头自带，流中换采样率合法（设备按新值重配解码器）。**M1 不重采样**：合成引擎输出
  不是 16k/24k（实测系统 TTS 可能是 22050Hz）时**放弃本轮下发**并在日志里说明，不做错误速率播放。
- 丢包不重传：丢一个 60ms 包只是某个字略微粗糙，不值得让朗读停住；`SEQ` 缺口由设备统计并在
  `tts_playback_done` 里回报。
- 设备侧解码队列只有 24 包（≈1.44s），溢出时**丢最旧的包**（用户还没听到的语音）。因此 App
  **不得**把超过约 2s 的音频跑到设备前面（固件协议要求），实际按 **800ms 目标**节流：
  `领先量 = 已推帧数 × 60ms − 自 tts_start 起的实时毫秒`，超过目标就等；硬上限 2s = 33 帧
  （`TtsFlowControl.MAX_LEAD_FRAMES`）。
- 帧只走现有的 BLE 串行写队列（`BleCentral.writeBytes`，不另开线程猛灌），节流只算**时间**，
  不把 `tts_playback_done` 当逐帧信用（设备只在整段结束时回报一次）。

三条 `CONTROL` 事件（手机→设备；用 `ev` 形状是因为它们报告「手机观测到的事实」，不是请设备执行的命令）：

| JSON | 含义 |
| --- | --- |
| `{"ev":"tts_start"}` | 手机即将推 TTS 音频：设备进入播放态（暂停上行采集、保持背光） |
| `{"ev":"tts_stop"}` | 后面没有音频了：设备把已入队的播完再退出播放态 |
| `{"ev":"tts_abort"}` | **立刻丢掉队列**：用户又按了 OK（barge）、App 取消或网关流断了；设备可立即恢复采集 |

设备回报的 `EVENT`：

| JSON | 含义 |
| --- | --- |
| `{"ev":"tts_playback_done","frames":N,"decoded":D,"dropped":X,"underruns":U,"decode_us_max":M}` | 一段播完；App 据此打一行日志与本地「已发送」对账 |
| `{"ev":"tts_playback_aborted"}` | 因 `tts_abort` 或解码反复失败而丢弃余下音频 |

App 侧时序（`pipeline/VoicePipeline.kt` + `service/VoiceBridgeService.kt`）：

1. 设备 `turn_start`（用户按下 OK）→ **无条件**发 `{"ev":"tts_abort"}`：设备可能还在播上一轮
   `tts_stop` 之后的 ≤2s 缓冲，只有 abort 能让它停手（该步与设置开关无关，abort 是幂等操作）；
2. 网关回复经 `TEXT('A')` 上屏**之后**，若设置项 `tts_enabled` 打开 → 合成 PCM → 编 Opus；
3. 发 `{"ev":"tts_start"}` → 按上面的流控逐帧发 `TTS_OPUS` → 发 `{"ev":"tts_stop"}`；
4. 合成/编码/节流全在协程里（**不阻塞**上屏与下一轮对话）；期间若有新的 `turn_start`/取消，
   在途推送立即停手（帧序号记账自增作废旧轮）；同一轮被新一段朗读替换（流式正文已在播、历史补正的真答案到了）
   也先发 `{"ev":"tts_abort"}` 再重新 `tts_start`，避免旧回复残音与新一段叠在一起；
5. 设备回报 `tts_playback_done` / `tts_playback_aborted` → 日志（`tts/TtsPlaybackReport.kt`）。

设置项（设置页，均与网关校验无关、**切换即落盘**）：`tts_enabled`（设备朗读回复（TTS），
**默认关**，等真机验收后再考虑默认开）、`tts_engine`（`android` 系统 TTS = M1 唯一实现 / `http` = 骨架）。

M1 的已知边界（启用前须知）：

- **未做 `caps` 门控**：固件协议要求「设备未在 hello 的 `caps` 里声明 `tts_opus` 就不要发 0x06」
  （未知类型会被接收端当错位，代价是它后面那一帧）。App 目前还没解析设备 hello 的 `caps`，
  所以 `tts_enabled` 默认关闭；启用前必须确认固件已实现该通路，后续补 caps 门控。
- **不做重采样**：只接受引擎输出的 16k/24k（见上）。
- **帧仍走 with-response 写**：固件协议建议音频帧用 without-response；M1 复用现有写队列
  （所有帧 with-response）以最小化改动，靠 800ms 目标领先吸收 ATT 往返（真机需观察 underrun）。
- **HTTP TTS 引擎只留骨架**（`tts/HttpTtsEngine.kt`）：`prepare()` 恒 false 并给出可读原因，
  TTS 服务选型（OpenAI 兼容 `/v1/audio/speech` 或自建）待确认后再实现网络细节。

## 7. 现有代码 → v1 的改动清单

| 文件 | 改动 |
| --- | --- |
| `protocol/VbFrame.kt` | 加 `TYPE_OPUS = 0x05` 与 `TYPE_TTS_OPUS = 0x06`（下行 TTS）；音频/Opus/TTS payload 首字节为 SEQ（TTS 另有 `rate_khz` / `frame_ms`）；重组器保留“插入帧头切分”逻辑；`encodeTtsOpusPayload` / `decodeTtsOpusPayload` / `vbEncodeTtsOpusFrame` |
| `ble/BleCentral.kt` | `connectGatt(..., autoConnect = true)` 后台自动重连（避免扫描限流 code=1）；对讲期间 `requestConnectionPriority(HIGH)`，空闲回退；写队列保持按逻辑帧串行（TTS 帧复用同一队列与节流） |
| `service/VoiceBridgeService.kt` | 加 hello 交换与就绪判定；`TEXT` / `EVENT` / `CONTROL` 下发之外新增 `sendControlJson` 与 `DeviceTtsPush`（合成 → Opus → `TTS_OPUS` 帧，领先 ≤2s）；把 `turn_end.dropped` / SEQ 缺口写进状态面板；时间同步保留；`sendEventFrame` 下发 `EVENT {"ev":"turn_ready"}`（录音就绪） |
| `pipeline/VoicePipeline.kt` | 支持 Opus 上行：`TYPE_AUDIO_OPUS` 剥掉首字节 SEQ 后整包原样转给 `SttEngine.feedOpus`（本地不解码/重编码）；`TYPE_AUDIO_PCM` 同样先剥 SEQ 再喂 PCM；每轮 `turn_start` 重置并按 SEQ 缺口统计丢帧率（日志 + 状态面板），仅在本地 STT 路径才需解码；`SttEngine.startTurn(onReady)` 的就绪回调 → `TurnReadyGate` 去重后下发 `turn_ready`；`turn_start` → `DeviceTtsSession.onTurnStart`（abort）、回复上屏后 → `onReply`、`tts_playback_*` → 日志 |
| `tts/DeviceTtsEngine.kt` / `AndroidTtsEngine.kt` | 设备朗读合成抽象 + 系统 TTS 实现（`synthesizeToFile` → WAV → PCM；未装引擎时报 `未安装语音引擎`；不做重采样） |
| `tts/HttpTtsEngine.kt` | HTTP TTS 引擎**骨架**（`prepare()` 恒 false，服务选型待确认） |
| `tts/TtsFraming.kt` / `TtsFlowControl.kt` / `TtsPlaybackReport.kt` / `DeviceTtsSession.kt` | 纯逻辑：60ms 分帧（16k→960 / 24k→1440 samples，尾帧补零）、WAV 解析、领先量节流（2s 上限 = 33 帧，目标 800ms）、播放回报解析与日志、`turn_start` 必发 abort 的编排 |
| `stt/Stt.kt` / `stt/XiaozhiStt.kt` | 新增 `feedOpus(packet)`（默认空实现）；小智把 Opus 包直接作为 WS 二进制帧上送，保留 PCM 兜底分支；`startTurn(onReady)`：小智在 `listen.start` 已发时回调就绪；识别通道**常驻预热**（`WarmLink` 热连接：跨轮复用同一 WS，见 `docs/gateway-adapters.md` 第 8 节） |
| `stt/VoskStt.kt` | 用 `app/libs/opus.aar`（`com.theeasiestway.opus`，编码/解码 JNI 均有）把 Opus 解码成 int16 PCM 后走 `feedPcm`；系统 SpeechRecognizer 只采本机麦克风，不假装支持设备上行音频 |
| `gateway/*` | 见 `docs/gateway-adapters.md` |
| `ui/SettingsFragment.kt` 等设置界面 | 网关类型切换（OpenClaw/Hermes/Echo）与两套配置；新增输出音量与麦克风增益控件（发 `CONTROL audio`）；按需拉 `status` 并在面板展示（电量/音量/增益/丢帧率） |

## 8. 测试

- JVM 单测：帧编解码（含 `0x06` 下行 TTS 帧的往返/上限/SEQ 回绕）、跨 notify 重组、插入帧头切分、
  UTF-8 边界分片、SEQ 缺口统计、60ms 分帧（16k/24k、尾帧补零规则）、WAV 解析、TTS 领先量
  节流（≤2s / 33 帧）、`turn_start` 必发 `tts_abort`、播放回报解析与日志。
- 真机：配对、按住说话、长回复分片与设备翻页、barge、断连重连、背光超时；TTS 下行需固件
  播放通路就绪后才能验收（`tts_enabled` 默认关）。

## 9. 已定与待定

已定：

- `status` 按需拉取，不做周期心跳；设备自身配置变更后补一次 `status`。
- 输出音量与麦克风增益由 App 通过 `CONTROL audio` 下发；亮度保留设备本地。
- 设备 UP/DOWN 只用于浏览历史，长按 OK 才是对讲（音量/增益不再占用设备按键）。
- 回复串读：手机侧本地 `TextToSpeech` 播报（`tts/TtsEngine.kt`）与设备朗读（`TTS_OPUS`）是两条独立通路，
  分别由既有行为与 `tts_enabled` 控制。

仍待定：

- PCM 兜底帧大小（1024B/帧）自 Opus 成为默认后尚未重测。
- `tts_engine` 的 HTTP 实现选型（OpenAI 兼容 `/v1/audio/speech` 还是自建服务）、字段与鉴权。
- `tts_enabled` 何时默认开（需真机验收：固件播放通路 + 是否与手机本地播放重复出声）。
- 设备 hello `caps` 的解析与 `tts_opus` 门控（见第 6 节 M1 边界）。
- TTS 音频帧改用 without-response 写（现为与文本共用的 with-response 串行队列）。
