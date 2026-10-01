# 后台保活：前台服务防降级 + 看门狗

语音桥是**常驻**服务：手机锁屏/息屏后仍要维持 BLE 连接、网关连接与识别通道热连接。
本文记录「系统拒绝前台服务」这个真机问题的实测证据、两层防护的实现与验证方法。

相关代码：`service/ServiceGuard.kt`（判定，纯逻辑）、`service/KeepAliveState.kt`（落盘状态）、
`service/ServiceWatchdogReceiver.kt`（看门狗）、`service/VoiceBridgeService.kt`（执行）、
`ui/SettingsFragment.kt`（把状态摆给用户看）。

## 1. 问题：前台服务会被系统**静默拒绝**

真机环境：Xiaomi 22041216C（HyperOS / Android 14），App `targetSdk 35`，`foregroundServiceType="connectedDevice"`。

从**非用户主动**路径启动时（刚装完 APK、被强停之后、开机广播拉起），系统会拒绝 `startForeground()`：

```
W/ActivityManager: Service.startForeground() not allowed due to bg restriction: service …/VoiceBridgeService
D/CompatibilityChangeReporter: Compat change id reported: 160794467   # AOSP FGS_START_RESTRICTION
ServiceRecord: allowStartForeground=DENIED  isForeground=false  startForegroundCount=1
```

两个要命的细节：

1. **App 收不到任何异常** —— 系统只写一条日志、直接忽略这次请求。所以
   `try { startForeground(...) } catch { ... }` 永远不触发，App 以为自己已经是前台服务；
2. **后果很重** —— 服务实际只是普通后台服务，App 闲置满 60s 后系统把它停掉：

```
W/ActivityManager: Stopping service due to app idle: u0a235 -1m0s379ms …/.service.VoiceBridgeService
D/VoiceBridgeService: 设备未连接,丢弃控制帧: {"ev":"tts_abort"}
I/VoiceBridgeService: 保活锁已释放
```

实测两次都精确停在 **60.379s**（`-1m0s379ms`），BLE / 网关 / 识别通道全断，
设备彻底用不了，直到用户再打开一次 App。

**用户自己点图标启动时一切正常**：

```
ServiceRecord: isForeground=true types=00000010 foregroundNoti=Notification(channel=voice_bridge_v2 … flags=0x62)
```

即 `types=0x10`（connectedDevice）、通知带 `FLAG_FOREGROUND_SERVICE`（0x40）。
所以这是**后台启动路径**的限制，不是前台服务配置写错。

## 2. 判定：只看通知上的前台服务标记

系统只在**前台服务真的生效**时才给那条通知补上 `FLAG_FOREGROUND_SERVICE`（0x40）。
同一台机器上两次实测：被拒时 `flags=0x2`，正常时 `flags=0x62`。

于是用 `NotificationManager.getActiveNotifications()` 反查自己那条常驻通知即可判定
（不用 `getRunningServices` 这类废弃 API）：

| 情况 | 判定 | 理由 |
| --- | --- | --- |
| 找到通知，带前台服务标记 | `GRANTED` | 真拿到了 |
| 找到通知，不带标记 | `DENIED` | 系统静默拒绝了 |
| **没找到通知** | `UNKNOWN` | 可能被用户划掉、也可能还没贴出 —— **不代表被拒**，不许误报打扰用户 |

`ServiceGuard.classify(...)` 是纯函数，`ServiceGuardTest` 覆盖以上全部分支。

## 3. 补救一：回到前台时重试（唯一会被放行的时机）

`VoiceBridgeService.startForegroundCompat(reason)` 在以下时机各跑一次：
`onCreate`、**每次 `onStartCommand`**（除 `ACTION_STOP`）、以及 `MainActivity.onResume` 触发的
`ACTION_SYNC_FOREGROUND`。每次跑完立刻 `verifyForegroundState(reason)` 核对真实结果。

> 这里修掉了一个让自救失效的细节：原来 `startForegroundCompat()` 只在 `startBridge()` 里调一次，
> 而 `startBridge()` 二次进入会 `if (initialized) return` —— 于是「服务已在跑（被降级）」时
> 用户再打开 App 也不会重试，被拒状态就一直留着。

## 4. 补救二：看门狗（用户没在手机前也能自愈）

`ServiceWatchdogReceiver` 每 5 分钟（`ServiceGuard.WATCHDOG_INTERVAL_MS`）巡检一次：

| 条件 | 动作 |
| --- | --- |
| 用户不想运行（`bridgeWanted=false`，显式停止过） | `NONE` —— 不许把用户停掉的服务拉回来 |
| 服务不在了（进程内静态标记 `isRunning=false`） | `START` —— 重新拉起 |
| 服务在跑但前台服务被拒 | `SYNC_FOREGROUND` —— 原地重试一次（重启也会被再拒一次，没必要） |
| 服务在跑且状态正常（或判不出来） | `NONE` |

实现要点：

- `AlarmManager.setAndAllowWhileIdle`（`ELAPSED_REALTIME_WAKEUP`）：**不需要任何特殊权限**，
  Doze 下系统会顺延（深度 Doze 里最多约 9 分钟一次），属正常；
- 是**一次性**闹钟，每次巡检结束都自己再排下一次（服务没被拉起来时也得续，否则看门狗只生效一次）；
- `KeepAliveState.bridgeWanted` 落盘：看门狗可能在服务已死的进程里被拉起，内存标记那时是空的；
  该标记只在**用户主动启动/停止**时改（`VoiceBridgeService.start/stop`、`ACTION_STOP`），
  服务被系统停掉时**不动**它；
- 开机广播（`BootReceiver`）也会排一次看门狗 —— 重启会清空所有闹钟，而开机后的服务启动同样可能被拒。

## 5. 让用户看得见

「静默」是这个 bug 最坏的地方，所以状态要摆出来：

- **常驻通知**：被拒时折叠行前缀 `⚠️ 后台受限`，展开正文追加一行完整警告；
- **设置页**：被拒时显示橙色警告 + 「打开系统应用设置（开自启动 / 后台无限制）」按钮
  （`ACTION_APPLICATION_DETAILS_SETTINGS`）；
- **日志**：`前台服务被系统静默拒绝(<原因>)：服务已降级为普通后台服务，App 闲置满 60s 后会被系统停掉…`
  与 `前台服务已确认(<原因>)：isForeground=true`。

## 6. 怎么验证

1. 装上 APK（安装路径必然触发拒绝），看日志是否出现
   `前台服务被系统静默拒绝(onCreate)`，以及 `keepalive` prefs 里 `fg_denied=true`：

   ```bash
   adb shell run-as com.shinku.aipassport.openclaw cat shared_prefs/keepalive.xml
   ```

2. 什么都不做等 ~60s：系统会打 `Stopping service due to app idle` 把服务停掉
   （这是**预期内**的，说明防护该出场了）；
3. 等 ≤5 分钟看门狗巡检：日志出现
   `ServiceWatchdog: 看门狗巡检：wanted=true 服务在跑=false 前台服务=… → START`，
   随后 `VoiceBridgeService` 重新初始化；
4. 用户**点图标**打开 App：日志出现 `前台服务已确认(onStartCommand)：isForeground=true`，
   通知里的 `⚠️ 后台受限` 消失、设置页警告消失；
5. 若始终为 `DENIED`：说明该系统策略不接受后台启动 —— 让用户去
   设置 → 应用管理 → 本应用 → **自启动** + **省电策略 → 无限制**（OEM 白名单，App 无法代替）。

> 判据说明：`UNKNOWN` 是**正常**输出（通知被划掉时会这样），不要把它当成失败；
> 只有 `DENIED` 才是问题。
