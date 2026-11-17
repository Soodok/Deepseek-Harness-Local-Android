import 'package:dsh_native_chat/src/model/settings_model.dart';
import 'package:dsh_native_chat/src/theme/display_scale.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_detail_pages.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_page.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// Guards the settings surfaces the review called out: rows keep a real 48dp
/// target after the compact canvas, small labels stay readable, and the
/// personalization copy describes the effect instead of the implementation.

Future<void> _pump(
  WidgetTester tester,
  Widget page, {
  DshDisplaySize size = DshDisplaySize.compact,
  ValueChanged<DshDisplaySize>? onChanged,
}) async {
  tester.view.physicalSize = const Size(432, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    DshDisplayScope(
      size: size,
      onChanged: onChanged,
      child: MaterialApp(
        theme: DshTheme.light(displayFactor: size.factor),
        builder: (context, child) =>
            DshDisplayCanvas(child: child ?? const SizedBox.shrink()),
        home: page,
      ),
    ),
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 50));
}

SettingsPage _settings() => SettingsPage(
  profile: null,
  usage: UsageHistory(
    days: const [],
    from: DateTime(2026, 1, 1),
    through: DateTime(2026, 1, 7),
  ),
  onLogin: () {},
  onLogout: () {},
  onOpen: (_) {},
  onRefresh: () async {},
);

void main() {
  testWidgets('display and theme rows keep a real 48dp target at compact', (
    tester,
  ) async {
    await _pump(tester, const PersonalizationPage(), onChanged: (_) {});
    // Painted global heights, not widget fields: the canvas draws 52 logical
    // units as 46.8dp at the compact size, which is below the touch floor.
    for (final key in [
      'settings-display-compact',
      'settings-display-standard',
      'settings-display-relaxed',
      'settings-theme-system',
      'settings-theme-light',
      'settings-theme-dark',
    ]) {
      final rect = tester.getRect(find.byKey(ValueKey(key)));
      expect(rect.height, greaterThanOrEqualTo(48), reason: key);
    }
  });

  testWidgets('settings group titles and row sublabels stay at 12sp or more', (
    tester,
  ) async {
    await _pump(tester, _settings());
    for (final label in ['运行与能力', '模型与服务提供商']) {
      final text = tester.widget<Text>(find.text(label).first);
      expect(text.style?.fontSize, greaterThanOrEqualTo(12), reason: label);
    }
  });

  testWidgets('the search hint never paints below the 12sp floor', (
    tester,
  ) async {
    await _pump(tester, _settings());
    // The placeholder has no size of its own: it merges the field's 15sp style,
    // so it already clears the floor and this guards a future shrink to 11.
    final hint = tester.widget<Text>(find.text('搜索设置'));
    expect(hint.style?.fontSize, greaterThanOrEqualTo(12));
  });

  testWidgets('the display size explanation names the effect, not the code', (
    tester,
  ) async {
    await _pump(tester, const PersonalizationPage(), onChanged: (_) {});
    expect(find.textContaining('Flutter'), findsNothing);
    expect(find.textContaining('几何'), findsNothing);
    expect(find.textContaining('不影响系统设置'), findsOneWidget);
    expect(find.textContaining('系统字体大小'), findsOneWidget);
  });

  testWidgets('without a persistence hook the group says it is unsupported', (
    tester,
  ) async {
    await _pump(tester, const PersonalizationPage());
    final tile = tester.widget<ListTile>(
      find.byKey(const ValueKey('settings-display-compact')),
    );
    expect(tile.onTap, isNull);
    expect(find.textContaining('不支持'), findsOneWidget);
    // The row is disabled, so the copy cannot promise a switch that this
    // environment does not have.
    expect(find.textContaining('本次运行'), findsNothing);
  });
}
