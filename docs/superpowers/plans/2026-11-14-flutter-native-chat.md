# Flutter 独立 DSH 聊天 UI 实现计划

> 本计划在隔离 worktree /e/tmp/wt-flutter-native-chat 执行；实现前遵循 TDD，任务完成后运行对应验证。

目标：在 dsh-android 中交付可独立运行和测试的 Flutter DSH 聊天 UI 首版，并保留现有 WebView 回退。

架构：flutter_ui Flutter module 负责视觉和聊天状态；Dart transport 抽象支持 fixture 与本机 HTTP/WebSocket；Kotlin NativeChatActivity/HostBridge 提供 EngineSupervisor URL 和 Web fallback，不直接承担会话文件解析。

技术栈：Flutter 3.50.0 / Dart 3.14.0、纯 Flutter SDK widgets、Dart HttpClient/WebSocket、flutter_test、现有 Android Gradle/Kotlin。

## 全局约束

- 所有修改只发生在 /e/tmp/wt-flutter-native-chat，主仓 main 的未提交 WIP 不触碰。
- dsh go 只作竞品研究；不复制其源码、素材、文案、图标或独特布局。
- UI 必须覆盖启动、连接、会话、流式回复、工具、审批、提问、停止、排队、错误、重连、fallback 全流程。
- 真实引擎协议尚未在当前无引擎实例上确认；fixture 不能伪装成真实连接，UI 必须显示数据源状态。
- 构建串行执行；每次构建前后记录命令与退出码，完成后只清理本 worktree 的 build/、.dart_tool/、.android/ 中间物。

## 任务 1：Flutter module 与 Android 入口

文件：创建 flutter_ui/（由 Flutter module 生成器创建）；修改 settings.gradle.kts、app/build.gradle.kts；创建 app/src/main/java/app/dsh/mobile/NativeChatActivity.kt；修改 app/src/main/AndroidManifest.xml；测试 flutter_ui/test/bootstrap_test.dart。

- [ ] 用 /e/tools/flutter/bin/flutter.bat create --template=module --org app.dsh --project-name dsh_native_chat flutter_ui 生成 module，并立即检查生成文件只位于新目录。
- [ ] 写 bootstrap 测试，断言 NativeChatActivity 对应 Flutter route 可以启动 fixture shell；先运行 flutter test test/bootstrap_test.dart，确认测试因入口不存在而失败。
- [ ] 接入 Flutter module 和 NativeChatActivity，HostBridge 暴露 webUrl、engineState、openWebFallback 三个方法；engine URL 读取 EngineSupervisor.webUrl()，空 URL 必须返回 unavailable 状态。
- [ ] 添加 manifest activity，不改变现有 launcher activity；在 Android 入口不可用时保持 WebView 主流程不变。
- [ ] 运行 bootstrap 测试和 git diff --check，提交 feat: add flutter chat module shell。

## 任务 2：数据模型与状态归并（先红后绿）

文件：创建 flutter_ui/lib/src/model/chat_model.dart、flutter_ui/lib/src/state/chat_state.dart、flutter_ui/lib/src/state/chat_reducer.dart、flutter_ui/test/chat_reducer_test.dart。

- [ ] 先写测试覆盖 snapshot、assistant delta、tool start/end、approval、question、cancel、queued、error、generation 丢弃旧事件和 sequence 缺口。
- [ ] 运行 flutter test test/chat_reducer_test.dart，确认失败原因是模型/归并器缺失而非测试语法错误。
- [ ] 实现不可变模型和 reducer；旧 generation 事件必须保持状态不变，sequence 缺口进入 needsResync，不能静默拼接。
- [ ] 重新运行测试，确认全部通过，再只做命名和重复代码重构。
- [ ] 提交 feat: add chat event state model。

## 任务 3：transport 与确定性 fixture

文件：创建 flutter_ui/lib/src/transport/dsh_transport.dart、flutter_ui/lib/src/transport/fixture_transport.dart、flutter_ui/lib/src/transport/http_ws_transport.dart、flutter_ui/test/transport_fixture_test.dart。

- [ ] 先写 fixture 测试，验证按步骤产生 session snapshot、delta、tool、approval、question、stop、queue、disconnect/reconnect 事件。
- [ ] 运行测试确认 transport 尚不存在导致红灯。
- [ ] 实现 DshTransport 接口；fixture 使用可控 Stream<ChatEvent>，HTTP/WebSocket transport 只做 URL/token 注入、POST unary 和 /api/remote.mux carrier 封装。
- [ ] 对 HTTP/WebSocket 错误、空 URL、重复关闭、重连 generation 做单元测试；不连接当前不存在的端口来制造假绿。
- [ ] 提交 feat: add replaceable chat transports。

## 任务 4：独立 DSH 聊天 UI

文件：创建 flutter_ui/lib/src/theme/dsh_theme.dart、flutter_ui/lib/src/ui/chat_shell.dart、session_drawer.dart、message_list.dart、tool_run_card.dart、action_cards.dart、composer.dart；修改 flutter_ui/lib/main.dart；测试 flutter_ui/test/chat_screen_test.dart。

- [ ] 先写 widget 测试：连接中、空会话、历史消息、流式助手、工具卡折叠/展开、审批确认、提问回答、停止、排队、错误重试、断线重连、Web fallback 按钮。
- [ ] 运行 widget 测试确认 UI 组件缺失导致红灯。
- [ ] 实现 DSH 自有主题、响应式单列消息区、会话抽屉、状态栏、消息渲染、工具卡和 action cards；所有动作通过 controller/transport 触发，不在 widget 内拼协议。
- [ ] 实现 composer 的发送/停止/排队状态，键盘和窄屏布局不能遮挡输入区。
- [ ] 重新运行 widget 测试，补充语义标签，确保按钮可被测试和无障碍树发现。
- [ ] 提交 feat: add independent dsh chat experience。

## 任务 5：Android host 接线与 fallback

文件：修改 settings.gradle.kts、app/build.gradle.kts；创建/修改 app/src/main/java/app/dsh/mobile/NativeChatActivity.kt、app/src/main/AndroidManifest.xml；测试 flutter_ui/test/host_bootstrap_test.dart。

- [ ] 先写 host bootstrap 测试，覆盖有 URL、无 URL、Web fallback intent 三个分支并确认红灯。
- [ ] 让 Kotlin activity 启动 Flutter module，注入当前 EngineSupervisor URL；无 URL 时显示连接不可用和 fixture 入口，不能伪造 Healthy。
- [ ] 保持现有 MainActivity/WebView route 不改行为；fallback 通过明确 intent 返回现有 WebView。
- [ ] 运行 Flutter 测试和 ./gradlew :app:compileDebugKotlin，修复真实编译错误。
- [ ] 提交 feat: connect flutter chat host boundary。

## 任务 6：全流程验证与清理

文件：创建 flutter_ui/test/full_flow_test.dart、docs/reports/flutter-native-chat-validation-2026-11-14.md；修改 AI_CONTEXT.md；更新 .superpowers/sdd/progress.md。

- [ ] 运行 dart format --output=none --set-exit-if-changed flutter_ui/lib flutter_ui/test。
- [ ] 运行 flutter analyze 和 flutter test --coverage，记录退出码和失败数。
- [ ] 运行 flutter build web --release，检查构建产物存在；如 Android module 接线稳定，再串行运行 ./gradlew :app:assembleDebug。
- [ ] 运行反向变异：暂时破坏 reducer 的 generation 门禁，确认对应测试失败；恢复后再次运行测试通过，并确认工作树只包含预期源文件。
- [ ] 记录 UI 全流程覆盖、真实 engine 未连接边界、构建内存/耗时和清理命令。
- [ ] 运行 flutter clean（仅 worktree）并删除本 worktree 生成的验证中间物；保留源代码、测试、报告和必要截图。
- [ ] 提交 test: validate flutter chat flow。

## 任务 7：最终审查与交付

- [ ] 获取分支起点 git merge-base main HEAD 和当前 HEAD，检查 git diff --stat、git status、提交列表。
- [ ] 派 checker 做只读代码审查，审查范围包含协议边界、状态机、全流程 UI、内存清理和许可证边界。
- [ ] 修复 Critical/Important 发现，重跑覆盖测试；不在未审查状态宣称完成。
- [ ] 最终回读主仓 status，确认主仓 WIP 未变化；输出 worktree 路径、分支、提交、验证命令和未覆盖真实引擎层级。
