# 能力地图与实施计划

> 生成时间：2026-11-02 · 版本基线 v1.5.0-beta.12（343）
> 本文档是「手机能干什么」的全量盘点与落地排序，不是路线图宣传稿。每条能力按**权限档实测定界**：已实现 / 待实现 / 不可实现（附失效原因）。
> 规范：本表只写可复核事实；标「推测」的条目不得对外承诺。

---

## 0. 执行环境事实（决定一切的前提）

| 事实 | 证据 |
|---|---|
| 引擎进程 `node` 以 **uid 0（root）+ SELinux 域 `u:r:ksu:s0`** 运行 | `/proc/4999/status` → `Uid: 0 0 0 0`、`/proc/4999/attr/current` → `u:r:ksu:s0` |
| 因此 AI 通过门脚本执行命令**天然是 root**，无需 `su` 前缀 | `su -c 'exec 3>/dev/uhid'` → `UHID_OK`；引擎同身份同域 |
| App 主进程是 `u0_a488`（无附加组），能力受限 | `su 10488 -c` → `uid=10488 groups=10488` |
| Shizuku/shell 身份是 `uid=2000`，域 `u:r:shell:s0`，有 `input(1004)`/`uhid(3011)` 组 | `id` 实测 |
| 设备：OPPO PLZ110 / Android 16 / KernelSU 4.1.3 / arm64 | `su -v` → `4.1.3:KernelSU` |

**三档权限模型**（本表所有「可行性」列都基于此）：

| 档位 | 身份 | 代表能力 |
|---|---|---|
| **A. 普通 App** | `u0_a488` | 应用层 API、无障碍、通知监听、传感器、应用自己的存储 |
| **B. shell / Shizuku** | `uid 2000` / `u:r:shell:s0` | `dumpsys`、`cmd`、`pm`、`am`、`settings`、`screenrecord` |
| **C. root** | `uid 0` / `u:r:ksu:s0` | `/dev/uhid`、`iptables`、`tcpdump`、`btsnoop`、`/data/misc`、`/dev/block` |

---

## 1. 已实现能力（可直接用，不需新开发）

### 1.1 屏幕与交互
| 端点 / 门脚本 | 能力 | 档 |
|---|---|---|
| `GET /screen` · `scr dump` | 无障碍节点树（结构化，含坐标） | A |
| `POST /tap` `/gesture` `/input` `/key` | 点按 / 手势 / 输入 / 按键 | A |
| `GET /screenshot` · `scr shot` | 截图 base64，**走 `AccessibilityService.takeScreenshot`（API 30+），不弹 MediaProjection 授权框** | A |
| `POST /tap-id` | 按 viewId 点按 | A |
| `POST /dialog` | 弹窗识别（几何判据：底部成对按钮） | A |
| `POST /verify` | 动作效果验证 | A |
| `GET /diff` · `POST /idle` | 屏幕差异 / 等待静止 | A |
| `GET /dev/record` · `POST /dev/record` | MediaProjection 录屏（**必须用户点头，无法静默**） | A |

### 1.2 设备能力（29 个 `/dev` 端点）
| 能力组 | 端点 | 档 |
|---|---|---|
| 状态感知 | `/dev/ping` `/dev/status` `/dev/sensors` | A |
| 通知 | `/dev/notifications` `/dev/notify-reply` | A |
| 剪贴板 | `/dev/clipboard` | A |
| 控制 | `/dev/control`（wifi/bt/volume/brightness/torch/vibrate） | A |
| 提醒 | `/dev/remind` `/dev/remind-list` `/dev/remind-cancel` | A |
| 蓝牙 | `/dev/bt`（status/enable/disable/scan/advertise/connect/services/read/write/rfcomm/log） | A |
| WiFi 射频 | `/dev/wifi-radio`（status/scan/enable/disable/link/capture） | A+C（capture 需 root） |
| 媒体 | `GET/POST /dev/media` | A |
| 个人数据 | `/dev/calendar` `/dev/contacts` `/dev/sms` `/dev/calls`（**四项独立授权**） | A |
| NFC | `GET/POST /dev/nfc`（enable/disable/clear/read，**无刷写**） | A |
| 地理围栏 | `/dev/geofence` | A |
| 权限自助 | `GET/POST /dev/perms` `/dev/perms/status` `/dev/perms/revoke` | C（脱离进程脚本） |
| 自描述 | `GET /dev/help` `GET /dev/routes` | — |

### 1.3 自描述通用入口 `dev`
`GET /dev/help` 返回 8 组 33 端点目录；`dev api <METHOD> <PATH> [BODY]` 可直调任意端点（**不绕过各端点自己的权限门**）。门脚本 `dev` 是纯渲染器，从服务端拉目录，不在脚本里硬编码能力名。

### 1.4 终端与运行时
- 真 PTY：`app/src/main/java/app/dsh/mobile/engine/TerminalPtySession.kt` 用 Termux `TerminalView`，含 `stty sane`，vim/tmux/htop 可用。
- 门脚本池：19 个（`scr` `say` `psx` `killx` `loop` `fast` `notify` `remind` `media` `notify-reply` `bt-radio` `wifi-radio` `device-control` `device-status` `device-ping` `getperm` `grantperm` `dev` + `_dsh_http.sh`）。

### 1.5 权限
Manifest 声明 **1358 个**权限，真机实收 **432 个**（install 385 + runtime 47）。关键：`WRITE_SECURE_SETTINGS` / `DUMP` / `READ_LOGS` / `PACKAGE_USAGE_STATS` / `SEND_SMS` / `CALL_PHONE` / `READ_CALL_LOG` / `CAMERA` / `BATTERY_STATS` / `GET_APP_OPS_STATS` / `INTERACT_ACROSS_USERS` 全部 `granted=true`。

---

## 2. 待实现能力（按优先级排序）

### P0 — 高价值、低成本、真机已验证可行

#### P0-1 uhid 虚拟 HID 设备（**真机已验证通过**）
- **能做什么**：创建虚拟键盘/鼠标/手柄/消费者控制设备，系统当作**真外接硬件**（`Classes: KEYBOARD | ALPHAKEY | EXTERNAL`）。绕开 `input keyevent` 被标记为「注入」的检测。
- **实测证据（2026-11-02 复验，OPPO PLZ110 / Android 16 / KernelSU 4.1.3）**：`hid tap vol+` → 音量 `60 → 70`、`hid tap vol-` → `50`、`hid tap mute` → `0`；`hid text hi` → `{"ok":true,"sent":2}`；早期注入 Keyboard 页 `0x80`（KEY_VOLUMEUP）也曾让音量 `41 → 81`。`dumpsys input` 出现 `DSH Virtual Keyboard`，节点 `/dev/input/eventNN`（每次 create 节点号会变）。
- **实现要点**：
  - 必须以 `r+` 打开 `/dev/uhid`（`w` 会 `EBADF`）。
  - `UHID_CREATE2 = 11`：`u32 type=11` + `name[128]` + `phys[64]` + `uniq[64]` + `u16 rd_size` + `u16 bus=0x03` + `u32 vendor/product/version/country` + `rd_data[4096]`。
  - `UHID_INPUT2 = 12`：`u32 type=12` + `u16 size` + report。
  - report descriptor 的 usage 范围必须覆盖目标键（消费者控制页要写 `0x19,0x00,0x2a,0xff,0x00`）。
  - **两个 HID 页的 usage 号不通用**（真机逐项实测）：Consumer 页 `vol+`=233(0xE9)、`vol-`=234(0xEA)、`mute`=226(0xE2)；Keyboard 页 `vol+`=128(0x80)、`vol-`=129、`mute`=127(0x7F)。在 Consumer 页发 `0x80` 无任何反应。因此 `tools/hid.mjs` 提供别名表（`vol+`/`next`/`enter`…），别名自带正确的 page，AI 不该记 hex。
  - **create 与 INPUT2 之间必须留 ≥200ms**：`device_add` 是异步的，写完 create 立刻注入会被内核丢弃。
  - **屏幕必须 Awake**：`Dozing` 下事件能到内核（`getevent` 抓得到 `KEY_VOLUMEUP DOWN/UP`）但 InputDispatcher 不派发给窗口——表现为「全部返回成功但毫无反应」。
- **档**：C（root）。
- **风险**：创建失败时返回值被忽略会导致静默无效;必须回读 `dumpsys input` 确认设备注册。
- **产出（已全部落地）**：
  - `app/src/main/assets/tools/hid.mjs` —— uhid 一次性执行器（`probe`/`tap`/`text`/`key`/`mouse`，含别名表）。
  - `app/src/main/assets/gates/hid` —— 门脚本，`exec` 引擎自带 node 直跑上面那支脚本。
  - `dev hid-status` / `dev hid op=...` 桥端点（`HidCap.kt`，角色是转发器；**推荐走门脚本**，因为桥那条路要在 App 进程里 `su` 等授权，无 UI 时会挂住）。
  - 能力开关 `cap.hid` 默认关（`PermissionCenterActivity` → 「Virtual input device」）。
  - AI 工具 `device_hid`（`android-plugins/device-tools.mjs`，op=`tap`/`text`/`key`/`mouse`/`status`/`wake`）。

#### P0-2 无障碍三个未接入的冷门能力
| 能力 | API | 价值 | 档 |
|---|---|---|---|
| `GLOBAL_ACTION_LOCK_SCREEN` | `performGlobalAction(28+)` | 直接锁屏，无需 root | A |
| `GLOBAL_ACTION_TAKE_SCREENSHOT` | `performGlobalAction(28+)` | 系统级截图（走系统截图流程，会落通知栏） | A |
| `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` | `setServiceInfo()` 运行时加 flag | 读到**多窗口**（分屏/悬浮窗）节点树 | A |
| `GLOBAL_ACTION_ACCESS_SHORTCUTS` | `performGlobalAction(34+)` | 打开无障碍快捷方式 | A |
- **现状**：`performGlobalActionByName`（`DshAccessibilityService.kt:1836`）只支持 5 个动作（back/home/recents/notifications/quick_settings）。
- **产出**：扩到 9 个 + `setServiceInfo` 动态加 flag。

#### P0-3 btsnoop 二进制解析（替代/补充现有文本日志）
- **能做什么**：解析 `/data/misc/bluetooth/cached_hci/btsnoop_*.cfa`（magic `btsnoop\0`，datalink 1002/H4），还原完整 HCI 帧流。
- **实测证据**：样本 64074 字节解析出 **1938 个 HCI 包**。
- **缺口**：`app/src/main/java/app/dsh/mobile/engine/cap/RadioCap.kt:423` 的 `btLog()` 只读 `*.log` 文本。
- **实现要点**：文件头 `>8sII`（magic/version/datalink），每条记录 `>IIIIQ`（orig_len/incl_len/flags/drops/timestamp）；按首字节区分 Command(0x01)/ACL(0x02)/SCO(0x03)/Event(0x04)。
- **档**：C（root，读 `/data/misc/bluetooth`）。
- **产出**：`/dev/bt` 的 `log` op 扩参数 `format=binary`。

#### P0-4 按 UID 网络封锁 + 流量查询
- **能做什么**：`iptables -A OUTPUT -m owner --uid-owner <uid> -j DROP` 精确断某 App 网络（实测 `DROP_OK`）；`dumpsys netstats detail` 读每 App 流量（微信 15775B / 抖音 644B）。
- **档**：拦截 C，查询 B。
- **产出**：`/dev/netblock` 端点（`list`/`block <pkg>`/`unblock <pkg>`/`status`）+ `dev net` 门脚本。

### P1 — 高价值、中成本

#### P1-1 手机当蓝牙外设（HID Device + GATT Server）
- **BluetoothHidDevice**（API 28+）：手机冒充蓝牙键盘/鼠标控制电脑。需要 `BluetoothHidDeviceAppSdpSettings` + `registerApp()`。**注意**：多数 OEM 需 `BLUETOOTH_PRIVILEGED`，实测成功率待验。
- **BluetoothGattServer**：手机作为 BLE 服务器，别的设备连上来读写自定义服务。纯 A 档可用。
- **产出**：`/dev/bt` 加 `gatt-server` / `hid` op。

#### P1-2 AudioPlaybackCapture（录其他 App 的声音）
- **API**：API 29+ `MediaProjection` + `AudioPlaybackCaptureConfiguration`。
- **限制**：需 MediaProjection 用户点头；目标 App 若设 `allowAudioPlaybackCapture=false` 或 DRM 内容会失败。
- **产出**：`/dev/record` 加 `audio` op。

#### P1-3 上下文总线 + 主动提醒闸门（**最大差异化方向**）
- **为什么**：现在所有 AI 助手都是「你问才答」。主动判断「此刻值不值得打扰」是空白。
- **实现**：本地汇总通知 + 前台应用（`PACKAGE_USAGE_STATS`）+ 位置 + 传感器 + 时间，用极小模型做「该不该提醒」的二元判断，通过通知栏推送。
- **档**：A（全部公开 API）。
- **关键**：必须克制——单一触发性事件不打扰，需连续/显著模式才提醒。

#### P1-4 「AI 的手」可视化
- **能做什么**：悬浮窗实时显示 AI 准备点哪里（圆点 + 脉冲 + 预测路径），配合无障碍坐标。
- **价值**：可解释性 + 调试手势时序。几乎没人做成常驻功能。
- **档**：A（`SYSTEM_ALERT_WINDOW` + 无障碍）。

#### P1-5 屏幕变化检测（不存视频）
- **能做什么**：`MediaProjection` + `VirtualDisplay` + `ImageReader` 每隔 N 帧抓一帧转灰度做差分，只算「有没有变」。省电的「看屏幕」方案。
- **产出**：`GET /dev/screenwatch` 返回变化率 + ROI。

### P2 — 中价值或高成本

| 项 | 能力 | 难点 | 档 |
|---|---|---|---|
| P2-1 振动摩斯电码反馈 | `VibrationEffect.createWaveform` 用长短振动「说」结果 | 无 | A |
| P2-2 通话筛选（`CallScreeningService`） | AI 判断是否接听/拒接 | 需用户设为默认来电筛选应用 | A |
| P2-3 通知中枢 + AI 摘要 | 汇总所有通知做分类/摘要 | 无（已有通知监听） | A |
| P2-4 传感器场景推断 | 气压计判楼层、光感判口袋、步频判走路 | 需自研算法 | A |
| P2-5 精确闹钟 | `SCHEDULE_EXACT_ALARM` | 用户可撤销 | A |
| P2-6 Doze 白名单自助 | `dumpsys deviceidle whitelist +<pkg>` | 无 | B |

### P3 — 高成本或不稳定，暂不做

| 项 | 为什么不做 |
|---|---|
| 虚拟屏（VirtualDisplay + 输入注入） | 需 `CAPTURE_VIDEO_OUTPUT` / `INJECT_EVENTS`（signature），root 下也依赖 OEM；工作量大 |
| WiFi monitor 抓 802.11 裸帧 | **驱动拒绝**：`iw phy phy1 interface add mon0 type monitor` → `Invalid argument (-22)`，内核日志 `__wlan_hdd_add_virtual_intf: Adding monitor interface not supported`（`peach_v2` 驱动）。硬件层面做不到 |
| 全局触摸/键盘监听（读 `/dev/input/event7`） | **SELinux 拒绝**：shell（有 `input` 组）与 root（`u:r:ksu:s0`）读均得 0 字节。厂商关闭了 `input_device` 规则 |
| 伪造 HCI 帧 | 无 `hcitool`/`hciconfig`/`btmon`；`AF_BLUETOOTH`+`BTPROTO_HCI` 需 `CAP_NET_ADMIN` 且 SELinux 限制 |
| NFC 刷写/卡模拟 | `NfcAdapter` 无 `transceive` 公开 API；全仓库 `IsoDep`/`writeNdef` 零命中。Android 10+ 应用不能静默开关 NFC（`cmd nfc enable-nfc` 在 app uid 下 EXIT=255） |
| 把用户 CA 装进系统信任库 | Android 7+ 需 root 改 `/system`；无 root 时不可能 |
| Device Owner 静默装任意 APK | 仍走 `PackageInstaller.Session`；`setGlobalSetting` 只认白名单 key |
| CVE 临时 root | 设备已有 KernelSU；更实际的是 Shizuku |
| WiFi CSI 人体感知 | 量产手机固件不暴露 CSI 接口（需 Nexmon 等定制固件） |

---

## 3. 实施顺序（本轮）

| 步 | 内容 | 预计 |
|---|---|---|
| 1 | 写本文档 | ✅ |
| 2 | P0-2 无障碍三能力（`lock_screen` / `take_screenshot` / 多窗口 flag） | 小 |
| 3 | P0-1 `/dev/hid` 端点 + `dev hid` 门脚本 + 能力开关 | ✅ 2026-11-02 真机验证 |
| 4 | P0-4 `/dev/netblock` 端点 + `dev net` 门脚本 | 中 |
| 5 | P0-3 btsnoop 解析（`/dev/bt` 加 `format=binary`） | 中 |
| 6 | 编译 + 门禁 + 真机验证 + 提交 | — |

---

## 4. 门禁与验收标准

- 编译：`gradle.bat -p . -Ppackage=noruntime assembleRelease` 必须 BUILD SUCCESSFUL。
- i18n：`python scripts/check-i18n.py` → `All i18n checks passed.`
- 权限门禁：`python scripts/check-no-bulk-perms.py` → OK。
- 探针：py 基线 37 passed / 1 failed（`_test_corepack_hardlink.py` 为 noruntime 既有基线），不得新增失败。
- 真机：每个新端点必须实调返回真实数据，不接受「调用成功」作为证据。
- 新增能力必须挂到 `EndpointCatalog.kt`，且 `GET /dev/routes` 的 `catalog_problems` 必须为空。

---

## 5. 明确不做的事

- 不伪造 HCI 帧、不伪造 NFC 卡、不伪造短信发件人。
- 不做「监听真人触摸/键盘」——SELinux 已封。
- 不做通话录音（Android 10+ 已禁，`VOICE_CALL` 在主流厂商不可用）。
- 不做需要改 SELinux 策略或刷内核的能力。
- 不为调试开绕过权限的通道；新能力一律挂到 `CapabilityGate` 且默认关闭（只读感知除外）。
