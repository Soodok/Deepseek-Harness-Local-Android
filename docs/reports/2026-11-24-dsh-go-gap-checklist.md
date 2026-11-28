# DSH Android 原生 Flutter 聊天 vs DSH Go / DSH Web UI 差距清单

> 日期：2026-11-24  
> 范围：**只读调研**。未改代码、未装依赖、未操作设备、未跑构建。  
> 目标：对比 `E:/tmp/wt-flutter-native-chat` 的 DSH Android 原生 Flutter 聊天与 DSH Go / DSH Web UI，产出“对方有我们没有 / 对方做得更好”的详尽清单，并给出可执行的超越建议。  
> 输出：本文件 `docs/reports/2026-11-24-dsh-go-gap-checklist.md`。

## 快速要点

| 优先级 | 差距 | 证据锚点 | 超越方向 |
|---|---|---|---|
| P0 | 长会话缺搜索/跳转/回底部，新消息感知弱 | Flutter `ConversationFlow` 仅 ListView.builder + 48 条分页，无 search/jump/cacheExtent：`flutter_ui/lib/src/ui/conversation_flow.dart:327-351`, `flutter_ui/lib/src/ui/message_list.dart:54`；Go 有 `session_search.dart` 本地+Host 内容搜索：`E:/tmp/dshgo-probe/app/lib/state/session_search.dart:1-260` | 做会话内搜索、命中高亮、跳转定位、回到底部/新消息浮标，性能预算纳入验收 |
| P0 | 断线恢复有自动重试，但用户可见恢复动作弱 | `chat_shell.dart:144-260,937-1016` 有指数退避与 banner；`turn_notice_card.dart:38-236` 有 nextStep 但无动作按钮 | 错误卡/断线 banner 动作化：重试、切运行环境、打开设置、复制诊断、恢复后发送草稿 |
| P0 | 工具/思考连续性与信息密度低于 Web/Go 叙事 | `execution_group.dart:28-47,50-86` 摘要粗；`tool_process_card.dart:66-221` reasoning/command/edit 分段；`conversation_flow.dart:491-508` reasoning+body+tools 分离 | 统一回合时间线：紧凑摘要、失败优先、同类聚合、思考/工具/正文时序锚点 |
| P1 | 草稿有持久化模型但缺状态可见性；队列能力基础 | `composer_draft.dart:1-54`, `session_drafts.dart:1-219`；`composer.dart:203-216,400-510` QueueTray；Go `draft_store.dart:1-122`、`chat_state.dart:26-80` | 草稿保存态、恢复提示、队列编辑/重发/排序、steer-queue-interrupt 语义显式化 |
| P1 | 设置/权限/运行环境覆盖主路径，但解释性与诊断深度不足 | `settings_page.dart:68-220`、`settings_detail_pages.dart:183-650`、`runtime_panel.dart:46-360`、`chat_controller.dart:67-177` | 三态能力模型（原生/只读/Web）、权限三层值、运行环境诊断抽屉、结构化失败原因 |
| P2 | 无障碍与动效是隐性短板 | 少量 Semantics/tooltip：`conversation_flow.dart:420,705`、`execution_group.dart:50-86`、`turn_notice_card.dart:67-116`；动效分散 | liveRegion、焦点管理、键盘遍历、减少动效全覆盖、统一 motion token |

## 证据范围与限制

| 来源 | 证据类型 | 说明 |
|---|---|---|
| Flutter 原生聊天 | 源码直接读取 | `E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/**`。行号基于 2026-11-24 本地工作树读取结果。 |
| DSH Web UI | 安装包 asar bundle 抽样 | 从 `C:/Users/Qt/AppData/Local/Programs/DeepSeek Harness/resources/app.asar` 解析并提取 `dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets/index-BgUyXZcc.js`（970770 bytes，SHA256 `c7e98216...`）。bundle 已 minified，只作为“能力/样式信号”，不写成确定源码实现。 |
| DSH Go | sparse checkout README + state | `E:/tmp/dshgo-probe` 仅含 `app/lib/state`、`app/lib/notify`、入口/配置等，无 `app/lib/ui` chat widget 源码；README 与 state 用于产品能力与状态模型证据。 |

> 约束遵守：本次未运行 `flutter`/`gradle`/`npm`/`adb`，未安装依赖，未操作设备，仅写本报告文件。

---

## 1. 消息与工具调用呈现密度与连续性

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 工具堆栈密度 | DSH Go README 表达“做完的过程折叠成一行”：`E:/tmp/dshgo-probe/README.md:93-101`。Web bundle 有 composer/dock 相关类信号（`composerStack`, `F07MqG_dock`, `DXuOLW_root`，asar bundle 抽样）。 | `ConversationFlow` 将 execution group 展开后逐条渲染 `ToolRunCard`，左侧 8px 缩进：`flutter_ui/lib/src/ui/conversation_flow.dart:208-252`。group 摘要为单行 TextButton：`flutter_ui/lib/src/ui/execution_group.dart:16-89`。 | 将连续工具改为“单容器 + 紧凑行 + 失败优先折叠”，默认只露关键对象与状态；展开态用 Divider/虚拟化行，不为每个工具重复卡片外框和大边距。 |
| 摘要语义 | Go/Web 更偏任务叙事与进度收束（Go README “过程折叠成一行”；Web 有 dock/execution 信号）。 | `_summary` 仅拼“已调用工具/已运行命令/已修改文件等”，失败/取消只加前缀：`flutter_ui/lib/src/ui/execution_group.dart:28-47`。 | 摘要升级为“阶段 + 对象 + 结果 + 可定位”：例如“3 命令 · 1 补丁 · 1 失败 pytest”，点击失败计数直接滚动到首个失败工具。 |
| 详情分层 | Web bundle 含 code block 行号/换行/copy/toolbar 信号：`data-line-numbers`, `data-code-wrap`, `md-code-block`（bundle 抽样）。Go 有折叠过程能力叙事。 | `ToolProcessCard` reasoning/command/edit 独立 `_ProcessSection`，每段独立展开：`flutter_ui/lib/src/ui/tool_process_card.dart:66-221`。命令 stdout/stderr 空时提示“命令没有输出”：`tool_process_card.dart:192-199`。 | 给命令/代码统一 Viewer：stdout/stderr/diff tab、复制、换行、搜索、仅看失败；默认折叠长输出，保留首尾关键行。 |
| 消息动作时机 | Go README 强调回复中可插话、完成后收束：`README.md:93-101`。 | 助手动作仅在 `settled && isFinalAnswer && !isStreaming && messageId 非空` 显示：`flutter_ui/lib/src/ui/conversation_flow.dart:509-537`。用户复制/撤回独立：`conversation_flow.dart:405-472`。 | 保持破坏性动作在 settled 后出现，但流式中提供“复制当前内容/停止后重试”轻量条，并在动作区出现时避免布局跳动。 |
| 结构化渲染 | Web bundle 明显具备更完整前端组件能力（minified，信号级证据）。 | `AssistantRichText` 支持 heading/quote/bullet/bold/code/table/chart：`flutter_ui/lib/src/ui/assistant_rich_text.dart:19-355`。conversation 对 JSON/code fence 做折叠预览：`conversation_flow.dart:564-720`。 | 为代码块加语言标签/复制/换行/行号；表格支持横向滚动+粘性首列；chart 增加空/异常态；富文本解析缓存按消息版本失效，避免流式全树重建。 |

## 2. 思考过程折叠策略

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 默认折叠与回看成本 | Go README 明确“做完的过程折叠成一行”：`README.md:93-101`。 | 消息 reasoning block 默认 `_expanded=false`，标签“正在思考/思考过程”：`conversation_flow.dart:491-507,669-753`。工具内 reasoning 另有 section：`tool_process_card.dart:114-134`。 | 统一 Reasoning 摘要组件：流式显示单行动态摘要，完成后折叠为“思考 N 段 · 用时 · 结论”；消息体与工具卡不重复同一份思考。 |
| 时序与归属 | Go state 有 inbox/slot/source kind 区分引擎注入与用户消息：`dshgo-probe/app/lib/state/chat_state.dart:26-140`。 | ConversationFlow 先渲染 reasoning，再 body，再 tool groups；unbound tools 仅在最新页尾追加：`conversation_flow.dart:491-508,189-195,312`。 | 引入按事件时间的回合时间线，渲染时可分组但保留真实顺序锚点；unbound tool 显示“归属未知/待同步”而非静默挂尾。 |
| 长思考可读性 | Web bundle 有代码块/折叠类样式信号，但具体 thinking 组件未在 minified 中定位到确定实现。 | `_MessageBlock` 展开后预览 12 行/1200 字符，超出进“查看完整内容”页：`conversation_flow.dart:730-800`。 | 原地支持“展开 20 行/展开全部/全屏查看/复制/跳到正文”；长思考使用等宽或阅读模式切换，并保留滚动位置。 |

## 3. 空状态与引导

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 首屏动作 | Go README 强调本机运行、遥控电脑、切 Web、分享回放等清晰路径：`README.md:105-170`。 | 空状态有品牌、小时问候、4 个 starter 卡、agent preset：`flutter_ui/lib/src/ui/message_list.dart:104-239`。starter 填充草稿：`chat_shell.dart:713` 传 `onStarter: _draft.append`。 | 将 starter 升级为“任务模板 + 前置条件”：选“无障碍操作/整理计划/编写代码”时显示所需权限、连接状态、预计输出形式。 |
| 空状态与系统状态联动 | Go 强调本机/远程连接切换（README）。 | 空状态不感知连接异常；异常主要在顶部 `_ConnectionBanner`：`chat_shell.dart:676-699,937-1016`。 | 空状态内嵌连接/运行环境卡：未连接时主 CTA 为“重试连接/打开运行环境/切 Web”；已连接显示当前 host/runtime/model。 |
| 恢复性引导 | Go 有 per-session draft 恢复、queue/steer：`draft_store.dart:1-122`, `chat_state.dart:26-80`。 | Flutter 有 `SessionDrafts` 和 agent preset，但空状态无“继续上次草稿/最近失败任务/最近会话”。 | 空状态增加“恢复未发送草稿”“重试上次失败回合”“继续最近会话”三入口，数据来自 `SessionDrafts`、turn notice、session list。 |

## 4. 设置与权限交互

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 设置 IA | Go `settings_state.dart` 覆盖 API key、主题、自动启动、auto-approve、phone storage、web layout、chat text scale、fold turns、visual replies、custom providers：`E:/tmp/dshgo-probe/app/lib/state/settings_state.dart:17-120`。 | Flutter 设置首页有搜索、账号、用量、分组入口：`flutter_ui/lib/src/ui/settings/settings_page.dart:68-220`。路由中部分项 Web fallback/unavailable：`flutter_ui/lib/src/ui/settings/settings_home.dart:24-112`。 | 建立能力矩阵：原生支持/只读镜像/Web 托底/需连接；入口直接标状态，避免“点进去才不可用”。 |
| 权限模型表达 | Go 有 session modes/preset 折叠：`session_modes.dart:1-60`；chat state 有 send mode/inbox slot。 | Flutter 区分 session permission、next-send draft permission、write-in-flight/error：`chat_controller.dart:67-177`。设置页有 session permission 行与 unavailable 解释：`settings_detail_pages.dart:585-650`。 | UI 明确展示三层值：Host 当前生效、下次发送、草稿选择；写入后显示回读时间与来源，失败给重试/打开 Web/复制诊断。 |
| 权限请求卡 | Web bundle 有 permission icon/approval 组件信号：`PermissionIcon*...`（bundle 抽样）。 | ApprovalCard 有批准/拒绝/停止；QuestionCard 支持单选/多选/自定义/草稿：`flutter_ui/lib/src/ui/action_cards.dart:17-360`。 | 审批卡增加“影响范围/可撤销性/建议选项”摘要；高风险动作要求二次确认并保留结构化审计信息。 |
| 设置搜索 | Go 有 session search 机制（虽偏会话）；Web 常见设置搜索。 | Flutter 设置搜索仅匹配 title/subtitle 小写包含：`settings_page.dart:153-211`。 | 加别名词典（sandbox/权限/审批）、最近使用、分组命中、深链高亮与空结果建议。 |

## 5. 运行环境与容器管理

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 运行模式切换 | Go README 强调本机/远程一键切换：`README.md:105-113`；connection state 有 local connector/network kicks：`connection_state.dart:107-220`。 | RuntimePanel 支持 native/linux/ssh_windows/ssh_linux 切换、pending/restart/feedback：`flutter_ui/lib/src/ui/runtime_panel.dart:46-360`。无宿主通道时回退外部页面。 | 切换前展示影响矩阵（会话保留、需重启、当前任务中断）；切换后轮询状态并给出可观测进度。 |
| SSH/容器深度 | Go 有 host/engine/runtime/provider 相关状态域；README 覆盖远程主机。 | Manual SSH 页支持连接、指纹校验、命令执行、断开：`flutter_ui/lib/src/ui/manual_ssh_page.dart:1-220`。RuntimePanel 仅入口级管理。 | 容器管理增加安装/启动/停止/日志/磁盘/修复；SSH 增加最近连接、指纹历史、认证方式、失败原因时间线、诊断包导出。 |
| 可观测性 | Go connection state 有 mux ready、provider key push/heal：`connection_state.dart:159-220`。 | 顶栏显示 host/runtime/status；连接 banner 展示 detail 与重试：`chat_shell.dart:771-935,937-1016`。 | 顶栏状态改为诊断抽屉：RPC 延迟、重连次数、最近错误码、引擎 phase、日志入口、复制诊断。 |

## 6. 连接与断线恢复

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 自动重连 | Go connection provider 监听 network kicks、connect timeout、ready 失败清理：`connection_state.dart:116-190`。 | ChatShell 监听 lifecycle，指数退避 `[1,2,4,8,16]s`，前台恢复立即重试：`chat_shell.dart:144-260`。HttpWsTransport 有 reconnect：`flutter_ui/lib/src/transport/http_ws_transport.dart:47-52`。 | 显示重连倒计时/原因分类/下一次尝试时间；后台恢复先轻量 health check，失败再全量 resync。 |
| 断线中可操作性 | Go chat state 明确 send/steer/queue/interrupt 语义：`chat_state.dart:26-120`。 | `canSend` 受配置错误/权限写入中/状态影响：`chat_controller.dart:205-210`。断线时 UI 有 banner，但草稿/队列恢复路径不显式。 | 离线态明确“可编辑草稿、可排队、不可立即发送”；恢复后提示“发送排队 N 条/保留草稿”。 |
| 失败提示 | Go 有 chat/engine error text 映射：`chat_error_text.dart:1-47`, `engine_error_text.dart:1-42`。 | TurnNoticeCard 结构化 title/message/nextStep/code/detail/copy：`turn_notice_card.dart:25-236`。ConnectionBanner 仅展示错误与重试：`chat_shell.dart:937-1016`。 | 统一错误分级与动作：可重试/需配置/需权限/需外部操作；banner 与 turn notice 共享错误码字典。 |
## 7. 长会话性能与虚拟滚动

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 列表策略 | Web 前端通常更易做虚拟窗口；Go UI 未暴露实现。 | `ConversationFlow` 用 `ListView.builder` + `findChildIndexCallback`，48 条 history page：`conversation_flow.dart:327-351`, `message_list.dart:54`。无 `cacheExtent`/itemExtent。 | 设定 `cacheExtent`、估算高度缓存、消息分段锚点；长代码/图片使用占位尺寸；300+ 轮切 sliver 分窗。 |
| 跳转与定位 | Go 有 session search/snippet/range：`session_search.dart:82-260`。 | Flutter 仅 pendingApproval 时 reverse tail/jumpTo 0：`conversation_flow.dart:76-106`。无回底部、无搜索命中跳转。 | 加“回到最新”“N 条新消息”“跳到上一个失败/审批/提问/工具变更”；跳转后高亮目标并保持滚动稳定。 |
| 重渲染隔离 | - | ChatShell 顶层 AnimatedBuilder 驱动大部分区域：`chat_shell.dart:614-760`。ConversationFlow 有 descriptor+message parse cache：`conversation_flow.dart:197-205,467-557`。 | 拆分 header/composer/transcript 的 listen 粒度；草稿输入与消息列表解耦；富文本块级 diff，避免整条消息重排。 |
| 性能证据 | 需要真实帧时间证据。 | 反馈台账 B07 记录 beta106 前台静置/滑动约单核 103%/123%，Flutter 单帧时间待验：`docs/reports/2026-11-22-beta92-feedback-ledger.md:33,125`。 | 建 30/100/300 轮基线：build/raster/jank/内存；把阈值写进 CI/profile 手册和发布门禁。 |

## 8. 输入区与草稿

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 草稿持久化 | Go `DraftStore` per-session 文本持久化、重启恢复、400ms flush、30 条上限：`dshgo-probe/app/lib/state/draft_store.dart:16-122`。 | Flutter `ComposerDraft` revision 防覆盖：`composer_draft.dart:1-54`；`SessionDrafts` 单写队列/flush/retry/unsaved：`session_drafts.dart:1-219`。UI 无保存状态。 | 显示“已保存/保存失败/恢复中”；异常退出后提示恢复；flush 在后台/切会话前可观测。 |
| 运行中输入语义 | Go 支持 now/steer/queue/interrupt，inbox slot nextStep/nextTurn：`chat_state.dart:26-80`。 | Flutter 有 QueueTray、stop/send slot、busy label：`composer.dart:203-216,400-510`, `composer_surface.dart:140-245`。 | UI 显式暴露“立即发送/排队/插话/停止后发送”；队列项可编辑、删除、重排、查看回执状态。 |
| 输入布局稳健性 | Web bundle 有 composer 相关结构信号。 | `ComposerViewport` 75% 高度上限、reverse scroll；`ComposerSurface` max 6 行；`FloatingConversationLayout` 测量 header/composer 预留 insets：`composer_surface.dart:9-75`, `floating_conversation_layout.dart:1-219`。 | 为键盘弹出、大字、窄屏、队列展开、长模型名建立布局快照回归；输入增长只影响 composer scroll，不影响消息锚点。 |
| 编辑/撤回 | Go 有 queue/updateQueue 语义；Flutter 有 retract/edit。 | `chat_shell.dart:303-450` 处理 retract/edit；`retract_dialog.dart`、`message_actions.dart`、`turn_plan.dart` 提供计划。 | 编辑态显示原文/差异/撤回文件计划；撤回后提供“恢复到草稿并定位输入框”。 |

## 9. 文件与图片

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 附件输入 | Web/Go 产品通常支持更完整附件工作流；Web bundle 信号有限。 | Composer 支持附件选择、5 文件/20MB、chip 删除：`composer.dart:145-177,240-320`, `chat_controller.dart:151-170`。 | 支持粘贴图片、拖拽文件、拍照/截图、上传前预览与压缩提示；附件失败可单项重试。 |
| 文件交付卡 | DSH Go README 有分享/回放能力叙事；Flutter 已有文件卡基础。 | `FileDeliveryCard` 从 Markdown 链接提取文件、提供预览/外部打开/分享：`file_delivery_card.dart:1-280`。`FilePreviewSheet` 支持图片/文本高亮：`file_preview_sheet.dart:1-167`。 | 增加 PDF/音视频/压缩包元信息预览、下载进度、hash/size 校验、失败重试；图片支持缩放旋转保存。 |
| 文件安全 | Flutter 通过 HostBridge/NativeFileDeliveryService 执行。 | `chat_shell.dart:560-612` 调用 preview/open/share；`tool_run_card.dart` 资源 open 走 HostBridge。 | 外部打开前显示 MIME/大小/来源会话；分享默认可脱敏文件名；预览失败明确区分权限、格式、解码、路径失效。 |

## 10. 搜索与跳转

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 会话内搜索 | Go `session_search.dart` 支持 fold term、UTF-16 安全 range、excerptAround、title+content hit 合并：`E:/tmp/dshgo-probe/app/lib/state/session_search.dart:1-260`。 | Flutter 无会话内搜索入口；ConversationFlow 无 query/highlight/jump API：`conversation_flow.dart` 全文无 search。 | 实现会话内搜索栏：消息/工具输出/文件名/错误码匹配，结果列表+上下文+高亮+上一条/下一条跳转。 |
| 跨会话搜索 | Go 同时支持本地标题与 Host 内容命中。 | SessionDrawer 只有刷新/新建/打开/引擎入口：`session_drawer.dart:24-260`，无搜索框。 | Drawer 顶部加搜索，支持标题、最近内容摘要、工作区过滤；空结果给清空过滤与建议。 |
| 跳转能力 | Web/Go 常见“回到底部/定位”。 | 仅 approval 自动跳尾：`conversation_flow.dart:76-106`；无回底部浮标。 | 加“回到最新”“跳到首个未读”“跳到上一个提问/失败/审批”；提供键盘快捷键与可发现提示。 |

## 11. 离线与错误提示

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 错误结构化 | Go `chat_error_text.dart`/`engine_error_text.dart` 将 DSH/HTTP/transport 错误映射为可读原因：`E:/tmp/dshgo-probe/app/lib/state/chat_error_text.dart:1-47`, `engine_error_text.dart:1-42`。 | TurnNoticeCard 展开显示 title/message/nextStep/code/detail/copy：`turn_notice_card.dart:25-236`。ConnectionBanner 仅展示错误与重试：`chat_shell.dart:937-1016`。 | 统一错误分级与动作：可重试/需配置/需权限/需外部操作；banner 与 turn notice 共享错误码字典。 |
| 离线恢复 | Go 连接层有 network kicks、ready timeout、local connector：`connection_state.dart:107-220`。 | ChatShell 自动重试，但无离线队列可视化、无恢复后“待发送”提示；`_linkBroken` 只影响 banner/重试：`chat_shell.dart:144-260,656-699`。 | 离线模式显式化：允许浏览/复制/草稿，禁用不可执行动作并说明原因；恢复后展示补发入口。 |
| 失败后行动 | Go 错误映射可指导用户。 | TurnNotice 有 nextStep 文本，但没有按钮：`turn_notice_card.dart:156-214`。 | nextStep 渲染为 action chips：重试、打开权限、打开运行环境、查看日志、复制详情；动作执行后回写结果。 |

## 12. 无障碍与动效手感

| 维度 | 对方有 / 做得更好 | 我们现状（Flutter 证据） | 如何超越 |
|---|---|---|---|
| 语义覆盖 | Web bundle 有 aria/tooltip/button 语义信号（minified 抽样）；Go Flutter app 有 l10n/状态文本体系。 | Flutter 仅局部 Semantics：用户气泡编辑态、执行组展开、turn notice 容器、SSH liveRegion：`conversation_flow.dart:420,705`, `execution_group.dart:50-86`, `turn_notice_card.dart:67-116`, `manual_ssh_page.dart:168`。 | 为流式新增、工具状态变化、错误、发送成功/失败补 liveRegion；所有 IconButton 提供 tooltip/semanticLabel；焦点在弹窗关闭后回到触发点。 |
| 减少动效 | Web/Flutter 平台都可支持 reduced motion，但需一致落地。 | 部分组件尊重 `disableAnimations/accessibleNavigation`：`execution_group.dart:59-65`, `composer_dock.dart:132-145`, `tool_process_card.dart:12-16`, `staggered_reveal.dart:67-101`, `conversation_edge_blur.dart:72-77`。 | 建立 motion token：统一时长/曲线/reduced 策略；减少动效下关闭位移、blur、stagger，仅保留颜色与文本状态。 |
| 命中与缩放 | Go 有 chat text scale：`settings_state.dart:32-33,84-90`。 | Flutter 有 `DshDisplayScope`、目标尺寸计算、部分 min target：`display_scale.dart:1-170`、`settings_detail_pages.dart:30-90`。但全局 48dp 审计未系统化。 | 做 48dp 命中审计与大字/窄屏/横屏回归；关键操作在 200% 文本缩放下不截断、不重叠、可聚焦。 |
| 动效手感 | Web 视觉层通常有更统一 CSS motion；Flutter 侧当前分散。 | 动效分散在 AnimatedRotation/AnimatedSwitcher/StaggeredReveal/LaunchFade/glass 等组件。 | 定义 120/180/260ms 三档节奏，发送/展开/错误/审批/抽屉统一曲线；触觉反馈只在可证明的业务成功点触发（已有 `interaction_haptics.dart`）。 |
---

## 额外观察（超出 12 类但影响体验）

| 维度 | 证据 | 如何超越 |
|---|---|---|
| 会话/工作区组织 | Flutter SessionDrawer 支持工作区分组、刷新、当前会话标记：`session_drawer.dart:153-360`。Go 有 session search/folderLabel：`session_search.dart:140-149`。 | 增加会话搜索、归档、置顶、最近活跃排序和工作区过滤，减少长列表滚动成本。 |
| 子代理体验 | Flutter 有 SubagentPanel/Chat page：`subagent_panel.dart:1-220`；Go state 有 subagent settled engine note 来源。 | 子代理卡片增加运行时长、token/步骤摘要、失败原因、快速跳转母会话上下文。 |
| 文件/代码查看深度 | FilePreviewSheet 只做图片和文本高亮：`file_preview_sheet.dart:1-167`；AssistantRichText 代码块不可复制/无行号：`assistant_rich_text.dart:47-120`。 | 统一代码/文件查看器：复制、行号、换行、搜索、diff、下载原文件。 |
| Web 能力信号 | bundle 中可见 reconnect UI、code-block line-numbers/wrap/copy、permission/workspace/ssh 相关 token（asar 抽样）。 | 将 Web 已有成熟交互抽象为移动端原生组件规范，避免“Web 有、原生缺”的功能漂移。 |

## 建议落地顺序

1. **P0 可用性底座**：会话内搜索+跳转+回底部；断线/错误动作化；长会话性能基线。
2. **P1 工作流效率**：工具/思考时间线重构；草稿/队列状态可见；权限三层值与诊断抽屉。
3. **P2 品质超越**：无障碍系统化、动效 token、文件查看器增强、空状态个性化。

## 验收清单（后续实现时执行，本轮未执行）

- [ ] 30/100/300 轮会话 profile：build/raster/jank/内存/滚动定位准确率。
- [ ] 弱网/断线/后台恢复：自动重连、手动重试、草稿保留、恢复后可继续发送。
- [ ] TalkBack：流式输出、工具完成、错误、审批出现有 liveRegion；焦点不丢。
- [ ] 360dp、390dp、平板横屏、大字 200%：composer、dock、文件卡、设置页无遮挡。
- [ ] 权限切换：UI 值、Host 回读值、下一次发送值一致或明确区分。

## 证据索引

### Flutter（`E:/tmp/wt-flutter-native-chat`）

| 文件 | 行号 | 证据点 |
|---|---:|---|
| `flutter_ui/lib/src/ui/chat_shell.dart` | 144-260,614-760,937-1016 | 自动重连退避、主布局、连接异常 banner |
| `flutter_ui/lib/src/ui/conversation_flow.dart` | 76-106,130-195,327-351,467-557,669-800 | 审批跳尾、分页/unbound、ListView.builder、解析缓存、思考折叠 |
| `flutter_ui/lib/src/ui/message_list.dart` | 54,104-239 | historyPageSize=48、空状态 starter/preset |
| `flutter_ui/lib/src/ui/composer.dart` | 145-177,203-216,240-320,400-510 | 附件限制、队列、附件 chip、QueueTray |
| `flutter_ui/lib/src/ui/composer_dock.dart` | 37-260 | question draft、plan/approval/question dock |
| `flutter_ui/lib/src/ui/composer_surface.dart` | 9-75,140-245 | viewport、高度约束、send/stop slot |
| `flutter_ui/lib/src/ui/tool_process_card.dart` | 66-221 | reasoning/command/edit 分段折叠 |
| `flutter_ui/lib/src/ui/execution_group.dart` | 28-47,50-86 | 摘要语义、展开语义 |
| `flutter_ui/lib/src/ui/turn_notice_card.dart` | 25-236 | 结构化错误、nextStep、复制详情 |
| `flutter_ui/lib/src/ui/action_cards.dart` | 17-360 | 审批/问答卡、draft、单选多选 |
| `flutter_ui/lib/src/ui/assistant_rich_text.dart` | 19-355 | Markdown/table/chart 轻量渲染 |
| `flutter_ui/lib/src/ui/file_delivery_card.dart` | 1-280 | Markdown 文件链接提取、预览/打开/分享 |
| `flutter_ui/lib/src/ui/file_preview_sheet.dart` | 1-167 | 图片/文本预览 |
| `flutter_ui/lib/src/ui/runtime_panel.dart` | 46-360 | 运行模式切换、pending restart、reason 映射 |
| `flutter_ui/lib/src/ui/manual_ssh_page.dart` | 1-220 | SSH 连接/指纹/命令 |
| `flutter_ui/lib/src/ui/settings/settings_page.dart` | 68-220 | 设置搜索与分组 |
| `flutter_ui/lib/src/ui/settings/settings_home.dart` | 24-112 | 设置路由与 Web fallback |
| `flutter_ui/lib/src/ui/settings/settings_detail_pages.dart` | 30-650 | 个性化、模型、provider、权限设置 |
| `flutter_ui/lib/src/ui/session_drawer.dart` | 24-260 | 会话/工作区列表、刷新、引擎入口 |
| `flutter_ui/lib/src/state/composer_draft.dart` | 1-54 | draft revision 防覆盖 |
| `flutter_ui/lib/src/state/session_drafts.dart` | 1-219 | 单写队列 flush/retry/unsaved |
| `flutter_ui/lib/src/state/chat_controller.dart` | 67-177,205-210,250-420 | 权限回读/写入、canSend、队列/stop |
| `flutter_ui/lib/src/state/chat_reducer.dart` | 1-160 | snapshot/needsResync/事件归约 |
| `flutter_ui/lib/src/transport/http_ws_transport.dart` | 47-52 | WS reconnect |
| `flutter_ui/lib/src/transport/dsh_rpc_connection.dart` | 1-220 | RPC 连接/超时/协议异常 |
| `flutter_ui/lib/src/theme/display_scale.dart` | 1-170 | 显示缩放与 48dp 目标计算 |
| `docs/reports/2026-11-22-beta92-feedback-ledger.md` | 20,33,118-125 | B07 长会话 CPU、B06 大字遮挡等历史证据 |

### DSH Go（`E:/tmp/dshgo-probe`，无 UI 源码暴露）

| 文件 | 证据点 |
|---|---|
| `README.md:1-220` | 本机/远程、steer/queue/interrupt、过程折叠、原生/Web 切换、分享回放、横屏平板、主题、语言、手机操控 |
| `README.en.md:1-220` | 同能力英文版，交叉确认 |
| `app/lib/state/chat_state.dart:26-140` | SendMode、InboxSlot、PendingPrompt、engineNoteSources、queue/steer/interrupt 语义 |
| `app/lib/state/draft_store.dart:16-122` | per-session draft、重启恢复、400ms flush、30 条限制 |
| `app/lib/state/session_modes.dart:1-60` | preset/plan mode 从 log 投影折叠 |
| `app/lib/state/settings_state.dart:17-120` | 设置域、provider keys、chat text scale、visual replies、custom providers |
| `app/lib/state/session_search.dart:1-260` | foldForSearch、matchRanges、excerptAround、title+Host content hit 合并 |
| `app/lib/state/connection_state.dart:107-220` | local connector、network kicks、mux ready、provider key push/heal |
| `app/lib/state/chat_error_text.dart:1-47` | chat 错误原因映射 |
| `app/lib/state/engine_error_text.dart:1-42` | engine unreachable/auth/service unavailable 映射 |

### DSH Web UI（asar bundle 抽样）

| 证据 | 类型 | 说明 |
|---|---|---|
| `C:/Users/Qt/AppData/Local/Programs/DeepSeek Harness/resources/app.asar` -> `dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets/index-BgUyXZcc.js` | bundle 文件 | 970770 bytes，SHA256 `c7e9821660f7e0f0524cc7cb1a375a5171eebbe68e167b80d290bd2ea391286d` |
| `composerStack`, `F07MqG_dock`, `DXuOLW_root` | 字符串信号 | composer/dock 布局存在 |
| `data-line-numbers`, `data-code-wrap`, `copyLabel/copiedLabel`, `md-code-block` | 字符串信号 | 代码块具备行号/换行/复制能力信号 |
| `reconnectActionLabel`, `restartActionLabel`, `onReconnect` | 字符串信号 | 连接恢复 UI 有动作标签信号 |
| `PermissionIcon*`, `workspace`, `ssh`, `container` | 字符串信号 | 权限/工作区/运行环境相关 UI 资产存在 |

## 备注

- 本报告只覆盖“可证据化”的差距；未在本轮运行真实 GUI，因此不对动画帧率、触控手感、TalkBack 实播做实测结论。
- DSH Go probe 无 `app/lib/ui`，凡 UI 细节均标注为 README/状态能力证据。
- DSH Web bundle 已 minified，凡无法确认行为处仅记为“信号”，不当作实现细节。