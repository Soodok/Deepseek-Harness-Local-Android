# AI 设备能力扩展规格书

> Agent 现在只能"看屏幕、点屏幕、开 App"。这份规格书定义了它接下来的第二层能力：
> **感知手机环境**（电量/网络/蓝牙/传感器/通知）、**操控硬件**（WiFi/蓝牙/音量/手电）、
> **主动提醒**（AI 自己排期、自己发通知、自定义文案与按钮）。
>
> 之所以先写规格书再写代码，是因为这层能力涉及权限、Doze、开机自启三条会互相牵连的链路，
> 边写边改必然返工。
>
> 相关代码：`app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt`
> 　相关门脚本：`app/src/main/assets/gates/`
> 　相关插件：`app/src/main/assets/android-plugins/fast-mode.mjs`
> 　相关探针：`scripts/probes/_test_plugin_install_errors.py`

---

## 1. 背景：为什么现在做，以及缺的是什么

### 1.1 现有能力边界（全仓 grep 实测，非推测）

| 能力 | `app/src/main/java/` 命中数 | 结论 |
|---|---|---|
| `BluetoothAdapter` | 0 | 完全无 |
| `WifiManager` | 0 | 完全无 |
| `SmsManager` | 0 | 完全无 |
| `SensorManager` | 0 | 完全无 |
| `CameraManager` | 0 | 完全无 |
| `BatteryManager` | 0 | 完全无 |
| `AlarmManager` | 2（`DshAccessibilityService.kt:84`、`service/EngineService.kt:99`，**均为注释且写明不做**） | 无 |
| `JobScheduler` | 1（`update/AppUpdateScheduler.kt:19` 注释"不引入 WorkManager / JobScheduler"） | 无 |
| `ClipboardManager` | 10（仅 AboutActivity / AppearanceActivity / CodeToast 自身 UI） | 未暴露给 AI |
| 通知读取 | `DshAccessibilityService.kt:1644-1650` 的 `recentNotifications(limit=10)`，靠无障碍事件缓存纯文本"包名: 文本 \| 文本" | 降级方案，无标题结构/无正文/无动作/不知已读 |

已声明的 14 个权限里**没有一个**是为了这些能力加的：
`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`FOREGROUND_SERVICE_SPECIAL_USE`、
`INTERNET`、`MANAGE_EXTERNAL_STORAGE`、`MODIFY_AUDIO_SETTINGS`、`POST_NOTIFICATIONS`、
`READ_EXTERNAL_STORAGE`、`RECORD_AUDIO`、`REQUEST_INSTALL_PACKAGES`、`SYSTEM_ALERT_WINDOW`、
`VIBRATE`、`WAKE_LOCK`、`WRITE_EXTERNAL_STORAGE`。

### 1.2 现有三条接入通道（新能力顺着长，不另起炉灶）

1. **HTTP 桥** `AgentBridge.kt`（2419 行，监听 `127.0.0.1:3083`，路由集中在 `:356-411` 的 `when(path)`，共 56 条）。
2. **CLI 门脚本** `app/src/main/assets/gates/`（`_dsh_http.sh` 169 行 + `notify`/`scr`/`say`/`psx`/`killx`/`loop`/`fast`/`parallel`）。部署在 `EngineConfig.kt:1715` 的 `applyAgentGates`，替换 `@DSH_BASH@`/`@DSH_LIBDIR@` 占位符并剥离 CRLF（v1.2.52 事故：CRLF 让 shebang 变成 `#!/system/bin/sh\r` → `bad interpreter`）。
3. **dsh 工具插件** `app/src/main/assets/android-plugins/fast-mode.mjs`。

**为什么必须做工具插件（`fast-mode.mjs` 头部注释的原话）**：
> `fast` 最初做成 shell 门脚本，AI **不会主动用** —— 因为它是 shell 命令、藏在 seed 说明里，模型要先『想起』再敲命令。注册成工具后它出现在模型的工具列表里，伴随任务型对话自然被选中。

所以每个新能力都要**双份**：CLI 门脚本（给脚本/自动化）+ dsh 工具插件（让模型主动发现）。

### 1.3 范围澄清（本文档的前提）

最初需求里的"发消息、发定时消息"经确认**不是短信**，而是：
> 「是应用发消息提醒，AI 可以自定义消息，给自己发通知」

因此本规格书**不包含**短信、拨号、向他人发消息。这砍掉了最大的滥用面（扣费、骚扰），
风险等级从"会造成外部后果"降为"只打扰自己"。

---

## 2. 架构

```
┌─────────────────────────────────────────────────────────────┐
│ CapabilityGate.kt    权限真值 · 用户开关 · 审计（唯一真值源） │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ engine/cap/                                                  │
│   StatusCap.kt   环境快照（电量/网络/存储/前台应用）          │
│   SensorCap.kt   传感器订阅                                   │
│   NotifyCap.kt   通知发送 + 渠道管理 + 按钮动作               │
│   SchedCap.kt    定时任务表 + AlarmManager + 重启恢复         │
│   ControlCap.kt  WiFi/蓝牙/音量/亮度/震动/手电               │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ AgentBridge.kt   路由注册表（不在本文件写实现）               │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────┬──────────────────────────────────┐
│ assets/gates/<name>      │ assets/android-plugins/          │
│ CLI 门脚本               │ device-tools.mjs（dsh 工具）      │
└──────────────────────────┴──────────────────────────────────┘
```

### 2.1 `CapabilityGate.kt` 的三项职责

这是整套能力安全成立的前提，缺一项就不能算完成：

1. **权限真值**：不能只看 `checkSelfPermission`。`targetSdk = 28` 下蓝牙走旧权限模型（安装即授予），
   而通知在 Android 13+ 走 `POST_NOTIFICATIONS`。Gate 要给出"这个能力现在到底能不能用"的统一答案。
2. **用户开关**：每类能力一个开关，默认**关**（用户已明确选择"默认关，显式开启"）。
   存储在 `SharedPreferences`，键名 `cap.<name>`。
3. **审计**：复用 `engine/LastAction.kt` 的模式（`SharedPreferences("last_action")` 存 `action` + `at`，异步 `apply()`），
   每次调用记一条，供"AI 刚才干了什么"追溯。

### 2.2 路由注册表化

现在 `AgentBridge.kt:356-411` 是一个 56 分支的 `when(path)`，再塞 5 类能力会到 3500+ 行。
改成：

```kotlin
private val routes: Map<String, Route> = buildMap {
    // 现有 56 条原样迁入，路径 → 处理函数引用
    putAll(statusRoutes()); putAll(sensorRoutes())
    putAll(notifyRoutes()); putAll(schedRoutes()); putAll(controlRoutes())
}
```

`when` 分支只留一条：`routes[path]?.handle(ctx, body) ?: (404 to ...)`。
**实现全部搬到 `engine/cap/`**，`AgentBridge.kt` 只做分发。

---

## 3. 端点规格

### 3.1 `GET /dev/status` — 环境快照（P1）

一次返回全部，不要一件事一个接口（否则 AI 每问一句付一次往返）。

```json
{
  "ok": true,
  "battery": { "level": 87, "charging": true, "temperature": 31.2, "health": "good" },
  "screen": { "on": true, "brightness": 128, "timeout_ms": 120000 },
  "audio": { "music_volume": 8, "music_max": 15, "ringer_mode": "normal", "dnd": false },
  "network": {
    "type": "wifi", "connected": true, "validated": true,
    "wifi": { "ssid": "3001", "rssi": -52, "link_speed_mbps": 288, "ip": "192.168.101.21" },
    "cellular": { "type": "5G", "signal": 4 },
    "proxy": { "interface": "vgate0", "addr": "172.30.250.61" }
  },
  "bluetooth": { "enabled": true, "connected": [{ "name": "FreeBuds", "address": "AC:..", "battery": 88 }] },
  "storage": { "internal_free_mb": 69120, "internal_total_mb": 238592 },
  "memory": { "avail_mb": 3204, "total_mb": 11904 },
  "foreground": { "package": "com.tencent.mm", "activity": ".ui.LauncherUI", "label": "微信" },
  "capabilities": { "notify": true, "wifi": true, "bt": true, "sensor": true, "sched": true }
}
```

`capabilities` 是 `CapabilityGate` 的真值输出，AI 靠它决定"能不能做"，不用自己猜。

### 3.2 `GET /dev/sensors?type=light|accel|gyro|proximity|step` — 传感器（P1）

按需取一次值，**不常开**（常开会显著耗电，且 Android 传感器需要 `registerListener` 生命周期管理）。

```json
{ "ok": true, "type": "light", "value": 312.0, "unit": "lux", "at": 1793412345678 }
```

多值传感器（accel/gyro）返回 `{"x":..,"y":..,"z":..}`。

### 3.3 `GET /dev/notifications?limit=20` — 通知读取（P1，升级）

现在只靠无障碍缓存纯文本。升级为真 `NotificationListenerService`：

```json
{
  "ok": true,
  "items": [{
    "key": "0|com.tencent.mm|1234|...",
    "package": "com.tencent.mm",
    "app_label": "微信",
    "title": "张三",
    "text": "晚上一起吃饭？",
    "big_text": "晚上一起吃饭？",
    "posted_at": 1793412345678,
    "ongoing": false,
    "clearable": true,
    "actions": [{ "title": "回复", "remote_input": true }],
    "group": "com.tencent.mm|0"
  }]
}
```

**代价**：`NotificationListenerService` 需要用户在系统设置里手动授权（`BIND_NOTIFICATION_LISTENER_SERVICE`），
不能靠运行时权限弹窗。所以 Gate 里 `notify_read` 的开关要带一个"去授权"按钮，直接跳
`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`。

**降级**：未授权时回落到现有无障碍缓存，并在响应里标 `"degraded": "accessibility_cache"`。

### 3.4 `POST /dev/clipboard` — 剪贴板读写（P1）

```json
{ "op": "read" }                    → { "ok": true, "text": "..." }
{ "op": "write", "text": "要写的" }  → { "ok": true }
```

Android 10+ 限制后台读剪贴板，但本 App 有前台服务 + `SYSTEM_ALERT_WINDOW`，实测可用。

### 3.5 `POST /dev/control` — 硬件操控（P2）

统一一个端点，用 `op` 区分，避免路由膨胀：

```json
{ "op": "wifi",   "enabled": true }
{ "op": "wifi",   "connect": "3001", "password": "..." }
{ "op": "bt",     "enabled": true }
{ "op": "volume", "stream": "music", "value": 10 }
{ "op": "brightness", "value": 200 }
{ "op": "torch",  "enabled": true }
{ "op": "vibrate", "ms": 200 }
```

约束（写进 Gate，不是写在文档里就算数）：
- `wifi connect` 在 Android 10+ 需要 `WifiNetworkSpecifier`（弹系统确认框），无法静默连；
  返回 `{"ok": false, "reason": "user_confirmation_required"}` 并给出引导。
- `bt enabled` 在 `targetSdk = 28` 下走 `BluetoothAdapter.enable()`，**无需** `BLUETOOTH_CONNECT`
  （那是 targetSdk 31+ 才强制的）。这是本项目停留在 targetSdk 28 的一个实际便利。
- `brightness` 需要 `WRITE_SETTINGS`（`Settings.System.SCREEN_BRIGHTNESS`），是特殊权限，
  只能跳设置页手动授予 —— 所以走"引导"而不是"静默失败"。

### 3.6 `POST /dev/remind` — 定时提醒（P3，本轮核心）

```json
{
  "title": "喝水",
  "body": "已经两小时没喝水了",
  "at": "2026-10-31T14:30:00+08:00",   // 绝对时间
  "in_seconds": 1200,                    // 或相对延时（二选一）
  "repeat": { "kind": "daily", "at": "09:00" },
                                         // kind: once | daily | weekday
  "priority": "high",                    // high | default | low
  "sound": true, "vibrate": true,
  "actions": [{ "id": "ack", "title": "知道了" },
              { "id": "snooze", "title": "稍后 10 分钟", "snooze_minutes": 10 }],
  "icon": "water"                        // 可选，映射到内置图标
}
→ { "ok": true, "id": "rm_1793412345678_1", "next_at": 1793414745678 }
```

配套：

```
GET  /dev/remind-list          → { "ok": true, "items": [...] }
POST /dev/remind-cancel {"id": "rm_..."}
```

**实现要点**：
- `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, at, pendingIntent)`。
  `targetSdk = 28` **不需要** `SCHEDULE_EXACT_ALARM`（那是 targetSdk 31+ 才要求），
  这是本项目停在 28 的第二个实际便利。
- `ReminderReceiver`（`BroadcastReceiver`）收到后调 `NotifyCap.show()`。
- 任务表持久化：`SharedPreferences("reminders")` 存 JSON 数组，**不能只靠内存**
  （进程被杀后 AlarmManager 的 PendingIntent 还在，但读不到内容就没法渲染通知）。
- `snooze` 动作：通知按钮 → `ReminderReceiver` → 重新 `setExact` 10 分钟后。
- `repeat`：`daily`/`weekday` 触发后自动算下一次并重新注册。

**通知渠道拆分**（现在全挤在 `agent_notify` 一个渠道里，用户调静音就把所有类型一起静音了）：

| 渠道 ID | 名称 | 重要级 | 用途 |
|---|---|---|---|
| `agent_reminder` | AI 提醒 | HIGH（横幅 + 响铃） | `priority: high` 的定时提醒 |
| `agent_notify` | Agent 任务通知 | DEFAULT（保留现有） | 任务完成推送、无障碍操作进度 |
| `agent_silent` | AI 静默通知 | LOW（无声音） | `priority: low`，只进通知栏 |

### 3.7 `POST /dev/launch-then-run` — 定时唤起 + 执行（P4）

复用现有 `/launch` 与 `loop`，只加编排：

```json
{ "package": "com.tencent.mm", "loop_plan": "..." , "at": "..." }
```

---

### 3.8 `POST /dev/notify-reply` — 代用户回复通知（v1.5.0-beta.3）

```json
{ "key": "0|com.tencent.mm|...", "text": "我十分钟后到", "action_index": 0 }
```

走 `RemoteInput`，能把回复直接送进微信/短信/QQ 的会话里，不需要打开 App、不需要无障碍点界面。
四个条件缺一不可：① 通知监听已开；② 「代回复通知」开关已开（独立于「读取通知」）；
③ 该通知的 action 带 `remote_input`（不是所有通知都可回复，群公告类通常不行）；
④ `key` 未过期（通知被撤回后 key 失效）。

`action_index` 省略时取第一个可回复 action。返回 `{"ok":true,"sent":true,"package":"..."}`。

### 3.9 `GET|POST /dev/media` — 媒体控制（v1.5.0-beta.3）

```json
{ "op": "toggle" }                        // play|pause|toggle|next|previous|stop
{ "op": "seek", "position_ms": 65000 }
{ "op": "volume", "volume": 0.4 }         // 会话音量（0..1 或绝对档位）
{ "op": "stream-volume", "stream": "music", "volume": 0.5 }
```

用 `MediaSessionManager.getActiveSessions(自己的通知监听组件)` —— 这是公开 API，
绕开了 `MEDIA_CONTENT_CONTROL`（signature 级，普通 App 拿不到）。
多会话时优先选 `STATE_PLAYING` 的那个，没有在播的取列表首个。
`GET` 列出全部会话（标题/艺人/时长/进度/音量/是否当前活跃）。

### 3.10 `GET|POST /dev/calendar`、`GET /dev/contacts`、`GET|POST /dev/sms` — 个人信息（v1.5.0-beta.3）

| 端点 | 参数 | 说明 |
|---|---|---|
| `GET /dev/calendar` | `from` `to` `limit` | 毫秒时间窗，默认取未来 7 天 |
| `POST /dev/calendar` | `title` `begin_ms` `end_ms` `location` `notes` | 建事件 |
| `GET /dev/contacts?q=` | `q` `limit` | 按姓名/号码模糊搜 |
| `GET /dev/sms?address=&limit=` | `address` `limit` | 按号码过滤，默认最近 20 条 |
| `POST /dev/sms` | `to` `text` | **不静默发送**，拉起系统短信界面由用户确认 |

短信统一走系统 UI 是刻意的：`SEND_SMS` 是危险权限且极易被滥用，
「AI 能读短信」与「AI 能悄悄给别人发短信」风险量级完全不同。

### 3.11 `GET|POST /dev/nfc`、`POST /dev/geofence` — NFC 与地理围栏（v1.5.0-beta.3）

NFC **无法主动轮询** —— Android 没有「现在扫一下」的 API。流程必须是：
`op=enable`（打开前台读卡）→ 请用户把标签贴上去 → `op=read`（取最近一次读到）。

```json
{ "op": "enable" }   // 需要 App 在前台
{ "op": "read" }     // 返回最近读到的标签（tech 列表 + UID + NDEF 文本）
{ "op": "clear" }
```

地理围栏：

```json
{ "op": "add", "id": "home", "lat": 31.23, "lon": 121.47, "radius_m": 200, "label": "到家" }
{ "op": "list" }
{ "op": "remove", "id": "home" }
```

进出会写进 `LastAction`，AI 可用 `device_status` 旁证。

### 3.12 `POST /dev/dialog`、`POST /dev/verify` — 弹窗拦截与动作校验（v1.5.0-beta.3）

手机 GUI Agent 失败的最大单一原因不是模型弱，而是弹窗：权限框、青少年模式、更新提示、
广告浮层。每次都让模型看图判断既慢又不稳，所以这里给一条**确定性快路径**。

```json
{ "auto": false, "includeDanger": true }    // /dev/dialog
{ "expect": "发送成功", "gone": false, "timeout_ms": 4000 }   // /dev/verify
```

`/dev/dialog` 返回 `dialog`（是否判定为弹窗）、`hits[]`（命中按钮 + 风险等级）、
`suggested`（建议点哪个）。风险分三档：

- `safe`：取消/以后再说/跳过/关闭/**拒绝**——点了不会改变授权或产生消费
- `neutral`：我知道了/了解——可点
- `danger`：**允许/同意/立即购买/立即更新/删除** —— 只报告，`auto=true` 也**绝不自动点**

这条策略不可动摇：漏点一个弹窗最多这一步失败，误点「允许」或「立即支付」的代价不可逆。

`/dev/verify` 把「点完不知道成不成」变成可判定结果：命中即生效，超时就该换定位方式。

### 3.13 `GET|POST /dev/record` — 屏幕录制（v1.5.0-beta.3）

```json
{ "op": "request" }   // 拉起系统授权弹窗（需 App 在前台）
{ "op": "start" }     // 授权后开始录，输出到 filesDir/recordings/rec-*.mp4
{ "op": "stop" }
```

**硬限制先说清**：`MediaProjection` 每次开始都必须由用户点系统弹窗确认，
没有任何 API 能静默录屏；一次授权只能启动一次投影（Android 14+）。
所以「定时自动录屏」在 Android 上做不到，别承诺。

---

## 4. 权限与授权矩阵

| 能力 | 需要什么 | targetSdk=28 下是否需要运行时申请 | 用户可见后果 |
|---|---|---|---|
| `dev/status` 电量/网络/存储 | 无 | 否 | 无 |
| `dev/status` WiFi SSID | `ACCESS_WIFI_STATE` | 否（normal） | 无 |
| `dev/status` 前台应用 | 无障碍服务（已开） | — | 无 |
| `dev/sensors` | 无 | 否 | 无 |
| `dev/notifications` | `BIND_NOTIFICATION_LISTENER_SERVICE` | **必须手动去系统设置开** | 能读到所有 App 的通知内容 |
| `dev/clipboard` | 无（前台服务 + 悬浮窗） | 否 | 无 |
| `dev/control` WiFi 开关 | `CHANGE_WIFI_STATE` | 否（normal） | 会断网 |
| `dev/control` 蓝牙开关 | `BLUETOOTH` / `BLUETOOTH_ADMIN` | 否（安装即授予） | 会断开耳机 |
| `dev/control` 亮度 | `WRITE_SETTINGS` | **特殊权限，跳设置页** | 改全局屏幕亮度 |
| `dev/remind` | `POST_NOTIFICATIONS`（已是 `Default mode: allow`）+ `RECEIVE_BOOT_COMPLETED` | 否 | 会在你设定的时刻响 |
| `dev/remind` 精确闹钟 | 无（targetSdk 28） | 否 | 无 |
| `dev/notify-reply` | 通知使用权 + `NOTIFY_ACTION` 开关 | 与读通知同一开关入口 | **以用户身份对外发言**，不可撤回 |
| `dev/media` | 通知使用权（`getActiveSessions` 要求） | 同上 | 能控制任何在播 App |
| `dev/calendar` | `READ_CALENDAR` / `WRITE_CALENDAR` | **是**（危险权限） | 能看全部日程、能建事件 |
| `dev/contacts` | `READ_CONTACTS` | **是** | 能看通讯录 |
| `dev/sms` 读 | `READ_SMS` | **是** | 能看全部短信 |
| `dev/sms` 发 | 无额外权限（走系统 UI） | 否 | 用户必须亲手点发送 |
| `dev/nfc` 读 | `NFC` + 系统 NFC 开关打开 | 否（normal） | 能读贴近的标签 |
| `dev/geofence` | `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` | **是** | 知道你在哪、进出触发 |
| `dev/dialog` / `dev/verify` | 无障碍服务（已开） | — | 能读界面、能点安全按钮 |
| `dev/record` | 系统录屏授权（每次） | **每次都要用户点头** | 录制屏幕内容 |

需要在 `AndroidManifest.xml` 新增的权限（P0 一次性加完）：

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FLASHLIGHT" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## 5. Doze 与后台存活（本轮最大风险）

### 5.1 实测现状

```
$ adb shell dumpsys deviceidle whitelist | grep dsh
user,app.dsh.asr,10477
```

**`app.dsh.mobile` 不在电池优化白名单里**，只有同门的 `app.dsh.asr` 在。
全仓 grep `IGNORE_BATTERY` / `BatteryOptim` **零命中** —— 项目从来没有申请过白名单。

`cmd appops get app.dsh.mobile POST_NOTIFICATION` → `No operations. Default mode: allow`
（targetSdk=28 让通知权限自动授予，通知通道本身是通的）。

### 5.2 后果与对策

ColorOS（OPPO / Android 16）对非白名单应用的后台执行限制比 AOSP 更激进。
即使 `setExactAndAllowWhileIdle`，闹钟仍可能被推迟到几十分钟后。

对策分三层，**都要做**：

1. **代码层**：`setExactAndAllowWhileIdle` + `setAlarmClock`（后者优先级更高，且系统通知栏会显示闹钟图标，用户可见）。
2. **引导层**：设置页加一行「允许后台准时提醒」，点击走
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（targetSdk 28 可直接拉起系统确认弹窗，不需要跳列表页）。
   同时在 Gate 里查 `PowerManager.isIgnoringBatteryOptimizations()`，未开启时提醒响应里带
   `"warn": "not_whitelisted"`。
3. **验收层**：**必须真机实测**「锁屏 → 等 20 分钟 → 通知是否准时」。代码写完不算完成。

### 5.3 开机自启

全仓 grep `BOOT_COMPLETED` **零命中** —— 重启后定时任务表还在磁盘上，但没人重新注册闹钟，
等于全丢。P3 必须加：

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<receiver android:name=".engine.cap.BootReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

`MY_PACKAGE_REPLACED` 也要接 —— 否则每次升级 APK（本项目的日常）都会丢任务。

---

## 6. 分期与验收

| 期 | 内容 | 验收标准 |
|---|---|---|
| **P0** | 路由注册表化、`engine/cap/` 拆包、`CapabilityGate.kt`、补权限声明、`dev ping` 单点 | `dev ping` 从 CLI 与工具插件两条路通；门禁全绿（`check-i18n.py` / 两个探针）；`AgentBridge.kt` 行数下降 |
| **P1** | `dev/status`、`dev/sensors`、`dev/notifications`（真 Listener）、`dev/clipboard` | 真机上 `dev status` 返回字段全部有值（不是 null）；通知读取能拿到微信的标题+正文；未授权时优雅降级 |
| **P2** | `dev/control`（WiFi/蓝牙/音量/亮度/手电/震动） | 每项真机执行一次并截图；`WRITE_SETTINGS` 未授权时返回引导而非崩溃 |
| **P3** | `dev/remind` 全套 + 渠道拆分 + `BootReceiver` + 白名单引导 | **锁屏等 20 分钟通知准时到达**；重启手机后任务恢复；点「稍后 10 分钟」真的 10 分钟后再响 |
| **P4** | `dev/launch-then-run` | 定时启动微信并执行一个 loop 计划 |
| **P5** | `device-tools.mjs` 注册工具、`SEED_VERSION` 49→50、四语文案 | 模型工具列表里出现 `device_status` / `device_control` / `remind`；四个 locale 都有文案 |

**建议顺序**：P0 → P1 → P3（用户最想要）→ P2 → P4 → P5。

---

## 7. 工具插件形态（P5 参考 `fast-mode.mjs`）

```js
// app/src/main/assets/android-plugins/device-tools.mjs
const inject = ["tools"]

const defineTool = await loadSdk("defineTool")   // 必须绝对路径动态 import，见下

ctx.tools.register(defineTool({
  name: "device_status",
  description: "读取手机当前状态：电量、网络、WiFi/蓝牙、存储、前台应用等。用于回答"
             + "「现在有网吗」「还有多少电」这类问题，比执行 shell 命令更快更准。",
  parameters: {
    fields: { type: "string", required: false,
              description: "逗号分隔，如 battery,network；省略返回全部" },
  },
  output: {
    schema: { type: "object" },
    render: (_args, v) => [{ type: "text", text: JSON.stringify(v, null, 2) }],
  },
  async execute(args) { /* 调 _dsh_http.sh 或直接用 http 请求 */ },
}))
```

**必须遵守的三条（都是踩过的坑）**：
1. **不能静态 `import { defineTool } from "@deepseek-ai/dsh-tools"`** —— 插件在
   `engine/android-plugins/`，不在 node 模块解析链上（`engine/` 下无 `node_modules`），
   裸包名必失败（`NODE_PATH` 对 ESM 无效）。必须从
   `path.dirname(new URL(import.meta.url).pathname)` 推 engine 根，按绝对路径
   `await import(pathToFileURL(c).href)`，候选 `engineRoot/lib/node_modules/@deepseek-ai/dsh-tools/lib/index.js`
   与 `engineRoot/node_modules/...`。
2. **`output` 是 `defineTool` 必填字段**（v1.2.169 踩坑：缺它抛
   `TypeError: Cannot read properties of undefined (reading 'render')`）。
3. 插件导出 `{ apply, inject, name }`，`inject = ["tools"]`。

---

## 8. 不做的事（明确划界）

| 不做 | 理由 |
|---|---|
| 短信发送 / 拨号 | 用户明确澄清"发消息"指通知提醒，不是短信。会扣费、骚扰他人，滥用面最大 |
| 读取他人短信 / 通话记录 | 同上，且无需求 |
| 静默安装 / 卸载其他 App | `REQUEST_INSTALL_PACKAGES` 已有但只用于自身更新 |
| 录屏 / 持续录音 | 只在用户显式调用 `/screen`、`/ocr` 时截一帧 |
| 常驻传感器监听 | 只在 `dev/sensors` 被调用时取一次值，订阅后立刻注销 |
| 修改通知渠道的重要级 | 渠道一旦创建，重要级由用户掌握；只能新建不能改（Android 限制） |

---

## 9. 已知遗留与后续

- **`app.dsh.mobile` 不在 Doze 白名单**：本规格书要求加引导，但不能自动加入（必须用户点击系统弹窗）。
- **通知渠道拆分有迁移问题**：已存在的 `agent_notify` 用户可能调过静音，新建渠道默认继承应用级设置，
  需要在设置页给出说明，避免"我明明没关却不响"。
- **`_bench/` 1.5G、`_runtime_bak/` 3.0G** 仍在仓库工作区（用户选择保留，见 `docs/HYGIENE.md`）。
