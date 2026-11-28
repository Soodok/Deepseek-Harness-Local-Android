# 消息视口上下边缘模糊过渡

基线：`7d9b26dfd42391fbed9c776a21c3f97f97a67b05`，仅 `E:/tmp/wt-flutter-native-chat`。本轮独立于旧 Sol 审查。

## 设计与范围

保留主/子页原有 Column、顶部栏、输入区与 dock。新增 `flutter_ui/lib/src/ui/conversation_edge_blur.dart`，在 `chat_shell.dart` 的 MessageList、`subagent_panel.dart` 的 ConversationFlow 外各包一次。正文只保留原有一棵 lazy 滚动树；不改 composer/glass 材质、状态或业务。

采用正文视口内顶部 28dp、底部 40dp 的局部过渡，见下节「连续羽化重做」。

## 连续羽化重做（2026-11-19，beta.81）

用户对上一版的判定：模糊带边缘生硬，文字一旦出了模糊区，原始清晰轮廓会「一瞬间露出来」。这是四段互不相交 `ClipRect` 的固有问题——每一段内部强度恒定，段与段的交界处清晰度突变，任何一段的结束边都等于一条可见接缝。加细段数只会把接缝做得更密，不会消失。

beta.81 先试了「整条带一层恒定 sigma + 单段 alpha 渐变」。真机剖面证明它仍不成立：一层滤镜的 sigma 沿带恒定，末端只能靠 `ClipRect` 裁断，模糊强度在带的内边界从 2.4 直接掉到 0；设备实测该处标准差在约 8–16 px 内从 31.5 跌到 1.0，cliff 占比 0.61（`feather_accept.py`），正是用户投诉的「一瞬间露出来」。

beta.82 的现实现（`_Edge`）改成**分层羽化**：8 条同心裁剪带，都在同一侧对齐，高度按 (layers-i)/layers 递减；每层 `ClipRect > BackdropFilter` 的 sigma 按层号递增（最宽的那层 sigma 为 0，最短、最贴边的那层最大）。Skia 下每层采样的是「之前各层已经画出的结果」，于是某个像素被多少层覆盖，就累积多少层模糊：贴边像素叠 8 层，带内像素只叠 1–2 层，内边界附近几乎不叠。强度逐行单调下降，任何单个边界只增加总量的 1/7，不再有开关式的陡变。其上仍叠一条单段 `LinearGradient`（paper alpha .92 → 0），只负责消掉边缘残留的字形。

带子同时**伸进浮层内边距**（overlap 16dp，顶/底带高 64dp）：之前顶带从浮层实测底边开始，而浮层自身还有 10dp 内边距，露出的第一行正好落在带外、effect 恒为 0，那段空隙就是「硬断口」。

`ClipRect` 是必需的：实测未裁剪的 `BackdropFilter` 会把滤镜结果画到子节点边界之外，整段正文都被抹开（测试里放大到 row 60/100 都是糊的，模糊比对的「深处应锐利」断言直接变红）。

叠加顺序也修正为「先模糊、后遮盖」；上一版四段是先各自模糊再统一遮盖，遮盖在半透明时才看得出分段。

## 保护与降级

仅监听 depth=0 的垂直 ScrollNotification / ScrollMetricsNotification，不接管 ScrollController、不主动滚动。顶强度以 extentBefore/28、底强度以 extentAfter/40 从 0 到 1；在对应首尾端点为 0，首末文本完整清晰。初始无指标时两缘关闭；空列表/短列表无多余遮盖。高对比、disableAnimations、accessibleNavigation 均关闭额外 blur 和 fade。小视口每缘限至高度的 1/4。透明装饰 IgnorePointer + ExcludeSemantics，保持点击/拖动/选择和语义。固定树形，降级不重挂消息/输入，不改草稿/焦点/offset。

## 串行实施与验收

1. 先新增 `flutter_ui/test/conversation_edge_blur_test.dart`：主/子真实页必须找到共享包装；最小视口探针验证覆盖区点击与拖动、首尾禁用、降级时 Scrollable State/offset 与输入草稿/焦点保留。先运行接入断言，确认基线失败。
2. 逐文件新增包装、主页面接入、子页面接入。不需要修改 ConversationFlow padding，因为对应 extent 处效果归零。
3. 显式 `E:/tools/flutter/bin/flutter.bat test --no-pub --concurrency=1 ...` 跑局部与相关既有测试，然后 `flutter analyze --no-pub`；仅格式化本轮 dart 文件。真实渲染截图若可从 widget-test 的 RepaintBoundary 输出则保留，禁止 AI 合成图与替代服务器。
4. 更新本报告及 AI_CONTEXT 顶部短段，交 Lead 统合；不 Gradle、不安装、不操作设备、不递增版本、不 commit。

## 当前状态

已读取交接顶部、参考 JPEG、真实主/子视口和共享玻璃源码；实现已完成，设备未验。源码改动仅为新增 `flutter_ui/lib/src/ui/conversation_edge_blur.dart`、新增 `flutter_ui/test/conversation_edge_blur_test.dart`，以及 `chat_shell.dart` / `subagent_panel.dart` 各一次包装接入；未改 composer、glass_panel、conversation_flow、state、transport、Android、插件或全局配置。

已验证：

- `/e/tools/flutter/bin/flutter.bat test --no-pub --concurrency=1 --dart-define=EDGE_BLUR_SCREENSHOTS=true test/conversation_edge_blur_test.dart`：退出 0，6 项通过；覆盖主/子共享组件、覆盖层点击/拖动、首末归零、实际模糊像素、calm 模式和主页面草稿/滚动/焦点保持。
- `/e/tools/flutter/bin/flutter.bat test --no-pub --concurrency=1 test/conversation_edge_blur_test.dart test/conversation_flow_test.dart test/subagent_panel_test.dart test/chat_screen_test.dart`：退出 0，36 项通过。
- `/e/tools/flutter/bin/flutter.bat analyze --no-pub`：退出 0，`No issues found!`。
- 负对照：临时把四档 `sigmaX/Y` 置 0，像素断言由期望差值 `>8` 变为实际 `1`，退出 1；已恢复真实 blur 参数。

### Lead 集成验证（2026-11-18）

- 当前产品代码全量 `flutter test --no-pub --concurrency=1 --reporter=compact`：退出 0，末行 `04:03 +440: All tests passed!`。
- 首次格式检查 `dart format --output=none --set-exit-if-changed` 退出 1，只报告 chat_shell.dart 的排版差异，不会写回；后续已实际执行 formatter。
- 修正截图采集：测试 harness 的 MaterialApp 关闭主题插值并断言目标 brightness，避免切深色后在动画首帧截出重复浅色图。带 `EDGE_BLUR_SCREENSHOTS=true` 的本轮 6 项测试重跑退出 0；新深色图 SHA-256 为 a6e69080208df2b7c4c62e9e536deecdb55560ac71c198fd456825567ea6eea5，浅色图为 7508419ef2afdac79d37be64bd181fd8052afbfb189382d1d4c4320f35a302f4。
- 版本递增为 versionCode 454 / 1.6.0-beta.79；原 beta.78 APK 已独立备份到 `E:/tmp/native-chat-web-preview/dsh-native-chat-beta78-x86_64-before-edge-blur.apk`。Gradle clean/assembleDebug 正在执行，尚未宣称构建或设备通过。

真实 Flutter widget-test 输出：`docs/reports/assets/2026-11-18-edge-blur-render.png`、`docs/reports/assets/2026-11-18-edge-blur-shell-light.png`、`docs/reports/assets/2026-11-18-edge-blur-shell-dark.png`。shell 截图使用测试字体，文案显示为色块，只作布局/渲染证据，不作可读性证据。未做 Gradle、安装、设备、ARM、TalkBack 或真实字体验收。回滚仅撤本轮包装、两处接入、测试、报告和截图，保留其他 WIP 与 `.superpowers/brainstorm/`。
