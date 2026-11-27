import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/main.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/glass_panel.dart';

// An open drawer contains a deliberate continuous marquee. Settle finite
// route/theme transitions with bounded frames instead of waiting for all tickers.
Future<void> settleUi(WidgetTester tester) async {
  await tester.pump();
  for (var i = 0; i < 6; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

/// 外观与自然配色只剩「设置 · 个性化主题」一个入口。
///
/// 抽屉里原先那两个下拉选择器已经拆除：同一个开关有两个入口，两边显示的
/// 当前值迟早会不一致。用例因此走真实路径——抽屉 → 账号菜单 → 设置 → 个性化主题。
Future<void> openAppearance(WidgetTester tester) async {
  await tester.tap(find.byTooltip('打开会话抽屉'));
  await settleUi(tester);
  await tester.tap(find.byKey(const ValueKey('account-menu')));
  await settleUi(tester);
  await tester.tap(find.text('设置'));
  await settleUi(tester);
  final entry = find.byKey(const ValueKey('settings-destination-appearance'));
  await tester.ensureVisible(entry);
  await settleUi(tester);
  await tester.tap(entry);
  await settleUi(tester);
}

Future<void> pickTheme(WidgetTester tester, ThemeMode mode) async {
  final row = find.byKey(ValueKey('settings-theme-' + mode.name));
  await tester.ensureVisible(row);
  await settleUi(tester);
  await tester.tap(row);
  await settleUi(tester);
}

/// 关掉个性化页与设置页，回到对话本身。
///
/// 断言必须在对话页面上做：设置页自己也有 Scaffold，留在那层会让
/// find.byType(Scaffold) 命中多个，也会把「切外观后聊天有没有重绘」
/// 这个真正的问题替换成「设置页有没有重绘」。
Future<void> backToChat(WidgetTester tester) async {
  await tester.pageBack();
  await settleUi(tester);
  final back = find.byKey(const ValueKey('settings-back'));
  // The page keeps its scroll offset, and the appearance row sits well below
  // the header, so the back action has to be scrolled into view first.
  await tester.ensureVisible(back);
  await settleUi(tester);
  await tester.tap(back);
  await settleUi(tester);
}

void main() {
  testWidgets('system appearance updates an already open runtime panel', (
    tester,
  ) async {
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.light;
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
    await tester.pumpWidget(const DshApp(preview: true));
    await settleUi(tester);
    await openAppearance(tester);
    await pickTheme(tester, ThemeMode.system);
    await backToChat(tester);
    await tester.tap(find.byKey(const ValueKey('runtime-selector')));
    await settleUi(tester);
    final panels = find.byType(GlassPanel);
    // The real material is the pane's own fill over a blurred backdrop, so the
    // light/dark assertion reads that fill instead of a package settings object.
    Color surfacePaint() {
      final pane = find
          .descendant(of: panels.last, matching: find.byType(DecoratedBox))
          .first;
      final decoration =
          tester.widget<DecoratedBox>(pane).decoration as BoxDecoration;
      return decoration.color!;
    }

    expect(Theme.of(tester.element(panels.last)).brightness, Brightness.light);
    expect(surfacePaint().computeLuminance(), greaterThan(.8));
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    await settleUi(tester);
    expect(Theme.of(tester.element(panels.last)).brightness, Brightness.dark);
    expect(surfacePaint().computeLuminance(), lessThan(.1));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  test('light theme uses real light material surfaces', () {
    final theme = DshTheme.light();
    expect(theme.brightness, Brightness.light);
    expect(theme.scaffoldBackgroundColor.computeLuminance(), greaterThan(.8));
  });
  testWidgets(
    'appearance switch repaints the chat without losing an edited draft',
    (tester) async {
      await tester.pumpWidget(const DshApp(preview: true));
      await settleUi(tester);
      final shell = tester.state(find.byType(ChatShell));
      await tester.enterText(
        find.byKey(const ValueKey('composer-input')),
        '保留正在编辑的内容',
      );
      await openAppearance(tester);
      await pickTheme(tester, ThemeMode.light);
      await backToChat(tester);
      expect(
        Theme.of(tester.element(find.byKey(const ValueKey('floating-header'))))
            .brightness,
        Brightness.light,
      );
      expect(tester.state(find.byType(ChatShell)), same(shell));
      expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('composer-input')))
            .controller!
            .text,
        '保留正在编辑的内容',
      );
      expect(
        tester
            .widget<Scaffold>(find.byType(Scaffold))
            .backgroundColor!
            .computeLuminance(),
        greaterThan(.8),
      );
      await openAppearance(tester);
      await pickTheme(tester, ThemeMode.dark);
      await backToChat(tester);
      expect(
        Theme.of(tester.element(find.byKey(const ValueKey('floating-header'))))
            .brightness,
        Brightness.dark,
      );
      expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('composer-input')))
            .controller!
            .text,
        '保留正在编辑的内容',
      );
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
