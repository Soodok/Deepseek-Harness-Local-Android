# 模块化与新手引导改良计划

> 日期：2026-11-12 · 基线 `main` `f18b56e9`（v1.6.0-beta.1）
> 依据：DSHA《新生计划》18 模块讨论稿 v0.1（2026-11-12，**非公开，仅作决策输入**）
> + 本仓代码实测盘点（86 个 Activity / 82 个 engine 文件 / 29 个 cap / engine 52,987 行）。
>
> **口径**：本计划做「逻辑边界 + 契约」，**不做 18 个 Gradle 子工程**。

---

## 0. 结论

1. DSHA 那套的价值不在「18」这个数字，而在四个契约：**模块声明、宿主注册、能力授权、资源归属**。
2. 我们只抄三个：**端点契约升级、授权总线、功能面注册表**。不抄模块安装/卸载、不拆 18 个工程、不做 Gecko、不做桌宠模块化。
3. 首个纵向闭环选**屏幕操作**（不是「关于/许可」）：它是唯一同时穿过目录、派发、凭据、确认、审计、MCP 的链路，收益最大。
4. 新手引导 9 项改良：目标从「装完不知道干啥」变成「5 秒知道 + 一次成功对话」。
5. 备份基座已有（`DataExport` zip + manifest、合并式导入），只做增量：自动计划、范围选择、校验与保留。

---

## 1. DSHA 18 模块拆解：哪些是真问题

| DSHA 模块 | 它想解决的真问题 | 我们的对应物 | 取舍 |
|---|---|---|---|
| C01 模块协议 | 声明、版本、依赖、贡献契约 | `EndpointCatalog`（62 端点）+ `verifyAgainst` | **升级**为能力契约 |
| C02 宿主核心 | 注册、路由、卡片、配置命名空间、故障态、安全模式 | 86 个 Activity 硬编码入口、Maintenance/Repair 分散 | **轻量做**功能面注册表 |
| C03 能力授权核心 | 调用者身份、上限、策略、确认、代次、撤销、一次性、结果语义 | `CapabilityGate` 只有开关；桥只有 token | **重点做**授权总线 |
| C04 容器与 DSH 适配 | 实例身份、启停屏障、就绪/会话/任务事件、关联 ID | `EngineSupervisor` / `LinuxContainerRunner` / `GuestPaths` | 只补屏障与事件关联 |
| A01 存储 | 私有/SAF/共享存储的分级授权 | 分散在 `SensitiveAccess` / `DataExport` / 各 cap | 归并到「文件域」 |
| A02 网络 | 受控请求、下载、凭据代办、目的地校验 | `HttpDownloader` / `LanWebProxy` / `RemoteAccess` | 归并到「网络域」 |
| A03 设备执行通道 | Root/Shizuku/ADB 的可用性与白名单 | `Privilege` / `ShizukuHttpBridge` / `PermGrant` | 已有，补策略挂接 |
| A04 实体屏 | 观察、动作、任务身份、暂停接管 | `DshAccessibilityService` + `ScreenDoc` | **补 Lease** |
| A05 虚拟屏 | 后台独立屏 | 无 | **不做**（已移交另一 AI） |
| A06 浏览器引擎 | WebView/Gecko 双内核 | 系统 WebView + polyfill | 不做 Gecko |
| F01 关于与许可 | 卡片注册的低风险验证 | `AboutActivity` | 可作注册表首个样例 |
| F02 运行环境工作台 | 状态、维护、可解释错误 | `LinuxActivity` | 已有 |
| F03 终端 | PTY / 简易终端 | `TerminalActivity` + `Pty` | 已有 |
| F04 文件工作台 | 浏览、导入导出 | 分散入口 | 后置 |
| F05 数据保护 | 备份、恢复、迁移、保留 | `DataExport` + `BackupActivity` | **增量** |
| F06 DSH 插件管理 | 列表、配置、安装、结果语义 | `PluginCenter` / `PluginStore` | 已有 |
| F07 Root 模块管理 | 管理器识别与模块生命周期 | 无（`Privilege` 只做执行） | 不做 |
| F08 桌宠与对话窗口 | 窗口 owner 与任务身份 | `pet/` 已有 | 不做 |

**判断**：18 项里有 8 项我们已经具备（终端、插件、容器、执行通道、屏幕观察、备份基座、关于、桌宠），
真正的缺口是 **C03 授权、C01 契约升级、C02 入口注册表** 三件事。

---

## 2. 我们现状（已验证，2026-11-12）

- 结构：单 `:app` 模块；`app/dsh/mobile/` 下 86 个 Activity、`engine/` 82 个文件、`engine/cap/` 29 个文件，engine 合计 52,987 行。
- 已有单一真源：`EndpointCatalog` 62 条端点 + 启动时 `verifyAgainst` 与路由表双向核对 + MCP stdio 工具表由它派生（36 个）。
- 已有能力开关：`CapabilityGate`（开关 + 系统权限 + `linuxReady` 缓存），**无风险分级、无调用主体、无到期/一次性**。
- 已有三通道：Root / Shizuku / ADB（`Privilege` / `ShizukuHttpBridge` / `PermGrant`），通道选一次不重放。
- 已有备份基座：`DataExport`（zip + manifest.json + schemaVersion，导出 sessions/workspaces，刻意排除 profiles 与 node_modules）+ 合并式导入；**无自动计划、无范围选择、无校验保留**。
- 缺口：无架构门禁、无授权审计、无屏幕凭据、无统一结果语义、设置一级入口硬编码。

---

## 3. 借鉴与不借鉴

**借鉴四条原则**（来自 DSHA 讨论稿，判断为真问题）
1. 模块 ≠ APK ≠ Gradle 工程 ≠ 安全隔离；先定逻辑边界。
2. 能力层管机制与安全边界，功能层管场景；同一件事只能有一个权威状态。
3. 功能只消费稳定 API，拿不到万能桥凭据、raw shell、宿主内部控制器。
4. 派发前撤销可零派发；派发后不承诺撤回已发生的副作用。

**不借鉴**
- 18 个 Gradle 子工程 / 可安装可卸载模块 / 模块市场：我们功能是内置的，拆分只增加编译与维护成本。
- 独立「应急系统」重造：我们已有 `SelfCheck` / `RepairActions` / `ProfileGuardian`，先聚合成一个入口。
- 桌宠/对话窗口模块、Gecko、虚拟屏：不在本计划。

---

## 4. 目标架构：三横一纵

```
┌─────────────── UI Shell ───────────────┐
│ 86 Activities / 设置主页 / 引导页        │
└───────────────┬────────────────────────┘
                │ 只调功能面 API（不 new engine 内部类）
┌───────────────▼────────────────────────┐
│ FeatureRegistry（一级入口/配置命名空间/故障态）│
└───────────────┬────────────────────────┘
                │ 申请能力（声明式）
┌───────────────▼────────────────────────┐
│ Capability Broker（主体/策略/凭据/审计/结果） │
│  ├ Grant：scope + TTL + generation + 一次性 │
│  ├ Policy：AUTO / ASK / BLOCK（per 能力 per 包）│
│  └ Audit：JSONL（who/what/decision/result） │
└───────────────┬────────────────────────┘
                │ 派发前复核
┌───────────────▼────────────────────────┐
│ Cap Handlers（29 个 cap/*）+ 三执行通道    │
└───────────────┬────────────────────────┘
                │
        Android / Engine / Container
```

一纵：**屏幕操作链路**（`dumpScreenCompact → #n 编号 → Lease → tap → verify → 审计`）作为首个闭环。

### 4.1 运行期扩展：命令注册表（AI 自建脚本）

编译期模块之外，补一条**运行期模块**通道：AI 写一个 `py` / `mjs` / `sh` 脚本，
注册成一条可快速调用的命令（引擎 `bin/` shim、`/dev/command/*`、MCP 工具三面），
由宿主统一做声明、策略、审计与清理。详设见 [COMMAND_REGISTRY.md](COMMAND_REGISTRY.md)。

- 模块 = 声明 + 入口 + 能力申请 + 生命周期；脚本即最小模块，不需要 Gradle 工程。
- 安全按不可信代码对待：解释器白名单、目录内封、哈希校验、超时杀进程组、能力声明 + 用户策略、全量审计；默认不拿 root。
- 与扩展中心（工具链包）、插件中心（引擎插件）职责分开，互不替代。

---

## 5. 分阶段计划

### Phase 0 · 边界与门禁（1–2 天）

- 新增 `docs/ARCHITECTURE.md`：分层图、依赖方向、禁止项、例外登记。
- 新增 `scripts/probes/_test_architecture.py`：
  - `engine/**` 不得 import `app.dsh.mobile.*Activity`；
  - `engine/cap/**` 不得直接读写 SharedPreferences（应走 `CapabilityGate` / 参数）；
  - `*Activity` 不得直接 `new` engine 内部实现类（单例 facade 除外）。
- 产出越界清单（允许带注释的例外，逐条登记原因）。
- 验收：门禁在现有代码上跑出可解释的基线（不要求一次清零），CI/本地可执行。

### Phase 1 · 端点契约升级（3–5 天）

- `EndpointCatalog.Entry` 增加字段：`domain`（screen/files/net/exec/notify/sensor/media/personal/container/plugin）、`risk`（low/medium/high）、`idempotent`、`sideEffects`、`confirm`（auto/ask/block 默认值）、`audit`、`returns`。
- `verifyAgainst` 扩为四向：目录 ↔ 路由表 ↔ `assets/gates/*` ↔ MCP 工具表，任一缺失即启动告警。
- `/dev/help` 与 MCP 工具描述自动带上风险与确认策略。
- 验收：62 条端点全部带 domain/risk；四向零漂移；新增探针断言覆盖。

### Phase 2 · 授权总线 + ScreenLease（5–8 天）

- 新增 `engine/auth/`：`CapabilityDecl`、`Grant`、`Broker`、`Policy`、`AuditLog`。
- `AgentBridge` handler 统一走 `Broker.dispatch(endpointId, args, subject)`；结果统一为
  `DENIED / NOT_DISPATCHED / OK / FAILED / CANCELLED / UNKNOWN`。
- 主体三档：桥 token 会话 / 内置 MCP / App 内部；不同主体不同上限。
- **ScreenLease**：30s TTL、一次性、epoch、refresh 后二次身份确认（windowId/enabled/password/editable/clickable/scrollable/viewId/className/text/description 任一变化即失效）。
- **三档策略**：AUTO / ASK / BLOCK（per 能力 per 目标包）；敏感包默认 ASK；策略解码失败 fail-closed。
- **三通道确认**：通知 + 悬浮条 + Activity 并行，先点先生效；60s 超时记「未回答」而非拒绝。
- 审计：JSONL 落 `files/audit/`，只记元数据，不落正文与凭据。
- 验收（负向必红）：过期 lease 必拒；换窗口后的动作必拒；ASK 下无确认不执行；MCP 与 HTTP 同策略。

### Phase 2.5 · 命令注册表（4–6 天）

- P1：目录 + `command.json` + 注册/列表/删除 + `bin/` shim 生成。
- P2：运行端点 + 超时/进程组 + 哈希校验 + 审计。
- P3：接入授权总线（risk/策略/确认）+ MCP 工具 + `dev help` 分组。
- 验收（负向优先）：保留名拒绝、目录外拒绝、篡改拒绝、死循环被杀、ASK 下无确认不执行。
- 详见 [COMMAND_REGISTRY.md](COMMAND_REGISTRY.md)。

### Phase 3 · 功能面注册表（5 天）

- 新增 `FeatureRegistry`：id / 标题 / 图标 / 入口 Activity / 依赖能力 / 配置命名空间 / 故障态。
- 设置主页一级入口由注册表渲染（**只映射一级入口，二级页面内部不动**），支持排序与隐藏。
- 故障态聚合：`SelfCheck` + `RepairActions` + `MaintenanceActivity` 收成一个「系统状态」入口。
- 验收：一级入口与注册表一一对应；隐藏/排序不改代码；架构门禁禁止 UI 直连 engine 单例。

### Phase 4 · 数据保护增量（5 天）

- 在 `DataExport` / 导入上增加：自动备份（每日定时 / 引擎停止后）、保留 3 份、SHA-256 校验、范围选择（full/sessions/settings/plugins）。
- 格式升 v2：manifest 增 `scope`、`createdAt`、`sha256`、`tool`、`schemaMin`；**保持 v1 可导入**。
- 恢复：只读预检 → 范围确认 → 冲突展示 → 提交 → 回滚点。
- 验收：改过名的旧包也能恢复；中断后可回滚；导入后逐项核对会话/设置/插件清单。

### Phase 5 · 新手引导 9 项（3–5 天）

见第 6 节。

> **已落地（v1.7.10，2026-11-12）**：运行环境预取从「点完成后」提前到「引导页一出现」——
> `OnboardingActivity.startRuntimePrefetch()` 在后台调 `RuntimeInstaller.ensureInstalled()`，
> 完成页显示「正在解压 N% / 已就绪 / 未完成」。完整包直接解压；**noruntime 包不预取**，
> 仍由 MainActivity 在用户完成引导后弹下载确认。与引擎启动共用 `INSTALL_LOCK`，不会重复解压。

**工期**：约 5–6 周单人，可与发版节奏交错；Phase 1/2 是主干，Phase 3/4/5 可并行。

---

## 6. 新手引导改良（9 项）

现状：7 页（语言 → 方向 → 主题 → 强调色 → 屏幕 → 权限 → 完成），全部是外观设置，没有价值主张，也没有首聊入口。

| # | 问题（证据） | 改法 | 验收 |
|---|---|---|---|
| 1 | 首屏没有「这 App 能干什么」 | 新增能力页：复用 2026-11-11 预览 C「叠卡切换」（编译环境 / Linux 容器 / 手机操控），3 张卡 + 轻动效 | 5 秒内可说出 3 项能力 |
| 2 | 5 张语言卡同一枚地球图标、副标题与标题重复（English / English） | 每语言用自身文字做卡面标识；副标题改「界面语言，可随时切换」 | 截图逐卡不同 |
| 3 | 主题三卡同一枚半圆图标 | 浅色/深色/跟随分别用太阳/月亮/自动图标 | 三卡可区分 |
| 4 | 屏幕与布局页无预览（方向页/主题页都有） | 缩放页加实时预览文字块（90% 默认态） | 拖动即变 |
| 5 | 权限页把「权限模式」与「能力开关」混在一屏，首启易劝退 | 拆两屏：模式页（Normal/Shizuku/Root）+ 能力页（默认折叠，说明可后开） | 首屏不再出现开关墙 |
| 6 | 完成页下半屏全空、不回顾已选项 | 下半屏改为「你的选择」回顾（语言/主题/缩放/权限）+ 两个按钮：开始第一次对话 / 先看设置 | 完成页无空半屏 |
| 7 | 没有引擎状态与首聊入口（finish 直接进 MainActivity） | 完成页加状态块：runtime 就绪 / Web 端口 / 模型未配置（跳转说明） | 能一键到首聊 |
| 8 | 平板内容列顶部对齐、下方约七成留白 | 内容列垂直居中或顶部对齐 + 底部按钮固定 | 平板截图核对 |
| 9 | 完成页仍显示进度条与「全部步骤」 | 完成页收起进度条与步骤轨 | 完成页干净 |

**度量**：本地计数（不上传）「引导完成 → 首条成功消息」转化；目标 ≥ 80%。

**已落地项**：预取链路（见 5.5 注）。**待做**：第 1、2、3、4、5、6、7、8、9 项。

---

## 7. 度量与门禁

- 架构：3 条 import 门禁，越界数只降不升。
- 契约：端点四向一致零漂移。
- 授权：三类负向测试（过期/换窗口/无确认）必须红过再绿。
- 数据：改名旧包恢复、中断回滚、导入核对，各一条回归。
- 引导：9 项截图 + 首聊转化。

---

## 8. 风险

| 风险 | 影响 | 应对 |
|---|---|---|
| Broker 改造触及 62 端点 / 125 路由 | 回归面大 | 双路径开关 + 逐端点迁移 + 四向探针 |
| 默认 ASK 打扰老用户 | 体验倒退 | 默认沿用 AUTO，仅敏感包 ASK，设置页可切 |
| 设置页替换丢自定义逻辑 | 丢功能 | 只做一级入口映射，页面内部不动 |
| 与当前 64 个 WIP 文件冲突 | 覆盖他人改动 | 新文件为主，AgentBridge 改动集中在一个新文件，先隔离分支 |
| 备份格式变更 | 旧包不可读 | v2 只加字段，v1 仍可导入，双版本回归 |
| 情报依赖 | 决策偏差 | 只用于判断节奏与痛点，不复制代码与文档 |

---

## 9. 不做清单

- 18 个 Gradle 子工程 / 模块安装卸载 UI / 模块市场
- 虚拟屏、GeckoView、桌宠模块化、Root 模块管理
- 一键「换机迁移」跨产品导入（先做自己的 v1/v2 与裸 `.dsh` 目录，DSHA 包格式后置）
- 主题皮肤、图标包
