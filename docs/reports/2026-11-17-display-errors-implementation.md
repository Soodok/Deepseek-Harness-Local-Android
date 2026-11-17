# 模型标签、独立错误卡、设置密度与显示大小实现记录（beta.36 候选）

基线 59f59401，分支 feat/flutter-native-chat，唯一写仓库 E:/tmp/wt-flutter-native-chat。
计划由 Lead 维护（docs/reports/2026-11-17-display-errors-plan.md）；本文件记录实现与证据。

## 状态

DeepSeek 实现及审查修补完成，Lead 已构建 beta.36/code411、安装并完成专用模拟器验收。最终实跑396项测试（01:11，EXIT=0）、analyze（1.2s，EXIT=0），Gradle构建退出0。具体产物、截图、范围和边界见同目录 2026-11-17-display-errors-validation.md 与 -review.md；源码状态由资产目录 source-state.json 绑定。

返工要点：显示大小从「只换 textScaler」改为 Navigator 之上的统一逻辑画布，删除 `DshScaledTextScaler`（clamp 双缩放 bug 随之消失），并把真实 48dp 下限铺到所有主要控件；新增 7 条跨页面/覆盖层/IME/2x 字号的几何测试。详见 4.1、4.2、第 6 节。

## 1. 模型胶囊：左对齐 + ds 缩写

- 新增 `flutter_ui/lib/src/model/model_display.dart`：`displayModelLabel()` 仅把显示标签里的 DeepSeek 令牌（大小写不敏感）替换成 `ds`，保留版本/后缀；模型 id、provider、wire 未动（`native_session_transport.dart` 里 `id = jsonEncode([provider, modelId])`、routes、RPC 参数保持原值）。
- `flutter_ui/lib/src/ui/composer_surface.dart` `ComposerModelPill`：
  - 旧实现外层 `Center` 只包高度，绘制表面被推到可用行宽中央（实测左缘离加号约 137dp）。改为 `Align(centerStart, widthFactor:1)` + `SizedBox(key: composer-model)`，表面贴前缘、宽度按内容。
  - 触控高度用 `DshDisplayScope.atLeastTarget(context, 48)`（画布 0.9 档时为 48/0.9 逻辑单位，物理上仍是 48dp），语义节点/InkWell 与可见胶囊同像素（不留"看着能点其实点空"的死区）。
  - 新增 `semanticLabel`：胶囊只画短标签，Tooltip/Semantics 保留完整模型名。
- `composer.dart`（主会话）、`subagent_panel.dart`（子会话只读胶囊）都传 `displayModelLabel(...)`。

## 2. 回合结束：DSH 式独立错误卡

- 新增 `flutter_ui/lib/src/model/turn_notice.dart`：`TurnNoticeKind{failed,stopped,signedOut,interrupted,maxTokens,blocked,forked,other}`、`TurnNotice{kind,message,code,detail}`、`isFailure`、`title`、`keyName`。
- `chat_model.dart`：`ChatRole.notice`、`ChatMessage.notice`、`ChatEvent.notice`；`chat_reducer.dart` 把它写到新消息。
- `dsh_session_projection.dart`：
  - 删除「turn/end 且 reason != completed 就 `ChatEvent.error(...)`」：回合失败不再当连接故障，不弹顶栏重试条，不写 `state.error`，`state.status` 保持 ready。
  - 新增 `_turnEndNotice()`（依据 rc.2 `conversation-nodes/turn-error.ts:36-51`）：`error`→failed（code + 经认证脱敏后的 detail；凭据类只保留 code）；`aborted` 仅当 `reason.kind=='hook' && reason.reason=='deepseek-account/signed-out'` → signedOut(ACCOUNT_SIGNED_OUT)，user/parent → stopped（中性）；`interrupted`→failed(INTERRUPTED)；max-tokens/blocked/forked → 中性；completed → 不生成；未知 kind → other + 原始 reason JSON。分类只看结构化 kind/code，不靠正文包含 error。
  - `_view()` 生成 `ChatRole.notice` 行，id 仍为 `sessionId:event:seq`；surfaceOp replace / seq 校验 / 历史分页 / assistant revision / 子会话只读等契约未改；journal 重放不重复出行。
  - 流式光标只落在 assistant/system 行；当只剩用户与卡片时，运行中的新回合得到自己的 `sessionId:activity` 系统行承载光标（不给已关闭的卡片挂 spinner）；回合结束时仍发关闭 delta，reducer 正常回 ready。
  - `_failureText` 的可读文案（MISSING_CREDENTIAL→「模型尚未配置…」等）保留，beta.34 行为不回退。
- 新增 `flutter_ui/lib/src/ui/turn_notice_card.dart`：状态图标 + 标题 + 可读原因 + 代码 chip（长码换到原因下方，避免窄屏 2x 字号溢出）+ 详情折叠 + 复制；失败用 bad/warn 色，停止用中性 inkSoft；`conversation_flow.dart` 只对 `ChatRole.notice` 渲染该卡，不进模型气泡/SelectableText。

## 3. 设置页更紧凑

- `settings_page.dart`：标题 34→28、标题留白 22→14、搜索/账号/用量间距 24→14、头像 64→52、Token 卡内边距 18→12、总量字号 30→24、分组间距 28→18、行最小高 64→56、图标底 34→30、标题 16→15、副标题初版12→11.5、审查后恢复12；分割线缩进 64→54、搜索框字号 16→15 与内边距收紧。
- `settings_detail_pages.dart`：ListTile 72/80→58/60、主题与显示大小行 64/56→52、色卡留白 18→14、分节标题下边距 12→6、页面内边距 20/18→16/10。
- `_action` 的 minHeight 48 未动；既有 360px + 2.0 系统字号循环断言行高 ≥56 仍通过。

## 4. 个性化整体显示大小

### 4.1 接线（本轮重做，取代上一版只改 textScaler 的做法）

上一版 `DshDisplayScaler` 只 `copyWith(textScaler:...)`，全 lib 只有 composer 用了几何 factor，设置行/顶部栏/modal sheet/dialog/子会话的几何并不随档位变化，因此不能说「整体显示大小」已实现。本轮改为 **Navigator 之上的统一逻辑画布**：一处缩放即覆盖全部 Flutter 表面，不必逐文件维护倍率。

- 新增 `flutter_ui/lib/src/theme/display_scale.dart`：
  - `DshDisplaySize{compact('compact','紧凑',0.9), standard(...,1), relaxed(...,1.1)}`，`fallback=standard`，`fromWire()` 对 null/空/未知（含旧值 '1.5'）回退 standard。
  - `DshDisplayScope{size,onChanged}`：`sizeOf/factorOf/onChangedOf`；`updateShouldNotify` 按 size/onChanged 通知消费者。
  - 真实触控下限：`physicalTarget = 48`、`minTargetOf(context) = max(48, 48/factor)`、`atLeastTarget(context, base)`、`targetSize(context, {width = 48})`（`width <= 0` 表示交给行宽）。
  - `DshDisplayCanvas`：读 factor；`scale == 1` 时 media 原样，否则 `copyWith(size: size/scale, devicePixelRatio*scale, padding/viewPadding/viewInsets/systemGestureInsets 各 /scale)`；结构固定为 `MediaQuery → OverflowBox(topLeft) → Transform.scale(topLeft) → SizedBox.fromSize(logical.size, child)`。**textScaler 完全不参与**，系统非线性 TextScaler 原样透传，缩放由绘制完成，因此文字不会被放大两次。
  - **`DshScaledTextScaler` 整类删除**：它只 clamp factor 不 clamp 结果（base=2、factor=1.1、clamp(1.2) 时 `scale(13)` = 28.6，而合法上限是 15.6），即 Lead 指出的双缩放 bug。该类被整体方案取代，不留死代码。
- `main.dart`：`theme/darkTheme` 传 `DshTheme.light/dark(mood: _mood, displayFactor: _displaySize.factor)`；`MaterialApp.builder` 改套 `DshDisplayCanvas`。每次 build 的 widget 链完全一致，换档不会 remount Navigator/route/State/焦点/草稿/滚动，也不丢 overlay（有测试）。启动 `_readDisplaySize()` 带 epoch，写入时 `++_displayEpoch`，迟到读取不覆盖新选择；写失败 SnackBar「显示大小没能保存，重启后会恢复上一个选择」。
- `dsh_theme.dart`：`light/dark` 新增 `displayFactor = 1`，`_build` 内 `touch(dp) => dp / displayFactor` 生成 `minSquare(48)`、`minWide(64,48)` 供四类按钮主题使用；新增 `listTileTheme: ListTileThemeData(minTileHeight: touch(48))`。
- 真实 48dp 下限铺开（画布缩小后 48 逻辑单位只剩 43.2 物理 dp，因此下限反向放大）：`composer_surface.dart` 胶囊与三个按钮、`composer.dart`「发送下一条」、`composer_dock.dart`、`chat_shell.dart` 运行时选择器、`execution_group.dart`、`tool_process_card.dart`、`tool_content_view.dart`、`subagent_panel.dart`、`action_cards.dart`（4 处）、`turn_notice_card.dart`（详情/复制）、`settings_page.dart`（`_action` 与用量格）。`composer_surface.dart` 的几何 factor 乘法已删除（画布统一负责），只保留 `withClampedTextScaling(maxScaleFactor: 1.2)` 限制**系统字号**把单行标签撑出胶囊。
- `host_bridge.dart`：`readDisplaySize()`（非字符串/空→null）、`writeDisplaySize(size)`。
- `app/src/main/java/app/dsh/mobile/NativeChatActivity.kt`：`readDisplaySize` 读 `native_chat_ui` 的 `displaySize`（默认 standard），`writeDisplaySize` 白名单 compact/standard/relaxed（其它 `result.error("display/invalid")`）；沿用同一 prefs 文件，不写系统 density / 不改系统字体。
- 个性化页新增「显示大小」三项（key `settings-display-compact|standard|relaxed`），无 onChanged 时该组禁用并提示当前环境不支持调整，不声称仍可切换。

### 4.2 覆盖不到的地方（如实记录）

- HostBridge 打开的原生 Android 页面（Web UI、系统权限/语言页）由 Android 侧渲染，**不受**此画布缩放；Flutter 内的路由、dialog、modal sheet、子会话页全部覆盖。
- Flutter 的 AppBar、返回箭头及 Material 图标在画布内，会随档位变化；Android 系统状态栏/导航栏不在画布内，由系统控制。
- 画布改变的是**逻辑** MediaQuery 尺寸（`size / factor`），所以 `ChatLayout.gutter(width)` 这类按宽度分档的响应式逻辑会随之判断，属于预期行为；它保证 0.9 档在 320dp 宽的设备上仍给内容更多逻辑宽度，而不是把内容挤窄。

## 5. 测试与证据

新增：`test/model_display_test.dart`、`test/turn_error_projection_test.dart`、`test/turn_notice_card_test.dart`、`test/display_scale_test.dart`、`test/display_size_ui_test.dart`。

改动既有断言（契约变更后的真实断言，不是放宽）：
- `test/dsh_session_projection_test.dart` turn/end 循环：`state.error` 由「含 provider failed」改为「为空」+ 无 `ChatEvent.error`；非 completed 断言 `ChatRole.notice` 与 code。
- `test/subagent_panel_test.dart`：子会话胶囊文字改 `ds-v4.1-flash`，新增 `semanticLabel == 'deepseek-v4.1-flash'`。

红→绿：
- 红：`model_display_test` 加载失败（符号不存在）；`turn_error_projection_test` 3 条断言失败（aborted 仍出 system 行、error 发 `ChatEvent.error`、provider 文本外泄）；`turn_notice_card_test` 3 条找不到 `turn-notice`；`display_size_ui_test` 先红在「胶囊居中」与「仅 TextScaler 不改几何」。
- 本轮新增画布测试后，把 `DshDisplayCanvas` 临时改成 `scale = 1.0`（画布退化为直通）复跑，`display_size_ui_test` 4 条立即变红：
  - `settings rows, sheet rows and headings all follow the size`：设置行高不再随档位变化（50.4/56/61.6 退化为 56/56/56）。
  - `a size change keeps the pushed route and its taps working`：换档后行高不再增长。
  - `a size change under an open sheet keeps the sheet usable`：sheet 标题字号不再缩小。
  - `320dp wide with 2x system text stays operable at compact`：胶囊/选项几何断言失败。
  改回后 12 条全绿。这一步证明新断言真的挂在「几何随档位变化」上，而不是只测文字。
- 绿：门禁日志 E:/tmp/native-chat-web-preview/android-beta36-scale-flutter.log（384 tests，All tests passed，EXIT=0）与 android-beta36-scale-analyze.log（No issues found，EXIT=0）。上一轮的 android-beta36-flutter.log / android-beta36-analyze.log 保留不动。

## 5.1 精准收尾修复（GLM 复核 4 点，2026-11-17 追加）

只改下面这些点，未扩大范围；基线仍是 59f59401，未构建、未提交。

1. **个性化页行高不足 48dp（真实 red）**：`settings_detail_pages.dart` 的显示大小三行与主题三行原为固定 `minTileHeight: 52`，compact 画布把 52 逻辑单位画成 **46.8dp**（探针实测 46.80，standard 52.00 / relaxed 57.20），确实低于 48 触控下限。改为 `DshDisplayScope.atLeastTarget(context, 52)`：compact 逻辑高度升到 48/0.9≈53.33 → 物理 48.00，其余两档仍是 52/57.2。
2. **认证错误 detail 泄密**：`dsh_session_projection.dart` 的 `_turnEndNotice` 用 `detail: jsonEncode(failure)`，绕过 `_failureText` 的脱敏——`reason.error.message` 与嵌套 `error.data`（如 accessToken）会原样进入可展开详情与复制文本。新增 `_credentialFailureCodes{MISSING_CREDENTIAL, NO_CREDENTIAL_STORE, INVALID_CREDENTIAL, ACCOUNT_TOKEN_INVALID, AUTH, ACCOUNT_SIGN_IN_REQUIRED}` 与 `_failureDetail()`：命中集合时 detail 只保留 `{"code": ...}`，其余错误仍 `jsonEncode(failure)` 保留完整诊断。认证 code 分支与 `_failureText` 一致，未新增/改动可读文案。
3. **字号**：错误码 chip `turn_notice_card.dart` 11→12sp；`settings_page.dart` 分组标题 11.5→12、设置行副标题 11.5→12。搜索提示没有自己的 fontSize（继承输入框 15sp，探针实测 size=15.00），未改动即已 ≥12，改为由测试守住下限（见下）。
4. **文案**：`turn_notice.dart` 的 signedOut 标题 任务已停止→**账号已退出，本轮已停止**，interrupted→**运行已中断**，stopped 仍为 任务已停止；新增 `nextStep`（signedOut=重新登录后重试、interrupted=恢复后重试、其余空），卡片渲染 key `turn-notice-next-step` 并写入复制文本，stopped 无恢复提示。个性化说明改为「调整文字和控件大小，不影响系统设置；仍遵循系统字体大小。」，去掉 Flutter/几何实现术语；无 onChanged 时改为「当前环境不支持调整显示大小。」，不再声称本次运行可用。

未处理（按指令）：GLM 的 mydeepseek 子串「疑似」、activity 空行。

新增测试 `test/settings_readability_test.dart`（5 条），并扩充 `turn_error_projection_test.dart`（+4，含卡片复制）与 `turn_notice_card_test.dart`（+3）。

红→绿证据（每条修前都真红）：
- 行高：`display and theme rows keep a real 48dp target at compact` 修前失败（6 个 key 均为 46.8）；负对照把两处改回 `minTileHeight: 52` 复跑该条再次变红，恢复后绿。
- 脱敏：`an AUTH failure...`/`a sign-in-required failure...`/`the card copy action...` 修前失败，真实输出含 `SENTINEL-9f3a-token`（detail 与复制文本都带）；负对照把 `detail: _failureDetail(failure)` 改回 `jsonEncode(failure)`，同 3 条立刻变红，恢复后绿。测试只用假 sentinel，未读取任何真实凭据。
- 标题/文案/chip：`sign-out and interruption name their own ending`、`the failure code chip is readable at 12sp or more`、分组与副标题 12sp、个性化说明三条修前均失败。

门禁（串行，本轮最终态）：完整 **396** 项 flutter test 通过（E:/tmp/native-chat-web-preview/android-beta36-review-flutter.log，末行 `All tests passed!`，TEST_EXIT=0）、flutter analyze No issues found（android-beta36-review-analyze.log，ANALYZE_EXIT=0）。改动文件 `dart format --set-exit-if-changed` 全干净（FMT=0）；仓库内 `composer_action_matrix_test.dart` 与 `mobile_geometry_test.dart` 本来就不符合格式化，未改、不在本轮范围。

## 6. 本轮新增的跨页面测试（`display_size_ui_test.dart`）

所有断言读**真实全局 rect**（`tester.getRect`）并在该 rect 的 `center` 用 `tapAt` 验证命中，不用 `getSize` 代替绘制位置。

1. `settings rows, sheet rows and headings all follow the size`：同一路径分别以 compact/standard/relaxed 启动，测「设置目的地行」（push 路由）、「模型弹层里的选项行」（overlay）与弹层标题（type）。行高精确断言 50.4/56/61.6，选项与标题按 ×0.9 / ×1.1 断言；并校验 compact 行高仍 ≥48。
2. `a size change keeps the pushed route and its taps working`：**先**推到设置页，再调 `onChanged(relaxed)`；断言页面未重挂载（行仍在）、行高变大，并在新的 `center` `tapAt` 成功进入模型配置页。
3. `a size change under an open sheet keeps the sheet usable`：先打开模型 sheet，再切 compact；断言 sheet 仍在、标题变小，并对移动后的选项行 `tapAt` 成功选中并关闭。
4. `compact keeps a real 48dp target on the main controls`：compact 下 `composer-model/send/attach` 三处 rect 高度 ≥48，中心 `tapAt` 能开 sheet；随后推设置页，设置行仍 ≥48 且中心 `tapAt` 能进模型配置页。
5. `a turn notice keeps 48dp copy and detail targets at compact`：错误卡在 compact + 画布下，详情/复制两键 rect ≥48，中心 `tapAt` 分别展开详情与写入剪贴板。
6. `the IME edge and the safe area stay on real physical pixels`：`padding.top=24`、`viewInsets.bottom=300`，在 compact 与 relaxed 两档断言顶部栏不侵入状态栏、发送键底边落在键盘上沿（≤600 +0.5 且 >460），即 insets 被按物理像素还原。
7. `320dp wide with 2x system text stays operable at compact`：320×640 + 系统字号 2x + compact，无异常、胶囊 ≥48 且不越界、`tapAt` 能开弹层并选中模型。

`display_scale_test.dart` 同时补了三条不依赖 UI 的单元级断言：`minTargetOf` 在 0.9 档等于 48/0.9（乘回 ≥48）、`targetSize` 的 width 0 语义、以及「画布不改 textScaler、insets 按 1/factor 换算」（`seen == platform`、`scale(13) == 26`）。
