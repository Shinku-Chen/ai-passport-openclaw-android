# 小智 AI 网关（Xiaozhi AI）设计方案

> 状态：**待评审**（2026-10-03 起草）。评审通过后再动代码；本文只描述设计与验证计划。

## 1. 目标

在 App 的「网关设置」里增加**第五种网关类型：小智 AI**，把现有小智会话从「只做语音识别（STT）」
延长为**一条完整的 小智 AI 流程**：上行语音 → 小智 ASR → 小智 LLM → 小智 TTS → 回复上屏 + 语音在设备上播出来。

- 后端：**官方小智云**（复用 App 里已有的激活流程与凭据，见 `stt/XiaozhiActivator.kt`）。
- 语音：**小智的 TTS 音频原样转发**给设备播放（音色 = 小智，句级流式），手机不再本地合成 TTS。
- 形态：**纯 App 改动**，固件不需要改、不用刷机（论证见 §3）。
- 与现有三种网关（OpenClaw / Hermes / 自定义 OpenAI 兼容）**并列**，不改变它们的行为。

### 非目标（本期不做）

- 小智的 MCP / 工具调用扩展、自建小智服务器（地址可留作二期）、音色与角色选择、情绪字段上屏。
- 不改动固件的 BLE 协议、分区、上屏格式与省电行为。

## 2. 现状（代码地图）

| 位置 | 现有职责 | 与本设计的关系 |
| --- | --- | --- |
| `stt/XiaozhiStt.kt`（73 行，薄适配器）+ `stt/XiaozhiSession.kt`（828 行，**已抽出的 WS 会话层**） | 会话层负责 hello 握手、上行 opus（16 kHz/60 ms 单声道）、预热（`WarmLink`）、断线重连重放（`TurnRecovery`），并把 `stt`/`llm`/`tts` 三类消息分流；适配器只保留「`{"type":"stt"}` → 文本」 | ✅ 已抽离（2026-10-03）；下一步在会话层之上实现 LLM/TTS |
| `stt/XiaozhiActivator.kt`（208 行） | 官方 OTA → 手持绑定码 → `activate` 轮询 → 授权（`lancelot` + HMAC） | 直接复用，激活状态是「小智 AI」能否可用的前提 |
| `stt/XiaozhiSettings.kt` | 小智开关（当前恒为 true） | 扩展为「小智 AI」的配置入口 |
| `pipeline/VoicePipeline.kt` | 一轮的编排：`turn_start` → 音频喂 STT → `endTurn()` 出文本 → 网关 `chatMulti` → 上屏（`sendText`）→ 设备朗读（`DeviceTtsSession`） | 新增「小智直连」模式的分支 |
| `tts/DeviceTtsSession` + `TtsFraming` + `TtsFlowControl` | 下行 TTS：本地合成 PCM → 编 Opus → `TTS_OPUS` 帧，含领先量/在途上限/`tts_abort` | **复用其下行与流控**，跳过本地合成 |
| `gateway/GatewayAdapter` + 4 实现 | 文本进 → 回复出（含 `chatMulti` / 历史补正） | 新增类型需要不同的编排入口（见 §4） |
| `ui/SettingsFragment` | 二级菜单（网关设置 / 设备管理 / 对话设置 / 应用设置 / 高级） | 网关设置的「网关类型」加一项 |

## 3. 协议要点与两处已核对的结论

一条小智会话里同时承载四件事（同一 WS）：

1. **上行**：设备音频 → App → 小智（opus 16 kHz/60 ms，现有实现已跑通）。
2. **识别**：`{"type":"stt","text":…}` —— 现状只用这一条。
3. **回复**：`{"type":"llm","emotion":…,"text":…}` 与 `{"type":"tts","state":"sentence_start","text":…}` —— 本设计新增使用。
4. **语音**：`{"type":"tts","state":"start|sentence_start|sentence_end|stop"}` + **二进制 opus 音频帧** —— 本设计新增使用。

**已核对的结论（决定「不用改固件」是否成立）**：

- 小智 hello 自报 `audio_params: {format: opus, sample_rate: 24000, frame_duration: 60}` → **TTS 音频是 24 kHz**。
- 固件侧：`main/oc_tts.c` 明确接受 `rate_khz == 16 || 24`；`main/oc_audio.c` 在速率变化时
  **重新 `opus_decoder_init(rate)` 并重开 codec**（`bsp_audio_set_format(rate*1000,16,1)`），PCM 帧长按
  `rate_khz` 计算 → **24 kHz 的 TTS 音频可以被原样解码播放**，无需固件改动，也无需手机侧重采样/重编码。
- `TTS_OPUS` 帧格式为 `[SEQ][rate_khz][frame_ms] + opus`（`main/oc_proto.h`），App 侧只要把
  小智的「sample_rate/frame_duration」映射进这两个字段即可。

## 4. 架构

```text
设备(BLE) ── opus 上行 ─┐
                        ├─ XiaozhiSession（新：从 XiaozhiStt 抽出的 WS 会话层）
小智云 WS ─────────────┘        ├─ 激活/预热/重连重放（沿用现有实现）
                                ├─ STT：{"type":"stt"} → 文本
                                ├─ LLM：{"type":"llm"} → 正文
                                └─ TTS：JSON 状态 + 二进制 opus 帧
                                        │
                       ┌────────────────┴─────────────────┐
                       │                                  │
            XiaozhiDirectPipeline 模式          现有模式（本地 STT + 网关）
                       │                                  │
      ┌────────────────┼────────────────┐        （OpenClaw / Hermes / OpenAI 兼容）
      │                │                │
 上屏 TEXT('A')   设备朗读直通        状态卡/通知
                 （TTS_OPUS 原样转发）
```

### 4.1 会话抽离

（✅ 已完成）WS 连接、hello、opus 编码、预热、重连重放已从 `XiaozhiStt` 抽到 `stt/XiaozhiSession.kt`，
`XiaozhiStt` 退化为「只负责 `stt` 事件 → 文本」的适配器（现有行为与日志保持不变）。
`XiaozhiSession` 暴露三组回调：`onStt(text)` / `onLlm(text)` / `onTtsState(state, text)` / `onTtsAudio(opus, rateKhz, frameMs)`。

### 4.2 新增网关类型与模式

「小智 AI」与其他三种网关的本质区别：**它不是「文本进、文本出」**，而是自带 ASR/TTS 的整条链路。
因此实现上不是简单再加一个 `GatewayAdapter` 实现，而是：

- 设置里仍然叫「网关类型 = 小智 AI」（满足用户心智：它就是一个可选的 AI 后端）；
- `VoicePipeline` 增加 `mode = LocalStt | XiaozhiDirect`：选中小智 AI 时走 `XiaozhiDirect`，
  一轮的输入直接是**设备音频**（不经过 App 的 `SttEngine` 抽象），输出是正文 + TTS 音频。
- `GatewayAdapter.chatMulti(text)` 在小智模式下**不再被调用**；`XiaozhiGateway` 只把
  「正文」与「TTS 音频」两路回给管线（接口收窄为 `onReplyText` / `onTtsAudio`，便于单测）。

### 4.3 TTS 直通

- 复用 `TtsFlowControl`（在途上限、领先量）与 `DeviceTtsSession` 的下行发送、`tts_start`/`tts_stop`/`tts_abort`、
  以及设备回报（`tts_playback_done` / `tts_playback_aborted`）对账。
- **跳过** `TtsFraming` 的本地「PCM → Opus」：小智已给出 opus 包，直接按
  `[SEQ][rate_khz=24][frame_ms=60]` 组 `TTS_OPUS` 帧。
- 小智的 `sentence_start` → 设备 `tts_start`（句级流式，不整段等）；`tts.stop` → 设备 `tts_stop`。

## 5. 一轮的时序（小智模式）

1. 按下 OK → `turn_start`（设备变红）→ App 立即 `XiaozhiSession.startTurn()`（沿用预热，就绪毫秒级）→
   下发 `turn_ready`（设备变绿）。
2. 设备音频逐帧上行；小智回 `{"type":"stt","text":…}` → **上屏 `TEXT('U')`**（与现有观感一致）。
3. 小智回 `{"type":"llm","text":…}` → **上屏 `TEXT('A')` 一次整段**（理由见 §6）。
4. 小智回 `tts.state=start/sentence_start` + opus 帧 → 设备开始用**小智音色**朗读；
   句级流式靠 `TtsFlowControl` 控制领先量。
5. `tts.state=stop` → 设备侧收尾；一轮结束回到就绪。
6. 用户在朗读过程中再按 OK → App 发小智 `abort` + 设备 `tts_abort`（打断），回到第 1 步。

## 6. 上屏策略（本设计里唯一有取舍的点）

小智的正文按句到达（`sentence_start`），而设备侧一条 `TEXT` 帧就是**一个气泡**，协议没有「就地更新气泡」。

- 方案 A（**推荐**）：用 `{"type":"llm","text":…}` 的**整段文本一次上屏**。观感与现有网关一致
  （一条回复一个气泡），实现最简单；代价是比句级晚一点点（通常只晚首句之后的一小段）。
- 方案 B：句级上屏 → 会出现一串碎气泡，阅读体验差。
- 方案 C：等 `tts.stop` 再整段上屏 → 太晚，设备屏在朗读期间是空的。

若小智在某些场景**不返回 `llm`**（只有 `tts.sentence_start`），则退化为：把同样时段内收到的
`sentence_start.text` 拼接起来，在**首句后 ~600 ms 无新增**或 `tts.stop` 时上屏一次。

## 7. 设置与文案

- 「网关设置 → 网关类型」新增第 5 项 **小智 AI**；选中后只显示小智相关项：
  - 激活状态（已激活 / 需要激活 → 显示 6 位绑定码与授权进度，复用现有激活 UI）；
  - 连接状态（WS 已连接 / 重连中 / 离线）；
  - 说明行：「回复由小智生成，并用小智的声音在设备上朗读」。
- 「对话设置」中依赖本地管线的项（语音附加提示、完整回传流）在**小智模式**下置灰隐藏。
- 保存后立即生效（与现有网关一致：不重启服务）。

### 7.1 返工修订（2026-10-04，以作者要求为准）

- **小智识别整块 UI 只在「高级」里有一份**：网关设置页不再重复摆「激活状态 / 连接状态 / 说明行」；
  两个 section 共用同一批控件会让同一状态被两处各自拨可见性、行为容易分叉，因此按「只留高级那一份」实现。
- **Device-Id = 已连接对讲设备的真实蓝牙 MAC**（`BleCentral.lastConnectedAddr()`，格式沿用 OTA 的
  `mac` 字段：`XX:XX:XX:XX:XX:XX` 大写）。OTA/绑定与识别 WS 用同一个值；设备未连接时给出可读提示
  并停手，**不**回退手机侧标识（全零匿名 MAC 等）。
- **保存「小智 AI」走设备绑定闸门**（与其它三种网关的「校验-落盘」闸门同一语义）：设备未连接 → 拦截；
  已绑定当前设备 → 直接保存；未绑定/换了设备 → 复用 `XiaozhiActivator` 亮出 6 位绑定码并轮询授权，
  成功才落盘；失败/超时**不落盘**，原配置继续生效并给出可读原因。绑定结果按 MAC 持久化（`xiaozhi_binding`），
  已绑定则不重复绑定。

## 8. 异常与回退

| 情况 | 行为 |
| --- | --- |
| 未激活 / 激活过期 | 状态卡给出可读原因 + 引导重新激活；对话不可用但不阻断其他网关 |
| WS 断线 | 沿用 `TurnRecovery`（一轮内重连重放一次）；持续失败则提示并可切回其他网关 |
| 小智只回 STT 不回 LLM | 视为失败，提示「小智没有返回回复」，**不发**空气泡 |
| TTS 无音频或解码失败 | 设备回报 `tts_playback_aborted` → 记录并只保留文字（不回退手机合成，保持行为可预期） |
| 打断 | 小智 `abort` + 设备 `tts_abort`，两条路径都要幂等 |

## 9. 验证计划

- **JVM 单测**：小智消息解析（stt/llm/tts 状态机）、句级拼接上屏策略、打断与超时、速率字段映射
  （24 kHz → `rate_khz=24`）、TTS 直通组的帧长/序号回绕。
- **真机**（一台设备 + 一台手机）：
  1. 对小智说一句 → 设备上屏正确、朗读是**小智音色**；
  2. 长回复 → 句级流式不卡顿（借用现有流控日志核对在途量）；
  3. 朗读中再按 OK → 立即打断、进入下一轮；
  4. 停掉网络 → 出现可读提示；恢复后能继续；
  5. 切回 OpenClaw → 原行为不受影响（回归）。
- **发版形态**：**App 1.13.1**（与固件 1.13 同大版本；固件不动、不用刷机）。
  若最终发现需要固件配合，再按「成套发版」处理。

## 10. 风险与待确认

1. **官方小智云的用量与服务条款**：把 LLM + TTS 也放到云上，是比「只做 STT」大得多的用量；
   一期先按官方默认策略走，若配额/条款有变化再评估自建服务器。
2. **上屏策略**（§6 的方案 A + 退化逻辑）是本设计里最需要你拍板的一处。
3. **TTS 直通与现有 `DeviceTtsSession` 的耦合**：需要小心保留「`turn_start` 无条件 `tts_abort`」
   这一既有不变量（防止上一轮音频残留）。
4. 小智的 `sentence_start` 文本与 `llm` 文本可能不完全一致（含情绪标记/语气词），需要以 `llm` 为准并在
   单测里固定规则。
