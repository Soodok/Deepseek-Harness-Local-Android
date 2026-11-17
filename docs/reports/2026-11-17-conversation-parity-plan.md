# 会话对齐（Conversation Parity）设计与实施计划

日期：2026-11-17 · 工作树：E:/tmp/wt-flutter-native-chat · 基线 HEAD 556ef615
授权来源：Lead 派发（2026-11-17T16:50:11，见 AI_CONTEXT.md 顶部「本轮任务」）。

## 1. 目标（可验收口径）

| # | 能力 | Web（应用内 DSH WebView） | Flutter 原生 |
|---|------|--------------------------|--------------|
| 1 | 会话入口 | —— | 删除独立选择页，抽屉直接列真实会话（仅人类可读标题）；无当前会话时直接空会话可输入，不填 cwd |
| 2 | 双向对齐 | 从原生切回时对齐当前会话 | 承接 Web 当前会话（同 sessionId、同历史），不经选择页 |
| 3 | 用户气泡 | 复制 / 撤回（文本 or 文本+文件回滚） | 同左 |
| 4 | 点气泡编辑 | 文本回填、草稿暂存、取消恢复 | 同左 |
| 5 | AI 回答后动作 | 赞 / 踩（DSH 权威反馈）、复制、重新输出、分享 | 同左 |
| 6 | 状态一致 | 两端读写同一会话与同一插件状态 | 同左 |

## 2. 关键约束（来自真实代码，已核实）

- rc.2 **没有** rewind/undo 包；`session` 的 surface 只有 append / replace{startSeq,endSeq}，**人类 transcript 取 append 来源**，replacement 只给模型 → **禁止截断日志**，撤回必须走 `session/fork(atSeq)` 保留分支。
- 运行时 **不带 git**（runtime zip 的 bin/ 里没有 git），因此文件快照**必须自建文件级**实现，不能复刻 dsh-workspace-changes 的 snapshotTree。
- `workspace/changes` 的 summary/diff 只活在会话生命周期内且**不持久化内容**，无法作为恢复依据 → 自建 `$DSH_HOME/turn-rewind/` 快照目录。
- Remote 端点方法参数名从函数源码解析，必须是单一合法标识符（不能解构/默认值/rest）；网关按位置传参，客户端 payload 用形参名。
- 无装饰器环境下可在原型上直接写 `'@deepseek-ai/dsh-typert-protocol/remote-methods'` 描述符（`{version:1, methods:[{method, invocation:{kind:'direct'}}]}`），等价于 `@Remote(name)`。
- 插件必须落在 `assets/android-plugins/<pkg>/`（package.json + index.js + client.js），并在 `EngineConfig.kt` 的部署列表与 overlay `insert` 段各加一条；裸包名在 engine 目录解析失败，须按绝对路径动态 import。

## 3. 方案取舍

### 3.1 撤回/回滚的落点
- 方案 A（选中）：**宿主插件** `turn-rewind` 提供权威实现，Web 与原生都调用同一 Remote 契约（`turnRewind/list`、`turnRewind/rewind`）。撤回 = `session/fork(atSeq=目标 user/message.seq-1)` 得到子会话 + 按记录逆向还原文件 + 把恢复记录写成可审计 JSON。原会话日志一字不改，随时可回到原分支。
- 方案 B（否决）：用 `surface replace` 遮蔽旧消息——只影响模型可见面，人类 transcript 仍显示旧内容，且会破坏"看到的历史不消失"。
- 方案 C（否决）：直接改写/截断会话日志——不可恢复，且违反授权。

### 3.2 文件快照粒度
- 在 `turn/start` 记录基线（用 fs 的 `stat`+内容读取），在 `turn/end` 对比。对 `fs/write-intent`/`fs/edit-intent`/`fs/delete-intent` 加 `{prepend:true}` 钩子，把"首次改动前"的内容抓下来（rename 不发 intent，靠 turn 前后对比补齐）。
- 快照落盘：`$DSH_HOME/turn-rewind/<sessionId>/turns/<turn>/manifest.json` + `blobs/<sha256>`。二进制按 `readBytes` 存 blob；超过 `maxFileBytes` 的文件记为 `oversized`，恢复时如实报"该文件无快照"。
- 配额：单会话默认 64 MiB / 500 文件，超限只记 manifest 不存内容并标记 `truncated`。

### 3.3 两端一致性
- 会话身份：Web 侧权威是 `uiWorkspace` 的 `selection`（持久名 `dsh.sessions.current`）；原生侧权威是 `session/list` + 显式承接的 sessionId。切换通过受限桥传 sessionId（不解析 DOM 文本）。
- 反馈：直接复用 `messageFeedback/list|put|delete`（乐观锁 ifVersion），与官方 UI 同一后端。

## 4. 实施步骤

1. 插件 `app/src/main/assets/android-plugins/turn-rewind/`：snapshot 记录 + `list` + `rewind`（含冲突检测、幂等 requestId、并发锁）。
2. 插件接线：`EngineConfig.kt` 部署列表 + overlay insert。
3. 插件单测（node，`docs/reports/.../plugin-test`）：临时真实文件（新建/修改/删除/二进制/冲突/重复请求/并发）。
4. Flutter：`engine_session.dart` 解析 title；`session_drawer.dart` 列真实会话；删除 `engine_session_picker.dart` 与 cwd 表单；空会话直接可输入（用宿主已选工作区）。
5. Flutter：用户气泡复制/撤回、点气泡编辑（草稿暂存/恢复/模糊背景）、AI 五图标、分享预览。
6. Web 客户端插件 `turn-rewind` client：替换 user 节点渲染加图标、assistant-actions 加 5 项、编辑态与撤回确认。
7. 构建 beta37/code412（x86_64），安装 emulator-5570，按清单实机验收。
8. 文档：plan/implementation/validation/handoff + status.json。

## 5. 自检

- 失败测试先行：插件侧先写能暴露"无快照/冲突/重复请求"缺陷的用例；Flutter 侧先写抽屉只显示标题、撤回后原文回填、编辑取消恢复的失败用例。
- 不做假绿：不 mock Remote 结果冒充真机；设备证据绑定 APK hash/版本/ABI。
- 未覆盖即如实写入 handoff（在线付费模型、ARM 真机、TalkBack 等）。
