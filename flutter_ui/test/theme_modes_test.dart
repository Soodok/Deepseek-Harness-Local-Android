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

void main() {
  testWidgets('system appearance updates an already open runtime panel', (
    tester,
  ) async {
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.light;
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
    await tester.pumpWidget(const DshApp(preview: true));
    await settleUi(tester);
    await tester.tap(find.byTooltip('打开会话抽屉'));
    await settleUi(tester);
    await tester.tap(find.byKey(const ValueKey('theme-selector')));
    await settleUi(tester);
    await tester.tap(find.byKey(const ValueKey('theme-system')));
    await settleUi(tester);
    await tester.tap(find.byTooltip('关闭会话抽屉'));
    await settleUi(tester);
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
      await tester.tap(find.byTooltip('打开会话抽屉'));
      await settleUi(tester);
      await tester.tap(find.byKey(const ValueKey('theme-selector')));
      await settleUi(tester);
      await tester.tap(find.byKey(const ValueKey('theme-light')));
      await settleUi(tester);
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
      await tester.tap(find.byKey(const ValueKey('theme-selector')));
      await settleUi(tester);
      await tester.tap(find.byKey(const ValueKey('theme-dark')));
      await settleUi(tester);
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
