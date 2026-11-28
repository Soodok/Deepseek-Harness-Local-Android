# 原生聊天二轮反馈：DeepSeek 实施与快速验收

仅在 `E:/tmp/wt-flutter-native-chat` 的 `feat/flutter-native-chat` / `29a4e57d` 上接手。先看 `git diff HEAD`、未跟踪文件和 `AI_CONTEXT.md` 顶部。保留全部 WIP。禁止 reset、stash、clean、提交、推送或操作主仓。以下步骤从现有实现开始，已经满足的行为只做回归。

## 1. 工具展开高度

截图中单命令先显示组标题，再显示命令。工作树 `flutter_ui/lib/src/ui/conversation_flow.dart` 的 `addGroup` 已仅为多个工具显示组标题。普通工具和结构化命令标题均已用 12sp。剩余高度来自 `tool_process_card.dart` 的 `_TextOutput` 按字段各建一个复制按钮，以及 `tool_run_card.dart` 对任何非空结果都单列“查看完整输出”。工具每多一个单行字段，就可能再占一个触控行。

- [ ] 先在 `conversation_tool_stack_test.dart` 和 `tool_process_card_test.dart` 固定样例：单个已完成命令，`pwd`、短 `cwd`、单行 `stdout`，默认字号，393dp 宽。用 `tester.getRect` 记录整个工具展开高度和相邻值的间隔。再加普通工具一行结果、窄屏无换行长输出、失败 `stderr` 三种用例。
- [ ] 只改聊天流的 `compact` 分支。将短字段排成“标签 + 值”的自然文本行，窄屏允许换行。把字段操作收进标题右侧一个有语义标签的 48dp 菜单，保留复制指定字段、复制全文及完整详情入口。完整页复用已有实现，传原始文本。紧凑普通工具的短结果可在原位读完，长结果按实际可见行数处理软换行。失败优先露出错误，原始参数和结果保留可查。
- [ ] 建议设备验收目标：同一夹具、同一字号下，短命令整个展开块比本轮 WIP 基线至少矮 25%；单工具一步看结果；相邻可点行各有 ≥48dp 独立命中区。用 tap 测试菜单和折叠互不误触。复验多工具、搜索命中、长输出复制至末尾、审批、普通消息与大字号。把重复复制按钮的旧断言替换成等价的操作结果断言。

## 2. 欢迎页与语言

`flutter_ui/lib/src/ui/message_list.dart` 当前品牌为 60×45，四卡为 132dp、18dp 圆角、16sp、2×2，使用 `GlassPanel`。`chat_strings.dart` 已按 ID 翻译四种内置模式；英文胶囊、菜单、自定义模式、未选和切换失败均已有用例。DSH Go 对照可在 `E:/tmp/dshgo-probe` 执行 `git show HEAD:app/lib/l10n/app_zh.arb` 和 `git show HEAD:app/lib/l10n/app_en.arb`，检索 `featuresPresetStandard`、`featuresPresetPtc`、`featuresPresetMinimal`、`featuresPresetCreator`。

- [ ] 以截图为基准，采用一个主视觉区域：品牌和问候语紧邻、四卡保持原规格、压轻重复描边和阴影。将两个选择器对齐卡片网格，当作次级操作；胶囊优先展示短值（如“标准”、目录名），语义标签和菜单展示完整含义、完整目录名称。窄屏、长译名或大字号时上下排列。先对比同尺寸前后图再调整间距。
- [ ] 让胶囊、菜单、提示和失败消息都走同一应用语言。内置显示名对照 DSH Go，自定义名称保留 Host 原文，选中后 `agentPresets/select` 仍发送原始 ID。复核实际 Host 拒绝时的提示来源，避免 UI 和桥同时弹出两条不同语言的提示。
- [ ] 在 `welcome_layout_test.dart` 验证中文、英文和一种长译名，含运行中切换语言、长目录名、393×851、360×640、键盘及两倍字号。卡片点击只填草稿。分别截图正常和紧凑首屏。问候语与四卡草稿模板的全面翻译可单独排期。

## 3. 工作区记忆

`flutter_ui/lib/src/ui/native_workspace_picker.dart` 的菜单已只列 Home、已有 Host 组、私有目录和系统目录。错误时“重新读取工作区”渲染在菜单外。`engine_sessions_native.dart` 已接通两种目录入口、持久选择与新建前复验。无需重写 Android 授权桥。

- [ ] 正常入口只显示当前选择。首次偏好确认为空时显示 Home。读取失败且尚无已确认值时显示“工作区暂不可用”并允许重选，别给 Home 画选中勾。重连由现有机制处理，必要的重试只在故障面板中提供，操作名为“重试”。保留用户显式选择 Home 的恢复路径。
- [ ] 菜单选择只保存本 Host 数据域的后续新建目标。已有会话和草稿保持原样。选择 Home 后后续普通新建走 Host Home；抽屉的组内 `+` 仅指定该次创建。保留 `workspace/create` 路径匹配、新 `workspace/follow` 帧的相同 ID/路径确认、授权保存、偏好写入回读，以及创建前目录复验。授权失效时提示重新授权或选私有目录；禁止将 `content://` 当路径或自动扩大权限。
- [ ] 快验序列：记下当前会话 → 新建并选择私有目录 → 见到真实 Host 组且会话数不变 → 明确新建两次 → 重启后再新建 → 每次核实 `request.workspaceId` 和会话实际 `pwd` → 写入并读回唯一的临时探针。再选 Home 验证后续普通新建回到 Home。共享目录单独走系统选择器，复验取消、失权和路径不符时保持前选且没有错误的 `session/create`。

## 验证与交付

11 月 27 日本轮从 `flutter_ui` 串行执行以下基线，结果 `+75: All tests passed!`，其中工具栈 15 项、欢迎页 10 项、EngineHome 50 项。该结果属于工作树。此前 beta.140 的设备记录可作对照，本轮 UI 仍需新包装机截图。

```powershell
E:/tools/flutter/bin/flutter.bat test --no-pub --concurrency=1 --reporter expanded test/conversation_tool_stack_test.dart test/welcome_layout_test.dart test/engine_home_test.dart
```

实施后按序执行上述定向测试，补跑 `tool_process_card_test.dart`、`tool_performance_test.dart`、`inline_approval_visibility_test.dart`、`native_workspace_directory_ui_test.dart`、`native_workspace_directories_test.dart`、`native_workspace_policy_test.dart` 和 `chat_screen_test.dart`，再跑 `flutter analyze --no-pub` 和 `flutter test --no-pub --concurrency=1`。单独保存各阶段结果。

出包前回读工作树及专用设备版本。工作树当前 `1.6.0-beta.140 / 515`，若届时仍为此基线，下一包用 `1.6.0-beta.141 / 516`。用根工程构建 `:app:assembleDebug -Pabi=x86_64 -Ppackage=noruntime --no-daemon -Pkotlin.incremental=false -Pkotlin.incremental.useClasspathSnapshot=false`。在 `E:/Android/Sdk/platform-tools/adb.exe -s emulator-5570 emu avd name` 返回 `dsh_flutter_20261116` 后，才在该设备执行 `install -r -t`，保留数据。禁止使用 `emulator-5554`。记录实际 APK 路径、版本、测试数、前后高度和截图、私有/共享验收结果与未验证项，更新 `AI_CONTEXT.md`，窗口保持可见。
