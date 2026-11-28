import 'dart:async';
import 'dart:ui' show Tristate;

import 'package:dsh_native_chat/src/model/settings_model.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_page.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

final _last = DateTime(2026, 11, 16);
const _profile = AccountProfile(
  id: 'account-1',
  displayName: '林同学',
  email: 'lin@example.com',
);

UsageHistory _history(List<TokenUsageDay> days, {bool available = true}) =>
    UsageHistory(
      days: days,
      from: DateTime(2026, 8, 25),
      through: _last,
      sourceLabel: '本机会话',
      available: available,
    );

Future<void> _pump(
  WidgetTester tester, {
  AccountProfile? profile,
  UsageHistory? usage,
  VoidCallback? onLogin,
  VoidCallback? onLogout,
  ValueChanged<SettingsDestination>? onOpen,
  Future<void> Function()? onRefresh,
  bool busy = false,
  bool loading = false,
  bool isDemo = false,
  String? error,
  Brightness brightness = Brightness.light,
  DshMood mood = DshMood.mist,
  double scale = 1,
  bool reduceMotion = true,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: brightness == Brightness.light
          ? DshTheme.light(mood: mood)
          : DshTheme.dark(mood: mood),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(
          textScaler: TextScaler.linear(scale),
          disableAnimations: reduceMotion,
        ),
        child: child!,
      ),
      home: SettingsPage(
        profile: profile,
        usage: usage,
        onLogin: onLogin ?? () {},
        onLogout: onLogout ?? () {},
        onOpen: onOpen ?? (_) {},
        onRefresh: onRefresh ?? () async {},
        loading: loading,
        busy: busy,
        error: error,
        isDemo: isDemo,
      ),
    ),
  );
  await tester.pump();
}

Future<void> _tap(WidgetTester tester, String key) async {
  final finder = find.byKey(ValueKey(key));
  await tester.ensureVisible(finder);
  await tester.pump();
  await tester.tap(finder);
  await tester.pump();
}

void main() {
  testWidgets('compact heatmap stays short and keeps cells tappable', (
    tester,
  ) async {
    await _pump(
      tester,
      usage: _history([
        TokenUsageDay(date: _last, inputTokens: 40, outputTokens: 10),
      ]),
    );
    final overview = find.byKey(const ValueKey('settings-usage-overview'));
    // A day panel used to be folded out below a header button; the card now
    // holds a dense grid plus one inline line, so it must stay visibly shorter
    // than the old 12-week panel even with a selected day.
    expect(tester.getSize(overview).height, lessThanOrEqualTo(160));
    final cell = find.byKey(const ValueKey('settings-usage-day-2026-11-16'));
    expect(cell, findsOneWidget);
    expect(tester.getSize(cell).height, lessThanOrEqualTo(24));
    expect(find.text('输入 40'), findsOneWidget);
    await _tap(tester, 'settings-usage-day-2026-11-15');
    expect(find.text('该日暂无记录'), findsOneWidget);
    expect(tester.getSize(overview).height, lessThanOrEqualTo(160));
    // No fold-out control is left behind.
    expect(find.byKey(const ValueKey('settings-usage-expand')), findsNothing);
  });
  testWidgets('signed out profile invokes the supplied login callback', (
    tester,
  ) async {
    var logins = 0;
    await _pump(tester, onLogin: () => logins++);
    expect(find.text('登录 DeepSeek'), findsOneWidget);
    expect(find.text('演示数据'), findsNothing);
    await _tap(tester, 'settings-login');
    expect(logins, 1);
  });

  testWidgets('profile shows supplied identity and signs out via callback', (
    tester,
  ) async {
    var logouts = 0;
    await _pump(tester, profile: _profile, onLogout: () => logouts++);
    expect(find.text(_profile.displayName), findsOneWidget);
    expect(find.text(_profile.email), findsOneWidget);
    await _tap(tester, 'settings-logout');
    expect(logouts, 1);
  });

  testWidgets('signed in layout remains usable at 360dp and 2x text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await _pump(tester, profile: _profile, usage: _history([]), scale: 2);
    expect(tester.takeException(), isNull);
    await _tap(tester, 'settings-logout');
    expect(tester.takeException(), isNull);
  });

  testWidgets('logout is beside the account and usage follows immediately', (
    tester,
  ) async {
    await _pump(tester, profile: _profile, usage: _history([]));
    final name = tester.getRect(find.text(_profile.displayName));
    final logout = tester.getRect(
      find.byKey(const ValueKey('settings-logout')),
    );
    final usage = tester.getRect(find.text('Token 记录'));
    expect(logout.left, greaterThan(name.right));
    expect(logout.center.dy, closeTo(name.center.dy, 24));
    expect(usage.top - logout.bottom, lessThan(80));
  });

  testWidgets(
    'search filters actual destinations and dispatches each enum value',
    (tester) async {
      final opened = <SettingsDestination>[];
      await _pump(tester, onOpen: opened.add);
      for (final destination in SettingsDestination.values) {
        await tester.ensureVisible(
          find.byKey(const ValueKey('settings-search')),
        );
        await tester.enterText(
          find.byKey(const ValueKey('settings-search')),
          destination.title,
        );
        await tester.pump();
        expect(
          find.byKey(ValueKey('settings-destination-${destination.name}')),
          findsOneWidget,
        );
        await _tap(tester, 'settings-destination-${destination.name}');
        expect(opened.last, destination);
      }
      expect(opened, SettingsDestination.values);
      await tester.ensureVisible(find.byKey(const ValueKey('settings-search')));
      await tester.enterText(
        find.byKey(const ValueKey('settings-search')),
        'not-a-setting',
      );
      await tester.pump();
      expect(find.text('没有找到相关设置'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('settings-destination-models')),
        findsNothing,
      );
      await _tap(tester, 'settings-clear-search');
      expect(
        find.byKey(const ValueKey('settings-destination-models')),
        findsOneWidget,
      );
    },
  );

  testWidgets('search also matches enum subtitles', (tester) async {
    await _pump(tester);
    await tester.enterText(
      find.byKey(const ValueKey('settings-search')),
      ' SDK ',
    );
    await tester.pump();
    expect(
      find.byKey(const ValueKey('settings-destination-extensions')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('settings-destination-models')),
      findsNothing,
    );
  });

  testWidgets(
    'settings search reaches detail controls by their visible terms',
    (tester) async {
      await _pump(tester);
      final search = find.byKey(const ValueKey('settings-search'));
      await tester.enterText(search, 'API Key');
      await tester.pump();
      expect(
        find.byKey(const ValueKey('settings-destination-models')),
        findsOneWidget,
      );
      await tester.enterText(search, 'SSH');
      await tester.pump();
      expect(
        find.byKey(const ValueKey('settings-destination-connections')),
        findsOneWidget,
      );
      await tester.enterText(search, '显示大小');
      await tester.pump();
      expect(
        find.byKey(const ValueKey('settings-destination-appearance')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'selecting a supplied day shows real input output and combined totals',
    (tester) async {
      final semantics = tester.ensureSemantics();

      await _pump(
        tester,
        usage: _history([
          TokenUsageDay(
            date: DateTime(2026, 11, 15, 8),
            inputTokens: 123,
            outputTokens: 45,
          ),
          TokenUsageDay(
            date: DateTime(2026, 11, 15, 22),
            inputTokens: 7,
            outputTokens: 5,
          ),
          TokenUsageDay(date: _last, inputTokens: 2, outputTokens: 3),
        ]),
      );
      await _tap(tester, 'settings-usage-day-2026-11-15');
      expect(find.text('2026-11-15'), findsOneWidget);
      expect(find.text('输入 130'), findsOneWidget);
      expect(find.text('输出 50'), findsOneWidget);
      expect(find.text('总量 180'), findsOneWidget);
      final node = tester.getSemantics(
        find.byKey(const ValueKey('settings-usage-day-2026-11-15')),
      );
      expect(node.label, '2026-11-15，输入 130，输出 50，总量 180 Token');
      expect(node.flagsCollection.isSelected == Tristate.isTrue, isTrue);
      expect(node.getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
      semantics.dispose();
    },
  );

  testWidgets(
    'missing days remain unknown while a recorded zero displays zero',
    (tester) async {
      await _pump(
        tester,
        usage: _history([
          TokenUsageDay(date: _last, inputTokens: 0, outputTokens: 0),
        ]),
      );
      await _tap(tester, 'settings-usage-day-2026-11-16');
      expect(find.text('总量 0'), findsOneWidget);
      await _tap(tester, 'settings-usage-day-2026-11-15');
      expect(find.text('该日暂无记录'), findsOneWidget);
      expect(find.text('输入 0'), findsNothing);
      expect(find.text('总量 0'), findsNothing);
    },
  );

  testWidgets(
    'heatmap contains exactly 84 calendar dates in 12 weekly columns',
    (tester) async {
      await _pump(tester, usage: _history([]));
      final days = find.byWidgetPredicate(
        (widget) =>
            widget.key is ValueKey<String> &&
            (widget.key! as ValueKey<String>).value.startsWith(
              'settings-usage-day-',
            ),
      );
      expect(days, findsNWidgets(84));
      expect(
        find.byKey(const ValueKey('settings-usage-day-2026-08-25')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('settings-usage-day-2026-11-16')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('settings-usage-day-2026-08-24')),
        findsNothing,
      );
      final first = tester.getTopLeft(
        find.byKey(const ValueKey('settings-usage-day-2026-08-25')),
      );
      final second = tester.getTopLeft(
        find.byKey(const ValueKey('settings-usage-day-2026-08-26')),
      );
      final nextWeek = tester.getTopLeft(
        find.byKey(const ValueKey('settings-usage-day-2026-09-01')),
      );
      expect(second.dx, first.dx);
      expect(second.dy, greaterThan(first.dy));
      expect(nextWeek.dx, greaterThan(first.dx));
      expect(nextWeek.dy, first.dy);
    },
  );

  testWidgets(
    'unavailable usage does not render supplied stale numbers as current',
    (tester) async {
      await _pump(
        tester,
        usage: _history([
          TokenUsageDay(date: _last, inputTokens: 999, outputTokens: 1),
        ], available: false),
      );
      expect(find.text('暂无 Token 记录'), findsOneWidget);
      expect(find.textContaining('1,000'), findsNothing);
      expect(find.text('总量 0'), findsNothing);
    },
  );

  testWidgets('demo provenance is opt in', (tester) async {
    await _pump(tester, isDemo: true);
    expect(find.text('演示数据'), findsOneWidget);
    await _pump(tester);
    expect(find.text('演示数据'), findsNothing);
  });

  testWidgets('busy state protects login logout refresh and destinations', (
    tester,
  ) async {
    var calls = 0;
    await _pump(
      tester,
      busy: true,
      onLogin: () => calls++,
      onLogout: () => calls++,
      onOpen: (_) => calls++,
      onRefresh: () async {
        calls++;
      },
    );
    await _tap(tester, 'settings-login');
    await _tap(tester, 'settings-refresh');
    await tester.ensureVisible(find.byKey(const ValueKey('settings-search')));
    await tester.enterText(
      find.byKey(const ValueKey('settings-search')),
      '模型配置',
    );
    await tester.pump();
    await _tap(tester, 'settings-destination-models');
    expect(calls, 0);
    await _pump(tester, profile: _profile, busy: true, onLogout: () => calls++);
    await _tap(tester, 'settings-logout');
    expect(calls, 0);
  });

  testWidgets('refresh serializes attempts and recovers from failure', (
    tester,
  ) async {
    var calls = 0;
    final pending = Completer<void>();
    await _pump(
      tester,
      onRefresh: () {
        calls++;
        return calls == 1 ? pending.future : Future<void>.value();
      },
    );
    await _tap(tester, 'settings-refresh');
    await _tap(tester, 'settings-refresh');
    expect(calls, 1);
    pending.completeError(StateError('backend unavailable'));
    await tester.pump();
    await tester.pump();
    expect(find.text('刷新失败，请稍后重试'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await _tap(tester, 'settings-refresh');
    await tester.pump();
    expect(calls, 2);
    expect(find.text('刷新失败，请稍后重试'), findsNothing);
  });

  testWidgets('invalid avatar schemes use the local fallback', (tester) async {
    await _pump(
      tester,
      profile: AccountProfile(
        id: 'unsafe',
        displayName: '头像',
        avatarUrl: Uri.parse('file:///private/avatar.png'),
      ),
    );
    expect(find.byType(Image), findsNothing);
    expect(
      find.byKey(const ValueKey('settings-avatar-fallback')),
      findsOneWidget,
    );
  });

  testWidgets('back at the root leaves settings visible', (tester) async {
    await _pump(tester);
    await _tap(tester, 'settings-back');
    expect(find.byType(SettingsPage), findsOneWidget);
  });

  for (final mood in DshMood.values) {
    for (final brightness in Brightness.values) {
      testWidgets(
        '360px 2x text scrolls without overflow: ${mood.name} ${brightness.name}',
        (tester) async {
          tester.view.physicalSize = const Size(360, 800);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          await _pump(
            tester,
            mood: mood,
            brightness: brightness,
            scale: 2,
            profile: const AccountProfile(
              id: 'long',
              displayName: '很长很长的个人资料显示名称',
              email: 'very-long-email-address@example.invalid',
            ),
            usage: _history([
              TokenUsageDay(
                date: _last,
                inputTokens: 1234567890,
                outputTokens: 9000000,
              ),
            ]),
            error: '连接暂时不可用，请检查网络后刷新重试',
            loading: true,
          );
          expect(tester.takeException(), isNull);
          await _tap(tester, 'settings-usage-day-2026-11-16');
          expect(tester.takeException(), isNull);
          await tester.ensureVisible(
            find.byKey(const ValueKey('settings-destination-about')),
          );
          await tester.pump();
          expect(tester.takeException(), isNull);
          final row = tester.getSize(
            find.byKey(const ValueKey('settings-destination-about')),
          );
          expect(row.height, greaterThanOrEqualTo(56));
          expect(find.byType(CircularProgressIndicator), findsNothing);
          expect(find.byType(LinearProgressIndicator), findsNothing);
        },
      );
    }
  }
}
