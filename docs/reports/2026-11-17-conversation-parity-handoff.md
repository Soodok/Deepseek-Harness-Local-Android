# 会话平权交接（2026-11-18，beta.52）

## 一句话状态

七个消息动作（复制/撤回/编辑/点赞/踩/重新输出/分享）在 Android 应用内 WebView 与 Flutter 原生两端均已完成实机点验，全部读写 DSH 权威后端；文件回滚的新建/修改/删除/改名/二进制/外部冲突六种场景都有设备级真实样例；撤回执行顺序已改为先行 fork、成功后才还原文件（fork 失败不再造成不可见改动）；失败原因已改为可读文案（不再把 session-busy 这类错误码丢给用户）。自动化门禁 429 项 Flutter 测试 + 39 项插件测试 + analyze + Gradle 全绿。

## 交付物

| 项 | 值 |
| --- | --- |
| 仓库 | E:/tmp/wt-flutter-native-chat（分支 feat/flutter-native-chat，基线 556ef615ac5162347ea205fd602301622d99d6f1） |
| APK | app/build/outputs/apk/debug/app-debug.apk |
| 大小 / SHA-256 | 248173894 B / e1886d94b3443f1feb042c836247d5fc4908ff1350666f96161d5d1de72f3cd9 |
| 版本 | versionCode 427 / versionName 1.6.0-beta.52 |
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
| flutter test --concurrency=1 | 0 | 429 tests passed（/tmp/ft_b52.log） |
| node --test client.test.mjs core.test.mjs | 0 | 39 / 39 pass |
| node --check client.js core.js index.js | 0 | 语法通过 |
| gradle :app:assembleDebug（beta.52） | 0 | BUILD SUCCESSFUL in 1m 31s |
| gradle :app:assembleDebug（beta.77） | 0 | BUILD SUCCESSFUL in 3m 2s；APK sha256 0b25d48f93f80fef13f9abe2464c9e640894398d76f4e466165e405cd2fb5afe，248183354 B，versionCode 452 |

## 已证实限制

1. 引擎在模拟器上每约 60–80 秒自愈重启（engine exited: status=0 signal=0），界面会被重建；期间原生页可能长时间停在「等待本机引擎就绪」。属引擎/宿主层既有问题，超出本轮授权范围。
2. 偶发降级启动 missing=[directoryPickerController,workspaceRegistry,workspaceController]，冷启后恢复 ok=true。
3. 设备上曾装着其他工作树（E:/tmp/wt-island2，versionCode 430）的 APK，导致插件一度「未进 bundle」。出现同现象先核 dumpsys versionCode。
4. 设备无模型凭据；本轮用设备已配的 gpt-6-luna（用户授权），在线回合均为真实模型请求。
5. 文件回滚已在设备造出六种真实样例（新建/修改/删除/改名/二进制/外部冲突，见 validation 3.2）；仍缺「权限不足 / 磁盘配额 / 部分失败重试」的真实故障注入，这部分只有离线单测。
6. Web / 原生切换动效已录逐帧视频（beta.77，k34.mp4）：入场上不再是硬切，屏幕上真的有 9 级可见过渡（亮度 32.0→65.4→83.3→117.4→149.9→167.1→183.5→200.5→218.1→233.8，约 15.88s→16.91s）。实现方式与注意点见 validation 3.8。仍存在的边界：
   - 淡化只作用于「原生页入场」这一段。Web 那一侧的退场是 WebView 切后台被系统停掉，没有可用的退场动画，屏幕上那一两秒仍是最后一张 Web 画面（不是黑，也不是渐变）——这是与 Claude 那种双端同时交叉的差别，如实记录。
   - 淡完后露出的不一定是会话页：冷启动时 Flutter 先画「本机引擎 / 正在准备对话」，会话页要再等几秒才换上（k34.mp4 帧 192 是淡完的占位页，帧 199 才是带消息的会话页）。淡化解决的是「不要一帧硬切」，不是「一次直接切到会话页」。
   - 冷启动帧率只有 3–5 帧/秒，12 步淡化的时间分辨率就是「每步 200–400ms」，过渡是分级的、不平滑；系统「减少动效」打开时按设计直接跳过（k30.mp4：32.0→233.8 单帧）。
   - 淡化依赖主界面在点击那一刻用 PixelCopy 拍到的那一屏；拍图失败（SDK<26 或异常）时退回原来的窗口 alpha 阶梯，观感不如底图方案。
7. ARM 真机、TalkBack、硬件键盘遍历未验。
8. 其余已知失败码的可读文案只有离线单测，设备上只实测了 session-busy。

## GLM 复核建议重点

1. RewindOutcome.parse 的冲突兼容（flutter_ui/lib/src/transport/native_action_transport.dart）：现在先读 files.conflicts、空则读顶层 conflicts，是否还有第三种形状没覆盖。
2. MessageActions.planForFresh 的失败路径：Host 读不到时不弹框、只提示，是否会让人误以为按钮无响应。
3. ChatShell._freshPlan 与 _regenerate 的重复查询：_regenerate 内部又调了一次 planForFresh，可否复用以免两次 RPC。
4. ~~rewind 先回滚文件再 fork~~ **beta.51 已修**：改为「冲突预检（只读）→ fork → 还原文件」，顺序由 core.rewindForkFirst 承担并有三例单测锁定。复核时请确认：①fork 成功后还原过程中若某文件失败，此时会话已分叉而文件只回滚了一部分，界面是否把 skipped 清单讲清楚；②是否还需要给「fork 成功但还原失败」补一条补偿/提示路径。
5. client.js 与 Flutter 端 describeChanges/planFrom 的文案口径是否仍完全一致。

## 文档索引

- docs/reports/2026-11-17-conversation-parity-plan.md —— 设计与实施计划。
- docs/reports/2026-11-17-conversation-parity-implementation.md —— 实现说明与设计取舍。
- docs/reports/2026-11-17-conversation-parity-validation.md —— 验收记录与证据索引。
- docs/reports/2026-11-17-conversation-parity-status.json —— 机器可读状态（版本/完成项/检查/限制/下一步）。
- docs/reports/2026-11-17-conversation-parity-plugin-test/ —— 插件离线单测。
- AI_CONTEXT.md 顶部 —— 交接摘要。
