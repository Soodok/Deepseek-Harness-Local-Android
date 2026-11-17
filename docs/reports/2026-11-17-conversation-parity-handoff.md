# 会话平权交接（2026-11-18，beta.50）

## 一句话状态

七个消息动作（复制/撤回/编辑/点赞/踩/重新输出/分享）在 Android 应用内 WebView 与 Flutter 原生两端均已完成实机点验，全部读写 DSH 权威后端；文件回滚的新建/修改/删除/外部冲突四种场景都有设备级真实样例。自动化门禁 427 项 Flutter 测试 + 34 项插件测试 + analyze + Gradle 全绿。

## 交付物

| 项 | 值 |
| --- | --- |
| 仓库 | E:/tmp/wt-flutter-native-chat（分支 feat/flutter-native-chat，基线 556ef615ac5162347ea205fd602301622d99d6f1） |
| APK | app/build/outputs/apk/debug/app-debug.apk |
| 大小 / SHA-256 | 248168526 B / 02b3ddf4a46d26bb9bf71615bc10e3b4a34daa8d172a658590537350e2a1e182 |
| 版本 | versionCode 425 / versionName 1.6.0-beta.50 |
| ABI | 仅 x86_64（-Pabi=x86_64 -Ptarget-platform=android-x64） |
| 安装 | adb -s emulator-5570 install -r -t -d <apk>（-t 必需；-d 因设备上曾有 versionCode 430 的其他工作树包） |
| 设备 | 专用 AVD dsh_flutter_20261116 / emulator-5570 / 1080x2340 |

## 新增与改动（按层）

### 内置 DSH 插件（新增，未 patch 缓存或第三方包）

- app/src/main/assets/android-plugins/turn-rewind/package.json —— dsh.client.platform=web，exports 暴露 ./client。
- app/src/main/assets/android-plugins/turn-rewind/core.js —— 快照/回滚纯逻辑：BlobStore、captureBaseline、diffTurn、planRewind、detectConflicts、restoreFile、readManifests/writeManifest、referencedBlobs。
- app/src/main/assets/android-plugins/turn-rewind/index.js —— 宿主 TurnRewindService，暴露 turnRewind/list 与 turnRewind/rewind；requestId 幂等、同会话串行、冲突不覆盖。
- app/src/main/assets/android-plugins/turn-rewind/client.js —— Web 客户端扩展：气泡动作、编辑横条、分享预览。
- app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt —— 目录插件清单与 overlay insert 段追加 turn-rewind。
- app/src/main/java/app/dsh/mobile/engine/ShareBridge.kt（新增）—— DshAndroidShare.shareText，ACTION_SEND + FileProvider。
- app/src/main/java/app/dsh/mobile/MainActivity.kt —— 注册 DshAndroidShare。

### Flutter

- lib/src/state/message_actions.dart —— 动作编排层，含 planForFresh（点前现查回合表）、撤回/编辑/重新输出/评分/复制/分享，全部带 _disposed 与迟到回包保护。
- lib/src/state/conversation_actions.dart —— ConversationActions 抽象 + StaticConversationActions（测试用，含 failTurnChanges）。
- lib/src/state/turn_plan.dart —— TurnPlan / planForSeq / startSeqOfTurn / describeChanges，撤回与重新输出共用同一口径。
- lib/src/transport/native_action_transport.dart —— TurnChangeSummary / RewindOutcome（冲突路径兼容顶层与 files 嵌套）/ NativeMessageActions 真实 RPC。
- lib/src/ui/chat_shell.dart —— _freshPlan 统一先问 Host 再弹框；三个确认入口都走它。
- lib/src/ui/retract_dialog.dart —— useRootNavigator: false。
- lib/src/ui/share_preview.dart —— 本地预览 + 复制 + 保存 + 系统分享。
- lib/src/ui/engine_session_picker.dart —— 已删除（不再作为普通入口）。

## 验证命令与退出码

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| flutter analyze --no-pub | 0 | No issues found! |
| flutter test --concurrency=1 | 0 | 427 tests passed |
| node --test client.test.mjs core.test.mjs | 0 | 34 / 34 pass |
| node --check client.js | 0 | 语法通过 |
| gradle :app:assembleDebug | 0 | BUILD SUCCESSFUL in 2m 11s |

## 已证实限制

1. 引擎在模拟器上每约 60–80 秒自愈重启（engine exited: status=0 signal=0），界面会被重建；期间原生页可能长时间停在「等待本机引擎就绪」。属引擎/宿主层既有问题，超出本轮授权范围。
2. 偶发降级启动 missing=[directoryPickerController,workspaceRegistry,workspaceController]，冷启后恢复 ok=true。
3. 设备上曾装着其他工作树（E:/tmp/wt-island2，versionCode 430）的 APK，导致插件一度「未进 bundle」。出现同现象先核 dumpsys versionCode。
4. 设备无模型凭据；本轮用设备已配的 gpt-6-luna（用户授权），在线回合均为真实模型请求。
5. 文件改名（rename）与二进制文件回滚只有离线单测，未在设备造真实样例。
6. Web / 原生切换动效只做目视与时序判断，未录逐帧视频。
7. ARM 真机、TalkBack、硬件键盘遍历未验。

## GLM 复核建议重点

1. RewindOutcome.parse 的冲突兼容（flutter_ui/lib/src/transport/native_action_transport.dart）：现在先读 files.conflicts、空则读顶层 conflicts，是否还有第三种形状没覆盖。
2. MessageActions.planForFresh 的失败路径：Host 读不到时不弹框、只提示，是否会让人误以为按钮无响应。
3. ChatShell._freshPlan 与 _regenerate 的重复查询：_regenerate 内部又调了一次 planForFresh，可否复用以免两次 RPC。
4. turn-rewind index.js 的 rewind：先回滚文件再 fork，若 fork 失败则文件已改而会话没分叉——是否要调整顺序或补偿。
5. client.js 与 Flutter 端 describeChanges/planFrom 的文案口径是否仍完全一致。

## 文档索引

- docs/reports/2026-11-17-conversation-parity-plan.md —— 设计与实施计划。
- docs/reports/2026-11-17-conversation-parity-implementation.md —— 实现说明与设计取舍。
- docs/reports/2026-11-17-conversation-parity-validation.md —— 验收记录与证据索引。
- docs/reports/2026-11-17-conversation-parity-status.json —— 机器可读状态（版本/完成项/检查/限制/下一步）。
- docs/reports/2026-11-17-conversation-parity-plugin-test/ —— 插件离线单测。
- AI_CONTEXT.md 顶部 —— 交接摘要。
