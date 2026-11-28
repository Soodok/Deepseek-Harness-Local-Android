# Composer 控件尺寸调整报告

日期：2026-11-20
范围：仅隔离工作树 E:/tmp/wt-flutter-native-chat；未修改主仓 E:/Deepeek harness/dsh-android。

## 实现

- flutter_ui/lib/src/ui/composer.dart: 权限圆面由 34dp 调整为 38dp，权限 glyph 由 18dp 调整为 20dp；权限与发送/停止槽位间距由 8dp 调整为 4dp。
- flutter_ui/lib/src/ui/composer_surface.dart: 发送与停止圆面由 36dp 调整为 40dp，箭头/停止 glyph 由 20dp 调整为 21dp。两个 IconButton 的 minimumSize 仍使用 DshDisplayScope.targetSize(context)，48dp 最小点击目标未改。
- flutter_ui/lib/src/ui/subagent_panel.dart: 未修改。子会话复用 ComposerActionButton，因此发送/停止尺寸自动与主会话一致；该文件没有独立同类圆面。
- flutter_ui/test/composer_action_matrix_test.dart: 增加实际渲染尺寸断言：发送与停止圆面均 40x40、发送与停止 glyph 均 21、权限圆面 38x38、权限 glyph 20、权限及发送触控目标至少 48dp。

## 验证

基线（修改前）：
- \/e\/tools\/flutter\/bin\/flutter.bat test --no-pub --concurrency=1 test/composer_controls_test.dart test/composer_action_matrix_test.dart
- 14 项通过，退出码 0。

修改后：
- \/e\/tools\/flutter\/bin\/dart.bat format lib/src/ui/composer.dart lib/src/ui/composer_surface.dart lib/src/ui/subagent_panel.dart test/composer_action_matrix_test.dart
- 输出：Formatted 4 files (1 changed)，退出码 0。
- \/e\/tools\/flutter\/bin\/flutter.bat test --no-pub --concurrency=1 test/composer_controls_test.dart test/composer_action_matrix_test.dart test/subagent_panel_test.dart
- 输出：All tests passed!，37 项通过，退出码 0（含新增停止态几何断言）。
- \/e\/tools\/flutter\/bin\/flutter.bat analyze --no-pub
- 输出：No issues found!，退出码 0（50.6s）。

未执行：Gradle、设备安装/截图、全量测试；按任务要求未触碰业务逻辑、旧 edgeblur、Android 原生文件或 pubspec。
