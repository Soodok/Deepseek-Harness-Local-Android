# Floating Composer / Turn Notice 交接（2026-11-18）

## 修改范围

本轮只改 Flutter 输入区、共享玻璃材质、Flutter 结构化回合提示，以及内置 turn-rewind Web 客户端的 toast/渲染层：

- flutter_ui/lib/src/ui/composer_surface.dart
- flutter_ui/lib/src/ui/composer.dart
- flutter_ui/lib/src/ui/glass_panel.dart
- flutter_ui/lib/src/ui/turn_notice_card.dart
- flutter_ui/test/turn_notice_card_test.dart
- flutter_ui/test/turn_error_projection_test.dart
- flutter_ui/test/display_size_ui_test.dart
- flutter_ui/test/widget_test.dart（验证阶段修掉一处会单独跑必红的定时器泄漏用例）
- app/src/main/assets/android-plugins/turn-rewind/client.js
- docs/reports/2026-11-17-conversation-parity-plugin-test/client.test.mjs（随 client.js 同步的用例）
- app/build.gradle.kts（版本递增到 1.6.0-beta.78 / versionCode 453）

未改插件 core.js/index.js、传输/回滚/会话逻辑、宿主安装、Android 转场、MainActivity、NativeChatActivity、全局 profile、第三方缓存；未读取凭据或发新的付费模型请求。

## 具体视觉值

- Composer 外壳内边距：14/12/8/8dp → 12/8/6/6dp（左/上/右/下），圆角 28 → 22dp。依据：减少厚重托盘感并给输入与动作留出稳定间距。
- Composer 专用玻璃：新增 composerGlass，仅输入区启用；模糊 16 → 8px，填充 alpha 0.62/0.58 → 0.34，无描边、无外阴影；减少动效/高对比仍使用 alpha 0.97 fallback。依据：输入区保持正文可读，同时减少低帧率模拟器的高成本采样和硬边。
- 发送/停止：保留同一个 action slot 和现有业务回调；视觉圆面 36dp、图标 20dp（原发送图标 26dp，停止 24dp），外层 DshDisplayScope.targetSize(context) 保证实际触控不低于 48dp；compact 通过 48/factor 反向放大。禁用发送使用既有 sendBlueMuted。
- 附件：视觉圆面 34dp、图标 20dp，触控仍由 48dp target 保证。
- 权限：保留原 DSH permission glyph，视觉圆面 34dp、glyph 18dp（原 glyph 22dp），触控仍为 48dp。

### 视觉值实测（emulator-5570，1080x2340，density 440 = 2.75x，displaySize=standard）

截图像素取色测量（发送蓝 #4B5E82 圆面 bbox）：

| 控件 | 像素 | dp |
| --- | --- | --- |
| 发送圆面 | 99x99 | 36.0 |
| 附件圆面 | 94x93 | ~34.0 |
| 权限圆面 | 93x93 | ~34.0 |

三项与设计目标值一致。compact（0.9 factor）下视觉圆面约 36*0.9=32.4dp / 30.6dp，符合画布缩放预期。

### 触控地板实测（Flutter 侧权威几何，临时 test/_scratch_probe_test.dart 探针后删除）

同时打印 logical 尺寸与经 Transform 后的 painted 尺寸（`box.localToGlobal(bottomRight) - localToGlobal(zero)`）：

| displaySize | logical | painted（真实触控 dp） |
| --- | --- | --- |
| compact (0.9) | 53.3x53.3（model 宽 137.3） | **48.0x48.0**（model 123.6x48.0） |
| standard (1.0) | 48.0x48.0 | **48.0x48.0** |
| relaxed (1.1) | 48.0x48.0 | **52.8x52.8** |

结论：三种尺寸档的 send/attach/permission/model 真实触控面积均 >=48dp；display_scale.dart:74-86 的 minTargetOf/targetSize 的「48/factor 反向放大」实现正确。
设备侧 uiautomator 语义框（send 87x87px≈31.6dp）不能用于判定触控地板——Flutter 语义树按绘制内容裁剪，不代表命中区域；命中面积以上表为准，并有下面的点击实验佐证。

## Flutter 错误提示方案

TurnNoticeCard 仍只接结构化 TurnNotice，没有通过文本判断失败。默认仅显示状态图标、单行小字号摘要和展开箭头，整行可点；默认隐藏原因、恢复建议、错误码、详情和复制按钮。展开后显示可读原因、signedOut/interrupted 恢复建议、code、上游已脱敏的 detail、复制详情（最小 48dp）。用户/父会话停止仍使用中性色和停止图标。组件不重写 projection，也不改变认证脱敏边界。

## Web 端改动（已从建议升级为已实现并真机验证）

turn-rewind/client.js 现在做两件事：
1. 撤回/编辑/重试动作失败的 Error.code/message toast：去除高饱和红色大块重阴影，改为主题 token、半透明低对比、状态图标、12.5px 紧凑行、安全区边距。
2. **以 `turn-error` 为 key 覆盖官方 conversation.chat.node 渲染器**，在 Web 的官方 turn-error slot 注入与原生同一套折叠结构（`.dsh-rw-notice`）：默认单行摘要 + 状态图标 + chevron，点整行展开原因/恢复建议/code/上游已脱敏 detail/复制详情，再点收起。没有从 toast 反推回合失败，认证原始 payload 不进 detail/clipboard，未知 code 只写未知或可能。

真机 WebView（Chromium DevTools via adb forward，页面 http://127.0.0.1:3080/ 真实会话）DOM 证据：
- `.dsh-rw-notice` 存在，折叠态 height=48，`aria-expanded="false"`，摘要「DSH 进程在本轮完成前退出，未完成的工具调用结果未知。」
- 点击后 innerText：标题「运行已中断」/ 原因 / 恢复建议「恢复后重试。」/「代码：INTERRUPTED」/「复制详情」；`.dsh-rw-notice-copy` rect 91.45x48（>=48dp 生效）。
- 再次点击后 `.dsh-rw-notice-body` 数量回到 0，可收起。
- 截图：/e/tmp/web_notice_collapsed.png、/e/tmp/web_notice_expanded.png。

因此 Web 侧的结构化 turn-error 是真实接上的，不是用 toast 冒充；AUTH 类载荷由上游脱敏后进入 detail。

## 验证证据（2026-11-18 接手实测）

工作树 E:/tmp/wt-flutter-native-chat（分支 feat/flutter-native-chat）。命令全部串行执行，工具链 E:/tools/flutter/bin/flutter.bat、SDK E:/Android/Sdk、JDK D:/新建文件夹/jbr、Gradle 9.3.1。

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| Dart 格式 | `dart format --output=none --set-exit-if-changed <本轮改动的 9 个 dart 文件>` | EXIT=0 |
| 静态分析 | `flutter analyze --no-pub` | `No issues found!` EXIT=0 |
| 全量测试 | `flutter test --concurrency=1` | `+434: All tests passed!` EXIT=0 |
| 插件语法 | `node --check app/src/main/assets/android-plugins/turn-rewind/client.js` | EXIT=0 |
| 插件单测 | `cd docs/reports/2026-11-17-conversation-parity-plugin-test && node --test` | tests 46 / pass 46 / fail 0 |
| 构建 | `gradle --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process --no-configuration-cache --console=plain :app:clean :app:assembleDebug -Pabi=x86_64 -Ptarget-platform=android-x64 -Ppackage=full -PruntimeZip=E:/tmp/dsh-flutter-runtime-20261116/runtime-x86_64-dsh-0.2.2-rc.2-20261116.zip` | BUILD SUCCESSFUL in 6m 12s（99 actionable tasks: 58 executed, 41 up-to-date） |

注：对**整个** lib+test 跑 `dart format` 会报 24-26 个文件需要改动，属 HEAD 上既有的格式漂移，与本轮改动无关，未擅自格式化他人文件。

### 产物

- APK：app/build/outputs/apk/debug/app-debug.apk
- 字节数：**248190718**
- SHA-256：**3b13bb25ad86240952e181149872c26f57edcff76122fe309e4a4dbf46a56947**
- versionCode 453 / versionName **1.6.0-beta.78**

### 负对照（证明绿灯可被打红）

1. `display_scale.dart:74-75` 的 minTargetOf 临时改为 `=> physicalTarget;`（去掉反向放大）→ display_size_ui_test 的 compact 用例失败「Expected: a value greater than or equal to <47.99> / Actual: <43.200000000000045>」；还原后与 HEAD 哈希一致。
2. `launch_fade.dart` 的 releaseBackdrop 调用临时替换为空注释 → widget_test 的 reduced-motion 用例失败「Expected: contains 'releaseBackdrop' / Actual: []」；还原后与 HEAD 一致（a8f616dd…）。

### 发现并修掉的假绿断言

- **display_size_ui_test.dart 原本测不出地板缺失**：原断言用 `tester.getRect(...).height >= 48` 读的是 logical 高度，而 compact 下 logical 恒为 53.3（已含反向放大），把地板拿掉后 logical 仍是 48 也照样绿。已改为新增 helper `Size _paintedSize(WidgetTester tester, Finder finder)` 测量 painted 尺寸，并把用例覆盖到 composer-model/send/attach/permission 四项、宽高双维度（`>= 48 - 0.01`）。
- **widget_test.dart 的同名重复用例单独跑必红**：第 2 个「a launch fade never fights the system reduced motion setting」pump 一次就结束，LaunchFade._handOver 里 `precacheImage(...).timeout(2500ms)` 的定时器仍挂着，flutter_test postTest 断言 `!timersPending` 失败「A Timer is still pending even after the widget tree was disposed」；整体跑时会被后续用例的时钟推进掩盖，属排队耦合掩盖。已重命名为「a reduced-motion launch fade still hands the backdrop over」，显式 `pump(3s)`+`pump(1s)` 推完交接，并断言淡出终态 0、`calls contains 'releaseBackdrop'`、内容仍在。

## 设备验收（emulator-5570，1080x2340，density 440）

- 输入区玻璃：圆角悬浮卡片，正文可从上方透出，无厚面板/硬描边/重阴影。
- 触控盒大于视觉圆面（实测）：点附件圆面左外侧 (36,2144) 仍触发输入框聚焦并弹键盘；点模型胶囊左侧空白 (150,2100) 打开权限菜单 → 命中盒向外扩展到视觉之外。
- TurnNotice 折叠↔展开：点整行后标题变「回合已分叉」+ 原因「这一轮已在分叉点关闭。」+「复制详情」，再点回到单行摘要（截图 /tmp/notice_exp.png、/tmp/notice_col.png）。原生历史里这批 turn 的结构化载荷是 forked（中性色、非错误红），所以没有 code/detail 段可展示。
- 减少动效 fallback：`animator_duration_scale=0` 重启后输入区填充变实（composer 区域 p10/med/p90 亮度 30.3 → 44.7），`BackdropFilter.enabled=false` + `fallbackFill alpha 0.97` 生效，正文与输入文字仍可读（/tmp/crop_glass_blur.png vs /tmp/crop_glass_fallback2.png）。
- 高对比模式（`high_text_contrast_enabled=1`）：同样走 calm 分支，未见异常（/tmp/hc2.png）。
- 1.5x 系统字体：正文放大，输入区与模型胶囊不被裁切，发送/附件/权限完整可见，无 overflow（/tmp/big_font2.png）。
- 设备设置验收后已全部还原：font_scale=1.0、animator/transition/window_animation_scale=1、high_text_contrast_enabled=0、displaySize=compact。

## 未覆盖 / 限制

- AUTH 类脱敏**没有设备侧真实载荷**可验证（本机历史 turn 只有 forked/interrupted 形态）；脱敏证据仅来自单测 turn_error_projection_test.dart（断言 code 保留、message/detail/copied 均不含 sentinel）。Web 侧同理，仅真机验证了 INTERRUPTED。
- 未知 code 的兜底文案（标「未知/可能」）只有单测覆盖，无真机载荷。
- 未做 TalkBack 实测，未在 ARM 物理机上验证，未做长时间低帧率性能采样。
- 语义树尺寸（uiautomator）与 Flutter 绘制尺寸不一致属已知差异，本报告以 Flutter 侧 painted 尺寸为触控地板依据。

## 回滚方式

仅回滚本轮上述文件的未提交 diff，不要删除或覆盖 .superpowers/brainstorm/。
