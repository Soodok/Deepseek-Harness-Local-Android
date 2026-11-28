# 文件交付卡 UI 实现报告

日期：2026-11-20。工作树：`E:/tmp/wt-flutter-native-chat`；本轮不构建 Gradle、不操作设备、不提交。

## 文件与接口

- `flutter_ui/lib/src/ui/conversation_flow.dart`：向 `ConversationFlow` 和 `ConversationMessage` 透传可选 `onFileTap`、`onFileShare`；仅 assistant 正文下附加入口，原 `SelectableText` 不变。保留该文件既有 floating chrome padding 改动。
- `flutter_ui/lib/src/ui/file_delivery_card.dart`：`FileDelivery(displayName, target)`；`FileDeliveryTap = void Function(FileDelivery, FileDeliveryAction)`，其中 action 为 `preview` / `openExternal`；`FileDeliveryShare = void Function(FileDelivery)`。无 callback 的对应菜单项禁用；取消不调用 callback。Lead 需从外层注入回调并对接数据服务。
- `flutter_ui/test/file_delivery_card_test.dart`：7 项组件测试。

## 视觉与交互

- 卡片使用 `DshPalette.paperDeep`、`line`、`ink`、`inkSoft`，圆角 8dp、边框 1dp；文字 14dp 半粗，类型与“打开选项” 12dp，图标 20dp。复用主题令牌，让入口次于 17dp 正文。
- 行内边距水平 12dp、垂直 8dp；图标与文字间隔 12dp，卡片之间或正文后间隔 8dp。行高度通过 `DshDisplayScope.minTargetOf` 保证真实触控目标至少 48dp，兼容 compact 画布。
- 弹层沿用既有分享面板的 `paper` 背景、顶部 24dp 圆角与 SafeArea，内边距 16/16/16/12dp，标题 16dp 半粗；使用 Material ListTile 和按钮提供点击反馈、文字语义与 ValueKey。无额外自定义动画，沿用 Flutter 底部弹层默认动效。
- 简单内联 Markdown `[显示名](目标)` 支持相对路径、Unix/Windows 绝对路径、`dsh-resource://file/`；一般目标必须带扩展名，排除 HTTP(S) 等 URI 和图片语法。当前不支持包含空格或括号的目标、Markdown 标题、转义及代码块语义；此为轻量识别边界，不是完整 Markdown 解析器。

## 验证

- `flutter test --no-pub --concurrency=1 test/file_delivery_card_test.dart test/conversation_flow_test.dart test/subagent_panel_test.dart test/chat_screen_test.dart`：EXIT=0，36 项通过，含文件链接、相对/绝对路径、普通正文、原文保留、三操作回调、取消、空 callback、320dp 深色 2 倍字号、compact 档真实触控高度测试。
- `dart format --output=none --set-exit-if-changed lib/src/ui/conversation_flow.dart lib/src/ui/file_delivery_card.dart test/file_delivery_card_test.dart`：EXIT=0，0 文件需修改。
- `dart analyze lib/src/ui/conversation_flow.dart lib/src/ui/file_delivery_card.dart test/file_delivery_card_test.dart`：EXIT=0，No issues found。
- `flutter analyze --no-pub`：本次运行 EXIT=1，唯一报告为并行开发文件 `lib/src/transport/native_file_delivery_service.dart:83:72` 的 nullable receiver `.cast`；已通知 Lead，未修改其文件。该结果仅对应当时快照。
- 初次测试在实现前因新类型与文件不存在而编译失败；实现后曾检出插值错误和菜单水波被背景遮挡，修正后上述测试通过。未做撤除实现的负对照。

## 自检与边界

- 已核对：原正文保留、card 与菜单交互、禁用态、取消、标签语义、320dp 深色 2 倍字号无测试异常、compact 实际目标高度不低于 48dp。
- 待确认：真实设备截图、TalkBack、全部主题对比、系统字体及应用缩放组合、动效时间、服务与 Android bridge 的真实预览/打开/分享链路。没有真机或截图证据，不将 widget 测试视为视觉验收。
- 复现：在 `flutter_ui` 目录执行上述测试；真实界面须由 Lead 接好回调后在现有 Flutter 原生聊天页打开含文件链接的 assistant 消息。
