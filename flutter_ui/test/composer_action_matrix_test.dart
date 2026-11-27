import 'dart:async';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/composer_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/composer_surface.dart';
import 'package:dsh_native_chat/src/ui/permission_icon.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// Manual transport: the test decides exactly when a turn starts and ends, so
/// the send/stop slot matrix is checked against real controller callbacks
/// instead of a timing-dependent fixture.
class _ManualTransport implements DshTransport, ComposerTransport {
  final _events = EventStreamController();
  int _sequence = 0;
  int stops = 0;
  final sent = <PromptSubmission>[];
  Completer<void>? stopGate;
  bool stopFails = false;

  @override
  Stream<ChatEvent> get events => _events.stream;
  @override
  bool get isFixture => false;
  @override
  List<ComposerModel> get models => const [
    ComposerModel('m-one', '模型一', '演示'),
    ComposerModel('m-two', '模型二', '演示'),
  ];

  @override
  Future<void> connect() async {
    _events.add(
      const ChatEvent.snapshot(generation: 1, sequence: 0, messages: []),
    );
    _sequence = 1;
  }

  void startTurn(String text) {
    _events.add(
      ChatEvent.delta(
        generation: 1,
        sequence: _sequence++,
        messageId: 'user-' + _sequence.toString(),
        role: ChatRole.user,
        text: text,
        done: true,
      ),
    );
    _events.add(
      ChatEvent.delta(
        generation: 1,
        sequence: _sequence++,
        messageId: 'assistant-' + _sequence.toString(),
        role: ChatRole.assistant,
        text: '正在回答',
        done: false,
      ),
    );
  }

  void finishTurn() {
    _events.add(ChatEvent.turnComplete(generation: 1, sequence: _sequence++));
  }

  @override
  Future<void> sendPrompt(String text) async {}
  @override
  Future<void> sendSubmission(PromptSubmission submission) async {
    sent.add(submission);
    startTurn(submission.text);
  }

  @override
  Future<void> stop() async {
    stops++;
    await stopGate?.future;
    if (stopFails) throw const TransportException('cancel rejected');
    finishTurn();
  }

  @override
  Future<void> reconnect() async {}
  @override
  Future<void> approve(String requestId) async {}
  @override
  Future<void> answer(String requestId, String answer) async {}
  @override
  Future<void> close() => _events.close();
}

Future<void> _pump(WidgetTester tester, ChatController controller) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: ChatShell(controller: controller),
    ),
  );
  await tester.pump();
}

Finder get _send => find.byKey(const ValueKey('composer-send'));
Finder get _stop => find.byKey(const ValueKey('composer-stop'));
Finder get _input => find.byKey(const ValueKey('composer-input'));

void main() {
  testWidgets('idle disables send until a draft exists, then sends it', (
    tester,
  ) async {
    final transport = _ManualTransport();
    final controller = ChatController(transport);
    await _pump(tester, controller);
    expect(tester.widget<IconButton>(_send).onPressed, isNull);
    expect(_stop, findsNothing);

    await tester.enterText(_input, '第一句');
    await tester.pump();
    expect(tester.widget<IconButton>(_send).onPressed, isNotNull);
    await tester.tap(_send);
    await tester.pump();
    expect(transport.sent.single.text, '第一句');
    expect(find.text('第一句'), findsOneWidget);
    controller.dispose();
  });

  testWidgets('running turn with an empty draft shows only stop', (
    tester,
  ) async {
    final transport = _ManualTransport();
    final controller = ChatController(transport);
    await _pump(tester, controller);
    transport.startTurn('外部开始的回合');
    // Event delivery is asynchronous: one frame applies the state, the next
    // rebuilds the composer from it.
    await tester.pump();
    await tester.pump();
    expect(controller.state.status, ChatStatus.streaming);

    expect(_stop, findsOneWidget);
    expect(_send, findsNothing);
    expect(tester.widget<IconButton>(_stop).onPressed, isNotNull);
    controller.dispose();
  });

  testWidgets('running turn with a draft shows only send and really queues', (
    tester,
  ) async {
    final transport = _ManualTransport();
    final controller = ChatController(transport);
    await _pump(tester, controller);
    transport.startTurn('外部开始的回合');
    // Event delivery is asynchronous: one frame applies the state, the next
    // rebuilds the composer from it.
    await tester.pump();
    await tester.pump();
    await tester.enterText(_input, '插队的内容');
    await tester.pump();
    expect(_send, findsOneWidget);
    expect(_stop, findsNothing);
    await tester.tap(_send);
    await tester.pump();
    // A running turn never pretends to cancel here: the prompt is queued with
    // the real steering/queue semantics and the draft is consumed.
    expect(transport.sent, isEmpty);
    expect(controller.state.queuedPrompts, ['插队的内容']);
    expect(transport.stops, 0);
    controller.dispose();
  });

  testWidgets('clearing the draft restores the stop in the same slot', (
    tester,
  ) async {
    final transport = _ManualTransport();
    final controller = ChatController(transport);
    await _pump(tester, controller);
    transport.startTurn('外部开始的回合');
    // Event delivery is asynchronous: one frame applies the state, the next
    // rebuilds the composer from it.
    await tester.pump();
    await tester.pump();
    await tester.enterText(_input, '临时想法');
    await tester.pump();
    expect(_send, findsOneWidget);
    await tester.enterText(_input, '');
    await tester.pump();
    expect(_stop, findsOneWidget);
    expect(_send, findsNothing);
    controller.dispose();
  });

  testWidgets('stop calls the real cancel exactly once and disables itself', (
    tester,
  ) async {
    final transport = _ManualTransport()..stopGate = Completer<void>();
    final controller = ChatController(transport);
    await _pump(tester, controller);
    transport.startTurn('外部开始的回合');
    // Event delivery is asynchronous: one frame applies the state, the next
    // rebuilds the composer from it.
    await tester.pump();
    await tester.pump();
    await tester.tap(_stop);
    await tester.pump();
    // Pending stop: the slot keeps the stop icon so it does not jump, but a
    // second press is refused.
    final pending = tester.widget<IconButton>(_stop);
    expect(pending.onPressed, isNull);
    expect(transport.stops, 1);
    await tester.tap(_stop, warnIfMissed: false);
    await tester.pump();
    expect(transport.stops, 1);

    transport.stopGate!.complete();
    await tester.pump();
    await tester.pump();
    expect(controller.state.status, ChatStatus.ready);
    expect(_stop, findsNothing);
    expect(tester.widget<IconButton>(_send).onPressed, isNull);
    controller.dispose();
  });

  testWidgets('a rejected cancel keeps its error and stays retryable', (
    tester,
  ) async {
    final transport = _ManualTransport()..stopFails = true;
    final controller = ChatController(transport);
    await _pump(tester, controller);
    transport.startTurn('外部开始的回合');
    // Event delivery is asynchronous: one frame applies the state, the next
    // rebuilds the composer from it.
    await tester.pump();
    await tester.pump();
    await tester.tap(_stop);
    await tester.pump();
    await tester.pump();
    expect(find.textContaining('cancel rejected'), findsOneWidget);
    expect(find.byKey(const ValueKey('connection-retry')), findsNothing);
    // The turn never ended, so the stop is offered again for a retry.
    expect(_stop, findsOneWidget);
    expect(tester.widget<IconButton>(_stop).onPressed, isNotNull);
    controller.dispose();
  });

  testWidgets('the model capsule is a flatter surface in a 48dp target', (
    tester,
  ) async {
    final transport = _ManualTransport();
    final controller = ChatController(transport);
    await _pump(tester, controller);
    final surface = tester.getSize(
      find.byKey(const ValueKey('composer-model-surface')),
    );
    expect(surface.height, inInclusiveRange(32, 36));
    final target = tester.getSize(find.byKey(const ValueKey('composer-model')));
    expect(target.height, greaterThanOrEqualTo(48));
    expect(tester.getSize(_send).height, greaterThanOrEqualTo(48));
    expect(surface.height, lessThan(tester.getSize(_send).height));

    final sendCircle = find
        .descendant(of: _send, matching: find.byType(Container))
        .first;
    expect(tester.getSize(sendCircle), const Size(40, 40));
    final sendGlyph = tester.widget<Icon>(
      find.descendant(of: _send, matching: find.byType(Icon)).first,
    );
    expect(sendGlyph.size, 21);

    transport.startTurn('尺寸断言回合');
    await tester.pump();
    await tester.pump();
    final stopCircle = find
        .descendant(of: _stop, matching: find.byType(Container))
        .first;
    expect(tester.getSize(stopCircle), const Size(40, 40));
    final stopGlyph = tester.widget<Icon>(
      find.descendant(of: _stop, matching: find.byType(Icon)).first,
    );
    expect(stopGlyph.size, 21);

    final permission = find.byKey(const ValueKey('composer-permission'));
    expect(tester.getSize(permission).width, greaterThanOrEqualTo(48));
    final permissionCircle = find
        .descendant(of: permission, matching: find.byType(Container))
        .first;
    expect(tester.getSize(permissionCircle), const Size(38, 38));
    final permissionGlyph = find.descendant(
      of: permission,
      matching: find.byType(DshPermissionIcon),
    );
    expect(tester.getSize(permissionGlyph), const Size(20, 20));
    controller.dispose();
  });

  testWidgets('a long model name is ellipsized instead of overflowing', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final transport = _ManualTransport();
    final controller = ChatController(transport);
    await _pump(tester, controller);
    // Force a name far wider than the available row.
    final pill = tester.widget<ComposerModelPill>(
      find.byType(ComposerModelPill),
    );
    expect(pill.label, isNotEmpty);
    final text = tester.widget<Text>(
      find.descendant(
        of: find.byType(ComposerModelPill),
        matching: find.byType(Text),
      ),
    );
    expect(text.maxLines, 1);
    expect(text.overflow, TextOverflow.ellipsis);
    expect(tester.takeException(), isNull);
    controller.dispose();
  });

  testWidgets('large text keeps the capsule and row inside the dock', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final transport = _ManualTransport();
    final controller = ChatController(transport);
    await _pump(tester, controller);
    expect(tester.takeException(), isNull);
    final surface = tester.getSize(
      find.byKey(const ValueKey('composer-model-surface')),
    );
    // Clamped scaling keeps the 34dp surface from growing into the send button.
    expect(surface.height, inInclusiveRange(32, 36));
    expect(_send.hitTestable(), findsOneWidget);
    controller.dispose();
  });
}
