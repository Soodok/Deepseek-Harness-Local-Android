import 'dart:async';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/turn_notice.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/state/composer_draft.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/turn_notice_card.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// A link the test heals on its own, without a reconnect request.
///
/// The engine can come back while the person is still typing: the transport
/// then pushes a fresh snapshot and the shell never had a chance to look at the
/// field again. That is the exact window where an offer built from an older
/// capture would append a second copy of text that is already on screen.
class _SelfHealingTransport implements DshTransport {
  final _events = EventStreamController();
  int _generation = 1;
  int _sequence = 0;
  bool _connected = false;

  /// What a recovery action must never do: add a prompt.
  final List<String> sent = [];

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

  /// The engine came back by itself and pushed a new generation.
  void heal() {
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
  Future<void> reconnect() async => heal();

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

/// A transcript whose last row is a turn-ending card.
class _NoticeTransport implements DshTransport {
  _NoticeTransport(this.notice);

  final TurnNotice notice;
  final _events = EventStreamController();

  @override
  Stream<ChatEvent> get events => _events.stream;

  @override
  bool get isFixture => false;

  @override
  Future<void> connect() async {
    _events.add(
      ChatEvent.snapshot(
        generation: 1,
        sequence: 0,
        messages: [
          ChatMessage(
            id: 'turn-1',
            role: ChatRole.notice,
            text: '',
            notice: notice,
          ),
        ],
      ),
    );
  }

  @override
  Future<void> reconnect() async {}

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

Future<void> _settle(WidgetTester tester) async {
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
  testWidgets('an ending with nothing to fix hides retry and settings', (
    tester,
  ) async {
    // The scope can honour both actions in general; this ending cannot use
    // either, so the card must not print a button whose only answer would be
    // "this does not apply".
    await tester.pumpWidget(
      _card(
        TurnNoticeActions(
          retry: () async => const ChatActionReport(ok: true, message: 'retry'),
          openSettings: (notice) async =>
              const ChatActionReport(ok: true, message: 'settings'),
          diagnostics: (notice) => 'DIAG',
          offersRetry: (notice) => notice.isFailure,
          offersSettings: (notice) => notice.kind == TurnNoticeKind.signedOut,
        ),
        const TurnNotice(kind: TurnNoticeKind.stopped, message: '你停止了这一轮'),
      ),
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();
    expect(
      find.byKey(const ValueKey('turn-notice-action-retry')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('turn-notice-action-settings')),
      findsNothing,
    );
    // The facts still travel: a stop is copyable and diagnosable.
    expect(
      find.byKey(const ValueKey('turn-notice-action-diagnostics')),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('turn-notice-copy')), findsOneWidget);
  });

  testWidgets('a failure keeps the actions its scope can honour', (
    tester,
  ) async {
    await tester.pumpWidget(
      _card(
        TurnNoticeActions(
          retry: () async => const ChatActionReport(ok: true, message: 'ok'),
          openSettings: (notice) async =>
              const ChatActionReport(ok: true, message: 'ok'),
          offersRetry: (notice) => notice.isFailure,
          offersSettings: (notice) => notice.isFailure,
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
    expect(
      find.byKey(const ValueKey('turn-notice-action-retry')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('turn-notice-action-settings')),
      findsOneWidget,
    );
  });

  testWidgets('an action that throws reads as a failure, never as a tick', (
    tester,
  ) async {
    await tester.pumpWidget(
      _card(
        TurnNoticeActions(
          retry: () async => throw StateError('channel went away'),
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
    final feedback = find.byKey(const ValueKey('turn-notice-action-feedback'));
    expect(feedback, findsOneWidget);
    final line = tester
        .widget<Text>(
          find.descendant(of: feedback, matching: find.byType(Text)).first,
        )
        .data!;
    expect(line, contains('操作没有完成'));
    expect(line, isNot(contains('已打开')));
  });

  testWidgets('text typed while the link was down is not offered back twice', (
    tester,
  ) async {
    final transport = _SelfHealingTransport();
    final controller = ChatController(transport);
    final draft = ComposerDraft();
    addTearDown(draft.dispose);
    await _pumpShell(tester, controller, draft: draft);
    await tester.enterText(find.byKey(const ValueKey('composer-input')), '先写的');
    await tester.pump();
    expect(draft.text, '先写的');

    transport.drop();
    await _settle(tester);
    // Typing continues during the outage: the shell parked the shorter text.
    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '先写的，断线后又补的',
    );
    await tester.pump();

    // The engine returns on its own: no tap, no reconnect request, and the
    // field was never empty, so nothing may be offered back.
    transport.heal();
    await _settle(tester);
    expect(controller.state.status, ChatStatus.ready);
    expect(find.byKey(const ValueKey('draft-recovery')), findsNothing);
    expect(_composerText(tester), '先写的，断线后又补的');
    expect(transport.sent, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('a stop in the real shell offers no reconnect button', (
    tester,
  ) async {
    final controller = ChatController(
      _NoticeTransport(
        const TurnNotice(kind: TurnNoticeKind.stopped, message: '你停止了这一轮'),
      ),
    );
    await _pumpShell(tester, controller);
    await tester.ensureVisible(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();
    expect(
      find.byKey(const ValueKey('turn-notice-action-retry')),
      findsNothing,
    );
    // Copying the report stays available: the shell can always describe a stop.
    expect(
      find.byKey(const ValueKey('turn-notice-action-diagnostics')),
      findsOneWidget,
    );
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });
}
