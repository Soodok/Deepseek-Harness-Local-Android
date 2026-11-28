import 'dart:async';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/composer_model.dart';
import 'package:dsh_native_chat/src/model/subagent_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/state/conversation_actions.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/subagent_panel.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// What the platform actually received.
///
/// A haptic request is one `HapticFeedback.vibrate` call on the
/// `flutter/platform` channel, carrying the type Flutter maps to an Android
/// constant. The same channel also carries the press click sound and clipboard
/// traffic, so every assertion below filters on that method: counting all calls
/// would pass on an unrelated sound and prove nothing about a haptic.
class _Haptics {
  /// [channel] picks what the platform answers. A device with no motor, a
  /// silenced system touch switch and a channel with no implementation all look
  /// like a plain successful null answer, so that is the default. An error
  /// answer is the only one that would surface as an exception if the call were
  /// not swallowed, so it gets its own channel.
  _Haptics(WidgetTester tester, {this.channel = _HapticChannel.answers}) {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method != 'HapticFeedback.vibrate') return null;
        calls.add(
          (call.arguments as String).replaceFirst('HapticFeedbackType.', ''),
        );
        if (channel == _HapticChannel.errors) {
          throw PlatformException(
            code: 'vibrate_unavailable',
            message: 'this device reports no haptic support',
          );
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

  final _HapticChannel channel;
  final calls = <String>[];

  int get total => calls.length;
  int get light => calls.where((type) => type == 'lightImpact').length;
  int get selection => calls.where((type) => type == 'selectionClick').length;
}

enum _HapticChannel { answers, errors }

/// A Host that answers exactly what the test says: one snapshot, and turns the
/// test starts and ends itself. It implements the composer capabilities so the
/// real send path is taken, not the "this engine takes no options" fallback.
class _ShellTransport implements DshTransport, ComposerTransport {
  _ShellTransport({this.snapshot = const []});

  final List<ChatMessage> snapshot;
  final _events = EventStreamController();
  int _sequence = 0;
  final sent = <PromptSubmission>[];
  int stops = 0;
  bool failSend = false;
  Completer<void>? sendGate;

  @override
  Stream<ChatEvent> get events => _events.stream;
  @override
  bool get isFixture => false;
  @override
  List<ComposerModel> get models => const [ComposerModel('m-one', '模型一', '演示')];

  @override
  Future<void> connect() async {
    _events.add(
      ChatEvent.snapshot(generation: 1, sequence: 0, messages: snapshot),
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
    await sendGate?.future;
    if (failSend) throw const TransportException('send offline');
    sent.add(submission);
    startTurn(submission.text);
  }

  @override
  Future<void> stop() async {
    stops++;
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

class _ChildService implements SubagentTransport {
  final sent = <String>[];
  Completer<void>? gate;
  bool failSend = false;

  @override
  Future<SubagentConversation> readSubagent(SubagentAddress address) async =>
      _conversation(address);

  @override
  Stream<SubagentConversation> watchSubagent(SubagentAddress address) =>
      const Stream<SubagentConversation>.empty();

  @override
  Future<SubagentReceipt> sendSubagent(
    SubagentAddress address,
    String text, {
    required String requestId,
  }) async {
    await gate?.future;
    if (failSend) throw StateError('send offline');
    sent.add(text);
    return SubagentReceipt(requestId: requestId, delivery: 'queue');
  }
}

const _child = SubagentAddress(
  parentSessionId: 'parent-1',
  childSessionId: 'child-1',
  mode: SubagentMode.continuable,
);

SubagentConversation _conversation(SubagentAddress address) =>
    SubagentConversation(
      address: address,
      messages: const [SubagentMessage(id: 'c1', role: 'user', text: '开始吧')],
      revision: 1,
    );

AgentTask _agent() =>
    const AgentTask(id: 'agent-1', name: '子代理', task: '整理证据', address: _child);

Finder get _send => find.byKey(const ValueKey('composer-send'));
Finder get _stop => find.byKey(const ValueKey('composer-stop'));
Finder get _input => find.byKey(const ValueKey('composer-input'));
Finder get _childSend => find.byKey(const ValueKey('subagent-send'));
Finder get _childInput => find.byKey(const ValueKey('subagent-input'));

String _composerText(WidgetTester tester) =>
    tester.widget<TextField>(_input).controller!.text;

String _childText(WidgetTester tester) =>
    tester.widget<TextField>(_childInput).controller!.text;

Future<void> _pumpShell(
  WidgetTester tester,
  ChatController controller, {
  ConversationActions? actions,
  Key? key,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: ChatShell(key: key, controller: controller, actions: actions),
    ),
  );
  await tester.pump();
  await tester.pump();
}

/// A notice is a SnackBar with a four second timer, and two notices can queue
/// behind each other (entering an edit, then cancelling it). The test binding
/// fails on any timer still pending when the tree is torn down, so every case
/// that shows one runs the whole queue out.
Future<void> _flushNotice(WidgetTester tester) async {
  for (var i = 0; i < 4; i++) {
    await tester.pump(const Duration(seconds: 5));
    await tester.pump(const Duration(milliseconds: 500));
  }
}

void main() {
  testWidgets('主会话：按钮发送被受理后恰好一次轻触', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);

    await tester.enterText(_input, '第一句');
    await tester.pump();
    expect(haptics.total, 0, reason: '打字本身不该有任何触感');

    await tester.tap(_send);
    await tester.pump();
    await tester.pump();

    expect(transport.sent.single.text, '第一句');
    expect(haptics.light, 1);
    expect(haptics.selection, 0);
    expect(_composerText(tester), isEmpty);
    expect(
      tester.widget<TextField>(_input).focusNode!.hasFocus,
      isFalse,
      reason: '受理后键盘应收起',
    );
    controller.dispose();
  });

  testWidgets('主会话：键盘提交与按钮同一入口，只产生一次轻触', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);

    await tester.enterText(_input, '键盘发送');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump();
    await tester.pump();

    expect(transport.sent.single.text, '键盘发送');
    expect(haptics.light, 1, reason: '按钮与键盘不能各算一次');
    controller.dispose();
  });

  testWidgets('主会话：空草稿与不可发送状态零触感', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);

    expect(tester.widget<IconButton>(_send).onPressed, isNull);
    await tester.tap(_send, warnIfMissed: false);
    await tester.pump();

    // Whitespace is not content for this composer either.
    await tester.enterText(_input, '   ');
    await tester.pump();
    expect(tester.widget<IconButton>(_send).onPressed, isNull);
    await tester.tap(_send, warnIfMissed: false);
    await tester.pump();

    expect(transport.sent, isEmpty);
    expect(haptics.total, 0);
    controller.dispose();
  });

  testWidgets('主会话：发送失败零触感且草稿保留', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport()..failSend = true;
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);

    await tester.enterText(_input, '会失败的');
    await tester.pump();
    await tester.tap(_send);
    await tester.pump();
    await tester.pump();

    expect(haptics.total, 0, reason: '被拒绝的发送不是受理');
    expect(_composerText(tester), '会失败的');
    expect(
      tester.widget<TextField>(_input).focusNode!.hasFocus,
      isTrue,
      reason: '发送失败时保留焦点，便于修改和重试',
    );
    expect(find.textContaining('send offline'), findsOneWidget);
    controller.dispose();
  });

  testWidgets('主会话：连点在受理前被挡住，只轻触一次', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport()..sendGate = Completer<void>();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);

    await tester.enterText(_input, '只发一次');
    await tester.pump();
    await tester.tap(_send);
    await tester.pump();
    expect(
      find.descendant(
        of: _send,
        matching: find.byType(CircularProgressIndicator),
      ),
      findsOneWidget,
      reason: '慢发送期间应显示进度，而不是静止的发送箭头',
    );
    expect(tester.widget<IconButton>(_send).onPressed, isNull);
    await tester.tap(_send, warnIfMissed: false);
    await tester.pump();
    expect(haptics.total, 0, reason: '受理回执还没回来就不该响');

    transport.sendGate!.complete();
    await tester.pump();
    await tester.pump();
    expect(transport.sent.single.text, '只发一次');
    expect(haptics.light, 1);
    controller.dispose();
  });

  testWidgets('主会话：重建与无关帧零触感', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pump(const Duration(milliseconds: 100));
    expect(haptics.total, 0);

    // A different key throws the whole page State away and builds it again
    // over the same controller: a real rebuild, with nothing accepted behind it.
    await _pumpShell(tester, controller, key: const ValueKey('rebuilt-shell'));
    expect(haptics.total, 0);
    controller.dispose();
  });

  testWidgets('主会话：运行中入队沿用受理判据，一次轻触', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);

    transport.startTurn('外部开始的回合');
    await tester.pump();
    await tester.pump();
    expect(controller.state.status, ChatStatus.streaming);

    await tester.enterText(_input, '插队的内容');
    await tester.pump();
    await tester.tap(_send);
    await tester.pump();
    await tester.pump();

    // The queue answers true, so one accepted tick - and no transport call yet.
    expect(controller.state.queuedPrompts, ['插队的内容']);
    expect(transport.sent, isEmpty);
    expect(haptics.light, 1);
    controller.dispose();
  });

  testWidgets('主会话：停止按钮零触感', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);

    transport.startTurn('外部开始的回合');
    await tester.pump();
    await tester.pump();
    expect(_stop, findsOneWidget);

    await tester.tap(_stop);
    await tester.pump();
    await tester.pump();
    expect(transport.stops, 1);
    expect(haptics.total, 0, reason: '停止不是受理，也不是进入编辑');
    controller.dispose();
  });

  testWidgets('主会话：队列发送按钮零触感（既知缺口，按设计不响）', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);

    transport.startTurn('外部开始的回合');
    await tester.pump();
    await tester.pump();
    await tester.enterText(_input, '插队的内容');
    await tester.pump();
    await tester.tap(_send);
    await tester.pump();
    await tester.pump();
    expect(haptics.light, 1, reason: '入队那一次是受理');

    transport.finishTurn();
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('queue-card')), findsOneWidget);
    final queueSend = find.widgetWithText(TextButton, '发送下一条');
    expect(tester.widget<TextButton>(queueSend).onPressed, isNotNull);
    await tester.ensureVisible(queueSend);
    await tester.pump();
    await tester.tap(queueSend);
    await tester.pump();
    await tester.pump();

    expect(transport.sent.single.text, '插队的内容');
    expect(haptics.light, 1, reason: 'sendNextQueued 没有布尔回执，这里刻意不新增反馈');
    controller.dispose();
  });

  testWidgets('主会话：减少动态效果开启时触感照常发出', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport();
    final controller = ChatController(transport);
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(disableAnimations: true),
          child: child!,
        ),
        home: ChatShell(controller: controller),
      ),
    );
    await tester.pump();
    await tester.pump();

    await tester.enterText(_input, '没有动效也要有触感');
    await tester.pump();
    await tester.tap(_send);
    await tester.pump();
    await tester.pump();

    expect(transport.sent.single.text, '没有动效也要有触感');
    expect(haptics.light, 1, reason: '动效开关不是触感开关');
    controller.dispose();
  });

  // The platform answer never reaches the send path. A silenced system switch,
  // a motorless device and a channel with no implementation all answer null; a
  // channel that reports an error is the only answer that could surface as an
  // exception, and it must not either.
  for (final channel in _HapticChannel.values) {
    testWidgets('主会话：平台应答 ${channel.name} 不改变发送结果', (tester) async {
      final haptics = _Haptics(tester, channel: channel);
      final transport = _ShellTransport();
      final controller = ChatController(transport);
      await _pumpShell(tester, controller);

      await tester.enterText(_input, '平台异常下的发送');
      await tester.pump();
      await tester.tap(_send);
      await tester.pump();
      await tester.pump();

      expect(transport.sent.single.text, '平台异常下的发送');
      // The request really went out; only its answer differed.
      expect(haptics.light, 1);
      expect(_composerText(tester), isEmpty);
      expect(tester.takeException(), isNull);
      controller.dispose();
    });
  }

  testWidgets('子会话：发送被受理后恰好一次轻触', (tester) async {
    final haptics = _Haptics(tester);
    final service = _ChildService();
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: SubagentChatPage(
          key: const ValueKey('child-page'),
          agent: _agent(),
          service: service,
        ),
      ),
    );
    await tester.pump();
    await tester.pump();

    await tester.enterText(_childInput, '继续');
    await tester.pump();
    expect(haptics.total, 0);

    await tester.tap(_childSend);
    await tester.pump();
    await tester.pump();

    expect(service.sent, ['继续']);
    expect(haptics.light, 1);
    expect(_childText(tester), isEmpty);
    expect(
      tester.widget<TextField>(_childInput).focusNode?.hasFocus,
      isFalse,
      reason: '子会话发送受理后也应收起键盘',
    );
  });

  testWidgets('子会话：发送失败零触感且草稿保留', (tester) async {
    final haptics = _Haptics(tester);
    final service = _ChildService()..failSend = true;
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: SubagentChatPage(
          key: const ValueKey('child-page'),
          agent: _agent(),
          service: service,
        ),
      ),
    );
    await tester.pump();
    await tester.pump();

    await tester.enterText(_childInput, '保留我');
    await tester.pump();
    await tester.tap(_childSend);
    await tester.pump();
    await tester.pump();

    expect(service.sent, isEmpty);
    expect(haptics.total, 0);
    expect(_childText(tester), '保留我');
    expect(
      tester.widget<TextField>(_childInput).focusNode!.hasFocus,
      isTrue,
      reason: '失败后重新聚焦子会话输入框，便于修改和重试',
    );
    expect(find.textContaining('send offline'), findsOneWidget);
  });

  testWidgets('主会话：点击转录留白收键盘但保留草稿', (tester) async {
    final transport = _ShellTransport(
      snapshot: const [
        ChatMessage(id: 'u1', role: ChatRole.user, text: '已有消息'),
      ],
    );
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);

    final input = find.byKey(const ValueKey('composer-input'));
    await tester.enterText(input, '还没发送的草稿');
    await tester.pump();
    final focus = tester.widget<TextField>(input).focusNode!;
    expect(focus.hasFocus, isTrue);

    final header = tester.getRect(
      find.byKey(const ValueKey('floating-header-extent')),
    );
    final composer = tester.getRect(
      find.byKey(const ValueKey('floating-composer-extent')),
    );
    await tester.tapAt(
      Offset(
        tester.getSize(find.byType(ChatShell)).width / 2,
        (header.bottom + composer.top) / 2,
      ),
    );
    await tester.pump();

    expect(focus.hasFocus, isFalse);
    expect(_composerText(tester), '还没发送的草稿');
    controller.dispose();
  });

  testWidgets('子会话：点击转录留白收键盘但保留草稿', (tester) async {
    final service = _ChildService();
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: SubagentChatPage(
          key: const ValueKey('child-page'),
          agent: _agent(),
          service: service,
        ),
      ),
    );
    await tester.pump();
    await tester.pump();

    await tester.enterText(_childInput, '子会话草稿');
    await tester.pump();
    final focus = tester.widget<TextField>(_childInput).focusNode!;
    expect(focus.hasFocus, isTrue);

    final header = tester.getRect(
      find.byKey(const ValueKey('floating-header-extent')),
    );
    final composer = tester.getRect(
      find.byKey(const ValueKey('floating-composer-extent')),
    );
    await tester.tapAt(
      Offset(
        tester.getSize(find.byType(SubagentChatPage)).width / 2,
        (header.bottom + composer.top) / 2,
      ),
    );
    await tester.pump();

    expect(focus.hasFocus, isFalse);
    expect(_childText(tester), '子会话草稿');
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('子会话：发送中连点只轻触一次', (tester) async {
    final haptics = _Haptics(tester);
    final service = _ChildService()..gate = Completer<void>();
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: SubagentChatPage(
          key: const ValueKey('child-page'),
          agent: _agent(),
          service: service,
        ),
      ),
    );
    await tester.pump();
    await tester.pump();

    await tester.enterText(_childInput, '只发一次');
    await tester.pump();
    await tester.tap(_childSend);
    await tester.pump();
    expect(
      find.descendant(
        of: _childSend,
        matching: find.byType(CircularProgressIndicator),
      ),
      findsOneWidget,
      reason: '子会话慢发送期间应显示进度',
    );
    expect(tester.widget<IconButton>(_childSend).onPressed, isNull);
    await tester.tap(_childSend, warnIfMissed: false);
    await tester.pump();
    expect(haptics.total, 0);

    service.gate!.complete();
    await tester.pump();
    await tester.pump();

    expect(service.sent, ['只发一次']);
    expect(haptics.light, 1);
  });

  testWidgets('编辑：点气泡直接进入后一次选择反馈', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport(
      snapshot: const [
        ChatMessage(id: 'u1', role: ChatRole.user, text: '原来的问题', seq: 7),
      ],
    );
    final controller = ChatController(transport);
    await _pumpShell(tester, controller, actions: StaticConversationActions());

    await tester.tap(find.byKey(const ValueKey('message-bubble-u1')));
    await tester.pump();
    await tester.pump();

    expect(_composerText(tester), '原来的问题');
    expect(find.byKey(const ValueKey('composer-editing')), findsOneWidget);
    expect(find.byType(SnackBar), findsNothing);
    expect(haptics.selection, 1);
    expect(haptics.light, 0);

    await _flushNotice(tester);
    controller.dispose();
  });

  testWidgets('编辑：确认切换另一条只反馈一次', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport(
      snapshot: const [
        ChatMessage(id: 'u1', role: ChatRole.user, text: '原来的问题', seq: 7),
        ChatMessage(id: 'u2', role: ChatRole.user, text: '另一条', seq: 9),
      ],
    );
    final controller = ChatController(transport);
    await _pumpShell(tester, controller, actions: StaticConversationActions());

    await tester.tap(find.byKey(const ValueKey('message-bubble-u1')));
    await tester.pump();
    await tester.pump();
    expect(haptics.selection, 1);

    await tester.tap(find.byKey(const ValueKey('message-bubble-u2')));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('retract-confirm')),
      findsOneWidget,
      reason: '切换要先确认',
    );
    expect(haptics.selection, 1, reason: '弹确认框本身不是进入');

    await tester.tap(find.byKey(const ValueKey('retract-confirm')));
    await tester.pumpAndSettle();

    expect(_composerText(tester), '另一条');
    expect(haptics.selection, 2, reason: '一次真实切换只加一次，不能取消一次再加进入一次');
    await _flushNotice(tester);
    controller.dispose();
  });

  testWidgets('编辑：取消切换不反馈', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport(
      snapshot: const [
        ChatMessage(id: 'u1', role: ChatRole.user, text: '原来的问题', seq: 7),
        ChatMessage(id: 'u2', role: ChatRole.user, text: '另一条', seq: 9),
      ],
    );
    final controller = ChatController(transport);
    await _pumpShell(tester, controller, actions: StaticConversationActions());

    await tester.tap(find.byKey(const ValueKey('message-bubble-u1')));
    await tester.pump();
    await tester.pump();
    expect(haptics.selection, 1);

    await tester.tap(find.byKey(const ValueKey('message-bubble-u2')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('retract-cancel')));
    await tester.pumpAndSettle();

    expect(_composerText(tester), '原来的问题');
    expect(haptics.selection, 1, reason: '取消后仍停在第一条，没有新的进入');
    await _flushNotice(tester);
    controller.dispose();
  });

  testWidgets('编辑：正在编辑的同一条再点不反馈', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport(
      snapshot: const [
        ChatMessage(id: 'u1', role: ChatRole.user, text: '原来的问题', seq: 7),
      ],
    );
    final controller = ChatController(transport);
    await _pumpShell(tester, controller, actions: StaticConversationActions());

    await tester.tap(find.byKey(const ValueKey('message-bubble-u1')));
    await tester.pump();
    await tester.pump();
    expect(haptics.selection, 1);

    // The row under edit drops its tap target, so a second tap is a no-op.
    await tester.tap(
      find.byKey(const ValueKey('message-bubble-u1')),
      warnIfMissed: false,
    );
    await tester.pump();
    await tester.pump();

    expect(find.byKey(const ValueKey('retract-confirm')), findsNothing);
    expect(_composerText(tester), '原来的问题');
    expect(haptics.selection, 1);
    await _flushNotice(tester);
    controller.dispose();
  });

  testWidgets('编辑：键入字符与无关帧不反馈', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport(
      snapshot: const [
        ChatMessage(id: 'u1', role: ChatRole.user, text: '原来的问题', seq: 7),
      ],
    );
    final controller = ChatController(transport);
    await _pumpShell(tester, controller, actions: StaticConversationActions());

    await tester.tap(find.byKey(const ValueKey('message-bubble-u1')));
    await tester.pump();
    await tester.pump();
    expect(haptics.selection, 1);

    await tester.enterText(_input, '原来的问题改一点');
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(_composerText(tester), '原来的问题改一点');
    expect(haptics.total, 1, reason: '只有进入编辑那一次，键入不响');
    await _flushNotice(tester);
    controller.dispose();
  });

  testWidgets('编辑：取消编辑不反馈且草稿回位', (tester) async {
    final haptics = _Haptics(tester);
    final transport = _ShellTransport(
      snapshot: const [
        ChatMessage(id: 'u1', role: ChatRole.user, text: '原来的问题', seq: 7),
      ],
    );
    final controller = ChatController(transport);
    await _pumpShell(tester, controller, actions: StaticConversationActions());

    await tester.enterText(_input, '没写完的草稿');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('message-bubble-u1')));
    await tester.pump();
    await tester.pump();
    expect(_composerText(tester), '原来的问题');
    expect(haptics.selection, 1);

    await tester.tap(find.byKey(const ValueKey('composer-cancel-edit')));
    await tester.pump();
    await tester.pump();

    expect(_composerText(tester), '没写完的草稿');
    expect(find.byKey(const ValueKey('composer-editing')), findsNothing);
    expect(haptics.total, 1, reason: '退出编辑不是进入编辑');
    await _flushNotice(tester);
    controller.dispose();
  });
}
