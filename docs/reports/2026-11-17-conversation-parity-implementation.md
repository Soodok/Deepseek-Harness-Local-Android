# 会话平权实现说明（2026-11-17/18，beta.37 → beta.50）

范围：E:/tmp/wt-flutter-native-chat，分支 feat/flutter-native-chat，基线 556ef615ac5162347ea205fd602301622d99d6f1。未 push、未 merge、未发布；主仓库与他人工作树未写。

## 1. 目标与边界

把 DSH Android 应用的会话入口与消息动作对齐到「像 Claude 那样」的体验，且 Web（应用内 WebView，127.0.0.1:3080）与 Flutter 原生两端共用同一份 DSH 权威状态：

- 删掉独立会话选择页作为普通入口；侧边抽屉直接列出真实 DSH 会话，只显示人类可读标题，不显示 session 标识与 cwd，也不需要用户填工作目录。
- 灵动岛「切换原生对话」自动承接 Web 当前会话与进行中状态；反向也对齐当前会话。
- 用户气泡：复制、撤回；撤回可选只改文本或同时回滚该轮文件。
- 点自己气泡进入编辑：文本进输入框、原草稿暂存、可取消恢复；发送时同样可选是否回滚文件。
- 助手回答完成：点赞、踩、复制、重新输出（默认回滚该轮文件后重做）、分享（本地预览 + 系统分享 + 保存到文件）。

不在范围内：宿主安装、全局 profile、在线模型凭据、他人设备/进程、任何 push/发布动作。

## 2. 关键设计取舍

| 决策点 | 采用方案 | 放弃的方案与原因 |
| --- | --- | --- |
| 撤回/编辑/重新输出的后端 | 内置 DSH 插件 `turn-rewind`，新增宿主 Remote `turnRewind/list` 与 `turnRewind/rewind`，与 DSH 自有 `messageFeedback/*` 并列 | 自建 HTTP 服务或直接改写会话日志：前者要另起端口与鉴权、与宿主状态不一致；后者会破坏会话日志的唯一权威性，也无法留下可审计记录 |
| 撤回的历史处理 | fork 子会话（`sessionController.fork({sessionId, atSeq})`），原会话逐字保留 | 直接截断会话日志：不可逆、无法审计、也违背「不物理删除唯一历史」 |
| 文件回滚 | 每回合落 manifest（`turns/<turn>.json`，记录每文件 before/after 的 blob 与 size）、按内容哈希检测冲突、逐文件恢复 | 全量目录快照：空间与耗时都不可控；仅记录文本 diff：覆盖不了二进制与删除 |
| 幂等 | 每个 `requestId` 的结论写 `rewinds/<requestId>.json`，重放直接返回原结论；同会话 rewind 用 Promise 链串行 | 无幂等：晚到重试会重复 fork、重复回滚 |
| 冲突 | `detectConflicts` 逐文件比对当前内容哈希与记录的 after 值，不一致就返回 `status:"conflict"` + 路径清单，绝不覆盖 | 静默覆盖：会吃掉用户回合后的手工修改 |
| 回答身份定位 | 从 rc.2 真实形状 `data.finalNode` 读 `messageId`/`seq`/`turn`，回落到 `data` 顶层 | 只读 `data.messageId`：实测 rc.2 的 assistant-step 节点没有这两个键，分享与重新输出都会定位失败 |
| 系统分享 | 新增 `ShareBridge`（`DshAndroidShare`）走 `ACTION_SEND` + FileProvider | 只判 `navigator.share`：Android WebView 根本没有这个 API，按钮必然是死的 |
| 回合表读取时机 | 每次点击确认前向 Host 现查（`planForFresh`），读不到就不弹框并如实提示 | 只在页面绑定时读一次：页面打开后才结束的回合不在表里，确认框会谎报「没有改动文件」 |
| 撤回的执行顺序 | **先 fork 会话，成功之后才还原文件**（`core.rewindForkFirst`）；冲突预检放在 fork 之前，因为它只读磁盘 | 先还原文件再 fork：fork 一失败，文件已被改回去而会话没分叉，界面报失败却已造成不可见改动；重试时自己写回去的内容还会被冲突检测当成「回合之后被外部改过」，撤回永远卡在冲突 |

## 3. 落地实现

### 3.1 内置 DSH 插件 `app/src/main/assets/android-plugins/turn-rewind/`

- `core.js`：快照与恢复的纯逻辑——`BlobStore`（内容寻址、配额与清理）、`captureBaseline`、`diffTurn`、`planRewind`、`detectConflicts`、`restoreFile`、`readManifests`/`writeManifest`、`referencedBlobs`。路径 canonical 化并拒绝越界与 symlink。
- `index.js`：宿主插件。`inject = ["sessions","fs","sessionPersistence"]`；挂 `TurnRewindService`，通过 `@deepseek-ai/dsh-typert-protocol/remote-methods` 描述符暴露 `list` 与 `rewind`。存在性判断走 `sessionPersistence.stat()` 而非 `sessions.get()`（后者只返回当前进程活着的会话）。只处理 `origin!==subagent` 且 `delegationDepth===0` 的会话。
- `client.js`：Web 客户端扩展。在 rc.2 页面里给用户气泡加复制/撤回、给助手气泡加点赞/踩/复制/重新输出/分享，并渲染编辑横条与分享预览。主题色用 rc.2 真实存在的 `--dsw-alias-*` 令牌。
- `package.json`：`dsh.client.platform = "web"`，`exports` 暴露 `./client`；与既有 `chat-perf`、`resume-reconnect` 同一部署形状。

### 3.2 Android 侧接线

- `engine/EngineConfig.kt`：目录插件清单追加 `"turn-rewind" to listOf("package.json","index.js","client.js","core.js")`，overlay 的 insert 段追加 `name: ./android-plugins/turn-rewind/index.js`。
- `engine/ShareBridge.kt`（新增）：`@JavascriptInterface fun shareText(text,title,fileName)`，写 `cacheDir/shared/<清洗名>-yyyyMMdd-HHmmss.txt`，`FileProvider.getUriForFile`，`ACTION_SEND` + `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION`，成功返回 `"ok"`。
- `MainActivity.kt`：`webView.addJavascriptInterface(ShareBridge(applicationContext), "DshAndroidShare")`，紧跟 `DshAndroidOpen` 之后。

### 3.3 Flutter 侧

- `state/conversation_actions.dart`：`ConversationActions` 抽象（`turnChanges` / `rewind` / `openFork` / `ratings` / `rate` / `share`）+ `StaticConversationActions`（测试与预览用，含 `failTurnChanges` 开关）。
- `state/message_actions.dart`：动作编排层。`refresh()` 读一次回合表与评分；`planForFresh()` 在每次确认前向 Host 现查；`retract` / `beginEdit` / `cancelEdit` / `submitEdit` / `regenerate` / `rate` / `copy` / `share` 全部带 `_disposed` 保护与迟到回包判空。
- `state/turn_plan.dart`：`TurnPlan`、`planForSeq`、`startSeqOfTurn`、`describeChanges`。撤回与重新输出共用同一套「从本轮起算」的口径。
- `transport/native_action_transport.dart`：`TurnChangeSummary` / `RewindOutcome` 解析与 `NativeMessageActions`（真实 RPC：`turnRewind/list|rewind`、`messageFeedback/put|delete|list`）。`RewindOutcome.parse` 的冲突路径同时兼容 `files.conflicts` 与顶层 `conflicts`。
- `ui/chat_shell.dart`：`_freshPlan()` 统一「先问 Host 再弹框」；`_confirmRetract` / `_confirmEdit` / `_regenerate` 三个入口都走它。
- `ui/retract_dialog.dart`：`useRootNavigator: false`，对话框跟着提出问题的那一页走，页面被替换时不会悬挂。
- `ui/share_preview.dart`：本地预览 + 复制 + 保存到文件 + 系统分享。
- `bridge/engine_sessions_native.dart`：抽屉数据来自真实引擎会话；`_EngineHomeContentState` 持有共享 `ComposerDraft`，fork 切页后草稿不丢。

## 4. 版本与产物

| 版本 | code | 主要变化 |
| --- | --- | --- |
| 1.6.0-beta.37 | 412 | 抽屉真实标题、无 picker、原生动作行骨架 |
| 1.6.0-beta.42 | 417 | 双端撤回首次真正成功 fork |
| 1.6.0-beta.44 | 419 | 原生端撤回/编辑/复制通过；修 dispose 后动作与 fork 丢草稿 |
| 1.6.0-beta.45 | 420 | Web 编辑态挂到 fork 会话、取消编辑还草稿 |
| 1.6.0-beta.46 | 421 | 编辑横条浮到输入卡片上方；改 rc.2 真实主题令牌 |
| 1.6.0-beta.47 | 422 | 回答身份改读 `data.finalNode` |
| 1.6.0-beta.48 | 423 | 新增 `ShareBridge`（`DshAndroidShare`） |
| 1.6.0-beta.49 | 424 | 原生端回合表改为点前现查 |
| 1.6.0-beta.50 | 425 | 冲突响应兼容顶层 `conflicts` |
| 1.6.0-beta.51 | 426 | 撤回改为先 fork 后还原文件（fork 失败不再产生不可见改动） |

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，248169662 B，SHA-256 `4ecec00f2d75fcdc1703491076684ba34fa16c25f3fd74c05b4749aecd4e4f35`，versionCode 426 / versionName 1.6.0-beta.51，ABI 仅 x86_64。

## 5. 参考与许可

参考了以下公开仓库的接口形状与快照机制（只借鉴思路与契约，未复制代码；均按 GitHub API 取 metadata 与文件树核对）：

- limbo947/dsh-recall-plugin —— 撤回类插件的用户流程与确认语义。
- extracurricular-ai/dsh-filesnap —— 按回合落文件快照、内容寻址存储的思路。
- fl0-0wer/dsh-retrace-fl0wer、JJXjustin/dsh-session-rewind、JochenYang/dsh-rewind-plugin —— 会话回退与 fork 的边界处理。
- EmptyVessel-000/dsh-file-history —— 文件历史与恢复前的冲突检测。

DSH 权威契约来自本地只读运行时 `E:/tmp/rc2_asar/dsh/node_modules/@deepseek-ai/`（0.2.2-rc.2）：`dsh-message-feedback`（feedback item 含 `messageId`/`rating`/`version`/`createdAt`/`updatedAt`）、`dsh-session/surface`（`finalNode` 形状）、`dsh-typert-protocol`（Remote 描述符）。

## 6. 未覆盖

- 文件改名（rename）与二进制文件的回滚：只有离线单测，未在设备上造真实样例。
- Web / 原生的切换动效：只做目视与时序判断，未录逐帧视频。
- ARM 真机、TalkBack、硬件键盘遍历未验。
