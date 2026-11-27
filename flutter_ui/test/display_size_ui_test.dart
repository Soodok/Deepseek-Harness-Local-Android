import 'dart:async';

import 'package:dsh_native_chat/main.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/turn_notice.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/theme/display_scale.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/conversation_flow.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _channel = MethodChannel('app.dsh.mobile/native_chat');

class _Harness {
  _Harness({this.saved = 'standard'});
  String? saved;
  int reads = 0;
  final writes = <String>[];
  bool failWrites = false;
  Completer<void>? readGate;

  void install(WidgetTester tester) {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, (
      call,
    ) async {
      switch (call.method) {
        case 'readAppearance':
          return {'mode': 'dark', 'mood': 'mist'};
        case 'readDisplaySize':
          reads++;
          if (readGate != null) await readGate!.future;
          return saved;
        case 'writeDisplaySize':
          final size = (call.arguments as Map)['size'] as String;
          writes.add(size);
          if (failWrites) return false;
          saved = size;
          return true;
        case 'writeAppearance':
          return true;
      }
      return null;
    });
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        _channel,
        null,
      ),
    );
  }
}

DshDisplayScope _scope(WidgetTester tester) =>
    tester.widget<DshDisplayScope>(find.byType(DshDisplayScope));

/// Pumps real frames instead of pumpAndSettle: the shell keeps a fixture stream
/// and a host poll alive, so "settled" never happens by design.
Future<void> _settle(WidgetTester tester, {int frames = 6}) async {
  await tester.pump();
  for (var i = 0; i < frames; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

/// The only real entry to the settings page: drawer, account menu, 设置.
Future<void> _openSettings(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('session-menu')));
  await _settle(tester);
  await tester.tap(find.byKey(const ValueKey('account-menu')));
  await _settle(tester);
  await tester.tap(find.text('设置').last);
  await _settle(tester, frames: 10);
}

/// Mounts the real app at [stored], then measures one geometry per surface:
/// a settings destination row (pushed route), a model row inside the modal
/// bottom sheet (overlay) and the sheet's own heading (type).
Future<({double row, double option, double heading, double left})> _measure(
  WidgetTester tester,
  String stored,
) async {
  final harness = _Harness(saved: stored);
  harness.install(tester);
  await tester.pumpWidget(const DshApp(preview: true));
  await _settle(tester);
  await tester.tap(find.byKey(const ValueKey('composer-model')));
  await _settle(tester);
  final option = tester.getRect(
    find.byKey(const ValueKey('model-option-fixture-balanced')),
  );
  final heading = tester.getRect(find.text('选择模型'));
  // Dismiss the sheet by tapping its scrim, far from any row.
  await tester.tapAt(const Offset(4, 4));
  await _settle(tester);
  await _openSettings(tester);
  final row = tester.getRect(
    find.byKey(const ValueKey('settings-destination-models')),
  );
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump();
  return (
    row: row.height,
    option: option.height,
    heading: heading.height,
    left: row.left,
  );
}

/// The size a control really occupies on the glass: the canvas paints the
/// subtree through a [Transform], so the widget's own size is in logical units
/// and must be converted before it can be compared with a dp floor.
Size _paintedSize(WidgetTester tester, Finder finder) {
  final box = tester.renderObject<RenderBox>(finder);
  final topLeft = box.localToGlobal(Offset.zero);
  final delta = box.localToGlobal(box.size.bottomRight(Offset.zero)) - topLeft;
  return Size(delta.dx, delta.dy);
}

void main() {
  testWidgets('a stored compact size is applied to the whole app on start', (
    tester,
  ) async {
    final harness = _Harness(saved: 'compact');
    harness.install(tester);
    await tester.pumpWidget(const DshApp(preview: true));
    await _settle(tester);
    expect(harness.reads, 1);
    expect(_scope(tester).size, DshDisplaySize.compact);
    // The persisted value was read, not invented, and nothing was written back.
    expect(harness.writes, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('an unknown stored size falls back to standard', (tester) async {
    final harness = _Harness(saved: 'gigantic');
    harness.install(tester);
    await tester.pumpWidget(const DshApp(preview: true));
    await _settle(tester);
    expect(_scope(tester).size, DshDisplaySize.standard);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a late startup read never overrides a newer choice', (
    tester,
  ) async {
    final harness = _Harness(saved: 'relaxed');
    final gate = Completer<void>();
    harness.readGate = gate;
    harness.install(tester);
    await tester.pumpWidget(const DshApp(preview: true));
    await _settle(tester);
    // The user picks compact while the stored read is still in flight.
    _scope(tester).onChanged!(DshDisplaySize.compact);
    await tester.pump();
    expect(_scope(tester).size, DshDisplaySize.compact);
    gate.complete();
    await _settle(tester);
    expect(_scope(tester).size, DshDisplaySize.compact);
    expect(harness.writes, ['compact']);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a failed save is reported instead of pretending it stuck', (
    tester,
  ) async {
    final harness = _Harness()..failWrites = true;
    harness.install(tester);
    await tester.pumpWidget(const DshApp(preview: true));
    await _settle(tester);
    _scope(tester).onChanged!(DshDisplaySize.relaxed);
    await _settle(tester);
    expect(harness.writes, ['relaxed']);
    expect(find.textContaining('没能保存'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('the pill is content sized at the leading edge of its row', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(432, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final controller = ChatController(FixtureTransport());
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(controller: controller),
      ),
    );
    await tester.pump();
    final touch = tester.getRect(find.byKey(const ValueKey('composer-model')));
    final surface = tester.getRect(
      find.byKey(const ValueKey('composer-model-surface')),
    );
    final attach = tester.getRect(
      find.byKey(const ValueKey('composer-attach')),
    );
    // The painted stadium starts at the leading edge of its own target instead
    // of floating in the middle of the free row space, and the target covers
    // exactly those pixels (never a wider invisible box around them).
    expect(surface.left - touch.left, lessThanOrEqualTo(1));
    expect(touch.width, lessThanOrEqualTo(surface.width + 0.5));
    expect(touch.height, greaterThanOrEqualTo(48));
    expect(surface.left - attach.right, inInclusiveRange(0, 4));
    // A tap at the visible centre really reaches the capsule. The shell's
    // header title pans its own animation at this width, so settle by frames.
    await tester.tapAt(touch.center);
    await _settle(tester, frames: 8);
    expect(find.text('选择模型'), findsOneWidget);
    controller.dispose();
  });

  // ------------------------------------------------------------- the canvas --
  //
  // The tests above own the stored preference. The ones below own what the
  // feature actually promises: one switch scales the whole Flutter surface —
  // type *and* widget geometry — across pushed routes and overlays, while real
  // 48dp tap targets, the IME edge and the safe area stay where the glass needs
  // them. Every assertion reads painted global rects, never widget fields.

  testWidgets('settings rows, sheet rows and headings all follow the size', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(432, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final compact = await _measure(tester, 'compact');
    final standard = await _measure(tester, 'standard');
    final relaxed = await _measure(tester, 'relaxed');

    // A settings destination row is a design-fixed 56dp row: only the canvas
    // can move it, so this fails if the switch merely restyles text.
    expect(compact.row, closeTo(standard.row * .9, .6));
    expect(relaxed.row, closeTo(standard.row * 1.1, .6));
    // The same holds for a row inside the modal bottom sheet...
    expect(compact.option, closeTo(standard.option * .9, .6));
    expect(relaxed.option, closeTo(standard.option * 1.1, .6));
    // ...and for the sheet's own heading, which is type, not a fixed extent.
    expect(compact.heading, closeTo(standard.heading * .9, .6));
    expect(relaxed.heading, closeTo(standard.heading * 1.1, .6));
    // Measured 50.4 / 56 / 61.6: the row follows the factor exactly, so a
    // build that only restyles type leaves this red.
    expect(compact.row, closeTo(50.4, .6));
    expect(standard.row, closeTo(56, .6));
    expect(relaxed.row, closeTo(61.6, .6));
    // The canvas also changes the logical width, so the responsive layout may
    // reshuffle; what must hold is that the row is drawn at the scaled height
    // and its left edge lands inside the real viewport at every size.
    for (final r in [compact, standard, relaxed]) {
      expect(r.left, inInclusiveRange(0, 432));
    }
    // The compact direction still cannot strip a row below a 48dp target.
    expect(compact.row, greaterThanOrEqualTo(48));
  });

  testWidgets('a size change keeps the pushed route and its taps working', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(432, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final harness = _Harness(saved: 'standard');
    harness.install(tester);
    await tester.pumpWidget(const DshApp(preview: true));
    await _settle(tester);
    await _openSettings(tester);
    final before = tester.getRect(
      find.byKey(const ValueKey('settings-destination-models')),
    );

    // Switch the size with the settings route already pushed.
    _scope(tester).onChanged!(DshDisplaySize.relaxed);
    await _settle(tester);
    expect(_scope(tester).size, DshDisplaySize.relaxed);
    // The route was not remounted: the page is still the settings page, with
    // the same rows, now painted larger.
    expect(
      find.byKey(const ValueKey('settings-destination-models')),
      findsOneWidget,
    );
    final after = tester.getRect(
      find.byKey(const ValueKey('settings-destination-models')),
    );
    expect(after.height, greaterThan(before.height));
    // The moved pixels are the ones that answer taps.
    await tester.tapAt(after.center);
    await _settle(tester, frames: 10);
    expect(
      find.byKey(const ValueKey('settings-model-fixture-balanced')),
      findsOneWidget,
    );
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a size change under an open sheet keeps the sheet usable', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(432, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final harness = _Harness(saved: 'standard');
    harness.install(tester);
    await tester.pumpWidget(const DshApp(preview: true));
    await _settle(tester);
    await tester.tap(find.byKey(const ValueKey('composer-model')));
    await _settle(tester);
    final before = tester.getRect(find.text('选择模型'));

    _scope(tester).onChanged!(DshDisplaySize.compact);
    await _settle(tester);
    // Same sheet, still open, now painted at the compact scale.
    expect(find.text('选择模型'), findsOneWidget);
    final after = tester.getRect(find.text('选择模型'));
    expect(after.height, lessThan(before.height));
    // And its rows are still where they are painted.
    final option = tester.getRect(
      find.byKey(const ValueKey('model-option-fixture-fast')),
    );
    await tester.tapAt(option.center);
    await _settle(tester);
    expect(find.text('选择模型'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('compact keeps a real 48dp target on the main controls', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(432, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final harness = _Harness(saved: 'compact');
    harness.install(tester);
    await tester.pumpWidget(const DshApp(preview: true));
    await _settle(tester);
    // The canvas paints at 0.9, so a widget's own size (53.33 logical) is not
    // what the finger touches. Measure the painted box in real dp; asserting
    // the logical size alone stays green even if the floor is dropped, because
    // 48 logical looks like "48" while the glass only gets 43.2dp.
    for (final key in [
      'composer-model',
      'composer-send',
      'composer-attach',
      'composer-permission',
    ]) {
      final painted = _paintedSize(tester, find.byKey(ValueKey(key)));
      expect(painted.height, greaterThanOrEqualTo(48 - .01), reason: key);
      expect(painted.width, greaterThanOrEqualTo(48 - .01), reason: key);
    }
    // A tap at the painted centre reaches the capsule even though the whole
    // surface is drawn at 0.9.
    final pill = tester.getRect(find.byKey(const ValueKey('composer-model')));
    await tester.tapAt(pill.center);
    await _settle(tester);
    expect(find.text('选择模型'), findsOneWidget);
    // A settings row keeps the same floor after a route push, and its real
    // pixels open the page behind it.
    await tester.tapAt(const Offset(4, 4));
    await _settle(tester);
    await _openSettings(tester);
    final row = tester.getRect(
      find.byKey(const ValueKey('settings-destination-models')),
    );
    expect(row.height, greaterThanOrEqualTo(48));
    await tester.tapAt(row.center);
    await _settle(tester, frames: 10);
    expect(
      find.byKey(const ValueKey('settings-model-fixture-balanced')),
      findsOneWidget,
    );
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a turn notice keeps 48dp copy and detail targets at compact', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 780);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final copied = <String>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied.add((call.arguments as Map)['text'] as String);
        }
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    const notice = TurnNotice(
      kind: TurnNoticeKind.failed,
      message: '模型尚未配置：当前模型提供方没有可用凭据。',
      code: 'MISSING_CREDENTIAL',
      detail: '{"code":"MISSING_CREDENTIAL"}',
    );
    await tester.pumpWidget(
      DshDisplayScope(
        size: DshDisplaySize.compact,
        child: MaterialApp(
          theme: DshTheme.light(displayFactor: DshDisplaySize.compact.factor),
          builder: (context, child) =>
              DshDisplayCanvas(child: child ?? const SizedBox.shrink()),
          home: const Scaffold(
            body: ConversationFlow(
              conversationIdentity: 'parent',
              messages: [
                ChatMessage(
                  id: 'notice-1',
                  role: ChatRole.notice,
                  text: '模型尚未配置：当前模型提供方没有可用凭据。',
                  notice: notice,
                ),
              ],
            ),
          ),
        ),
      ),
    );
    await _settle(tester);
    final toggle = tester.getRect(find.byKey(const ValueKey('turn-notice')));
    // The canvas paints at 0.9, so the 48dp floor lands here as 53.33 logical
    // units; the sub-nanometre shortfall is float noise on that division, not a
    // smaller target, so the assertion keeps a tolerance far below one dp.
    const floor = 48 - .001;
    expect(toggle.height, greaterThanOrEqualTo(floor));
    await tester.tapAt(toggle.center);
    await _settle(tester);
    expect(find.byKey(const ValueKey('turn-notice-detail')), findsOneWidget);
    final copy = tester.getRect(find.byKey(const ValueKey('turn-notice-copy')));
    expect(copy.height, greaterThanOrEqualTo(floor));
    await tester.tapAt(copy.center);
    await _settle(tester);
    expect(copied.single, contains('MISSING_CREDENTIAL'));
  });

  testWidgets('the IME edge and the safe area stay on real physical pixels', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(432, 900);
    tester.view.devicePixelRatio = 1;
    tester.view.padding = const FakeViewPadding(top: 24, bottom: 12);
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPadding);
    addTearDown(tester.view.resetViewInsets);

    for (final stored in ['compact', 'relaxed']) {
      final harness = _Harness(saved: stored);
      harness.install(tester);
      await tester.pumpWidget(const DshApp(preview: true));
      await _settle(tester);
      final header = tester.getRect(
        find.byKey(const ValueKey('floating-header')),
      );
      final send = tester.getRect(find.byKey(const ValueKey('composer-send')));
      // Safe area: nothing rides under the status bar at either scale.
      expect(header.top, greaterThanOrEqualTo(24), reason: stored);
      // IME: the composer's bottom stays above the keyboard edge, in real
      // pixels, and lands close to it rather than hiding 300px away.
      expect(send.bottom, lessThanOrEqualTo(900 - 300 + .5), reason: stored);
      expect(send.bottom, greaterThan(900 - 300 - 140), reason: stored);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    }
  });

  testWidgets('320dp wide with 2x system text stays operable at compact', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final harness = _Harness(saved: 'compact');
    harness.install(tester);
    await tester.pumpWidget(const DshApp(preview: true));
    await _settle(tester);
    expect(tester.takeException(), isNull);
    final pill = tester.getRect(find.byKey(const ValueKey('composer-model')));
    expect(pill.height, greaterThanOrEqualTo(48));
    expect(pill.right, lessThanOrEqualTo(320.5));
    await tester.tapAt(pill.center);
    await _settle(tester);
    expect(find.text('选择模型'), findsOneWidget);
    expect(tester.takeException(), isNull);
    final option = tester.getRect(
      find.byKey(const ValueKey('model-option-fixture-fast')),
    );
    expect(option.height, greaterThanOrEqualTo(48));
    await tester.tapAt(option.center);
    await _settle(tester);
    expect(find.text('选择模型'), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
