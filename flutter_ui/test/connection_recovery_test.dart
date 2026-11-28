import 'dart:async';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/turn_notice.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/state/composer_draft.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/turn_notice_card.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// A link the test owns: it decides when the socket drops, whether a reconnect
/// answers and whether that answer succeeds. Nothing here retries on its own,
/// so a recovery action is the only thing that can move the state.
class _GatedTransport implements DshTransport {
  final _events = EventStreamController();
  int _sequence = 0;
  int _generation = 1;
  bool _connected = false;

  /// Count of reconnect attempts, including the shell's own backoff ones.
  int reconnects = 0;

  /// Prompts the transport was asked to send. A recovery action must never add
  /// to this list.
  final List<String> sent = [];

  /// Held open to keep one reconnect in flight.
  Completer<void>? gate;

  /// True makes every reconnect answer with a failure, as a dead engine would.
  bool reconnectFails = false;

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
    await gate?.future;
    if (reconnectFails) throw const TransportException('engine still down');
    _generation++;
    _sequence = 0;
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

  @override
  Future<void> sendPrompt(String text) async => sent.add(text);

  @override
  Future<void> stop() async {}

  @override
  Future<void> approve(String requestId) async {}

  @override
  Future<void> answer(String requestId, String answer) async {}

  @override
  Future<void> close() => _events.close();
}

Future<void> _pumpShell(
  WidgetTester tester,
  ChatController controller, {
  ComposerDraft? draft,
}) async {
  tester.view.physicalSize = const Size(400, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: ChatShell(controller: controller, draft: draft),
    ),
  );
  await tester.pump();
  await tester.pump();
}

/// One frame is not enough after a transport event: the first applies the
/// state, the next rebuilds the shell from it.
Future<void> _settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 16));
}

/// Lets a real platform channel answer land.
///
/// The host channel is a real messenger round trip, and the widget tester's
/// fake clock never delivers it: without this the action stays pending forever
/// and the test would read a missing answer as a passing "no feedback".
Future<void> _hostRoundTrip(WidgetTester tester) async {
  await tester.runAsync(
    () => Future<void>.delayed(const Duration(milliseconds: 20)),
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 16));
}

String _composerText(WidgetTester tester) => tester
    .widget<EditableText>(
      find.descendant(
        of: find.byKey(const ValueKey('composer-input')),
        matching: find.byType(EditableText),
      ),
    )
    .controller
    .text;

void main() {
  testWidgets('a broken link offers retry, runtime, settings and diagnostics', (
    tester,
  ) async {
    final transport = _GatedTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    transport.drop();
    await _settle(tester);
    expect(find.text('连接已中断'), findsOneWidget);
    for (final id in ['retry', 'runtime', 'settings', 'diagnostics']) {
      expect(
        find.byKey(ValueKey('connection-action-' + id)),
        findsOneWidget,
        reason: id,
      );
    }
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('a reconnect that still fails reports failure, not success', (
    tester,
  ) async {
    final transport = _GatedTransport()..reconnectFails = true;
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    transport.drop();
    await _settle(tester);
    // The shell's own backoff has not fired yet (its first delay is 1s), so
    // this tap is the only attempt in flight.
    await tester.tap(find.byKey(const ValueKey('connection-action-retry')));
    await tester.pump();
    await _settle(tester);
    expect(transport.reconnects, greaterThanOrEqualTo(1));
    final feedback = find.byKey(const ValueKey('connection-action-feedback'));
    expect(feedback, findsOneWidget);
    expect(
      tester
          .widget<Text>(
            find.descendant(of: feedback, matching: find.byType(Text)).first,
          )
          .data,
      contains('仍然无法连接'),
    );
    // The strip stays: the link really is still down.
    expect(find.text('引擎连接失败'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('one attempt at a time: siblings wait while reconnect runs', (
    tester,
  ) async {
    final transport = _GatedTransport()..gate = Completer<void>();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    transport.drop();
    await _settle(tester);
    await tester.tap(find.byKey(const ValueKey('connection-action-retry')));
    await tester.pump();
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('connection-action-retry')),
        matching: find.byType(CircularProgressIndicator),
      ),
      findsOneWidget,
    );
    // Every sibling is disabled while the attempt is unanswered.
    for (final id in ['runtime', 'settings', 'diagnostics']) {
      final button = tester.widget<TextButton>(
        find.byKey(ValueKey('connection-action-' + id)),
      );
      expect(button.onPressed, isNull, reason: id);
    }
    // A second tap does nothing: no second request.
    final before = transport.reconnects;
    await tester.tap(find.byKey(const ValueKey('connection-action-retry')));
    await tester.pump();
    expect(transport.reconnects, before);
    transport.gate!.complete();
    transport.gate = null;
    await _settle(tester);
    await tester.pump(const Duration(milliseconds: 16));
    expect(controller.state.status, ChatStatus.ready);
    expect(find.text('连接已中断'), findsNothing);
    expect(transport.sent, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('a settings entry this surface cannot open says so', (
    tester,
  ) async {
    final transport = _GatedTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    transport.drop();
    await _settle(tester);
    await tester.tap(find.byKey(const ValueKey('connection-action-settings')));
    await tester.pump();
    await _hostRoundTrip(tester);
    final feedback = find.byKey(const ValueKey('connection-action-feedback'));
    expect(feedback, findsOneWidget);
    expect(
      tester
          .widget<Text>(
            find.descendant(of: feedback, matching: find.byType(Text)).first,
          )
          .data,
      contains('打不开设置'),
    );
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('switching runtime opens the environment panel', (tester) async {
    final transport = _GatedTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    transport.drop();
    await _settle(tester);
    await tester.tap(find.byKey(const ValueKey('connection-action-runtime')));
    await tester.pumpAndSettle();
    expect(find.text('运行环境'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('copy diagnostics puts the connection facts on the clipboard', (
    tester,
  ) async {
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
    final transport = _GatedTransport()..reconnectFails = true;
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    transport.drop();
    await _settle(tester);
    await tester.tap(
      find.byKey(const ValueKey('connection-action-diagnostics')),
    );
    await tester.pump();
    await _settle(tester);
    expect(copied.single, contains('DSH 原生聊天诊断'));
    expect(copied.single, contains('重连中'));
    expect(
      find.byKey(const ValueKey('connection-action-feedback')),
      findsOneWidget,
    );
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('a draft typed before a drop can be filled back, never sent', (
    tester,
  ) async {
    final transport = _GatedTransport();
    final controller = ChatController(transport);
    final draft = ComposerDraft();
    addTearDown(draft.dispose);
    await _pumpShell(tester, controller, draft: draft);
    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '断线前想说的话',
    );
    await tester.pump();
    expect(draft.text, '断线前想说的话');

    transport.drop();
    await _settle(tester);
    // The page rebuilt without the text: the field is empty again, which is
    // exactly the loss the recovery offer exists for.
    draft.clear();
    await tester.pump();

    await tester.tap(find.byKey(const ValueKey('connection-action-retry')));
    await tester.pump();
    await _settle(tester);
    await tester.pump(const Duration(milliseconds: 16));
    final recovery = find.byKey(const ValueKey('draft-recovery'));
    expect(controller.state.status, ChatStatus.ready);
    expect(recovery, findsOneWidget);
    expect(find.textContaining('断线前想说的话'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('draft-recovery-restore')));
    await tester.pump();
    expect(draft.text, '断线前想说的话');
    expect(_composerText(tester), '断线前想说的话');
    // Filling back is not sending: the composer still holds the text.
    expect(transport.sent, isEmpty);
    expect(find.byKey(const ValueKey('draft-recovery')), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('a draft that survived the reconnect is left alone, no strip', (
    tester,
  ) async {
    final transport = _GatedTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '还在输入框里',
    );
    await tester.pump();
    transport.drop();
    await _settle(tester);
    await tester.tap(find.byKey(const ValueKey('connection-action-retry')));
    await tester.pump();
    await _settle(tester);
    await tester.pump(const Duration(milliseconds: 16));
    expect(controller.state.status, ChatStatus.ready);
    // Nothing was lost, so nothing is offered: a strip here would be noise on
    // every ordinary reconnect.
    expect(find.byKey(const ValueKey('draft-recovery')), findsNothing);
    expect(_composerText(tester), '还在输入框里');
    expect(transport.sent, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('a turn card runs its recovery action and shows the answer', (
    tester,
  ) async {
    final calls = <String>[];
    final gate = Completer<ChatActionReport>();
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: TurnNoticeActionScope(
            actions: TurnNoticeActions(
              retry: () {
                calls.add('retry');
                return gate.future;
              },
              openSettings: (notice) async {
                calls.add('settings:' + notice.kind.name);
                return const ChatActionReport(ok: true, message: '已打开模型配置');
              },
              diagnostics: (notice) => 'DIAG-' + notice.kind.name,
            ),
            child: const TurnNoticeCard(
              notice: TurnNotice(
                kind: TurnNoticeKind.signedOut,
                message: '账号已退出登录，这一轮已停止。',
                code: 'ACCOUNT_SIGNED_OUT',
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();
    for (final id in ['retry', 'settings', 'diagnostics']) {
      expect(find.byKey(ValueKey('turn-notice-action-' + id)), findsOneWidget);
    }

    await tester.tap(find.byKey(const ValueKey('turn-notice-action-retry')));
    await tester.pump();
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('turn-notice-action-retry')),
        matching: find.byType(CircularProgressIndicator),
      ),
      findsOneWidget,
    );
    expect(
      tester
          .widget<TextButton>(
            find.byKey(const ValueKey('turn-notice-action-settings')),
          )
          .onPressed,
      isNull,
    );

    gate.complete(
      const ChatActionReport(ok: false, message: '仍然无法连接：engine still down'),
    );
    await tester.pump();
    await tester.pump();
    final feedback = find.byKey(const ValueKey('turn-notice-action-feedback'));
    expect(feedback, findsOneWidget);
    expect(
      tester
          .widget<Text>(
            find.descendant(of: feedback, matching: find.byType(Text)).first,
          )
          .data,
      contains('仍然无法连接'),
    );

    await tester.tap(find.byKey(const ValueKey('turn-notice-action-settings')));
    await tester.pump();
    await tester.pump();
    expect(calls, contains('retry'));
    expect(calls, contains('settings:signedOut'));
    expect(
      tester
          .widget<Text>(
            find.descendant(of: feedback, matching: find.byType(Text)).first,
          )
          .data,
      contains('已打开模型配置'),
    );
  });

  testWidgets('diagnostics from a card copies and reports the receipt', (
    tester,
  ) async {
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
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: TurnNoticeActionScope(
            actions: TurnNoticeActions(
              diagnostics: (notice) => 'DIAG-' + notice.code!.toString(),
            ),
            child: const TurnNoticeCard(
              notice: TurnNotice(
                kind: TurnNoticeKind.interrupted,
                message: 'DSH 进程在这一轮结束前退出。',
                code: 'INTERRUPTED',
              ),
            ),
          ),
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
    expect(copied.single, 'DIAG-INTERRUPTED');
    expect(
      find.byKey(const ValueKey('turn-notice-action-feedback')),
      findsOneWidget,
    );
  });

  testWidgets('a card without a shell offers no action it cannot honour', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: const Scaffold(
          body: TurnNoticeCard(
            notice: TurnNotice(
              kind: TurnNoticeKind.failed,
              message: '请求失败',
              code: 'MISSING_CREDENTIAL',
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();
    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget.key is ValueKey<String> &&
            (widget.key! as ValueKey<String>).value.startsWith(
              'turn-notice-action-',
            ),
      ),
      findsNothing,
    );
    // The details and the existing copy stay available.
    expect(find.byKey(const ValueKey('turn-notice-copy')), findsOneWidget);
  });
}
