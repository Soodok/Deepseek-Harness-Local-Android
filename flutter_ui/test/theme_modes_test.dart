import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/main.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/glass_panel.dart';

void main() {
  testWidgets('system appearance updates an already open runtime panel', (
    tester,
  ) async {
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.light;
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
    await tester.pumpWidget(const DshApp());
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('打开会话抽屉'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('theme-selector')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('theme-system')));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('关闭会话抽屉'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('runtime-selector')));
    await tester.pumpAndSettle();
    final panels = find.byType(GlassPanel);
    Color surfacePaint() {
      final decorations = tester
          .widgetList<DecoratedBox>(
            find.descendant(
              of: panels.last,
              matching: find.byType(DecoratedBox),
            ),
          )
          .map((w) => w.decoration)
          .whereType<BoxDecoration>();
      return (decorations.firstWhere((d) => d.gradient != null).gradient!
              as LinearGradient)
          .colors
          .first;
    }

    expect(Theme.of(tester.element(panels.last)).brightness, Brightness.light);
    expect(surfacePaint().computeLuminance(), greaterThan(.8));
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    await tester.pumpAndSettle();
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
      await tester.pumpWidget(const DshApp());
      await tester.pumpAndSettle();
      final shell = tester.state(find.byType(ChatShell));
      await tester.enterText(
        find.byKey(const ValueKey('composer-input')),
        '保留正在编辑的内容',
      );
      await tester.tap(find.byTooltip('打开会话抽屉'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('theme-selector')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('theme-light')));
      await tester.pumpAndSettle();
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
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('theme-dark')));
      await tester.pumpAndSettle();
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
