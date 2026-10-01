# Changelog

## Unreleased

- 新增：**点「保存网关设置」需要授权时弹出等待框**。
  - 保存校验进入「等待网关授权」状态即弹对话框：显示原文（含 deviceId）、控制台 Devices 页路径、
    `openclaw devices list` + `openclaw devices approve <deviceId>`，并说明批准后会自动继续校验保存；
    批准成功 / 超时 / 失败时自动关闭，重试循环不受影响。
- 修 bug：**设备的网关状态更新不及时（一直停在「网关 连接中」）**。
  - 根因：`monitorGateway()` 在「尚未就绪、且没有失败原因」时**直接 return**，从不主动探活；
    而 OpenAI 兼容 / Hermes 适配器是“一问才会连”的，不主动发请求就永远不 ready ——
    于是设备屏与状态卡长期停在 connecting。
  - 修复：新增 `maybeProbeGateway()`，未就绪且无错误时**每 10 秒主动探活一次**，
    探通即播报「网关已就绪」并强制同步给设备；探活失败只记日志，不污染 `lastError` 语义。

- 修 bug：**设备屏的「网关 就绪」与 App 状态刷新不及时**。
  - 根因：`monitorGateway()` 在网关 `isReady()` 时，只在前一次报过错的情况下才播报「网关已恢复连接」；
    首次由 connecting → ready 的转变什么都不下发，于是设备屏一直停在「网关 连接中」，
    直到下一次状态变化（或用户手动操作）才更新。
  - 修复：监控每轮（6 秒）调用一次 `syncGatewayStateToDevice(force = false)` —— 把当前网关状态同步到设备，
    重复内容由 `publishGatewayState` 的 3 秒去抖拦掉，不刷屏；链路刚就绪时仍按原来的强制同步走。

- 新增：**等待网关授权时给出可操作的提示**（不再只有一行灰字）。
  - 顶部状态栏的按钮在等待授权期间由「刷新」变为「**去授权**」，点击弹出步骤说明。
  - 状态第一次进入「等待网关授权…」时自动弹一次对话框：显示原文（含 deviceId）、
    控制台 Devices 页批准路径、`openclaw devices list` + `openclaw devices approve <deviceId>` 命令行路径，
    并说明批准后 App 会自动继续（每 5 秒重试、最多 180 秒）。同一文案只弹一次，避免反复弹窗。
- 修 bug：**未配对的设备在设备列表里显示成「已连接」**。
  - 根因：设备卡片直接用原始链路状态当文案（`LINK_CONNECTED` 的值就是「已连接」），
    而 `LINK_CONNECTED` 的含义其实是"连上但还没配对/加密"。
  - 修复：卡片状态改为映射文案——未配对显示「未配对：等待配对完成」，加密后「已配对（已加密）」，
    可用了「已连接（已就绪）」；`isLinkUp()` 同步收紧为"必须完成配对(加密)才算连上"。

- 修 bug：**点「断开设备」后，设备列表里仍然显示「已连接」**。
  - 根因：`VoiceBridgeService` 的连接监控每 6 秒检查 `ble.isConnected()`，断开就无条件 `rescan()` 自动重连；
    `ACTION_DISCONNECT` 只停了 BLE，没告诉监控“这是用户主动断开的”。
  - 修复：新增 `userDisconnected` 标记（`ACTION_DISCONNECT` 置位、`ACTION_SCAN` 清除），
    监控在标记为真时不再自动重连；标记只在内存，App 重启后恢复“自动重连上次设备”的既有行为。
- 修 bug：**新加 OpenClaw 网关/设备时，等待授权的状态不明确，而是报了别的错误**。
  - 根因：设备审批识别只覆盖 `NOT_PAIRED` / `PAIRING_REQUIRED` /「not approved」/「pairing required」；
    网关换别种说法（如 `DEVICE_NOT_APPROVED`、「unrecognized device」、「device not registered」）就落到普通错误分支。
  - 修复：`isPairingRequired()` 扩展覆盖上述错误码与文案（含「approve + device」兜底），
    一律走可恢复的「等待网关授权：请在 OpenClaw 控制台批准本设备 (deviceId …)」；
    新增单测覆盖 8 种新写法。
- 变更：**版本号与固件/社区对齐到 `1.8.0`**。
  - App `versionName` `0.1.2 → 1.8.0`（`versionCode 4`），对应固件 release tag `v1.8.0-intercom`；
    约定写进 `AGENTS.md`：固件 `vX.Y.Z-intercom` ↔ App `X.Y.Z`，社区作品说明里注明同一版本号。

- 修 bug：**配对输完 6 位密码后卡在「已连接,等待加密」，设备屏的配对码面板也不消失**。
  - 真机现象：设备重启后重新配对，App 输入密码后一直停在「已连接,等待加密」；
    设备侧的配对码对话框也不再自动消失（只能断开重来）。
  - 根因：`BleCentral` 在 PIN / PASSKEY / DISPLAY_PASSKEY 这些**输入类**配对变体上提交密码时，
    同时调了 `setPin()` 与 `setPairingConfirmation(true)`。`setPairingConfirmation()` 只属于
    `PAIRING_VARIANT_PASSKEY_CONFIRMATION`（两侧显示同一数字需确认）；在输入类变体上附带确认
    会打断协议栈的状态机，加密阶段就此停住——App 等不到 `BOND_BONDED` 广播，设备也等不到加密完成事件。
  - 修复：输入类变体**只提交 `setPin()`**；确认逻辑仍留在 `PAIRING_VARIANT_PASSKEY_CONFIRMATION` 分支。
  - 固件侧同步：把「配对已完成」的判定从“手机订阅完成”提前到“加密完成”，并给配对码面板加 90 秒
    兜底超时（`main/oc_app.c` / `main/oc_ui.c`），对端中途放弃时不会一直挡着屏幕。
- 修 bug：**设备关机重启后 App 重新连接特别慢**。
  - 真机现象：设备重启后要等很久才连上，有时还得手动去设备页点「扫描并连接」。
  - 根因：重连路径固定“退避 8 秒 + 重新扫描”（`scheduleReconnect(8_000L)`），而设备重启只需几秒；
    启动时那次按地址直连失败后也没有重试或扫描兜底，双向都慢。
  - 修复：新增纯逻辑策略 `LinkRetryPolicy`——前 3 次**直连上次记住的地址**（间隔 1.5 秒，
    覆盖“设备刚重启完”这个主场景），连续失败后再改为 8 秒退避 + 扫描（覆盖设备换地址/绑定被清除）；
    并给直连加上 10 秒**连接超时**（Android 的直连请求本身不超时，设备不在时会无限期挂着）。
    新增 `LinkRetryPolicyTest`（4 个用例）。
  - 同一轮真机验证中又定位并修掉两个“配对卡住”的帮凶：
    - **单飞保护**：`rescan()` 与“启动后自动重连”会几乎同时发起连接，同一设备上出现两个
      `BluetoothGatt` 客户端与两条 `onConnectionStateChange` 回调，加密/配对状态机被搞乱——
      现在同一设备已有连接在途时忽略重复请求。真机验证：重复的「已连接」消失，
      加密在连接后 18 ms 内完成（此前一直停在「已连接,等待加密」）。
    - **配对广播不再依赖 `targetDevice`**：原来是 `targetDevice ?: return`，一旦 `targetDevice` 一时
      为空（例如刚 rescan 过）配对请求就被静默丢弃——手机不弹输入框、设备等不到加密，两边一起卡住。
      现在只在“确实是别的设备”时忽略，并为配对/绑定广播补上日志（含 variant），便于现场定位。

- 重构：把 BLE 设备接入参数收进「设备档案」，为以后接入别的品牌做准备（**行为不变**）。
  - 新增 `ble/DeviceProfile.kt`（档案数据类：服务/RX/TX/CCCD UUID、广播名前缀、请求 MTU、配对方式、
    帧格式、音频参数、设备能力）与 `ble/DeviceProfiles.kt`（档案目录：AI Passport 档案 + `byId`/`detect`）。
  - `BleCentral` 不再直接读 `BleNus` 常量，而是读 `profile`（当前 = `DeviceProfiles.default` = AI Passport 档案）：
    扫描过滤、名字二次过滤、服务/特征/CCCD 查找、请求 MTU 全部走档案字段。
  - `BleNus` 保留为**字面值唯一来源**（仍与固件 `voice_bridge_ble.h` 逐字对齐），由档案引用它，
    避免两处各写一份而漂移；档案文件顶部写明新增品牌的做法（加一个档案，必要时再补该品牌的帧编解码器，
    扫描/连接/音频流程一行不改）。
  - 新增 `DeviceProfileTest`（7 个用例）：断言档案与重构前字面值逐一相同（回归防线）、名字匹配仍是
    大小写敏感前缀匹配、`detect`/`byId` 行为，以及档案自身字段自洽（MTU/采样率/帧长/文本上限为正、前缀非空）。

- 修 bug：**解除配对后再配对，卡在「已连接,等待加密」**。
  - 场景：先在设备页正常配对成功，再到手机蓝牙设置里解除配对，然后在 App 里重新连接。
  - 根因：手机侧 bond 已清，但连接还活着（设备侧还留着旧绑定）——两边都不会再发起 `createBond`，
    界面就永远停在「等待加密」；之前只有“重连/重扫描”才可能重新走配对。
  - 修复：`VoiceBridgeService` 新增**加密看门狗**：连上后 12 秒仍未加密就重建一次连接，
    让 `BleCentral.ensureEncryption()` 重新走一遍（该弹配对码输入框时就会弹），状态同时显示「等待加密超时，重新建立连接…」。
    若连续重建 3 次仍未加密（手机与设备侧配对状态对不上时会发生），改为显示可操作提示：
    「配对卡住了：请到系统蓝牙里取消配对本设备，再回设备页点「扫描并连接」」，不再无声地反复重试。

## 0.1.2

- 新增应用图标：以 AI Passport 设备外观为主体、屏幕里是 OpenClaw 的红色吉祥物；自适应图标（前景按 72dp 安全区缩放，圆形/圆角遮罩下主体完整）、圆形图标与各密度 PNG 一应俱全，并接上 `AndroidManifest` 的 `icon`/`roundIcon`（此前用的是系统默认图标）。

- 修 bug：**切换网关（Hermes → OpenClaw）后误报「网关不可达」，重启 App 才正常**。
  - 真机现象（抓包原文）：`网关配置已重载: type=openclaw host=… port=18789`（重载成功、配置正确），紧接着
    `网关配置已重载,正在重连… — unknown method: usage`，并把这条 detail 下发给设备；重启 App 后一切正常。
  - 根因：概览页对每个分区都发辅助查询（`rpcUsage()` / `rpcSkills()` …），而该网关实测**不存在** `usage`，
    回 `unknown method: usage` —— 关键操作与辅助查询**没有区分**，错误被写进网关的 `lastError`；
    随后重载/重连的状态文案把这条**过期错误**拼了上去，链路一直正常的连接看起来就成了「网关不可达」。
    更隐蔽的是 `onSocketDown` 只在 `lastRpcError == null` 时才写断开原因，于是**真正的**断开原因反而被这条旧文案挡掉；
    App 重启后 `lastError` 为空，所以「重启后正常」。
  - **关键 / 辅助错误隔离**：`OpenClawGateway.rpcQuery(method, params, critical: Boolean = false)` 新增 `critical`
    （默认非关键）。**关键**= `connect` 握手 / `chat.send` / 重连本身：失败才允许写 `lastError`、影响网关状态；
    **辅助**= 概览/用量/技能/渠道/会话列表/定时任务等查询，以及正文补正用的 `chat.history`：失败只经 `RpcResult`
    返回给调用方，**绝不写 `lastError`、绝不影响网关状态、不发状态文案**。判定抽成纯函数
    `shouldWriteGatewayError(critical, code, message) = critical && !isUnknownMethod(code, message)`：
    辅助查询一律不写；关键操作除「网关不认识这个方法」外都写；每条请求自带 `error`，
    因此辅助查询失败仍能拿到可读原因。
  - **状态文案不再携带过期错误**：重载/重连**开始**前先清掉上一次的错误（新增 `GatewayAdapter.clearLastError()`，
    OpenClaw / Hermes / 自定义 OpenAI 兼容重写；`VoiceBridgeService` 的 `reportedGatewayError` 同步置空，
    重连路径用 `lastReconnectReason` 兜底保证重连循环不会因缺原因而停），状态 detail 只拼**本次尝试**的原因。
  - **`unknown method` / `missing scope` 不再算网关故障**：`mapRpcError` 新增 `unknown method` 分支 → `state=ready`
    （不再落到兜底的 `offline`）；辅助查询的失败日志按「能力/权限 vs 连接层」分类（纯函数
    `isBenignQueryError`）；监控路径对 `isUnknownMethod` 的原因不播报、也不驱动重连（只拦这一个，
    重连循环绝不会因权限类原因停下）。
  - **概览页对「网关不支持」友好**：命中 `unknown method` / `missing scope` 时显示「该网关不支持用量查询」/
    「当前 token 无权读取用量（需网关 admin 权限）」并**隐藏卡片**（标题+内容一起隐藏）；
    同一会话内记住「不支持」（按配置指纹索引，换网关/地址/token 自动作废），不再每次进页都报一次错；
    其它失败（断开/超时）仍保留卡片并显示可读原因。新增纯逻辑 `gateway/QuerySupport.kt`。
  - 单测：新增 `QuerySupportTest`（11 例：错误→关键性/状态映射、辅助查询失败不得写 `lastError`、
    概览页文案 + 隐藏、会话内记忆按指纹作废）、`OpenClawErrorsTest` 补 `unknown method` 不得报 `offline`；
    单测总数 223 → 235，全绿。文档：`docs/gateway-adapters.md` 新增「2.6 关键 / 辅助 RPC 的错误隔离」。

## 0.1.1

- 打包与签名改由 GitHub Actions 完成：keystore 与口令存放在仓库 Secrets，推送 `v*` 标签即产出并附上签名 APK。
- 修复：进设置页时网关类型下拉显示已保存的类型；保存网关设置只写当前类型那一组。
- 修复：BLE 扫描 code=1 自锁、断开设备后重扫失效；GATT 写入调用失败改为短退避重试。
- 修复：轮换网关 token 后提示「更新 Token」，不再误导向「批准设备」。

- 改进：**「自定义 OpenAI 兼容」支持直接填完整请求路径**（不再写死 `/chat/completions`）。
  - 设置页 `Base Path` 改名 **`请求路径`**（prefs key `openai_base_path` 与输入框 id 不变，旧配置继续生效）：
    填 `/v1` 仍按老行为自动补成 `/v1/chat/completions`；直接填完整端点（如 `/openai/v1/chat/completions`）
    则原样使用；带查询串、连续斜杠、结尾多余斜杠都会归一化。
  - 新增纯逻辑 `gateway/OpenAiPath.kt`（`resolveChatPath` / `modelsPath`，KDoc 写明规则）；
    网关 URL 拼装改为 `origin()`（`http(s)://host:port`，不含路径）+ 解析后的完整路径，
    不再用带 basePath 的 `baseUrl()` 拼接；探活 `GET …/models` 由 chat 路径推导。
  - JVM 单测 `OpenAiPathTest`：空/前缀补全/完整路径/结尾多余斜杠/查询串/连续斜杠 + `modelsPath` 推导。

- 新增：**设备朗读回复（下行 TTS）：手机合成音频 → `TTS_OPUS`(0x06) 帧推给设备播放**（M1）。
  - 网关回复仍照旧经 `TEXT('A')` 上屏（既有行为不变），**上屏之后**再触发一次 TTS 下发；
    默认 **关**（设置项 `tts_enabled`），等真机验收（固件播放通路就绪）后再考虑默认开。
  - **协议帧**（`protocol/VbFrame.kt`，与固件 `intercom-wire-protocol.md` 一致）：
    `TYPE_TTS_OPUS = 0x06`，payload = `[SEQ:1B][rate_khz:1B][frame_ms:1B][Opus 包 ≤512B]`；
    新增 `TTS_HEADER_SIZE = 3`、`TTS_OPUS_PAYLOAD_MAX = 512`（载荷上限 515）、
    `encodeTtsOpusPayload` / `decodeTtsOpusPayload` / `vbEncodeTtsOpusFrame`，
    以及 `VbTtsOpusPayload.nextSeq`（SEQ 1 字节回绕）。
  - **合成可插拔**（`tts/DeviceTtsEngine.kt`）：`id` + `prepare()`（预检：等 `TextToSpeech` 异步初始化，
    `tts_default_synth` 为 null 时给出可读原因 `未安装语音引擎`）+ `synthesize(text, sampleRateHz)` → 单声道
    16bit 小端 PCM。M1 实现 `tts/AndroidTtsEngine.kt`（`synthesizeToFile` → 读 WAV → 取 PCM，
    采样率以 WAV 头为准并在与请求不一致时记日志）；`tts/HttpTtsEngine.kt` 只留骨架 + TODO
    （`prepare()` 恒 false 并给出原因，TTS 服务选型待确认）。
  - **60ms 分帧 → Opus**（`tts/TtsFraming.kt`，复用既有 `com.theeasiestway.opus` libopus 封装）：
    16k → 960 samples / 1920B，24k → 1440 samples / 2880B，每帧一个 Opus 包；
    **尾帧不足一帧补零（静音）成整帧**（libopus 要固定帧长，既保证末字不被截断、也不引入协议外的短帧），
    空 PCM 不发帧、int16 落单尾字节进补零区；**M1 不重采样**：引擎输出不是 16k/24k（实测系统 TTS 可能
    22050Hz）时放弃本轮下发并如实记日志。
  - **下发通路**（`service/VoiceBridgeService.kt` 的 `DeviceTtsPush`，与既有文本下发并列）：
    `{"ev":"tts_start"}` → 逐帧 `TYPE_TTS_OPUS` → `{"ev":"tts_stop"}`；帧只走现有 BLE 串行写队列
    （`BleCentral.writeBytes`，不另开线程猛灌），节流由纯逻辑 `tts/TtsFlowControl.kt` 计算：
    **领先量 = 已推帧数 × 60ms − 自 `tts_start` 起的实时毫秒**，硬上限 2s（= 33 帧未播完就得等，
    对应固件「App 最多领先设备 ≈2s」），实际按 **800ms 目标**节流（设备解码队列只有 24 包 ≈1.44s，
    贴近 2s 会溢出丢最旧的包）。
  - **barge/取消**：设备 `turn_start`（用户再次按下 OK）→ **无条件**发 `{"ev":"tts_abort"}` 并作废在途推送
    （设备可能仍在播上一轮 `tts_stop` 后的 ≤2s 缓冲；该步与设置开关无关，abort 幂等）；
    BLE 断开/服务销毁同样中止；同一轮被新一段朗读替换（历史补正的真答案到了）也先 abort 再重新 `tts_start`，
    避免旧回复残音与新一段叠在一起。编排在 `tts/DeviceTtsSession.kt`（纯逻辑，可 JVM 单测），
    流水线只调 `onTurnStart` / `onReply` / `onCancel` / `onDeviceEvent` 四个点，
    **不阻塞**上屏与下一轮对话（合成/编码/节流都在协程里，放 `Dispatchers.Default`）。
  - **设备回报**：`{"ev":"tts_playback_done",frames,decoded,dropped,underruns,decode_us_max}` /
    `{"ev":"tts_playback_aborted"}` 解析（`tts/TtsPlaybackReport.kt`）后打日志
    `TTS 播放完成: frames=… decoded=… dropped=… underruns=… …`，并与本地「已发送」对账（设备没带的字段显示 `?`，
    不假装 0）。
  - **设置**：新增 `tts_enabled`（默认 **false**）与 `tts_engine`（`android` / `http`，非法值退回 `android`），
    设置页新增开关「设备朗读回复（TTS）」与下拉「设备朗读引擎」（`系统 TTS(Android)` / `HTTP 服务(未实现)`）；
    两项与网关连接无关，因此**切换即落盘**（不参与网关探活/校验，也不需要重启服务）。
  - **单测（JVM）**：`protocol/TtsFrameTest`（6 例：0x06 往返、常量与载荷上限 515、512B 上限与超限拒绝、
    SEQ 回绕、整帧经重组器还原）、`tts/TtsFramingTest`（13 例：16k/24k 帧几何、尾帧补零、短帧/奇数字节、
    计划编码与超限丢弃、不支持采样率、WAV 解析与拒绝）、`tts/TtsFlowControlTest`（6 例：2s = 33 帧边界、
    领先量计算、按节流后领先恒 ≤2s、目标领先留出设备队列余量）、`tts/DeviceTtsSessionTest`（10 例：
    `turn_start` 必发 abort、开关门控、空文本不下发、取消、设备回报转发与日志格式、三条 CONTROL JSON 契约）。
    单测总数 168 → 209，全绿。
  - **文档**：`docs/wire-protocol.md` 补 `0x06` 行、新增第 6 节「TTS 下行（手机合成 → 设备播放）」（payload 表、
    分帧与流控规则、三条 CONTROL 事件、两条设备回报、App 侧时序、M1 已知边界）并同步改动清单/测试/待定项。
  - **M1 已知边界（写明于协议文档第 6 节）**：未解析设备 hello 的 `caps`（固件要求声明 `tts_opus` 才可收 0x06，
    故默认关；后续补门控）、不做重采样、音频帧仍用与文本共用的 with-response 写队列（固件建议 without-response，
    靠 800ms 目标领先吸收 ATT 往返，需真机观察 underrun）、HTTP TTS 仅骨架。

- 修问题：**按下设备 OK 后不能立马说话，要先等「准备中」**（整屏红 0.5–2s 才变绿）。
  - 真机现象：设备按下 OK → 整屏红（准备中）→ 等 App 下发 `turn_ready`（或设备侧 2.5s 兜底）才变绿。
    原因：App 每次按下都要跟小智云端**重做一次 WebSocket 握手 + `hello` 往返**（实测 0.5–2s），
    「按下 → 可说话」因此有明显空窗。
  - **修法：识别通道常驻预热（热连接）**。`stt/XiaozhiStt` 跨轮复用同一条 WS：
    - 一轮结束（`endTurn`）后**不再关 socket**，保留 `hello` 结果作热连接；`barge` 也只发 `listen.stop`
      不关连接（关掉就等于每轮重新握手，正是要修的空窗）；
    - 下一轮 `startTurn`：热连接可用 → **直接 `listen.start`** 并回调 `onReady()`（毫秒级，目标 < 300ms，
      日志 `复用热连接,直接 listen.start(按下即可说话)`）；已断开/闲置超时/尚未预热 → 走原有
      「按下才连」的 `connectAndHello` 路径（行为与未预热时完全一致，日志 `热连接不可用,重连中:<原因>`）；
    - **预热时机**：① 服务启动（`VoiceBridgeService.startBridge` → `pipeline.prewarm()`）；
      ② BLE 链路就绪（`BleCentral.Listener.onReady`）；③ 每轮结束后保留。握手完成日志 `识别通道常驻预热已建立`；
    - **闲置超时**：闲置 ≥ 90s（`WarmLink.IDLE_TIMEOUT_MS`，常量）主动关闭（日志 `热连接闲置超时,已关闭`）；
      有轮次在跑时定时器不关；
    - **立即关闭**：服务停止（`VoicePipeline.shutdown` → `release()`）与 BLE 断开
      （`VoicePipeline.onDisconnected` → 新增 `SttEngine.onLinkDown()`，默认 = `barge()`）立即关 socket；
    - **失败兜底**：复用热连接时 `listen.start` 发不出去 → 自动重连**一次**（`WarmLink.shouldFallbackReconnect`），
      再失败只记日志；预热/复用失败**绝不影响**「按下才连」，不让整轮识别失败。
  - **既有正确性约定不变**：`onReady` 仍只在**本轮识别会话真的建立**（`listen.start` 已进 OkHttp 写队列）时回调
    （`WarmLink.sessionEstablished`；复用热连接时按下即发，因此该回调**可能同步**触发）；
    `turnRunning` 仍在 `startTurn` 一开始置 true，按下起的 Opus/PCM 立即累积上送（**绝不丢开头音频**）。
  - 新增纯逻辑 `stt/WarmLink.kt`：`WarmLink.decide(state, nowMs, idleTimeoutMs)`（可用 → `REUSE` /
    已断开 → `RECONNECT_DISCONNECTED` / 闲置超时 → `RECONNECT_IDLE_TIMEOUT`）、`shouldReuse(...)`、
    `shouldFallbackReconnect(attemptsThisTurn)`、`sessionEstablished(listenStartSent)`；
    新增 JVM 单测 `WarmLinkTest`（9 个用例，含「可用复用 / 已断开重连 / 闲置超时重连 / 时钟回拨不误杀 /
    `onReady` 只在 `listen.start` 发出后放行 / 复用失败只允许一次兜底重连」）。
  - 文档：`docs/gateway-adapters.md` 新增第 8 节「识别通道常驻预热（热连接）」（原「待定」顺延为第 9 节）；
    `docs/wire-protocol.md` 的 `turn_ready` 时机与改动清单同步说明；`docs/README.md` 索引同步。

- 新增：**录音就绪事件 `turn_ready`**（配合固件「按下即红、就绪变绿」）。
  - 设备按下 OK 的瞬间整屏红（准备中），收到 `EVENT {"ev":"turn_ready"}` 才变绿（可以说话）；
    绿色 = 用户此时说话一定能被识别，因此这条事件**只在 App 真的开始录音/识别之后**才发。
  - **发送时机**：`SttEngine.startTurn(onReady)` 的就绪回调 → `VoicePipeline.onRecordingReady`。
    小智云端在服务器 `hello` 回包 + `listen.start` 已发出（识别会话建立）时回调；Vosk 在识别器
    `reset` 完成后回调；系统识别在 `onReadyForSpeech`（麦克风已开）回调。**不在**收到 `turn_start`
    的瞬间发（那时还录不到音），也**不等**识别结果。
  - **帧格式**：与设备→App 的事件帧同格式 —— `[A5][5A][0x04 EVENT][FLAGS=0][LEN]` + `{"ev":"turn_ready"}`
    （`VoiceBridgeService.sendEventFrame`；`VbFrame.TYPE_EVENT`）。协议文档同步更新（`docs/wire-protocol.md`
    第 2 节表格 EVENT 改为双向 + 第 4 节新增 `turn_ready` 实现约定）。
  - **只发一次**：新增纯逻辑 `pipeline/TurnReady.kt`（`TurnReadyGate` + `TURN_READY_EVENT_JSON`）按轮号去重，
    barge/断开后的旧轮迟到回调一律丢弃；**设备未连接/未初始化时不发**，只记 DEBUG 日志
    （设备侧另有 2.5s 兜底超时，不会一直红屏）。可观测日志：`已通知设备: turn_ready(录音就绪)`。
  - 新增 JVM 单测 `TurnReadyTest`（4 个用例：首轮放行、同轮去重、旧轮丢弃、事件 JSON 契约）。

- 改 bug：**文字输入（typed chat）的正文补正从「追加气泡」改成「就地替换」**。
  - 真机现象（同事上次报告的遗留项）：语音路径补正时用 `ConversationStore.replaceById` 就地替换正文气泡，
    文字输入路径却是追加一个 `[来自历史]` 正常气泡 —— 一轮里出现「`[流式]` + `[来自历史]`」**两个正常正文气泡**。
  - **现在统一规则**：`ChatFragment` 把「…」占位气泡当作本轮正文气泡的槽位（`BodyBubbles.onBodyWritten`），
    历史补正到达时**就地替换**它（`ConversationStore.replaceById`，来源标记 `[来自历史]`），**不新增气泡**；
    同一轮若还有其它正文气泡则降级成弱化小字 `正文 · 全文`（新增 `ConversationStore.demoteById`，只改 label/flag）。
    **raw 弱化条目不受影响**，照常追加；补正前的 `[流式]` 来源标记规则不变。
  - **顺序无关**：补正回调是异步的（`OpenClawGateway.holdCollectorForGrace`），两种到达顺序都只留一个正常气泡 ——
    先写流式正文、后到补正（就地替换）；先到补正、后写流式正文（补正先落地，后到的流式正文降级成弱化小字）。
  - 新增纯逻辑 `ui/BodyBubbles.kt`（`BodyBubbles` / `BodyCorrectionPlan` / `writeReplyDisplay` /
    `writeBodyCorrection`）；新增 JVM 单测 `BodyBubblesTest`（8 个用例），核心回归 =
    **补正后同一轮正常气泡数量不因补正而增加**（消息条数也不增加）。

- 改 bug：**同一答案出现两条正常气泡**（body 气泡 + raw 的 `正文 · 全文`），且命中状态话术的 body
  仍会上设备屏。
  - **raw 一律弱化小字**：`replyDisplayEntries` 的输出改为「**body 正常气泡在前 + raw 全部条目弱化小字跟在后**」。
    raw 里的 `正文 · 全文`（`kind=assistant`）与 `终局 · …`（`kind=terminal`）不再当正常气泡（标签原样保留）——
    **正常气泡只留给 body**（含补正后的正文）。`showRaw=false` 仍只输出 body。
    App 侧 `VoicePipeline` / `ChatFragment` 因此不再出现「同一答案两条正常气泡」。
  - **正文来源标记**：body 气泡上标 `[流式]`（未补正、直接来自流式 body）或 `[来自历史]`（经 `chat.history` 补正），
    与既有 `[疑似状态话术]` **可同时存在**（拼接顺序：来源在前、疑似在后，如 `[流式][疑似状态话术]`）；
    设备屏与 TTS 永不带标记。纯函数 `ui/ReplyDisplay.kt` 的 `BodySource` / `bodySourceOf` / `bodyFlag`。
  - **命中状态话术的 body 缓发**：`GatewayAdapter` 新增 `supportsBodyCorrection`（只有 OpenClaw 为 true）；
    `VoicePipeline` 逐条 body 走纯函数 `pipeline/BodyDispatch.kt` 的
    `bodyDispatch(streamedBody, correctedBody, correctedAvailable, historyCorrectionSupported): BodyAction`：
    - 流式命中 `looksLikeStatusTalk` 且通道支持历史补正 → **先不发设备**（仍进 App 调试流），
      日志 `状态话术 body 暂缓下发,等历史补正判定`；
    - 历史补正给出真答案 → **只发补正后的正文**，日志 `历史补正到位,丢弃缓发的状态话术(N 字)`；
    - 历史没有更好的正文 / 宽限窗（`POST_TERMINAL_GRACE_MS + 1000`）走完仍无结论 →
      **补发缓存的流式 body**，日志 `历史无更好正文,补发缓发的状态话术(N 字)`（设备不能空着）；
    - 非状态话术的 body 行为**完全不变**（立即下发，时延不增加）；不支持的通道（Hermes / Echo / 自定义 OpenAI 兼容）**绝不缓发**。
  - **补正不再多出一条正常气泡**：补正到位时用 `ConversationStore.replaceById` 把本轮已写入的 body 气泡
    **就地替换**成 `[来自历史]` 正文（不再是「追加一个新气泡」）；同一轮若还有其它 body 气泡则降级成弱化小字
    （`正文 · 全文`），做到「同一轮只有一个正常正文气泡」；body 为空但历史有答案时仍新增一个正文气泡。
    TTS 行为不变（补正不重新触发 TTS）。
  - 新增纯函数 `pipeline/BodyDispatch.kt`（`bodyDispatch` + `BodyAction`）；新增 JVM 单测
    `BodyDispatchTest`（7 个用例）；`ReplyDisplayTest` 扩充到 11 个用例（含
    「raw 模式下正常气泡数量 == body 数量」「raw 条目全部 label 非空」核心回归与来源标记）；
    文档：`docs/gateway-adapters.md` 新增「2.5 正文来源标记与「状态话术 body 缓发」」。

- 修 bug：**流式帧只推最后一条 assistant 消息**，真正的答案只存在于网关历史里 —— 新增「用 `chat.history` 补正正文」。
  - 真机现象（问「新疆天气是什么？」）：一轮 run 里有多条 assistant 文本消息（查天气 → 工具结果 →
    **答案** → MemOS 工具 → **状态话术**），而流式 `chat delta/final` **只推最后一条**，
    也就是「没有活跃的 exec 会话或子代理…新疆天气已回复完毕…」这类状态话术，设备屏与 App 因此看不到答案。
    网关实测可用的历史 RPC 是 **`chat.history{sessionKey, limit}`**（返回 `{sessionKey, sessionId, messages:[…]}`）；
    `session.history` / `messages.list` / `chat.messages` 等一律 `INVALID_REQUEST unknown method`。
  - **时机**：在既有的**终局后宽限窗**（`POST_TERMINAL_GRACE_MS = 3000`）**之内**取历史，与宽限窗**共用**这段时间
    （查询最多等这一窗），**不额外增加设备侧时延**；只对 `Reply` / `EmptyFinal` 触发。
  - **本轮边界**：以我们发送的用户文本为界（历史里最后一条内容相同的 `user` 消息；找不到时退回最后一条 user 消息）。
    边界之后所有 `assistant` 的 `content[*].type == "text"` 是本轮正文候选；`toolCall` / `toolResult` **不进正文**
    （可作为 raw 条目补充）。
  - **正确正文**：先排除 `looksLikeStatusTalk` 命中项，在剩下的里取**最长**（并列取最早）；一条都没剩（整轮只有状态话术）→
    **保留现有流式 body**（不凭空造）。与流式 body **不同**（trim 比较）时才**再补发一帧 `'A'`** 给设备
    （设备屏最终看到真正的答案），并在 App 里以**正常气泡**展示；**相同 → 什么都不做**（不重复上屏、不重复朗读）。
    日志：`历史补正: 用 chat.history 的答案替换/补发正文(N 字, 流式 body M 字)`。
  - **App raw 全量视图**：本轮历史条目追加在**流式条目之后**（内部保持历史顺序），标签
    `正文(历史) · 全文` / `工具(历史) · <工具名>` / `工具输出(历史) · 全文`（超 200 字截断并标注 `(截断 N 字)`），
    与流式条目**按文本去重**；命中状态话术的仍加 `[疑似状态话术]` 标记。
  - **失败优雅**：`chat.history` 报错/超时/返回空只记一条 DEBUG/WARN 日志，行为**退回现状**（设备下发、App 展示、
    TTS 全不变），且不污染对外的 `lastError`；barge 后旧轮的补正被 `turnId` 过滤丢弃。
  - `GatewayAdapter.chatMulti` 新增可选回调 `onBodyCorrection: ((String) -> Unit)? = null`（只有 OpenClaw 会回调）；
    `VoicePipeline` 用它补发设备帧 + App 正常气泡（不重新触发 TTS），`ChatFragment` 追加 App 正常气泡（与调试开关无关）。
  - 新增纯函数 `gateway/ChatHistory.kt`：`assistantTextsSince` / `pickCorrectBody` / `historyRawEntries` /
    `planHistoryCorrection`；真机历史 fixture `app/src/test/resources/gateway/chat-history-xinjiang.json`（已去敏感字段）；
    新增 JVM 单测 `ChatHistoryTest`（13 个用例）；文档：`docs/gateway-adapters.md` 新增「2.4 用 `chat.history` 补正正文」。

- 修 bug：一轮回复的**终局后帧不再被丢弃**（真机 App 侧 `chat/final` 恒为 0）。
  - 真机现象：`agent lifecycle phase=end` 与 `chat final` 几乎同一时刻到达（实测两者 `seq` 相同），
    `end` 触发定局、`awaitOutcome()` 一返回，旧实现立刻把 `activeCollector` 清成 `null`，
    紧随其后的 `final` 与更晚的 `item/tool/command_output` 结果帧在 `handleReplyPayload` 里被整帧丢弃。
  - **终局后宽限窗**：新增常量 `OpenClawGateway.POST_TERMINAL_GRACE_MS = 3000`，定局后再把 collector
    挂在 `activeCollector` 上 3s 才回收（回收用 `===` 校验：期间已开新一轮 barge 时绝不碰新 collector）；
    窗口内迟到帧照常进 raw，body 的区间规则完全不变。**body / 设备屏 / TTS 不等窗口**：
    `chatMulti()` 拿到结局就立即构造并返回 `ChatReply`，窗口只延期回收 collector。
  - **迟到帧增量**：`GatewayAdapter.chatMulti` 新增可选回调 `onRawUpdate: ((List<RawEntry>) -> Unit)? = null`，
    只报**新增**条目（不重复 `ChatReply.raw` 已发过的、按 `seq` 升序）；`ReplyCollector.snapshotRawAndArmUpdate`
    在同一个锁内完成「取首批快照 + 记游标 + 装回调」，首批 + 增量不重不漏。
  - `VoicePipeline` / `ChatFragment` 把增量条目按与首批相同的规则（正文正常气泡、其余弱化小字标签 +
    「疑似状态话术」标记）追加到 App 对话列表；「App 显示完整回传流（调试）」关闭时增量不上屏；
    barge 后旧轮的迟到条目不再上屏。设备屏与 TTS 完全不走该回调。
  - 新增 JVM 单测 `ReplyCollectorGraceTest`（4 个用例），文档：`docs/gateway-adapters.md` 新增「2.3 终局后宽限窗 + 迟到帧增量」。

- 「所有回复都采集到，然后显示在 App 里」：把下行内容拆成 **body / raw 双路**。
  - **body（正确正文，给设备屏与 TTS）**：`ReplyCollector` 按优先级计算 ——
    ① `agent stream=lifecycle` 的 `phase=end` 帧里 `terminalReply`（`disposition == "visible"`，
    或 `terminalReceipt.terminalDisposition == "visible"`）→ 用 `terminalReply.text`；
    ② 回退到 `[lifecycle phase=start, phase=finishing/end]` 区间内的 `chat state=final`（多条按 seq 全保留，
    边界含同 seq 的 end）;③ 再退到区间内最后一个 `chat state=delta` 的累计全文;
    ④ 非 visible 的 terminalReply、run 结束后（seq > end）才到的 chat 正文**不进 body**。
    `phase=finishing` 不再立即定局，而是进沉降窗口等紧随其后的 `end` 帧（否则收不到 `terminalReply`）。
  - **raw（完整回传流，只给 App）**：新增纯函数 `gateway/RawEntries.kt`
    （`rawEntriesOf` / `lifecycleEventOf` / `truncateToolOutput` / `looksLikeStatusTalk`）。
    按到达顺序收下所有 `event=agent`/`event=chat` 的信息条目，**一条不丢**：
    `status`（`状态 · <phase>`）/ `lifecycle`（`生命周期 · <phase>[(stopReason)]`）/ `step`（`步骤 · <title>`）/
    `tool`（`工具 · <name> · <phase>(error)`）/ `tool_output`（`工具输出 · <title>`，**超 200 字截断并标注 `(截断 N 字)`**）/
    `assistant`（`正文 · 全文`）/ `terminal`（`终局 · <disposition>`）/ `usage`（`用量 · outputTokens=261`）。
    `ChatReply` 新增 `raw: List<RawEntry>`（非 OpenClaw 通道为空）。
  - **展示与开关**：设置页新增开关 **「App 显示完整回传流（调试）」**，默认**开**，状态存 prefs
    （`show_raw_stream`）；切换即落盘（纯 App 展示，不走「保存网关设置」的校验-落盘闸门）。
    开启时 App 对话列表按到达顺序展示 raw 全部条目（`assistant` 正文用正常气泡，其余用弱化小字标签）；
    关闭后 App 也只展示 body（正式演示用）。可选纯函数 `ui/ReplyDisplay.kt` 的 `replyDisplayEntries`。
  - **设备与 TTS 仍只吃 body**：说话期间按 body 逐条发 `'A'` 帧，TTS 读 body 合并文本，raw 与标记不下发设备。
  - 对 body 命中「疑似状态话术」（`已回复完毕` / `已答复完毕` / `无待处理事项` /
    `无进行中的(任务|exec 会话|子代理|子会话)` / `无活跃(exec 会话|子代理)`，含 `没有进行中的…`、`无活跃 exec…`）
    **不删不拦不改写**，只在 App 对应条目上加标记 **`[疑似状态话术]`**，便于看出这是网关的行为问题。
  - 新增真机抓帧 fixture `app/src/test/resources/gateway/gw-probe-run2.jsonl`（北京天气，terminalReply=visible）、
    `gw-probe-run4.jsonl`（状态话术 + 八类条目）、`gw-probe-note.jsonl`（带工具那一轮），
    并新增/扩充 JVM 单测 `RawEntriesTest`、`ReplyBodyTest`、`ReplyDisplayTest`。
  - 文档：`docs/gateway-adapters.md` 新增「2.2 body / raw 双路：设备只要正确正文，App 要看到全部」。

- 修 bug：一轮网关会话可能有**多条回复**，现在全部收取并全部上屏（不再只留最后一条）：
  - 真机现象：问北京天气时，App/设备显示的是后到的一条状态消息（「（无进行中的后台任务或子会话…）」），
    天气答案被冲掉。旧 `ReplyCollector` 每次 `set(full)` 覆盖收集器，最后一帧赢。
  - `ReplyCollector` 改为收集**消息列表**，`OpenClawGateway.chatMulti()` 返回整轮全部回复；
    `VoiceBridgeService` / `VoicePipeline` 对每条消息各发一帧 `'A'` TEXT 给设备（设备端每条一个气泡，最多 6 个），
    每条各占 App 对话列表一个气泡（顺序与到达一致）；Hermes / 自定义 OpenAI 兼容 / Echo 仍是「一条回复」，行为不变。
  - 正文来源收紧：**只有** `event=chat` 的 `state=delta` / `state=final` 才是回复正文；
    `state=status` 与 `agent` 各 stream（run_status/lifecycle/item/tool/command_output/usage/assistant）只走进度/状态面板，不进正文。
    帧里带 `sessionKey` / `runId` 时必须与本轮一致，否则丢弃（避免串到别的会话/别的 run）。
  - 分段规则（抽成纯函数 `gateway/ReplyMessages.kt` 的 `replyMessagesOf` + `mergeMessages`）：
    新文本以当前消息开头 → 同一消息就地替换为更全文本；否则结束当前消息、新开一条；连续完全相同去重；
    `state=final` 或 `agent lifecycle phase=finishing/end` 结束本轮。
  - 超时/断连/被打断不再把答案丢光：`ReplyOutcome` 各分支都带已收到的部分消息，
    `ChatReply(messages, error)` 把部分消息与可读原因一起交给调用方。
  - 新增真机抓帧 fixture `app/src/test/resources/gateway/chat-frames.jsonl` 与单测
    `ReplyMessagesTest` / 扩充的 `ReplyCollectorTest`（覆盖：单条、答案+后续状态两条、delta=final 去重、
    累计增长、跨会话/跨 run 丢弃、进度帧不进正文、超时/断连保留部分消息）。

- 语音输入附加「回复约束提示词」(`voice_prompt_suffix`)：
  - 新增设置项，默认 `请用不超过 200 字回复，不要使用 emoji 表情，也不要使用 Markdown 表格。`，设置页可编辑
    （字段「语音附加提示（仅语音输入时追加）」+ 常驻 label），清空即关闭；**校验通过才落盘**，与其它字段一致。
  - 只作用于语音输入：发给网关的文本 = `STT 原文 + "\n" + 提示词`（`VoicePrompt.compose`）；
    对话框**文字输入绝不追加**；提示词为空/全空白不追加；STT 原文为空时只发提示词。
  - 设备屏 `'U'` 帧仍只显示 STT 原文（提示词不上屏）；对话列表在用户气泡下方另起一行弱化小字
    「＋附加提示：<suffix>」，让用户看到实际发给网关的完整文本。**不截断**网关回复。
  - 组合后的长文本仍走 2048B/帧的 UTF-8 安全分片；提取纯函数 `splitTextPayload` 时修正了原内联循环的
    字符边界 off-by-one（旧实现会把中文切到两片中间、并可能丢掉末尾续字节）。
- 修 bug：保存网关设置后 **服务必须重载配置**（原来只有网关类型变化才重启）：
  - 新增 `VoiceBridgeService.ACTION_RELOAD_SETTINGS`；保存成功且类型不变时由设置页触发，
    服务重建适配器并重连，**不重启服务、不动 BLE 链路**；类型变化仍保留原有重启逻辑。
  - 日志打一行 `网关配置已重载: type=… host=… port=…`（**绝不打印 token**）。
  - 「是否需要重载」抽成纯函数 `needsGatewayReload(prev, next)`（比较 `GatewayConfigSnapshot`），
    JVM 单测 `GatewayReloadTest` 覆盖「仅 host/port/token 变化也必须重载」。
  - 修的是「只改 host/端口/token 时保存成功、prefs 已写，运行中的服务却还在用旧配置」
    （真机表现：顶部横幅一直「网关未配置,请在 App 设置中填写」）。

- 「等待网关授权」不再显示成含糊的「网关断开/鉴权失败」:OpenClaw 网关对每台设备做 ed25519 设备配对,
  新设备首次连接会被回 `NOT_PAIRED: pairing required: device is not approved yet`,现在这一情况被单独识别:
  - 运行期(服务连网关):进入独立的「等待网关授权」状态，状态词仍是四态里的 `connecting`，
    detail 为「等待网关授权：请在 OpenClaw 控制台批准本设备 (deviceId 前8位…)」(完整 deviceId 写日志)；
    视为**可恢复**，重连/退避循环照常运行，批准后下一次重试自动恢复并走既有 `publishGatewayStatus`
    路径播报「网关已恢复连接」；新增状态位 `GatewayAdapter.isAwaitingPairing`。
  - 保存网关设置：校验失败原因若是「等待网关授权」(而不是配置错)，按钮改显示「等待授权…」并置灰，
    日志逐行记录，**每 5s 自动重试、最长 180s**(常量，与回复等待上限解耦)，通过即自动落盘并提示
    「授权完成，网关设置已保存」；超时给出含 deviceId 前 8 位与「请在 OpenClaw 控制台/CLI 批准后重试」
    的明确文案。仍然遵守既有硬规则：**校验不通过绝不落盘**。
  - 判断逻辑抽成纯函数 `gateway/OpenClawErrors.kt` 的
    `mapRpcError(code, message, deviceIdShort): GatewayStatus`(state/detail/awaitingPairing/recoverable)，
    `GatewaySaveGuard.validate()` 返回 `Ok` / `AwaitingPairing` / `Failed` 三态，新增 JVM 单测
    覆盖 NOT_PAIRED、INVALID_REQUEST+device、missing scope、EHOSTUNREACH、SocketTimeout、
    HTTP 401/403/404 与「等待授权不被当成致命错误且绝不落盘」。

- 界面精简(仅 UI / 接线改动,不动 BLE 协议与连接逻辑):
  - 移除「通知」Tab:底部 Tab 由 5 个减为 4 个(对话 / 概览 / 设备 / 设置),`NotificationsFragment`、
    `NotificationAdapter` 与 `fragment_notifications.xml` / `item_notification.xml` 一并下线
    (主界面 `createFragment()` 索引同步改为 0/1/2,其余落到设置页)。
  - 设备页显示**真实连接的设备**:服务在状态广播 `ACTION_STATUS` 上新增
    `EXTRA_DEVICE_NAME` / `EXTRA_DEVICE_ADDR`(地址断开后仍保留,便于重连),设备页据此显示
    「已连接的设备」卡片 = 广播名(Passport-XXXX)+ MAC + 链路状态(已连接 / 已加密 / 已就绪);
    未连接时不显示卡片,只有状态行。新增 `ACTION_REQUEST_STATUS`:进入设备页主动问一次当前状态,
    不必等下一次状态变化。扫描 / 断开两个按钮保留。
  - 设置页:网关类型由 4 个单选按钮改为**下拉菜单**(选项 OpenClaw / Hermes / 自定义 OpenAI 兼容 /
    Echo(本地回环),切换仍只切可见分组、保存时才落盘);删除「启动/停止语音桥服务」两个按钮
    (服务常驻,主界面 onStart 自动拉起);所有字段补常驻 label(小字灰色,hint 输入后消失也认得出字段);
    全部 CheckBox 改为 MaterialSwitch(使用 HTTPS/WSS、允许自签证书、Hermes 的
    useTls/allowInsecure/serverSideConversation/stream、OpenAI 的 useTls/allowInsecure/stream),
    默认值与保存逻辑不变。

- 网关回复等待上限默认 180s 且可在设置页配置(15–900s);设备侧
  网关状态统一走 publishGatewayStatus(监控路径不再漏推)、链路就绪后主动同步一次、
  四态映射补全 ready/connecting/working/offline;流水线状态流转补全(录音中→识别中→
  等待网关→就绪),并修正 CONNECT_TIMEOUT 相关文案。
- 修复「设备说完话,App 立刻显示(网关无回复)」:OpenClaw 的 WS 一断开,`onSocketDown()` 原来直接
  `activeCollector?.finish()`,用**空文本**结束在途回复收集 → `chat()` 立即返回 null → 上层写成
  「(网关无回复)」(实测 277ms 就报,而网关的 final 只是晚到)。现在断开**不是**这一轮的结局:
  只把本轮标记为「连接中断」(记原因),由 `OpenClawGateway` 在剩余时限内按指数退避重连,
  并用**同一幂等键**重新订阅、继续等同一个 `runId`;到时限仍无结果时返回可读中断原因
  (`网关连接中断:<原因>`),设备屏与对话列表直接显示它,不再出现裸的「无回复」。
  真正判「空回复」的条件收窄为「**收到 final 且文本为空**」(新增 `ReplyOutcome.EmptyFinal`,显示「(网关空回复)」)。
  回复收集逻辑抽成纯逻辑 `ReplyCollector`(`gateway/ReplyCollector.kt`),可在 JVM 单测直接覆盖「断开判空」回归。
- 消除 OpenClaw 的 WS 抖动(实测 `E OpenClawGateway: WS 失败` 每约 6s 一次,而网关事件其实正常到达):
  新增 `OpenClawGatewayRegistry`,顶部探针、概览页、对话页、语音桥服务**复用同一个 `OpenClawGateway` 实例**
  (引用计数:UI 页面 `.close()` 只减一次引用,不会关掉服务正在用的健康 socket);
  连接监控只在 `isReady()==false` 且已有失败原因时排队重连,重连间隔改为指数退避
  `ReconnectBackoff`(2s→4s→8s→16s→30s 封顶,恢复后重置),每次重连都打一行带**原因**与退避时长的日志。
  设置页保存前校验仍用草稿值建独立实例(不污染共享连接)。
- OpenClaw 回复等待上限由固定 45s 改为可配置、默认 **180s**(`GatewayConfig.DEFAULT_REPLY_TIMEOUT_SECONDS`,
  设置页新增「回复等待上限 秒」输入框,越界夹紧到 15..900;`OpenClawConfig.replyTimeoutSeconds`)。
  等待期间把网关进度(`event:chat` 的 `phase`、agent 的 `run_status`/`tool`)**节流**转成状态文案
  「网关工作中: preparing_context」(不同阶段 ≥2s、同一阶段 ≥6s),顶部状态卡显示为黄色过渡态,
  并通过设备侧已有的 `{"cmd":"gateway","state":"working","detail":…}` 通道同步到设备屏
  (重连中发 `state=connecting`,恢复发 `idle`,同一 state+detail 3s 内不重复下发)。
- 日志降噪:OpenClaw 的 event/下行帧不再 INFO 打印**全文**(单条曾达 140KB,把 logcat 刷爆),
  改 DEBUG 且只打摘要(event 名/state/phase/runId/进度/文本长度);保留鉴权结果、连接与断开原因、
  run 开始/结束/中断等关键状态行的 INFO/WARN。
- 新增 JVM 单测 `ReplyCollectorTest`(8 例:socket 中途断开不把在途回复判成空回复、迟到 final 仍能收到、
  断开后返回可读中断原因、等满窗口而不是立刻返回、空 final 才算空回复、无中断超时、barge 取消、终结只认第一次)
  与 `ReconnectBackoffTest`(4 例:2/4/8/16/30s 封顶序列、attempts 计数、reset 回到 2s、自定义上下界)。

- 明文 HTTP/WS 放行修复:`network_security_config.xml` 增加 `<base-config cleartextTrafficPermitted="true">`
  (存在 NSC 时 manifest 的 `usesCleartextTraffic` 会被忽略,所以只保留这一套配置),
  内网 `http://` / `ws://` 不再被拦成 `CLEARTEXT communication ... not permitted by network security policy`;
  仍只信任系统 CA,没有放宽 TLS 校验。设置页新增**非阻断**明文提示:host 以 `http://` 填或关掉 TLS 时提示
  「明文连接:token 会以明文发送,建议仅在局域网/自签环境使用」。
- 新增第 4 种网关类型「自定义 OpenAI 兼容」(`openai_*` 独立配置前缀,切换类型不丢配置):
  `POST {scheme}://{host}:{port}{basePath}/chat/completions` + `Authorization: Bearer apiKey`,
  body `{model, messages, stream}`;model 必填,可选 systemPrompt 与 maxHistory(默认 20 条历史)。
  messages = `[可选 system] + ConversationStore 历史(user/assistant) + 本轮用户文本`,按 maxHistory 截断;
  回复取 `choices[0].message.content`,stream:true 时按 SSE 增量拼接。
  新增 `OpenAiCompatibleGateway`/`OpenAiConfig` 与共用实现 `OpenAiCompat`(历史拼装/非流式解析/SSE 解析/
  信任所有证书构造:四处与 Hermes 只留一份实现),`GatewayDraft.OpenAi` 与 `GatewayFactory`/
  `VoiceBridgeService` 装配,`GatewaySettings` 新增一整套字段。保存前校验沿用 `GatewaySaveGuard`:
  优先 `GET {basePath}/models`,404/405 时退化为一次最小 `chat/completions`,失败给出可读原因且不落盘。
  设置页类型单选新增「自定义 OpenAI 兼容」与字段分组,概览页「控制台仅支持 OpenClaw」文案覆盖新类型。
- OpenClaw 通道补「允许自签证书(仅调试)」开关(默认关闭,仅作用于本通道,与 Hermes 同款):
  打开后 wss 自签可连;`OpenClawConfig`/`GatewaySettings` 增加对应字段与设置页复选框,
  复用统一的信任所有证书构造,注释说明中间人风险。
- 顶部状态探针改走适配器:原「刷新」对 OpenClaw 请求写死的 REST `/health`(8s 超时),
  与实际对话的 WS 通道是两套判断,WS 已通时会误报「网关不可达」。现统一用当前类型的
  `GatewayAdapter.connect()` / `isReady()` / `lastError` 决定文案(Echo 恒通),与保存前校验一致。
- 固件 v1 Opus 上行支持:`VoicePipeline` 对 `TYPE_AUDIO_OPUS`/`TYPE_AUDIO_PCM` 都先剥掉首字节 SEQ
  (1 字节回绕,仅用于丢帧统计)再交给识别端;Opus 整包原样上送、不再本地解码再编码;
  每轮 `turn_start` 重置并按 SEQ 缺口统计丢帧率(写入日志,有丢帧时上状态面板)。
  重组器改为按帧类型限制载荷长度(音频载荷含 SEQ,故 PCM 上限 1025B),缓冲放大到 6+2048。
- `SttEngine` 新增 `feedOpus(packet)`(默认空实现):小智把 Opus 包直接作为 WS 二进制帧上送
  (保留 PCM 兜底编码分支);Vosk 用 `app/libs/opus.aar` 的解码器把 Opus 解成 PCM;
  系统 SpeechRecognizer 只采本机麦克风,不假装支持设备上行音频。
- 新增 JVM 单测 `OpenAiCompatibleGatewayTest`:历史顺序与 maxHistory 上限、无历史、有/无 systemPrompt、
  本轮文本去重、非流式提取、SSE 增量(含跨分片与未知事件)、401/404/超时/空 model 的 `lastError`
  与 `chat()` 返回 null、`/models`→最小对话退化、校验失败不落盘。
- `docs/gateway-adapters.md` 补新类型、共用历史规则与各通道校验方式、明文/自签说明;
  `docs/wire-protocol.md` 同步 SEQ 剥除与 Opus 上行实现清单。

- 设置页/顶部状态的可用性修复:OpenClaw token 与 Hermes API_SERVER_KEY 输入框改为明文
  (`textVisiblePassword`,不再隐藏密码,也不再触发密码输入法联想);`MainActivity` 给根布局
  补 `systemBars + displayCutout` 的 inset padding(原顶栏「状态圆点 + 正在连接设备… + 刷新」
  与系统时钟/电量画在同一条带上重叠),根布局用应用已有深色填充新暴露出来的窗口区域。
- 「保存网关设置」改为**先校验连接再落盘**:用输入框里的草稿值建适配器探活
  (OpenClaw=WS connect 鉴权,Hermes=`GET /health`,Echo=恒通),校验有超时、期间按钮置灰
  显示「校验中…」;失败时一个字段都不写(含网关类型——切换类型改为保存成功后才写盘并重启服务,
  原「切换类型即写盘」取消)、原配置继续生效,失败原因用对话框展示。新增 `GatewaySaveGuard`
  (校验-落盘闸门,先校验后写)与 `GatewayDraft`/`OpenClawConfig` 显式配置入口
  (适配器与工厂可直接收配置对象,不再需要先写 SharedPreferences 再回滚);
  删除与保存重复的「测试连接」按钮及其监听。
- 网关断开/鉴权失败时向上透出可读原因:OpenClaw WS 断开按异常/HTTP/关闭码映射为
  「连接超时/连接被拒绝/无法解析主机名/TLS 证书校验失败/明文连接被网络安全策略拦截」,
  RPC 错误保留网关原文(`missing scope`、需 `openclaw devices approve` 等);
  Hermes 补齐明文被网络策略拦截的映射。`VoiceBridgeService` 连接监控新增网关检测:
  `lastError` 变化时广播到顶部状态卡(如「网关连接已断开(连接超时:…) — 正在重连…」)
  并按原因自动重连,恢复后播报「网关已恢复连接」;`MainActivity` 状态圆点把「断开」判为异常(红)。
- 新增 JVM 单测 `GatewaySaveGuardTest`:Hermes 校验 401/404/连接拒绝/超时 → 不落盘且
  `lastError` 可读,成功 → 恰好落盘一次;Echo 恒通过。
- `network_security_config.xml` 移除早期联调域名 `hs0033439-openclaw.my.hiksemi.net`,
  改为通用示例域名并按需替换说明;自签证书联调引导到 App 的「允许自签证书」开关
  (原条目只信任系统 CA,并不支持自签证书)。
- 概览页在非 OpenClaw 网关(Hermes/Echo)下顶部显示「网关控制台当前仅支持 OpenClaw」中文提示,
  不再让空白/不可用内容看起来像坏了(控制台 RPC 逻辑与 OpenClaw-only 现状不变);
  设置页切换网关类型(openclaw|hermes|echo)后自动重启 `VoiceBridgeService`(stop 后稍等再 start,
  异步 binder + 协程避免旧实例未走完 onDestroy,服务状态广播让主界面状态卡自更新),
  修改 host/token 仍不自动重启;清理 `GatewayConfig` 头注释里早期联调的具体网关域名/端口,
  只保留 health/tools.invoke/WS 端点等协议结论;补 Gradle wrapper
  (`gradlew`/`gradlew.bat`/`gradle/wrapper/*`,Gradle 8.10.2,distributionUrl 保持官方地址),
  并在 AGENTS.md 记录不可访问 services.gradle.org 时的本机构建/换镜像写法。

- 引入网关抽象层 `GatewayAdapter`(connect/isReady/chat/interrupt/lastError/close)并新增
  `HermesGateway`(OpenAI 兼容 HTTP:Bearer key、`GET /health` 探活、`POST /v1/chat/completions`、
  可选 SSE 流式并忽略 `hermes.tool.progress`、conversation 服务端会话与客户端完整 messages 两种模式、
  真实取消在途请求的 interrupt、401/403/404/超时等可读 lastError、可选仅调试用允许自签证书开关)
  与本地 `EchoGateway`;原 `GatewayClient` 收敛为 `OpenClawGateway : GatewayAdapter`(协议行为、
  Ed25519 设备身份、sessionKey、chat.send、事件流收集保持不变,仅暴露 lastError)。
  `GatewaySettings` 新增网关类型(openclaw|hermes|echo)与 Hermes 一套配置(独立 key 前缀,切换类型
  不丢配置),并清空遗留的测试用默认 Host/Token;设置页新增类型选择与两套字段
  (OpenClaw 走 WS connect 鉴权,Hermes 走 /health),顶部状态卡与对话框同步按类型选择后端;
  `VoiceBridgeService` 按类型实例化适配器,`VoicePipeline` 改为只依赖 `GatewayAdapter` 接口。
  新增 JVM 单测(MockWebServer)覆盖 Hermes 非流式提取、SSE 拼接与跨缓冲分片、工具进度混入、
  401/403/404/超时/连接失败的 lastError 与 chat 返回 null、interrupt 取消在途请求与旧结果不回流。

- 新增 App 侧开发文档 `docs/`:对讲机线协议实现说明(`wire-protocol.md`,含 v1 帧格式/
  握手/Opus 上行/文本分片与 App 改动清单)、网关接入说明(`gateway-adapters.md`,含
  OpenClaw 收敛到接口与 Hermes OpenAI 兼容 API server 的接入方式)与文档索引。
  权威契约在固件仓库 `ai-passport` 的 `docs/development/engineering/intercom-wire-protocol.md`。
- 接入小智(xiaozhi.me)云端流式中文识别替代 Vosk:App 作小智 WebSocket 客户端(manual 模式),
  设备 BLE 送来的 16k PCM 在 App 经 libopus 编成 60ms Opus 帧上送,只取 `stt` 文本喂 OpenClaw 网关,
  丢弃小智 llm/tts。新增 XiaozhiStt(WS+Opus 编码)、XiaozhiSettings(默认填官方 URL/`test-token`)、
  XiaozhiActivator(OTA→绑定码→activate 轮询激活)、XiaozhiUtil(MAC 稳定派生 Client-Id)。
  关键:Device-Id 用真实蓝牙 MAC、Client-Id 用 MAC 派生固定 UUID(v4)、首次需在 xiaozhi.me 网页
  输入绑定码激活;SttFactory 配置小智则优先 XiaozhiStt 否则回退 Vosk。
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

