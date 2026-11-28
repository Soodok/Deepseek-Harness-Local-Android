# beta.78 Flutter 独立核查

审查对象：`7d9b26dfd42391fbed9c776a21c3f97f97a67b05`，基线 `c93e187f`。结论绑定该提交。未预设既有交接中的触控、玻璃或脱敏结论成立。

结论：analyze 退出 0，原有完整测试 **434 通过 / 0 失败**。独立需求探针 **5 通过 / 2 失败**；另补充无障碍与子会话探针 **3 通过 / 0 失败**。仍有两项明确需求缺口：notice 载荷更新不重置展开态；正文视口没有延伸到 Composer 后方。按钮视觉尺寸、三档触控地板、正确回调与普通/降级材质在本次离线覆盖内成立。另需更正既有交接中以错误控件回调佐证触控达标的论证。

## 取证与执行边界

- 先读 [AI_CONTEXT.md:1](<E:/tmp/wt-flutter-native-chat/AI_CONTEXT.md:1>) 的前 20 行及 [floating-composer-handoff](<E:/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md:1>)。原始共享树 HEAD 与固定提交相同；`git diff --quiet 7d9b26df -- flutter_ui/lib flutter_ui/test` 退出 0。
- 为避免 Flutter 生成文件写入共享树，使用 `git archive 7d9b26df flutter_ui` 导出到 `E:/tmp/sol-flutter-review-20261118/flutter_ui`。仅复制既有 package_config/package_graph，使用 `--no-pub`，没有拉取依赖。包自身 rootUri 为 `../`，解析到外部副本；依赖 material_ui 1.11.0。
- 开始验证及中途核对时，源码/原有测试副本与共享树 `git diff --no-index --quiet` 均退出 0。本代理仅新增本报告；其余写入仅在授权临时目录。没有改实现或原有测试，没有 git 写操作、Gradle、设备、凭据、模型请求或再次委派。收尾时 tracked lib/test 仍与固定提交无差异；并行流程新增未跟踪的 [conversation_edge_blur_test.dart](<E:/tmp/wt-flutter-native-chat/flutter_ui/test/conversation_edge_blur_test.dart>)，使整目录 no-index 返回 1，差异仅为该新增文件。本轮固定快照不包含它，434 通过数不覆盖这个新测试。
- 工具链：`E:/tools/flutter/bin/flutter.bat`，Flutter **3.50.0**（framework `ab2912035e160d9cad6306df307576da6128ebdb`），Dart **3.14.0**。未假定 PATH 中存在 dart。
- 证据分级：A＝本轮真实 Flutter widget/RenderBox/回调/语义实测；B＝固定源码与基线差异静态确认；C＝既有交接的外部设备描述，本轮未重验。

## F1 · P2：notice 载荷变更后仍保持旧展开态

**位置**：[turn_notice_card.dart:19](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/turn_notice_card.dart:19>) 的 `_expanded` 与 [conversation_flow.dart:207](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/conversation_flow.dart:207>) 的无 key 子卡。

**触发**：显示同一个 message id 的 failed/OLD notice，点击展开，再通过相同 ConversationFlow 行发布 interrupted/NEW notice。新的载荷仍显示恢复建议、代码、复制按钮，没有回到默认单行。

**影响**：组件 State 复用时，新错误内容未经本轮用户展开就直接摊开，违背“新载荷默认折叠”的要求。复制读取的是当前载荷，未发现复制旧内容；缺陷是展开状态归属错误。

**证据 A+B**：[probe-final.log:34](<E:/tmp/sol-flutter-review-20261118/probe-final.log:34>) 打印 `NOTICE updated payload copyCount=1`；期待 `findsNothing`，实际仍存在一个 `turn-notice-copy`。组件没有 didUpdateWidget，也没有通过 notice 值重建 State。[TurnNotice:67](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/model/turn_notice.dart:67>) 已实现值相等，可用于判断真正载荷变化。

**最小修法**：在 State.didUpdateWidget 中比较 `oldWidget.notice != widget.notice`，变化时设 `_expanded = false`；新轮身份应保留 message id key，必要时由调用方给卡片 `ValueKey((message.id, notice))`。不要按 notice 对象引用变化重置，否则同值重建也会意外收起。补三种断言：同值重建保持、载荷变化折叠、不同 turn row 折叠。

**基线归属**：基线已有 `_expanded`、没有更新复位；本轮把整个卡片改为折叠披露后仍未满足这条新验收要求，不将它宣称为新引入的数据或安全回归。补充探针确认同值重建保持展开、不同 message id 的新轮正常折叠，见 [notice-accessibility-final.log:2](<E:/tmp/sol-flutter-review-20261118/notice-accessibility-final.log:2>)。

## F2 · P2：主、子正文没有位于输入玻璃后方

**位置**：[chat_shell.dart:384](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/chat_shell.dart:384>) 的 `Column(Expanded(MessageList), Composer)`；[subagent_panel.dart:511](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/subagent_panel.dart:511>) 的相同上下分区布局。正文 [ListView:169](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/conversation_flow.dart:169>) 在自身视口内滚动。

**触发**：正常主会话或可继续的子会话；432×900dp、standard、实际 ChatShell/SubagentChatPage 与本地 fixture。

**影响**：Composer 的 BackdropFilter 是真的，但正文的可绘制视口在输入区之前结束。主会话只能采样 DshBackdrop 的渐变/光晕，子会话输入区后方是 Scaffold 底色；不能实现明确要求的“正文实际在其后可采样”。这不等于否认材质实现，也不是以审美偏好要求另一套配色。

**证据 A+B**：[probe-final.log:69](<E:/tmp/sol-flutter-review-20261118/probe-final.log:69>) 的真实全局矩形如下；探针对两者的相交断言均失败：

| 入口 | 正文视口 dp | ComposerSurface dp | 是否相交 |
| --- | --- | --- | --- |
| 主会话 | `(0,78)–(432,764)` | `(16,778)–(416,888)` | 否，间隔 14dp |
| 子会话 | `(0,64)–(432,770)` | `(16,778)–(416,888)` | 否，间隔 8dp |

**最小修法**：将会话内容区改为 Stack：正文视口铺满，输入区 Align/Positioned 到底部；根据实际 Composer 高度给列表末尾留可滚达的底部 padding，避免最后一条被遮住。主、子入口共用同一布局策略，并验证多行输入、附件/队列 tray、IME、安全区及正文滚入玻璃下方时的 hit test。保留输入区局部 ClipRRect/BackdropFilter。

**基线归属**：上述两个布局文件本轮未变，属于新玻璃要求未补齐的原有结构；不是 beta.78 新引入的正文裁切。

## F3 · P2（证据更正）：既有触控论证使用了错误目标行为

**位置**：[handoff:51](<E:/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md:51>)、[handoff:107](<E:/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md:107>)、[handoff:119](<E:/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md:119>) 与 [AI_CONTEXT:7](<E:/tmp/wt-flutter-native-chat/AI_CONTEXT.md:7>)。

**问题/影响**：“点附件外却聚焦输入”“点模型空白却打开权限”只说明某个目标响应，不能证明附件/模型自己的有效目标扩展；仅 painted rect≥48 也不能单独证明命中与语义框正确。用这些现象推导权威触控结论，会让后续验收漏掉错控件或重叠目标。设备 uiautomator 的小框差异本轮没有权限重验，不能据此一概断言是 Flutter 对绘制内容裁剪。

**证据 B+C**：既有描述的回调与所声称目标不一致。**本轮 A 证据支持当前 Flutter 代码的触控达标**：四个控件的各 9 点网格命中均归属自身，painted/实际可点击语义节点一致，相邻框不重叠；外圈附件真正调用 picker 一次且输入未聚焦，模型打开“选择模型”，权限打开权限选项，发送提交 fixture 一次，停止结束 fixture。

**修法**：交接中分别写 painted geometry、实际 hit-test、正确回调、可点击语义节点；用本报告可复现探针替换旧点击论证。保留 Android/TalkBack 桥接边界为未验证，不把此次离线结果外推为真机结论。

## 已确认项与自检

| 项目 | 结论与证据 |
| --- | --- |
| 发送/停止视觉 | [ComposerActionButton:177](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/composer_surface.dart:177>) / [203](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/composer_surface.dart:203>)：36dp 圆面、20dp 图标，单 action slot；加载中的 stop 显示 18dp spinner。为设计逻辑尺寸，compact 视觉随画布变为 32.4dp。B，回调实测 A。 |
| 权限视觉 | [composer.dart:290](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/composer.dart:290>)：34dp 圆面、DSH glyph 18dp；未知权限用 help 图标 18dp。附件 34dp 圆面/20dp 图标。B。 |
| compact 触控与语义 | 432dp 宽时 attach/permission/send **48×48dp**；model **123.6×48dp**。目标矩形不重叠，4×9 个采样点命中自身。A。 |
| standard / relaxed | 圆按钮 **48×48 / 52.8×52.8dp**；模型 **137.3×48 / 151.1×52.8dp**。A。 |
| 窄屏大字输入控件 | 320dp 宽、2× 系统字号、compact：三圆按钮 48×48，模型约118.4×48；相邻框不重叠，正确外圈回调、无 overflow。A。 |
| 主/子输入一致 | 两者复用 ComposerSurface / ComposerActionButton；子会话 compact send 的可点击语义与外圈 target **48×48dp**，正确发送本地 fixture。子会话未伪造停止 RPC。A+B。 |
| 输入玻璃参数 | [glass_panel.dart:50](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/glass_panel.dart:50>)：fill alpha .34（背景透出部分约 .66）、局部 blur 8、无描边/高光/外阴影；Composer 圆角22、padding12/8/6/6。[surface:70](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/composer_surface.dart:70>) 唯一启用新增 composerGlass；其余 GlassPanel 默认 false。A+B；正文采样仍受 F2 限制。 |
| 高对比/减少动效 | [glass_panel.dart:130](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/glass_panel.dart:130>) OR 条件关闭 blur，fill alpha .97；结构不变。组合切换实测保留输入控制器与草稿，既有全套玻璃测试通过。独立系统开关/真实 Android 画面未测。A+B。 |
| 错误默认/展开/复制 | 新挂载默认单行12.5sp摘要、状态图标、chevron；可展开/收起。320dp×640dp、2×字号、compact 下展开→滚达复制→收起无 overflow，复制按钮实测 **133.2×48dp**、可见且内容完整。A。 |
| 认证脱敏 | 固定 [projection:673](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/transport/dsh_session_projection.dart:673>) 六码列表 MISSING_CREDENTIAL / NO_CREDENTIAL_STORE / INVALID_CREDENTIAL / ACCOUNT_TOKEN_INVALID / AUTH / ACCOUNT_SIGN_IN_REQUIRED 对 detail 仅保留 code；已运行原有 AUTH、ACCOUNT_SIGN_IN_REQUIRED 合成 sentinel 与复制测试。只证明这些明确码分支，不泛化到所有未知认证码、字段或真实设备。A+B。 |
| 结构化分类 | 失败来自 turn/end.reason 结构；user/parent stop 中性，signed-out hook/interrupted 独立分类，未见本轮靠正文包含 error 判定。原有历史/实时单条 notice 测试通过。A+B。 |

布局对齐、触控间距与字体层级在上述离线尺寸内通过；配色沿用 DshPalette，无新增全局材质。focus/disabled/loading 的现有 action matrix 和全套测试通过；F1、F2 为遗留需求项。未把未截取的视觉画面或审美判断列为通过证据。

附带覆盖警示：[natural_glass_test:59](<E:/tmp/wt-flutter-native-chat/flutter_ui/test/natural_glass_test.dart:59>) 的 disabled-send 对比度测试挂载的是旧 ComposerSendButton（26dp 图标），[当前实现:347](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/composer_surface.dart:347>) 在生产 lib 中没有调用点。该测试通过不能单独替代当前 ComposerActionButton 的截图或最终合成色对比；本次未据此发现可见性故障。

## 命令、结果、日志

工作目录均为 `E:/tmp/sol-flutter-review-20261118/flutter_ui`。所有 Flutter 调用串行，未并发或重复启动相同运行中的任务。

| 命令 | 真实结果 | 日志 |
| --- | --- | --- |
| `E:/tools/flutter/bin/flutter.bat analyze --no-pub` | exit **0**；No issues found；40.8s | [analyze.log](<E:/tmp/sol-flutter-review-20261118/analyze.log:1>) |
| `E:/tools/flutter/bin/flutter.bat test --no-pub --concurrency=1 --reporter=expanded` | exit **0**；**+434 All tests passed!**；测试计时1m44s | [flutter-test.log:486](<E:/tmp/sol-flutter-review-20261118/flutter-test.log:486>) |
| `E:/tools/flutter/bin/flutter.bat test --no-pub --concurrency=1 --reporter=expanded ../sol_probe_test.dart` | exit **1**；**5 pass / 2 fail**；失败严格对应 F1/F2 | [probe-final.log](<E:/tmp/sol-flutter-review-20261118/probe-final.log:1>) |
| `E:/tools/flutter/bin/flutter.bat test --no-pub --concurrency=1 --reporter=expanded ../sol_notice_accessibility_test.dart` | exit **0**；**3 pass / 0 fail** | [notice-accessibility-final.log](<E:/tmp/sol-flutter-review-20261118/notice-accessibility-final.log:1>) |

外部探针：[sol_probe_test.dart](<E:/tmp/sol-flutter-review-20261118/sol_probe_test.dart:1>)、[sol_notice_accessibility_test.dart](<E:/tmp/sol-flutter-review-20261118/sol_notice_accessibility_test.dart:1>)。没有往生产测试目录塞入临时测试。

探针自身调试记录均保留：最初 hitTestInView 调用少一个参数；随后错误读取模型外层非语义祖先；输入保持聚焦时少数边缘采样出现空命中，取消聚焦后消失（覆盖层具体种类未单独证实）；语义句柄需在测试体内释放；窄屏 OverflowTitle 持续动效使 pumpAndSettle 不适用；补充文件移除无效 const。最终改用 hitTestOnBinding、实际可点击模型 Semantics、先取消选区焦点、显式释放句柄及有界帧推进后收敛。先前 probe/probe-v2/probe-v3/notice-accessibility 日志是探针调试过程，不算产品测试失败。未修改产品实现来让探针过关。

作业已逐个收集：analyze `bash-cc64df63f6ac4d50`；全套 `bash-926113c20bb943f1`；最终主探针 `bash-0d2a22d4fd584316`；最终补充 `bash-7ea09ce45be84bb5`。各中间失败作业同样已收集，没有需继续等待的 Flutter 作业。

## 未验证与下一步

- 未运行 Gradle、安装或读取设备、ADB、TalkBack、ARM、性能/帧率采样；未读取真实凭据、请求真实模型、核查既有截图真实性。本次不重复任何旧设备通过声明。
- Flutter widget 语义矩形成立，不代表 Android 无障碍桥导出框和 TalkBack 焦点朗读已经通过。模型 label/tooltip 的完整朗读、hover 视觉和键盘导航未作设备验收。
- 主/子一致仅对输入面板、材质、ActionButton 与此处回调成立。基线的 [native_subagent_transport.dart:540](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/transport/native_subagent_transport.dart:540>) 对 turn/end 返回空消息；[SubagentMessage:39](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/model/subagent_model.dart:39>) 无 notice 字段；[child映射:435](<E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/subagent_panel.dart:435>) 不生成 ChatRole.notice。因此不能声称子会话也显示这套错误卡。此为静态确认的既有协议/UI限制，本轮未做真实子会话错误请求，交 Lead 单独界定范围。
- Lead 先串行修 F1，再修共享正文/输入覆盖布局 F2，补上相应断言；随后复跑本报告两项失败探针及原有完整检查。同步更正 F3 的交接文字。
- 本审查未修改实现，无实现回滚。报告可独立移除；外部探针与日志仅在授权临时目录，清理时先确认该绝对目录后由 Lead 处理。
