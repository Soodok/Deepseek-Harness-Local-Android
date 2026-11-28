import 'dart:async';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/turn_notice.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/turn_notice_card.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// A link whose reconnect always fails, so one recovery action can be run as
/// many times as a test likes and every attempt is observable.
class _DeadLinkTransport implements DshTransport {
  final _events = EventStreamController();
  static const int _generation = 1;
  int _sequence = 0;
  bool _connected = false;

  /// Every reconnect the shell asked for, including a second tap on a button
  /// that already answered with a failure.
  int reconnects = 0;

  @override
  Stream<ChatEvent> get events => _events.stream;

  @override
  bool get isFixture => false;

  @override
  Future<void> connect() async {
    _connected = true;
    _events.add(
      ChatEvent.snapshot(
        generation: _generation,
        sequence: 0,
        messages: const [],
      ),
    );
    _sequence = 1;
  }

  void drop() {
    if (!_connected) return;
    _connected = false;
    _events.add(
      ChatEvent.disconnected(generation: _generation, sequence: _sequence++),
    );
  }

  @override
  Future<void> reconnect() async {
    reconnects++;
    throw const TransportException('engine still down');
  }

  @override
  Future<void> sendPrompt(String text) async {}

  @override
  Future<void> stop() async {}

  @override
  Future<void> approve(String requestId) async {}

  @override
  Future<void> answer(String requestId, String answer) async {}

  @override
  Future<void> close() => _events.close();
}

/// Makes the clipboard itself fail, which is the one thing a "复制诊断" tap
/// cannot report as success unless the copy really happened.
void _failClipboard(WidgetTester tester) {
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
    SystemChannels.platform,
    (call) async {
      if (call.method == 'Clipboard.setData') {
        throw PlatformException(code: 'clipboard-unavailable');
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
}

Future<void> _pumpShell(WidgetTester tester, ChatController controller) async {
  tester.view.physicalSize = const Size(400, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: ChatShell(controller: controller),
    ),
  );
  await tester.pump();
  await tester.pump();
}

/// One frame applies the transport event, the next rebuilds the shell from it.
Future<void> _settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 16));
}

String _feedback(WidgetTester tester, String key) {
  final row = find.byKey(ValueKey(key));
  return tester
      .widget<Text>(find.descendant(of: row, matching: find.byType(Text)).first)
      .data!;
}

bool _hasHandler(WidgetTester tester, String key) =>
    tester.widget<TextButton>(find.byKey(ValueKey(key))).onPressed != null;

Widget _card(TurnNoticeActions actions, TurnNotice notice) => MaterialApp(
  theme: DshTheme.light(),
  home: Scaffold(
    body: TurnNoticeActionScope(
      actions: actions,
      child: TurnNoticeCard(notice: notice),
    ),
  ),
);

void main() {
  testWidgets('the broken-link banner names all four actions', (tester) async {
    final transport = _DeadLinkTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    transport.drop();
    await _settle(tester);
    expect(find.text('连接已中断'), findsOneWidget);
    // The labels are the product contract: waiting is no longer the only offer.
    for (final label in ['重试连接', '切换运行环境', '打开设置', '复制诊断']) {
      expect(find.text(label), findsOneWidget, reason: label);
    }
    // Nothing here sends a prompt, and no action is offered twice.
    expect(find.text('重试'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('a failed turn offers retry, diagnostics and its own settings', (
    tester,
  ) async {
    await tester.pumpWidget(
      _card(
        TurnNoticeActions(
          retry: () async => const ChatActionReport(ok: true, message: 'ok'),
          openSettings: (notice) async =>
              const ChatActionReport(ok: true, message: 'ok'),
          diagnostics: (notice) => 'DIAG',
        ),
        const TurnNotice(
          kind: TurnNoticeKind.failed,
          message: '请求失败',
          code: 'MISSING_CREDENTIAL',
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();
    for (final label in ['重试', '复制诊断', '打开相关设置']) {
      expect(find.text(label), findsOneWidget, reason: label);
    }
  });

  testWidgets('a clipboard that refuses the copy reads as a failure', (
    tester,
  ) async {
    _failClipboard(tester);
    final transport = _DeadLinkTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    transport.drop();
    await _settle(tester);
    expect(find.text('连接已中断'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey('connection-action-diagnostics')),
    );
    await tester.pump();
    await _settle(tester);

    final line = _feedback(tester, 'connection-action-feedback');
    expect(line, contains('操作没有完成'));
    expect(line, isNot(contains('已复制连接诊断')));
    expect(line, isNot(contains('诊断信息已复制')));
    // The entry stays: a copy that failed must be retryable by hand.
    expect(_hasHandler(tester, 'connection-action-diagnostics'), isTrue);
    expect(find.text('连接已中断'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('a failed retry leaves the banner able to try again', (
    tester,
  ) async {
    final transport = _DeadLinkTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    transport.drop();
    await _settle(tester);

    await tester.tap(find.byKey(const ValueKey('connection-action-retry')));
    await tester.pump();
    await _settle(tester);
    final first = transport.reconnects;
    expect(first, greaterThanOrEqualTo(1));
    expect(_feedback(tester, 'connection-action-feedback'), contains('仍然无法连接'));
    expect(_hasHandler(tester, 'connection-action-retry'), isTrue);

    // A second tap is a second attempt, not a disabled trophy.
    await tester.tap(find.byKey(const ValueKey('connection-action-retry')));
    await tester.pump();
    await _settle(tester);
    expect(transport.reconnects, first + 1);
    expect(_feedback(tester, 'connection-action-feedback'), contains('仍然无法连接'));
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('a card retry that fails can be run again and then succeed', (
    tester,
  ) async {
    var calls = 0;
    await tester.pumpWidget(
      _card(
        TurnNoticeActions(
          retry: () async {
            calls++;
            return calls == 1
                ? const ChatActionReport(
                    ok: false,
                    message: '仍然无法连接：engine still down',
                  )
                : const ChatActionReport(ok: true, message: '已重新连接');
          },
        ),
        const TurnNotice(kind: TurnNoticeKind.interrupted, message: '运行已中断'),
      ),
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();

    await tester.tap(find.byKey(const ValueKey('turn-notice-action-retry')));
    await tester.pump();
    await tester.pump();
    expect(calls, 1);
    expect(
      _feedback(tester, 'turn-notice-action-feedback'),
      contains('仍然无法连接'),
    );
    expect(_hasHandler(tester, 'turn-notice-action-retry'), isTrue);

    await tester.tap(find.byKey(const ValueKey('turn-notice-action-retry')));
    await tester.pump();
    await tester.pump();
    expect(calls, 2);
    expect(_feedback(tester, 'turn-notice-action-feedback'), contains('已重新连接'));
  });

  testWidgets('a settings entry that fails keeps its button and its reason', (
    tester,
  ) async {
    var calls = 0;
    await tester.pumpWidget(
      _card(
        TurnNoticeActions(
          openSettings: (notice) async {
            calls++;
            return const ChatActionReport(
              ok: false,
              message: '这个入口打不开设置，请在 Android 应用里打开模型配置',
            );
          },
        ),
        const TurnNotice(
          kind: TurnNoticeKind.failed,
          message: '请求失败',
          code: 'MISSING_CREDENTIAL',
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();

    await tester.tap(find.byKey(const ValueKey('turn-notice-action-settings')));
    await tester.pump();
    await tester.pump();
    expect(calls, 1);
    final line = _feedback(tester, 'turn-notice-action-feedback');
    expect(line, contains('打不开设置'));
    expect(line, isNot(contains('已打开')));
    // The entry stays so the person can try again from the app.
    expect(_hasHandler(tester, 'turn-notice-action-settings'), isTrue);
  });

  testWidgets('a card diagnostics copy that throws keeps its entry', (
    tester,
  ) async {
    _failClipboard(tester);
    await tester.pumpWidget(
      _card(
        TurnNoticeActions(diagnostics: (notice) => 'DIAG'),
        const TurnNotice(
          kind: TurnNoticeKind.failed,
          message: '请求失败',
          code: 'MISSING_CREDENTIAL',
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();

    await tester.tap(
      find.byKey(const ValueKey('turn-notice-action-diagnostics')),
    );
    await tester.pump();
    await tester.pump();

    final line = _feedback(tester, 'turn-notice-action-feedback');
    expect(line, contains('操作没有完成'));
    expect(line, isNot(contains('诊断信息已复制')));
    expect(_hasHandler(tester, 'turn-notice-action-diagnostics'), isTrue);
  });
}
