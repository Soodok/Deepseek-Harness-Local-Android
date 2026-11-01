# 对标 DSHA 的功能差距与落地路线

> 依据：DSHA 内测版全量源码（`18980663483/DSHA-prerelase` @ `codex/prerelease-190`，
> 673 个 Java 文件 / 21M app 代码）+ 六路子代理逐包分析与本仓库逐项 grep 核实。
> 生成时间 2026-11-01。**只写「用户能感知到的功能与体验」差距**，不列权限清单。

---

## 一、我们已经追平或反超的（不必再做）

| 能力 | 我方实现 | DSHA 对应 | 判定 |
| --- | --- | --- | --- |
| 真 PTY 终端 | `app/src/main/java/app/dsh/mobile/engine/TerminalPtySession.kt` + `app/src/main/java/app/dsh/mobile/TerminalActivity.kt`（576 行、多标签、Termux `TerminalView`） | `PtySession.java` + `TerminalFragment.java` | 追平（v1.5.0-beta.7 补了 `stty sane`） |
| 读屏结构化输出 | `DshAccessibilityService.dumpScreenCompact()`（压缩 6.8 倍）/ `dumpScreenJson()`；含 hint 文本 `p:`、勾选态 `v`/`o`、图标上下文关联、OCR 兜底 | `ScreenSnapshotReader` | 追平，细节更细 |
| 悬浮状态条 | `app/src/main/java/app/dsh/mobile/StatusOverlay.kt`（914 行）+ `TickerBar.kt`（259 行，v1.5.0-beta.7 修好真机） | `dsh-status-overlay/lib/index.js`（421 行） | 反超（DSHA 无流式正文能力） |
| 设备能力端点 | `AgentBridge.kt` **79 条**路由，26 个 `/dev/*` | `BridgeRoutes.java` 35 个枚举 | 反超 |
| 局域网 Web UI | 已有（`docs/LAN_WEBUI.md`） | `LanProxyService` + `LanAuth` | 追平 |
| 备份导出 | `DataExport.kt`（397 行）+ `BackupActivity.kt` | `backup/` 17291 行 | 功能少但基础在 |

---

## 二、真差距（按「用户可感知度 × 工作量」排序）

### P0 · 一周内可交付，用户天天能感知

#### 1. 屏幕操作凭据（ref + epoch）
- **我们的现状**：`POST /tap-id`（`AgentBridge.kt:2363`）→ `DshAccessibilityService.tapById()`（`:1605`）
  **只按 viewId 查找、无一次性、无过期、无观察闸门**。
- **DSHA**：`ScreenNodeLeaseStore.java`（单树 200 节点、**30 秒过期**、先 remove 再校验一次性用过即焚）
  + `ScreenSemanticNode.java:25-39` 二次身份确认（refresh 后逐项比 windowId/enabled/password/editable/
  clickable/scrollable/viewId/className/text/description，任一变即「不是同一控件」）
  + `ControlTaskAuthority.java:118-119` 的 `needsObservation` 总闸（换窗口立刻置回 true）。
- **用户感知**：AI 不会「点错旁边的按钮」，也不会拿上一次看到的坐标去点已经变了的界面。
- **落地**：新建 `engine/ScreenLease.kt`（纯 Kotlin，无 Android 依赖，可单测）；`dumpScreenCompact`
  输出的每个可点节点分配 `#n` 编号；`tapById` 改为 `tapByLease(leaseId)`。约 300 行。

#### 2. 工具权限三档（自动允许 / 每次询问 / 禁止）
- **我们的现状**：`CapabilityGate` 只有「开/关」布尔，无「本次询问」。
- **DSHA**：`ScreenConfirmationPolicy.java` 三档 `MANUAL`/`CUSTOM`/`ALLOW_ALL`，默认 `MANUAL`，
  解码失败 fail-closed 回退 `manual()`。
- **用户感知**：敏感 App（支付宝/微信/银行）可以设成「每次问我」，普通 App 设成「自动允许」。
- **落地**：`CapabilityGate` 增加 `POLICY_AUTO/ASK/BLOCK`；`DialogCap` 已有弹窗能力可复用；
  权限中心加三档选择器。约 200 行。

#### 3. 确认三通道并行（通知 + 悬浮条 + Activity）
- **我们的现状**：`UserPromptActivity` 单一通道 —— 用户切走 App 就看不见，只能干等。
- **DSHA**：`AssistantConfirmationWindow.java` + `showConfirmNotification` + `OverlayController.askConfirm`
  三通道并行，谁先点谁生效；**60 秒超时算「无回答」而非拒绝**（`Decision(Boolean allowed, int windowId)`
  的 `allowed` 可为 null）。
- **用户感知**：AI 请求操作手机时，不管用户在看什么都能批；不批也不会被当成拒绝。
- **落地**：`AgentBridge` 的确认请求改三路广播；新增 `notify` 通道（`NotifyCap` 已有）。

### P1 · 两到三周

#### 4. 独立虚拟屏（VirtualDisplay）
- **我们的现状**：**完全没有**。AI 只能操作真实屏幕，用户被迫让出手机。
- **DSHA**：`vscreen/` 27 个文件 8391 行；`app_process --server` 起的特权 Core 创建 `VirtualDisplay + ImageReader`；
  需 Standard 版 + Android 11+ + ADB/Shizuku/Root 三者之一。
- **用户感知**：**这是最大的体验质变** —— AI 在后台用一块看不见的屏跑 App（下单、签到、刷任务），
  用户照常用手机。
- **落地**：`Privilege.runPrivileged` 已有（Shizuku/Root 双通道），`RadioCap` 已大量使用；
  需新建 `engine/vscreen/` 包，实现 Core 脚本 + `VirtualScreenManager`。**工作量最大，收益也最大**。
  建议先做 go/no-go 验证：用现有 `su` 通道跑一个 `app_process` 创建 VirtualDisplay 看是否被 ROM 拦。

#### 5. 助手主动入口（长按 Home / 语音键）
- **我们的现状**：无 `ASSIST` intent-filter。**DSHA 自己也没有**（全库零命中 `startLocal()` 是死代码）。
- **用户感知**：长按 Home 直接唤起 AI，比找 App 图标快。
- **落地**：Manifest 加 `ASSIST` + `VOICE_COMMAND` intent-filter + 透明 Activity 转发。

#### 6. 自动备份计划
- **我们的现状**：`BackupActivity.kt` 只有手动导出/导入。**无定时、无保留策略、无校验**。
- **DSHA**：默认开启，三种模式（每天指定时间 / 每隔 1–168 小时 / 停止 DSH 后），
  保留最近 3 份已验证副本，运行中自动延后不打断，分范围备份（全量/只对话/只插件/只设置）。
- **用户感知**：重装 App 数据不丢。
- **落地**：`WorkManager` 周期任务 + `DataExport` 加 SHA-256 校验 + 保留策略。

### P2 · 一个月+

#### 7. MCP Server（对外）
- **我们的现状**：插件中心有 `mcp-connector` 目录项但无实现。
- **DSHA**：内部 stdio 已实现（`dsha-android` 10 工具 / `dsha-vscreen` 15 工具），
  对外 HTTP MCP **也还没有**（`ROADMAP.md:132` 仍是路线图）。
- **用户感知**：Claude Desktop / Cursor 能直接调手机能力。
- **落地**：在 `AgentBridge` 加 MCP Streamable HTTP 端点，复用现有 Bearer token。

#### 8. 画中画（PiP）
- 我们零命中，DSHA 有（默认关闭）。实现成本低，优先级看宝宝偏好。

#### 9. WebDAV 备份上传
- 我们零命中，DSHA 在阶段 4.3（也未实现）。可并入第 6 项一起做。

---

## 三、DSHA 也没做好的（我们的机会窗口）

| 维度 | DSHA 短板 | 我们的机会 |
| --- | --- | --- |
| **助手主动性** | 用户**完全无法主动唤起**（`startLocal()` 是死代码） | 加 ASSIST 入口即可反超 |
| **语音** | 无 TTS、无唤醒词、无连续对话（自己承认「不是连续双向语音通话」） | 做完整的语音闭环 |
| **TTS 反馈** | 全库 `TextToSpeech` 零命中 | 边执行边播报 |
| **流式悬浮条内容** | `dsh-status-overlay` 421 行，只显示状态不显示 AI 正文 | 我们已有 `SessionTail`（v1.5.0-beta.7 修好） |
| **MCP 对外** | 仍是路线图 | 抢先落地 |
| **插件权限** | 无 per-plugin 权限系统（自认「自动安装不是独立安全认证」） | 做细粒度插件授权 |

---

## 四、建议的推进顺序

1. **先修 P0 三项**（屏幕凭据 / 权限三档 / 确认三通道）—— 都是「让 AI 不乱动手」的体验，
   改动都在我们已有架构内，风险低。
2. **同时做虚拟屏 go/no-go**（1 天验证）—— 这是唯一能形成代差的功能，先确认 ROM 是否放行。
3. 虚拟屏验证通过再排 P1 其余；不通过就跳过，把资源投到助手入口 + 自动备份。
4. P2 按宝宝的偏好排序。

---

## 五、已在本轮修掉的两个真 bug（顺带产出）

- `ZstdTail.kt` 漏跳过 zstd 帧尾校验和（descriptor bit 2）⇒ 真机只解析第 1 帧，悬浮条永远空白。
- `ZstdTail.kt` 漏注入 `OPENSSL_CONF` ⇒ engine 内置 node 启动即 `exit=13`，解压必失败。
- `TerminalPtySession.kt` 未做 `stty sane` ⇒ PTY 可能出现「按键无回显」。
