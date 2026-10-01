# 网关接入：OpenClaw、Hermes 与自定义 OpenAI 兼容

App 同时支持三类后端网关（外加本地回显）。本文说明抽象层、各实现的协议要点，以及设置页字段。目标是把「网关」从流水线里抽出来，让 `VoicePipeline` 只面对一个接口。

---

## 1. 抽象层

```kotlin
interface GatewayAdapter {
    /** 建链/鉴权；返回是否可用。失败不抛异常，原因写进 lastError。 */
    suspend fun connect(): Boolean
    /** 是否已完成鉴权、可收发。 */
    fun isReady(): Boolean
    /** 发一段文本，返回完整回复；失败/超时/被打断返回 null。 */
    suspend fun chat(text: String): String?
    /**
     * 一轮对话的【全部】回复（OpenClaw 一轮可能多条）。
     * 默认实现 = chat() 的单条回复；失败/超时返回已收到的部分消息 + 可读原因 ChatReply.error。
     * onRawUpdate = 「终局后宽限窗」内迟到帧的**增量** raw 回调（只 OpenClaw 支持，见 2.3）。
     * onBodyCorrection = 用 chat.history 补正的**正文**回调（只在不同的时候回调，见 2.4）。
     */
    suspend fun chatMulti(
        text: String,
        onRawUpdate: ((List<RawEntry>) -> Unit)? = null,
        onBodyCorrection: ((String) -> Unit)? = null,
    ): ChatReply
    /** 打断在途回复收集（barge：回复中用户再次按 PTT 说话）。 */
    fun interrupt()
    /** 最近一次错误的可读描述，供设置页与状态面板显示。 */
    val lastError: String?
    /** 清掉上一次的错误（重载/重连开始时用：状态文案只能含本次尝试的原因）。默认空实现。 */
    fun clearLastError()
    /** 是否支持「用 chat.history 补正正文」（决定状态话术 body 要不要先缓发，见 2.5）。默认 false。 */
    val supportsBodyCorrection: Boolean
    fun close()
}
```

- `VoicePipeline` 只依赖这个接口（`.chatMulti()` / `.interrupt()`），两个实现的差异全部关在网关层。
- `chatMulti()` 返回 `ChatReply(messages: List<String>, error: String?)`：**一轮可能多条回复**，
  失败/超时/被打断时返回已收到的部分消息（可能为空），原因在 `error`（同时写进 `lastError`）。
  只有 Hermes / 自定义 OpenAI 兼容 / Echo 仍保持「一条回复」，用默认实现即可。
- 加一个 `EchoGateway`：本地回显，无网关也能联调 BLE 链路与设备 UI。
- 设置页决定实例化哪一个：`GatewaySettings.type = openclaw | hermes | openai | echo`。

## 2. OpenClaw（现有实现，收敛到接口）

保持现有协议行为不变，只把 `GatewayClient` 改造成 `OpenClawGateway : GatewayAdapter`。

| 项 | 取值 |
| --- | --- |
| 通道 | WebSocket `wss://<host>:<port><wsPath>?sessionKey=main`（默认 `wsPath=/message/messages/ws`） |
| 握手 | 服务端 `connect.challenge`{nonce} → 客户端 `connect`（`client.id=openclaw-android` + ed25519 设备签名 + token） |
| 设备身份 | `DeviceIdentity`：私钥 seed 持久化；`device.id = SHA-256(公钥)` 的 hex；`publicKey = Base64url(原始 32B)` |
| 会话 | `sessionKey = agent:main:main`（网关只认 main agent） |
| 对话 | `chat.send{sessionKey,message,deliver:false,idempotencyKey,agentId}` → 收 `message.content` **全量**文本 → 收齐本轮**全部**回复（见 2.1） |
| 等待上限 | 默认 **180s**（设置页「回复等待上限」，夹紧 15..900）；等待期间把 `phase`/`run_status`/`tool` 节流转成「网关工作中: …」 |
| 断线续等 | WS 断开**不**判本轮失败：标记「连接中断」→ 指数退避重连（2s→4s→8s→16s→30s）→ 用同一 `idempotencyKey` 重新订阅并继续等同一 `runId`；到时限仍无结果返回可读原因 `网关连接中断:…` |
| 实例复用 | 全进程一条 WS（`OpenClawGatewayRegistry` 引用计数复用）；探针/概览页/对话页/服务不再各建一条连接互相顶掉 |
| 等待授权 | 网关回 `NOT_PAIRED: pairing required: device is not approved yet` 时进入独立的「等待网关授权」状态：状态词仍是 `connecting`，detail 为「等待网关授权：请在 OpenClaw 控制台批准本设备 (deviceId 前8位…)」，完整 deviceId 只写日志 |
| 权限错误 | `missing scope` / `device` 相关错误要原文透出到状态面板，便于提示用户去网关主机 `openclaw devices approve` |
| 日志 | event/下行帧 DEBUG 且只打摘要（event/state/phase/runId/文本长度）；鉴权、连接/断开原因、run 起止为 INFO/WARN |

注意：`GatewaySettings` 里的 `DEFAULT_HOST` / `DEFAULT_TOKEN` 是早期联调遗留的测试值，收尾时必须清空（token 属 secret，绝不入库）。

### 2.1 一轮多回复：正文来源、分段规则与结束信号

真机抓帧（`app/src/test/resources/gateway/chat-frames.jsonl`，一次真实 run 的关键帧）结论：
一轮 run 里，除了进度/工具细节，**`event=chat` 的 `state=delta` / `state=final` 才是回复正文**；
一轮可能先后推**多条** assistant 消息（例如答案 + 后续状态消息），必须全部收集，不能后到覆盖先到。

| 帧 | 是否为正文 | 去处 |
| --- | --- | --- |
| `event=chat` `state=status`（带 `phase`） | 否 | 进度：`网关工作中: <phase>` |
| `event=agent` `stream=run_status` | 否 | 进度：`data.phase` |
| `event=agent` `stream=lifecycle` `data.phase=start/model` | 否 | 生命周期（其它 phase 略过） |
| `event=agent` `stream=item/tool/command_output/usage` | 否 | 工具执行细节（客户端步骤标题来源） |
| `event=agent` `stream=assistant` `data.text` | 否 | 执行细节；**不是**对话正文 |
| `event=chat` `state=delta` / `state=final` | **是** | `payload.deltaText` 与 `payload.message.content[0].text`（全量累计），两者文本通常相同 |
| `event=presence/health/tick`、`type=res` | 否 | 噪音 |

分段与结束语义（纯函数 `gateway/ReplyMessages.kt`，JVM 可测）：

| 规则 | 行为 |
| --- | --- |
| 只收正文帧 | `replyMessagesOf(frameJson, sessionKey, runId)` 只认 `event=chat` 且 `state in (delta, final)`；其余返回空列表 |
| 归属过滤 | 帧里带 `sessionKey` / `runId` 时必须与本轮一致，否则丢弃（避免串到别的会话/别的 run）；期望值为空串表示「暂不知道」，用于本轮首帧学习 `runId` |
| 流式累计（分段） | `mergeMessages(acc, next)`：`next` 以最后一条开头 → 同一条消息就地替换为更全的 `next`；否则 → 结束当前消息、**新开一条** |
| 去重 | `next` 与最后一条完全相同（delta 与 final 常见）→ 不新增 |
| 空文本 | 空白 `next` 不产生空消息 |
| 结束一条消息 | `state=final`（此后进入短暂沉降窗口，等同一轮可能紧随的后续消息） |
| 结束本轮 | `agent stream=lifecycle phase=finishing/end` 立即定局；或沉降窗口内不再有新正文 |
| 不丢部分 | 超时/断连/被打断时，已收到的部分消息仍随结局返回（`ReplyOutcome` 各分支都带 `messages`） |

`ReplyCollector`（每轮一个）收集完一轮的全部消息后，`OpenClawGateway.chatMulti()` 把它们
逐条返回给 `VoicePipeline`：**每条各回一帧 `'A'` TEXT 给设备**（设备端每条一个气泡，最多保留 6 个）、
**每条各占 App 对话列表一个气泡**（顺序与到达一致）。`chat()` 仍返回第一条，供只要单条回复的调用方使用。

### 2.2 body / raw 双路：设备只要正确正文，App 要看到全部

真机现象：一轮 run 里除了正文，还有大量状态 / 生命周期 / 步骤 / 工具 / 工具输出 / 用量帧；
网关还会把「已回复完毕，当前无进行中的 exec 会话或子代理。」这类**状态话术**当成终局回复推出来。
因此对下行内容分两路处理：

**body（正确正文，给设备屏与 TTS）** —— `ReplyCollector` 按优先级计算：

| 优先级 | 来源 | 说明 |
| --- | --- | --- |
| 1 | `event=agent` + `stream=lifecycle` + `data.phase=end` 的 `data.terminalReply` | 当 `terminalReply.disposition == "visible"`（或 `data.terminalReceipt.terminalDisposition == "visible"`）时用 `terminalReply.text`——网关自己标注「该给用户看」的文本 |
| 2 | 回退：`[lifecycle phase=start, phase=finishing/end]` 区间内的 `chat state=final` | 多条按 seq 顺序全保留；final 与 end 可能同 seq，边界用 `<=` |
| 3 | 再退：区间内最后一个 `chat state=delta` 的累计全文 | 网关的 delta 文本本身是全量累计 |
| 4 | 非 `visible` 的 terminalReply、run 结束后（seq > end）才到的 chat 正文 | **不进 body**（只进 raw） |
| 5 | 只有一条 visible 的状态话术 | body 就是它——这是网关的行为问题，App 侧**不擅自改写**，只加「疑似状态话术」标记 |

**raw（完整回传流，只给 App 调试视图）** —— `gateway/RawEntries.kt` 的 `rawEntriesOf` 把每一帧的
「有信息条目」按到达顺序全部收下，一条都不丢；`rawEntriesOf` 与 `replyMessagesOf` 用同一套归属过滤
（`sessionKey` 必须属于本会话，带 `runId` 时必须属于本轮）。

| kind | 来源 | label（用户可见文案） | text |
| --- | --- | --- | --- |
| `status` | `chat state=status` / `agent stream=run_status` 的 `phase` | `状态 · <phase>` | phase |
| `lifecycle` | `agent stream=lifecycle` | `生命周期 · <phase>[(stopReason)]` | `phase=… stopReason=… aborted=…` |
| `step` | `agent stream=item` | `步骤 · <title 或 name>` | `status`（缺则 `phase`） |
| `tool` | `agent stream=tool` | `工具 · <name> · <phase>` + `(error)` | `phase=… isError=true` |
| `tool_output` | `agent stream=command_output` | `工具输出 · <title 或 name>` | `data.output`，超过 **200 字**截断并在末尾标注 **`(截断 N 字)`**（N=丢弃字符数） |
| `assistant` | `chat state=delta/final`、`agent stream=assistant` | `正文 · 全文` | 文本（全量累计） |
| `terminal` | lifecycle `phase=end` 的 `data.terminalReply` | `终局 · <disposition>` | `terminalReply.text`（非 visible 的也收进 raw） |
| `usage` | `agent stream=usage` | `用量 · <一行摘要>` | `outputTokens=261` 之类的 `k=v` 摘要 |

`ChatReply` 因此携带两个字段：`messages`（body）与 `raw`（`List<RawEntry>`；非 OpenClaw 通道恒为空）。

**展示与开关语义**：

| 位置 | 行为 |
| --- | --- |
| 条目顺序 | **body 正文条目在最前**（正常气泡），raw 全部条目按到达顺序跟在后 |
| 设置页开关 | **「App 显示完整回传流（调试）」**，默认**开**，状态存 prefs（`show_raw_stream`）；它是纯 App 展示开关，**切换即落盘**（不走「保存网关设置」的校验-落盘闸门，校验失败也不会把它回滚） |
| App 对话列表（开关开） | body 正文 = **正常气泡 + 来源标记**（`[流式]` / `[来自历史]`，见 2.5）；raw **一律弱化小字 + 标签**（含 `正文 · 全文`、`终局 · …`）——**正常气泡只留给 body**，同一答案不会再出现两条正常气泡 |
| App 对话列表（开关关） | 只展示 `body`（全部正常气泡，带来源标记），适合正式演示 |
| 设备屏与 TTS | **只吃 `body`**，永远不含 raw、不含来源标记；正文逐条发 `'A'` 帧，TTS 读 body 合并文本（命中状态话术的 body 会先缓发，见 2.5） |
| 疑似状态话术 | 命中 `已回复完毕` / `已答复完毕` / `无待处理事项` / `无进行中的(任务|exec 会话|子代理|子会话)` / `无活跃(exec 会话|子代理)`（`没有进行中的…`、`无活跃 exec…` 也计入）时，只在该条目上加标记 **`[疑似状态话术]`**，**不删、不拦、不改文本**；body 气泡上它与来源标记同时存在时顺序为「来源在前、疑似在后」（如 `[流式][疑似状态话术]`） |

纯函数 `gateway/RawEntries.kt`（`rawEntriesOf` / `lifecycleEventOf` / `truncateToolOutput` / `looksLikeStatusTalk`）
与 `ui/ReplyDisplay.kt`（`replyDisplayEntries`）都可在 JVM 单测直接覆盖；`ReplyCollector` 本身也不依赖 Android。

### 2.3 终局后宽限窗 + 迟到帧增量

真机现象（`adb logcat`，`log.tag.OpenClawGateway=DEBUG` 每帧一行）：一轮里 App 收到的帧里
`chat/final` **恒为 0**，而 PC 探针每个 run 都能抓到；`agent/item`、`agent/tool`、
`agent/command_output` 也比探针少。原因不在网关：`agent lifecycle phase=end` 与 `chat final`
几乎同一时刻到达（实测两者 `seq` 相同），`end` 触发定局、`awaitOutcome()` 一返回，
旧实现立刻把 `activeCollector` 清成 `null` —— 紧随其后的 `final` 与更晚的
`item/tool/command_output` 结果帧在 `handleReplyPayload` 里被整帧丢弃。

| 项 | 语义 |
| --- | --- |
| 常量 | `OpenClawGateway.POST_TERMINAL_GRACE_MS = 3000`（毫秒）；常量而非配置项，原因是它只影响调试流的完整性 |
| 触发 | 结局为 `Reply` / `EmptyFinal`（真正定局）才开窗；`Timeout` / `Interrupted` / `Cancelled`（barge）立即回收 |
| 窗口内 | collector 继续挂在 `activeCollector` 上，迟到帧照常进 **raw**；body 的区间规则完全不变（区间外的 `chat` 正文仍**不进** body） |
| 回收 | 窗口结束才尝试 `activeCollector = null`，且用 `===` 校验：期间若已开新一轮（barge）或已换新 collector，**绝不动新的那个**（两轮不混） |
| body / 设备 / TTS | **不受窗口影响**：`chatMulti()` 拿到结局就立即构造并返回 `ChatReply`，body 立即下发设备、立即喂 TTS，窗口只延期**回收 collector** |
| 进度播报 | 已定局的 collector 不再触发 `reportProgress`：迟到的工具帧只进 raw，不会把状态卡从「已就绪」拉回「网关工作中」 |
| `chat.history` 补正 | 同一窗内**还会**查一次 `chat.history`，把本轮真正的答案（流式帧只推了最后一条状态话术）补发一帧给设备（见 **2.4**）；查询失败只记日志，不影响上面任何行为 |

**迟到帧增量（`onRawUpdate`）**：`GatewayAdapter.chatMulti(text, onRawUpdate = null)` 新增可选回调，
只有 OpenClaw 会回调。

| 规则 | 说明 |
| --- | --- |
| 只报增量 | `ReplyCollector.snapshotRawAndArmUpdate(sink)` 在**同一个锁内**完成「取首批快照 + 记游标 + 装回调」，因此首批（`ChatReply.raw`）+ 增量既**不重复**也**不丢**；只有游标之后新到的条目才回调 |
| 顺序 | 每批按 `seq` 升序（与 `rawEntries` 共用同一比较器） |
| 线程 | 回调可能在网关线程执行：实现必须线程安全、不碰 UI/Context，也不得回调网关 |
| App 展示 | `VoicePipeline` / `ChatFragment` 把增量条目按与首批**相同**的规则追加（`replyDisplayEntries(emptyList(), added, showRaw)`）：raw 条目**一律弱化小字 + 标签**、命中「疑似状态话术」只加标记；「App 显示完整回传流（调试）」关闭时增量没有 body 可展示 → 不上屏（仍打进 DEBUG 日志） |
| 设备与 TTS | **不走**回调：设备屏与 TTS 只吃 body |
| barge | `VoicePipeline` 用本轮 `turnId` 过滤：已开新一轮时丢弃旧轮的迟到条目，不与新一轮混排 |
| 单测 | `ReplyCollectorGraceTest`（JVM，真机 fixture `gateway/chat-frames.jsonl`） |

### 2.4 用 `chat.history` 补正正文（流式帧不够用）

**为什么流式帧不够用**（真机抓帧，用户问「新疆天气是什么？」＋语音附加提示词）：

| 序 | role | content | 说明 |
| --- | --- | --- | --- |
| #5 | user | text=新疆天气是什么？＋提示词 | 本轮输入 |
| #6 | assistant | `toolCall: exec` | 查天气 |
| #7 | toolResult | 乌鲁木齐 JSON | 工具输出 |
| #8 | assistant | text=「新疆以首府乌鲁木齐为例（09-30 00:05 实况）：多云，14.6℃…AQI 54（良）…」 | ★**真正的答案**★ |
| #9 | assistant | `toolCall: exec` | MemOS add turn |
| #10 | toolResult | JSON | 工具输出 |
| #11 | assistant | text=「没有活跃的 exec 会话或子代理…新疆天气已回复完毕…」 | ✗状态话术✗ |

一轮里有**多条** assistant 文本消息，而流式 `chat delta/final` 只推**最后一条** ——
也就是 `#11` 的状态话术。设备屏与 TTS 因此只看到「已回复完毕…」，**答案只存在于网关历史里**。
网关实测可用的历史 RPC 是 **`chat.history`**（参数 `{sessionKey, limit}` →
`{sessionKey, sessionId, messages:[…]}`）；`session.history` / `messages.list` / `chat.messages`
等一律 `INVALID_REQUEST unknown method`。

| 项 | 语义 |
| --- | --- |
| 调用 | `OpenClawGateway.rpcChatHistory(limit = CHAT_HISTORY_LIMIT = 20)` → `rpcQuery("chat.history", {sessionKey, limit})`（sessionKey 与 `chat.send` 完全一致，即本机自己的会话） |
| 时机 | **在既有的终局后宽限窗内**（`POST_TERMINAL_GRACE_MS = 3000`），与宽限窗**共用**这段时间；查询最多等这一窗（`withTimeoutOrNull`），**不额外增加设备侧时延** |
| 触发范围 | 与宽限窗一致：结局为 `Reply` / `EmptyFinal`；`Timeout` / `Interrupted` / `Cancelled`（barge）不查 |
| 本轮边界 | 以**我们发送的用户文本**为准：历史里最后一条内容相同的 `user` 消息；找不到时退回**历史里最后一条 user 消息**；没有 user 消息 → 不做任何补正 |
| 正文候选 | 边界之后所有 `role=assistant` 的 `content[*].type == "text"`（同一条消息的多个 text 片段拼成一条）；`toolCall` / `toolResult` **不进正文** |
| 挑正文 | 先排除 `looksLikeStatusTalk`，在剩下的里取**最长**（并列取最早）；一条都没剩（整轮只有状态话术）→ **保留流式正文**，不凭空造 |
| 补发 | 选中的正文与流式 body **不同**（trim 比较，含「不在已下发的多条正文里」）才补：给设备**再发一帧 `'A'`**，并在 App 里以**正常气泡**（`[来自历史]` 标记）展示（与「App 显示完整回传流（调试）」开关无关：它是正文，不是调试流）；App 侧用 `ConversationStore.replaceById` **就地替换**流式正文气泡、不新增气泡（见 **2.5**）；**相同 → 什么都不做**（不重复上屏、不重复朗读；补正不重新触发 TTS） |
| 日志 | 补正：`历史补正: 用 chat.history 的答案替换/补发正文(N 字, 流式 body M 字)`；一致：`历史补正: 历史正文与流式正文一致(N 字),不重复上屏`；只有状态话术：`历史补正: 本轮历史没有非状态话术的正文,保留流式正文`；查询失败：`chat.history 查询失败,跳过正文补正: <原因>`（WARN）/ `chat.history 返回空,跳过正文补正`（DEBUG） |
| App raw 视图 | 历史条目作为 raw 补充追加在**流式条目之后**（历史条目内部保持原顺序），标签：`正文(历史) · 全文`（`history_assistant`）/ `工具(历史) · <工具名>`（`history_tool`，正文用命令以便区分同名工具）/ `工具输出(历史) · 全文`（`history_tool_output`，超 200 字截断并标注 `(截断 N 字)`）；命中状态话术的条目由展示层加 **`[疑似状态话术]`** 标记；与**流式条目按文本**去重（同一轮两次同名工具调用不互相去重） |
| 失败优雅 | 报错/超时/返回空只记一条 DEBUG/WARN 日志，**行为退回现状**（设备下发、App 展示、TTS 均不变）；补正查询不污染对外的 `lastError`（查询前后原样保存/恢复） |
| 回调 | `GatewayAdapter.chatMulti(text, onRawUpdate, onBodyCorrection)`：`onBodyCorrection` 只在不相同的时候回调；回调可能在网关线程执行（只用线程安全的 `ConversationStore` 与 `sendText`） |
| barge | `VoicePipeline` 用本轮 `turnId` 过滤：已开新一轮时丢弃旧轮的补正（不上屏、不发给设备） |
| 纯函数 | `gateway/ChatHistory.kt`：`assistantTextsSince(historyJson, userText)` / `pickCorrectBody(streamedBody, texts)` / `historyRawEntries(historyJson, userText, streamed)` / `planHistoryCorrection(historyJson, userText, streamedBody, streamed)`（一次拿全判定：正文、是否补发、raw 条目）；JVM 单测 `ChatHistoryTest` |

### 2.5 正文来源标记与「状态话术 body 缓发」

真机现象：正文（body）既可能直接来自流式 `chat delta/final`，也可能是 `chat.history` 补正出来的；
而流式正文本身常常就是「已回复完毕…」这类**状态话术** —— 它不该出现在设备屏上。
因此：**正文气泡上标来源**，**命中状态话术的 body 先缓发**，等历史判定后再决定发什么。

**来源标记（只在 App 正文气泡上，设备屏与 TTS 永不带标记）**

| 标记 | 含义 | 判定 |
| --- | --- | --- |
| `[流式]` | 未补正，直接来自流式 `chat delta/final` | `bodySourceOf(corrected = false)` |
| `[来自历史]` | 经 `chat.history` 补正后的正文 | `bodySourceOf(corrected = true)` |

同一个气泡上「疑似状态话术」与来源标记可同时存在，拼接顺序固定为**来源在前、疑似在后**
（`bodyFlag`）：如 `[流式][疑似状态话术]`。

**状态话术 body 缓发（纯函数 `bodyDispatch`）**

| 时机 | 输入 | 动作 |
| --- | --- | --- |
| 刚拿到流式 body | `correctedAvailable = false` | 命中 `looksLikeStatusTalk` **且**通道支持历史补正（`supportsBodyCorrection`）→ `Hold`（先不发设备）；否则 → `SendNow`（**非状态话术行为完全不变，时延不增加**） |
| 历史判定完成 | `correctedAvailable = true` + 补正正文 | `ReplaceWithCorrected`（**只发历史答案**） |
| 历史判定完成 | `correctedAvailable = true` + 无补正正文 + 流式是状态话术 | `SendHeld`（**补发缓存的流式 body**，设备不能空着） |
| 历史判定完成 | `correctedAvailable = true` + 无补正正文 + 流式不是状态话术 | `SendNow`（正文早已下发，幂等无害） |
| 通道不支持历史补正（Hermes / Echo / 自定义 OpenAI 兼容） | 任意 | `SendNow`（**绝不缓发**：没人会来给出结论，设备会空着） |

`VoicePipeline` 侧的落地：

| 项 | 语义 |
| --- | --- |
| 缓发判定 | 逐条 body 走 `bodyDispatch(..., correctedAvailable = false, historyCorrectionSupported = gateway.supportsBodyCorrection)`；`Hold` → 存入本轮 `TurnBodies.held`，**不下发设备**（仍进 App 调试流：正常气泡 + `[流式][疑似状态话术]` 标记），日志 `状态话术 body 暂缓下发,等历史补正判定` |
| 结算时机 | ① `onBodyCorrection` 回调（历史补正到位，宽限窗内）；② 兜底定时器 `HELD_BODY_WAIT_MS = POST_TERMINAL_GRACE_MS + 1000`（body 到手后开始计时），覆盖「历史无更好正文」「超时/中断/被打断」等没有任何补正回调的结局。同轮只结算一次（`TurnBodies.resolved`） |
| 补正到位 | 日志 `历史补正到位,丢弃缓发的状态话术(N 字)`（无缓发时仍是既有日志 `历史补正: 补发正文到设备屏与 App(N 字)`）；设备**只收补正后的正文**（再发一帧 `'A'`） |
| 历史无答案 | 日志 `历史无更好正文,补发缓发的状态话术(N 字)`；缓发的 body 逐条补发 `'A'` 给设备 |
| App 气泡 | 补正到位时把本轮**已写入的 body 气泡就地替换**（`ConversationStore.replaceById`，第一条变成补正正文 + `[来自历史]`），同一轮若还有其它 body 气泡则降级成弱化小字（`正文 · 全文`）—— **同一轮只有一个正常正文气泡** |
| body 为空 | body 为空但历史有答案时，补正到位会**新增**一个 `[来自历史]` 正常气泡（不重复上屏） |
| barge | 用本轮 `turnId` + `TurnBodies.turn` 过滤：已开新一轮时旧轮的缓发/补正一律丢弃 |
| TTS | 行为不变（仍按 body 合并朗读，历史补正**不重新触发** TTS） |
| 纯函数 | `pipeline/BodyDispatch.kt`：`bodyDispatch(streamedBody, correctedBody, correctedAvailable, historyCorrectionSupported)` → `BodyAction`（`SendNow` / `Hold` / `ReplaceWithCorrected` / `SendHeld`）；来源标记 `ui/ReplyDisplay.kt` 的 `BodySource` / `bodySourceOf` / `bodyFlag`；JVM 单测 `BodyDispatchTest` / `ReplyDisplayTest` |

### 2.6 关键 / 辅助 RPC 的错误隔离（真机 bug：切换网关后「网关不可达」）

**真机现象**（用户原话：「Hermes 切换为 openclaw，网关不可达，重启 app 后正常」）：

```text
01:35:43  网关配置已重载: type=openclaw host=192.168.31.5 port=18789   ← 重载成功，配置正确
01:35:58  网关配置已重载,正在重连… — unknown method: usage
          下发网关状态给设备: {"cmd":"gateway","state":"connecting","detail":"… — unknown method: usage"}
01:38:59  重启 App 后: type=openclaw 一切正常
```

PC 探针实测该网关：**不存在**的方法 `usage` / `usage.get` / `system.usage` / `stats*` / `metrics` / `cost` / `skills`
一律回 `unknown method: xxx`；**存在** `health` / `system.info` / `sessions.list` / `cron.list` / `chat.history` / `chat.send` / `connect`。

根因链：概览页会对每个分区各发一次辅助查询（`rpcUsage()` / `rpcSkills()` …），失败后错误被写进网关的
`lastError` —— 关键操作与辅助查询**没有区分**；随后重载/重连的状态文案把这条**过期错误**拼了上去
（`网关配置已重载,正在重连… — unknown method: usage`），于是链路一直正常的连接看起来成了「网关不可达」。
更隐蔽的是 `onSocketDown` 只在 `lastRpcError == null` 时才写断开原因，于是**真正的**断开原因反而被这条旧文案挡掉。
重启 App 后 `lastError` 是空的，所以「重启后正常」。

**规则：只有关键操作的失败才允许写 `lastError` / 影响网关状态**

| 类别 | 成员 | 失败行为 |
| --- | --- | --- |
| **关键** | `connect` 握手、`chat.send`、重连本身 | 允许写 `lastError` 并影响网关状态：连接失败/等待授权/token 失效都必须让用户看到 |
| **辅助** | 概览页的 `agent.identity.get` / `health` / `system.info` / `sessions.list` / `cron.list` / `channels.list` / `skills` / `usage`，以及正文补正用的 `chat.history` | **只经 `RpcResult` 返回给调用方**：不写 `lastError`、不发状态文案、不影响网关状态 |

实现要点：

| 项 | 语义 |
| --- | --- |
| 签名 | `OpenClawGateway.rpcQuery(method, params, critical: Boolean = false)`：默认**非关键**（辅助） |
| 关键性在请求上 | `PendingRequest.critical`：`connect` / `chat.send` 显式 `critical = true`；其余走默认 |
| 写入门槛 | `shouldWriteGatewayError(critical, code, message) = critical && !isUnknownMethod(code, message)`（纯函数）：辅助查询一律不写；关键操作除「网关不认识这个方法」外都写（含权限不足 —— 它虽链路可用，但这次操作真的做不成，必须留原因） |
| 每条请求自带原因 | 请求对象记 `error`：即使不写 `lastError`，调用方也能从 `RpcResult.error` 拿到可读原因 |
| 良性错误 | `isUnknownMethod`（`unknown method` / `method not found` / `METHOD_NOT_FOUND` / 「未知方法」）与 `isMissingScope` → `isBenignQueryError`：对**辅助查询**而言链路是通的，只是这条查询拿不到数据（日志里按「能力/权限」与「连接层」分类，便于定位是不是真断线） |
| 状态词 | `mapRpcError` 新增 `unknown method` 分支 → `state=ready`（不再落到兜底的 `offline`）；`benignQueryState(isConnected)` 也保证辅助查询失败**永不**报 `offline` |
| 纯函数 | `gateway/OpenClawErrors.kt` 的 `isUnknownMethod` / `isBenignQueryError` / `shouldWriteGatewayError` / `benignQueryState` / `queryUnavailable`；JVM 单测 `QuerySupportTest` + 扩充的 `OpenClawErrorsTest` |

**规则：状态文案不得携带过期错误**

| 位置 | 规则（逐字） |
| --- | --- |
| 重载（`VoiceBridgeService.reloadGatewaySettings`） | 连接尝试**开始**前先 `gateway.clearLastError()` 并把 `reportedGatewayError` 置空 → 详情只可能是本次尝试的原因：`网关配置已重载,连接就绪` / `网关配置已重载,正在重连… — <本次原因>` |
| 重连（`VoiceBridgeService.monitorGateway`） | 重连**开始**前 `clearLastError()`；本次尝试没留下原因时用 `lastReconnectReason` 兜底（重连循环不能因「没有原因」而停掉）；播报仍是 `<本次原因> — 正在重连…` |
| 辅助查询 | 永不进入状态文案；监控路径再加一道：原因是 `isUnknownMethod` 时不播报、也不驱动重连（只拦这一个 —— 权限不足之类的仍要重试/播报，重连循环绝不能停在那上面） |
| 接口 | `GatewayAdapter.clearLastError()`（默认空实现）：OpenClaw / Hermes / 自定义 OpenAI 兼容各自重写 |

**规则：概览页对「网关不支持」友好（不再每次进页都报错）**

| 情况 | 文案 | 卡片 |
| --- | --- | --- |
| 网关不认识该方法 | `该网关不支持用量查询` | **隐藏**（标题 + 内容一起隐藏） |
| 无权限（missing scope） | `当前 token 无权读取用量（需网关 admin 权限）` | **隐藏** |
| 其它失败（断开/超时） | `不可用: <可读原因>` | **保留**（这是真的连不上，用户需要看到原因） |
| 同一会话内已知「不支持」 | —（不再请求、不再报错） | 直接隐藏 |

- 记忆放在纯逻辑 `gateway/QuerySupport.kt`（进程级 `QuerySupport.shared`），按**配置指纹**
  （`OpenClawGatewayRegistry.keyOf`）索引：换网关/地址/token 时自动作废旧结论，App 重启即重新探测。
- 分区的方法名取自 `OpenClawGateway.RPC_METHOD_*` 常量 —— 与 `rpcAgents()` / `rpcUsage()` 这些包装函数
  共用同一份字面量，避免「请求用新名字、记忆记旧名字」这类偏差。

## 3. Hermes（新增）
Hermes 的接入方式是 **OpenAI 兼容 HTTP API server**（由 `hermes gateway` 提供），比 OpenClaw 简单得多：无需设备签名，用 Bearer key 直接对话。

| 项 | 取值 |
| --- | --- |
| 基址 | `http(s)://<host>:<port>`，端口默认 **8642** |
| 鉴权 | `Authorization: Bearer <API_SERVER_KEY>`（`~/.hermes/.env` 或 `~/.hermes/config.yaml` 的 `gateway.api_server.key`） |
| 探活 | `GET /health` → `{"status":"ok"}`；`GET /v1/models` 用于确认模型名 |
| 对话（无状态） | `POST /v1/chat/completions`，body `{model, messages:[...], stream:false}` → 取 `choices[0].message.content` |
| 对话（流式） | 同端点 `stream:true` → SSE `chat.completion.chunk` 增量；另有 `event: hermes.tool.progress`（工具进度，可不显示或单独显示） |
| 服务端会话 | `POST /v1/responses` + `previous_response_id`，或用 `conversation:"<设备ID>"` 让服务端按名字串历史（最多保留 100 条，LRU） |
| 长任务 | `POST /v1/runs` → `GET /v1/runs/{id}` 轮询 / `GET /v1/runs/{id}/events` SSE / `POST /v1/runs/{id}/stop` 中断 |
| 能力探测 | `GET /v1/capabilities` 返回支持项（streaming / runs / reasoning 等） |

实现要点：

1. **model 字段只是展示用途**：请求里的 `model` 会被接受，但真正用的模型由服务端配置决定。
2. **Hermes 是 agent 运行时**：回复可能较长、可能带推理内容。默认只取 `choices[0].message.content`；若开启 `stream`，忽略 `hermes.tool.progress` 事件或把它显示成"正在执行工具…"。
3. **不支持文件上传**（`file` / `input_file` / `file_id` 会 400）。内联图片 `image_url` 支持，但本项目不需要。
4. **会话归属**：优先用 `conversation = <设备名或 device id>`（简单、服务端管历史）；若服务端版本不支持，退回"每次带完整 messages"的客户端模式，由 `ConversationStore` 提供历史。客户端模式的消息拼装由 `OpenAiCompat.buildClientMessages` 统一实现（与「自定义 OpenAI 兼容」共用同一份，上限 20 条），不再各写一份。
5. **中断**：`interrupt()` 需要真正取消在途 HTTP 请求（OkHttp `Call.cancel()` / 协程取消），不能只忽略结果；配合 `VoicePipeline` 的 `turnId` 判定丢弃过期回复。
6. **错误映射**：401/403 = key 错或未设 key（绑定非回环时必须设 `API_SERVER_KEY`）；404 = 路径或服务未启用；连接超时/失败 = 网络或 Tailscale 未起；都要给出可读提示而不是静默返回 null。
7. **网络**：用户走远程/内网（Tailscale serve/funnel 或公网反代），因此必须支持：自定义 host/port、http 与 https 两种 scheme、可选"仅调试用允许自签证书"开关（默认关闭）。

## 4. 自定义 OpenAI 兼容（新增）

面向任意实现了 OpenAI 兼容 HTTP API 的服务（vLLM / llama.cpp server / LM Studio / Ollama 的 `/v1` / 各家中转）：
只要 `POST {请求路径}` 能返回 `choices[0].message.content` 就能用。

**请求路径**：设置页「请求路径」直接填端点路径，由纯逻辑 `gateway/OpenAiPath.kt` 解析（规则写在该文件 KDoc）：

- 填 `/v1`（旧默认）→ 自动补成 `/v1/chat/completions`，与历史行为一致；
- 填完整端点 `/openai/v1/chat/completions` → 原样使用，适配任意布局的服务；
- 以 `/chat/completions` 结尾（允许结尾多余斜杠）→ 原样，否则按「前缀」补 `/chat/completions`；
- 带查询串时只对 `?` 之前的部分判定与拼接，查询串原样保留在末尾；
- 归一化：去首尾空白、合并连续 `/`、补前导 `/`、结尾不留 `/`。

| 项 | 取值 |
| --- | --- |
| 基址 | `{scheme}://{host}:{port}`（origin，不含路径），端口默认 **8080** |
| 请求路径 | 设置页「请求路径」，默认 `/v1`（解析为 `/v1/chat/completions`）；可直接填完整端点（如 `/openai/v1/chat/completions`）。解析规则见 `OpenAiPath` |
| 鉴权 | `Authorization: Bearer <apiKey>`（本地服务不校验时可留空） |
| 对话 | `POST {请求路径}`，body `{"model":…, "messages":[…], "stream":false}` → 取 `choices[0].message.content` |
| 流式 | 同端点 `stream:true` → SSE 增量拼接（与 Hermes 共用 `OpenAiCompat.readSse`；未知事件按畸形事件丢弃，不影响正文） |
| model | **必填**：服务端按它选模型（与 Hermes 的"仅展示用途"不同） |
| 历史 | 每次请求都带：`messages = [可选 system] + ConversationStore 历史(user/assistant) + 本轮用户文本`，历史按 `maxHistory`（默认 20 条）截断 |
| 保存前校验 | 优先 `GET {请求路径所在目录}/models`（由 `OpenAiPath.modelsPath` 推导）；404/405 时退化为一次最小 `chat/completions`；失败给出可读原因且不落盘 |

实现要点：

1. **历史拼装只有一份实现**：`OpenAiCompat.buildClientMessages` 同时被 Hermes 的客户端历史模式与本通道调用
   （可选 system 在最前、历史保持原序、`maxHistory` 只截断历史、历史末尾已含本轮文本时不重复追加）。
   调用方（`GatewayFactory.conversationHistory`）不再自带截断，避免两处上限互相覆盖。
2. **中断与错误映射**与 Hermes 同一套：`interrupt()` 真正 cancel 在途 OkHttp Call；
   401/403 = API Key 错，404 = 请求路径/服务不对，超时/连接失败/明文被拦/TLS 失败都给可读 `lastError`。
3. **路径拼装**：URL = `origin()`（`http(s)://host:port`，不含路径）+ `OpenAiPath.resolveChatPath(basePath)`；
   探活 `/models` 由 `OpenAiPath.modelsPath(chatPath)` 推导（不再有写死的 `/chat/completions`）。
4. **不含 Android 依赖**，可在 JVM 单测里用 MockWebServer 覆盖（见 `OpenAiCompatibleGatewayTest`）。

## 5. 设置页字段

| 字段 | OpenClaw | Hermes | 自定义 OpenAI 兼容 |
| --- | --- | --- | --- |
| 类型 | 下拉菜单选择「OpenClaw / Hermes / 自定义 OpenAI 兼容 / Echo(本地回环)」 | 同 | 同 |
| Host | 域名或 IP | 域名、内网名或 Tailscale 名 | 同左 |
| Port | 默认 8035 | 默认 8642 | 默认 8080 |
| TLS | 开关 + 「允许自签证书」调试开关 | 开关 + 「允许自签证书」调试开关 | 开关 + 「允许自签证书」调试开关 |
| Path | `wsPath`（默认 `/message/messages/ws`） | 基址路径前缀（反代场景可配） | 请求路径（默认 `/v1`，自动补 `/chat/completions`；也可填完整路径如 `/openai/v1/chat/completions`） |
| 回复等待上限 | 「回复等待上限 秒」（默认 180） | — | — |
| Token | 网关 token | `API_SERVER_KEY` | `apiKey`（Bearer，明文输入框，可留空） |
| Model / 会话 | 固定 `agent:main:main` | `model`（默认 `hermes-agent`）+ `conversation` 名 | `model`（**必填**）+ 可选 `systemPrompt` + `maxHistory`（默认 20） |
| 流式 | — | 「流式回复(SSE)」开关 | 「流式回复(SSE)」开关 |
| 保存前校验 | WS connect 鉴权（草稿值，失败不落盘） | `GET /health`（草稿值，失败不落盘） | `GET {请求路径所在目录}/models`，404 时退化最小对话（草稿值，失败不落盘） |
| 明文 HTTP | `network_security_config.xml` 里 `<base-config cleartextTrafficPermitted="true">` 统一放行内网 `http://` / `ws://`（存在该文件时 manifest 的 `usesCleartextTraffic` 会被忽略，所以只留这一套配置）；设置页给出**非阻断**提示：host 填了 `http://` 或关掉 TLS 时提示「明文连接：token 会以明文发送，建议仅在局域网/自签环境使用」。放行明文**不**等于放宽 TLS 校验，`trust-anchors` 仍只信任系统 CA | 同 | 同 |

「保存网关设置」= 先用输入框里的草稿值校验连接，通过才把各套字段与网关类型写进 SharedPreferences；
失败时一个字段都不写（含类型），原配置继续生效，失败原因用对话框展示，校验超时 20s（期间按钮置灰显示「校验中…」）。
原来的「测试连接」按钮已删除（与保存校验重复）。详情见 `GatewaySaveGuard` / `GatewayDraft`。

例外（**不是**配置错误）：校验失败原因是「等待网关授权」（见第 6 节）时，设置页**自动继续校验** ——
每 5s 重试一次（`PAIRING_RETRY_INTERVAL_MS`）、最长 180s（`PAIRING_RETRY_TIMEOUT_MS`，与「回复等待上限」解耦），
期间按钮文案改为「等待授权…」并置灰，日志逐行记录；一旦通过就**自动落盘**并提示「授权完成，网关设置已保存」。
超时后弹出明确文案（含 deviceId 前 8 位 + 「请在 OpenClaw 控制台/CLI 批准后重试」）且仍然**不落盘**；
用户离开设置页（协程被取消）时同样不落盘，只记一行「已取消等待网关授权，未保存任何字段」。

设置页的其余界面约定：网关类型是下拉菜单，切换只切可见分组、**保存时才落盘**；
每个字段都有常驻 label（hint 输入后会消失，没有 label 就认不出字段）；所有打钩项都是开关（`MaterialSwitch`，读取仍是 `isChecked`）。
语音桥服务常驻（主界面 `onStart` 自动拉起，无需手动起停），因此设置页**没有**「启动/停止语音桥服务」按钮。
底部 Tab 为 4 个（对话 / 概览 / 设备 / 设置），原「通知」Tab 已下线。

各套配置分别持久化（同一份 SharedPreferences，不同 key 前缀：`gateway_*` / `hermes_*` / `openai_*`），切换类型不丢配置。

顶部状态卡的「刷新」也走适配器：用当前类型的 `GatewayAdapter.connect()` / `isReady()` / `lastError` 决定文案（Echo 恒通），
不再对 OpenClaw 单独请求写死的 REST `/health`，避免「探针协议」与「真实通道」两套判断互相矛盾。

### 5.1 语音附加提示词（`voice_prompt_suffix`）

语音输入在发给网关前，其末尾会自动附加一条「回复约束提示词」（默认
`请用不超过 200 字回复，不要使用 emoji 表情，也不要使用 Markdown 表格。`）：

| 项 | 语义 |
| --- | --- |
| 存储 | 同一份 `gateway_settings` prefs，key = `voice_prompt_suffix`（与网关类型无关，所有通道共用） |
| 设置页字段 | 「语音附加提示（仅语音输入时追加）」+ 常驻 label，位于保存按钮之前；保存规则与其它字段一致（**校验通过才落盘**） |
| 默认值 | `DEFAULT_VOICE_PROMPT_SUFFIX`；用户清空即关闭该行为（空/全空白不追加） |
| 追加格式 | 语音输入 → `STT 原文 + "\n" + suffix.trim()`；STT 原文为空时只发 suffix |
| 只作用于语音 | 对话框文字输入**绝不**追加（见 `VoicePrompt.compose(input, suffix, isVoice)`） |
| 上屏 | 设备屏 `'U'` 帧只显示 STT 原文，附加提示词**不上屏** |
| 对话列表 | 用户气泡显示 STT 原文，下方另起一行弱化小字「＋附加提示：<suffix>」，对应实际发给网关的完整文本 |
| 回复 | **不截断**网关回复（本机制只处理上行文本） |
| 分片 | 组合后的长文本仍走 2048B/帧的 UTF-8 安全分片（`splitTextPayload`），不切坏汉字/emoji、不丢字节 |
| 纯函数 | `gateway/VoicePrompt.kt` 的 `VoicePrompt.compose(...)`；JVM 单测 `VoicePromptTest` / `TextChunkingTest` |

### 5.2 保存成功后：让运行中的服务重载配置

语音桥服务在启动时按当时的设置构造一次网关适配器。网关类型变化时仍重启服务
（`restartServiceIfTypeChanged`）；类型不变（只改 host/端口/token/提示词等）时，
设置页发 `VoiceBridgeService.ACTION_RELOAD_SETTINGS`，服务据此：

- 把 `voice_prompt_suffix` 同步给流水线（与连接无关，总是生效）；
- 比较网关配置快照（`GatewayConfigSnapshot`），变了才重建适配器并重连（OpenClaw 由
  `OpenClawGatewayRegistry` 按配置指纹换连接）；
- **不重启服务、不动 BLE 链路**，已连接设备与在途对话不受影响；
- 日志打一行 `网关配置已重载: type=… host=… port=…`，**绝不打印 token**。

修的是这个 bug：原来只在类型变化时重启，于是只改 host/端口/token 时保存成功、prefs 也写了，
运行中的服务却仍在用旧配置（真机表现：顶部横幅一直「网关未配置,请在 App 设置中填写」）。
判定逻辑抽成纯函数 `needsGatewayReload(prev, next)`（比较前后 `GatewayConfigSnapshot`），
JVM 单测 `GatewayReloadTest` 覆盖「仅 host/port 变化也必须重载」。

## 6. 等待网关授权（设备配对）

OpenClaw 对每台设备做 ed25519 设备配对认证：新设备首次连接会被网关回
`NOT_PAIRED: pairing required: device is not approved yet`。这**不是**配置错误，也不需要用户改任何字段 ——
只需要有人在网关主机/控制台批准这台设备。因此它被单独建模，而不是掉进「鉴权失败/网关断开」的通用失败路径。

| 项 | 语义 |
| --- | --- |
| 触发条件 | `error.code == "NOT_PAIRED"`（或 `PAIRING_REQUIRED`），或 message 含 `not approved` / `pairing required`；旧的 `INVALID_REQUEST` + `device` 分支同样归入等待授权 |
| 状态词 | 固定 `connecting`（设备端只认 `ready\|connecting\|working\|offline` 四个词） |
| detail | 「等待网关授权：请在 OpenClaw 控制台批准本设备 (deviceId 前8位…)」；**完整 deviceId 只写日志** |
| 可恢复性 | `GatewayStatus.recoverable == true`、`fatal == false`：重连/退避循环（2s→4s→8s→16s→30s）照常继续，**不**被当成永久失败停掉 |
| 自动恢复 | 批准后下一次重试 `connect` 成功即清零等待授权标记，走既有 `publishGatewayStatus` 路径播报「网关已恢复连接」，设备端收到 `state=ready` |
| 状态位 | `GatewayAdapter.isAwaitingPairing`（默认 false，只有做设备配对的 OpenClaw 重写），设置页据此决定「继续等」还是「报错让用户改配置」 |
| 纯函数 | `gateway/OpenClawErrors.kt`：`fun mapRpcError(code: String?, message: String?, deviceIdShort: String): GatewayStatus`，返回 `GatewayStatus(state, detail, awaitingPairing, recoverable)`；JVM 单测见 `OpenClawErrorsTest` |

设置页侧的落盘规则不变：**校验不通过绝不落盘**，等待授权也只是「继续校验」而不是「先存下来」。
`GatewaySaveGuard.validate()` 把结果分成 `Ok` / `AwaitingPairing(reason)` / `Failed(reason)` 三态，
调用方（设置页）用类型而不是文案匹配来判断「要不要继续等」；`validateAndPersist()` 保留为一次校验+落盘入口。

## 7. 测试

| 用例 | 方式 |
| --- | --- |
| 非流式对话与回复提取 | MockWebServer 返回固定 `chat.completion` JSON |
| SSE 流式增量 | MockWebServer 返回 `chat.completion.chunk` 序列，断言拼接结果 |
| 工具进度事件混入 | SSE 里插入 `hermes.tool.progress`，断言不影响正文 |
| 401 / 404 / 超时 | 断言 `lastError` 可读、`chat()` 返回 null、不崩溃 |
| 中断 | 请求在途时调用 `interrupt()`，断言请求被取消且旧结果不回流到新一轮 |
| 历史顺序 / 上限 / 无历史 / 有·无 system / 去重 | `OpenAiCompatibleGatewayTest` 断言请求体 messages 的顺序、角色与条数 |
| 自定义 OpenAI 兼容 SSE 与未知事件 | 与 Hermes 共用解析器；未知事件不进入正文 |
| 空 model / 401 / 404 / 超时 | 断言 `lastError` 可读、`chat()` 返回 null；空 model 不发请求 |
| 校验 `/models` → 退化最小对话 / 失败不落盘 | `connect()` + `GatewaySaveGuard.validateAndPersist` 断言落盘回调未被调用 |
| 错误映射：NOT_PAIRED / INVALID_REQUEST+device / missing scope / unknown method / EHOSTUNREACH / SocketTimeout / HTTP 401·403·404 | `OpenClawErrorsTest` 断言状态词只在四态内、detail 可读、等待授权 `awaitingPairing=true` 且 `fatal=false`、`unknown method` 不得报成 `offline` |
| 关键 / 辅助 RPC 的错误隔离：`unknown method`·`missing scope` 属辅助（不致命、状态仍由连接决定）；`NOT_PAIRED`·`token mismatch`·传输层错误属关键；辅助查询失败**不得**写 `lastError`；概览页「不支持」→ 友好文案 + 隐藏卡片；同一次会话内记住、换网关（指纹变）重新探测 | `QuerySupportTest`（10 例，纯函数 `isUnknownMethod` / `isBenignQueryError` / `shouldWriteGatewayError` / `benignQueryState` / `queryUnavailable` / `QuerySupport`） |
| 等待授权不被当成致命错误、且绝不落盘 | `GatewaySaveGuardTest` 用假适配器（`isAwaitingPairing=true`）断言 `SaveValidation.AwaitingPairing` 且 persist 回调未被调用 |
| 语音附加提示：语音追加 / 文字不追加 / 空 suffix / trim / 空输入 | `VoicePromptTest` 断言 `VoicePrompt.compose` |
| 仅 host/port/token 变化也必须重载 | `GatewayReloadTest` 比较前后 `GatewayConfigSnapshot`，断言 `needsGatewayReload == true` |
| 中文多字节文本分片不切坏汉字、不丢字节 | `TextChunkingTest` 断言逐片可解码且拼回原文 |
| 一轮多回复：只答案 / 答案+后续状态消息 / delta=final 去重 / 累计增长 / 跨会话·跨 run 丢弃 / 进度帧不进正文 | `ReplyMessagesTest`（真机 fixture `gateway/chat-frames.jsonl`）断言 `replyMessagesOf` + `mergeMessages` |
| 收集器：只有 chat delta/final 进正文、lifecycle 与 final 都能定局、沉降窗口内两条 final 都收集 | `ReplyCollectorTest` |
| 收集器：超时/断连/被打断不丢已收到的部分消息（`ReplyOutcome.messages`） | `ReplyCollectorTest` |
| 收集器：body 优先 `terminalReply(visible)` / 回退区间内 `final` / 非 visible 用 `final` / 区间外丢弃但留在 raw | `ReplyBodyTest`（真机 fixture `gateway/gw-probe-run2.jsonl`） |
| raw 不丢帧：八类条目各一帧 → kind 序列与条数、label 文案、归属过滤 | `RawEntriesTest`（含真机 fixture `gateway/gw-probe-run4.jsonl`） |
| 工具输出截断：超 200 字 → `(截断 N 字)`；恰好 200 字不截断 | `RawEntriesTest` |
| 疑似状态话术识别（只识别，不改写） | `RawEntriesTest` |
| body / raw 展示映射与「App 显示完整回传流（调试）」开关：关闭时不展示 raw、开启时 raw 全展示且**一律弱化小字 + 标签**（正常气泡数量 == body 数量，含 `正文 · 全文` / 终局条目）、正文气泡带 `[流式]` / `[来自历史]` 来源标记、状态话术只加标记且「来源在前、疑似在后」 | `ReplyDisplayTest` |
| 状态话术 body 缓发决策：状态话术 + 历史有答案 → 只发历史答案；状态话术 + 历史无答案 → 补发缓存的流式 body；状态话术 + 判定未完成 → 先缓发；正常答案 → 立即发；不支持历史补正的通道 → 绝不缓发 | `BodyDispatchTest` |
| 终局后宽限窗：终局后到达的 `final`/`item`/`tool` 仍进 raw、已定局的 body 不被改写、增量回调只报新增（不重复首批）且按 seq 升序、`awaitOutcome` 的返回不等宽限窗 | `ReplyCollectorGraceTest`（真机 fixture `gateway/chat-frames.jsonl`） |
| `chat.history` 补正：多轮历史里只取本轮（工具消息不进正文）、文本对不上时退回最后一条 user 消息、状态话术过滤 + 最长优先（并列取最早）、全命中返回 null、历史条目与流式条目按文本去重且保持历史顺序、工具输出截断、补发只在不同的时候发生、历史不可用时全部退回流式正文 | `ChatHistoryTest`（真机 fixture `gateway/chat-history-xinjiang.json`） |
| OpenClaw 回归 | 现有 WS 协议用例（握手/chat.send/全量 content 覆写）保持不变，改由 `chatMulti` 全部返回，`chat` 取第一条 |
| 就绪去重：首轮放行 / 同轮只放一次 / barge 后旧轮迟到就绪一律丢弃 / 事件 JSON 契约 | `TurnReadyTest` |
| 识别通道预热：热连接可用 → 复用（不重连）；已断开 → 重连；闲置超时 → 重新建连；超时可配置 / 禁用；时钟回拨不误杀；`onReady` 只在 `listen.start` 发出后放行；复用失败只允许一次兜底重连 | `WarmLinkTest` |

## 8. 识别通道常驻预热（热连接）

**问题**（真机现象）：设备按下 OK 后不是立刻能说话，而是先整屏红「准备中」（或 2.5s 后变绿）。
原因是 App 每次按下都要跟小智云端重做一次 WebSocket 握手 + `hello` 往返（实测 **0.5–2s**），
「按下 → 可说话」因此有明显空窗。

**做法**：把识别通道**常驻预热** —— 一轮结束后不关 socket，服务启动 / BLE 链路就绪时先建一次；
下一轮按下时热连接仍可用就**直接发 `listen.start`**，`turn_ready` 毫秒级到达（目标 < 300ms）。

| 项 | 语义 |
| --- | --- |
| 复用 | `XiaozhiStt.startTurn` 先判定热连接：可用 → 直接 `listen.start`，**不重连**，日志 `复用热连接,直接 listen.start(按下即可说话)` |
| 重连 | 已断开 / 闲置超时 / 尚未预热 → 退回既有「按下才连」路径（`connectAndHello`，行为与未预热时完全一致），日志 `热连接不可用,重连中:<原因>` |
| 预热时机 | ① 服务启动（`VoiceBridgeService.startBridge` → `pipeline.prewarm()`）；② BLE 链路就绪（`BleCentral.Listener.onReady`）；③ 每轮结束后保留（`XiaozhiStt.endTurn` → `keepWarm`）。握手完成时日志 `识别通道常驻预热已建立` |
| 闲置超时 | 闲置 ≥ `WarmLink.IDLE_TIMEOUT_MS`（**90s**，60–120s 区间内）主动关闭，日志 `热连接闲置超时,已关闭`；有轮次在跑时定时器不关（只在真闲置时关） |
| 立即关闭 | 服务停止（`VoicePipeline.shutdown` → `release()`）与 BLE 断开（`VoicePipeline.onDisconnected` → `onLinkDown()`）立即关 socket；**`barge` 不关**（只发 `listen.stop`，保留热连接供下一轮复用） |
| `onReady` 语义不变 | 仍只在**本轮识别会话真的建立**（`listen.start` 已进 OkHttp 写队列）时回调：复用热连接时按下即发 → 可能**同步**回调（毫秒级，这正是绿光及时的原因）；`send` 返回 false 时绝不回调（设备不能变绿） |
| 音频不丢 | `turnRunning` 在 `startTurn` 一开始就置 true，按下起的 Opus/PCM 立即累积上送，与是否复用热连接无关 |
| 失败兜底 | 复用热连接时 `listen.start` 发不出去 → 自动重连**一次**（`WarmLink.shouldFallbackReconnect`），再失败只记日志并等设备侧 2.5s 兜底；**预热失败绝不影响**「按下才连」 |
| 纯函数 | `stt/WarmLink.kt`：`WarmLink.decide(state, nowMs, idleTimeoutMs)` → `REUSE` / `RECONNECT_DISCONNECTED` / `RECONNECT_IDLE_TIMEOUT`；`shouldReuse(state, nowMs, idleTimeoutMs)`；`shouldFallbackReconnect(attemptsThisTurn)`；`sessionEstablished(listenStartSent)`；JVM 单测 `WarmLinkTest`（9 个用例） |

## 9. 待定

- Hermes 工具进度是否上屏（设备屏空间有限，倾向只在 App 状态区显示）。
- 长回复分片大小仍是 2048B/帧；若 Hermes 回复普遍更长，需要核对设备端合并与翻页体验。
- 是否把 `ConversationStore` 的历史与 Hermes 服务端会话对齐（避免两边历史不一致）。
- 自定义 OpenAI 兼容的 `systemPrompt` / `maxHistory` 是否要按设备维度分别保存（当前每台手机一份）。
