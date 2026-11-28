import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/state/chat_reducer.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';
import 'package:dsh_native_chat/src/transport/dsh_session_projection.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/action_cards.dart';
import 'package:dsh_native_chat/src/ui/conversation_flow.dart';
import 'package:dsh_native_chat/src/ui/execution_group.dart';
import 'package:dsh_native_chat/src/ui/tool_run_card.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _maxStackGap = 4.0;

ToolRun _command(String id, String messageId) => ToolRun(
  id: id,
  name: 'bash',
  messageId: messageId,
  status: ToolStatus.complete,
  content: ToolContent(
    title: 'bash',
    command: CommandResult(
      command: 'echo $id',
      stdout: 'OUTPUT-$id',
      exitCode: 0,
    ),
  ),
);

Finder _card(String id) => find.byWidgetPredicate(
  (widget) => widget is ToolRunCard && widget.tool.id == id,
);

Future<void> _pumpFlow(
  WidgetTester tester, {
  required List<ChatMessage> messages,
  required List<ToolRun> tools,
  List<Widget> trailing = const [],
  (int, String)? pendingApproval,
  int? historyPageSize,
}) async {
  tester.view.physicalSize = const Size(420, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: Scaffold(
        body: ConversationFlow(
          conversationIdentity: 'stack',
          messages: messages,
          tools: tools,
          trailing: trailing,
          pendingApproval: pendingApproval,
          historyPageSize: historyPageSize,
        ),
      ),
    ),
  );
}

Finder _transcriptScroll(WidgetTester tester) {
  final list = find.byKey(const ValueKey('conversation-list'));
  final controller = tester.widget<ListView>(list).controller;
  return find.descendant(
    of: list,
    matching: find.byWidgetPredicate(
      (widget) =>
          widget is Scrollable && identical(widget.controller, controller),
    ),
  );
}

// Open every existing group so the old implementation fails on measured gaps,
// not merely because a tool remains hidden behind a second disclosure.
Future<void> _openGroups(WidgetTester tester) async {
  final keys = tester
      .widgetList<TextButton>(find.byType(TextButton))
      .map((button) => button.key)
      .whereType<ValueKey<String>>()
      .where((key) => key.value.startsWith('execution-toggle-'))
      .toList();
  for (final key in keys) {
    await tester.ensureVisible(find.byKey(key));
    await tester.tap(find.byKey(key));
    await tester.pumpAndSettle();
  }
}

void _expectGap(WidgetTester tester, Finder before, Finder after) {
  expect(before, findsOneWidget);
  expect(after, findsOneWidget);
  final gap = tester.getRect(after).top - tester.getRect(before).bottom;
  expect(gap, greaterThanOrEqualTo(0), reason: 'Stack rows must not overlap');
  expect(
    gap,
    lessThanOrEqualTo(_maxStackGap),
    reason: 'Adjacent execution rows must have at most 4dp of empty space',
  );
}

// Exercise the production journal adapter and reducer, not a hand-authored
// ToolRun list: tool-call-only assistant messages do not create ChatMessages,
// and real bash content contains both parameter/result reasoning and a command.
class _StackJournal {
  _StackJournal() {
    apply(
      projection.accept({
        'type': 'snapshot',
        'header': {'id': 'stack'},
        'cursor': -1,
        'records': <Object?>[],
        'hasMore': false,
        'projections': {'asOfSeq': -1, 'values': <String, Object?>{}},
        'assistantStream': {'revision': 0},
      }),
    );
  }

  final projection = DshSessionProjection(sessionId: 'stack', generation: 13);
  ChatState state = ChatState.empty();
  int _seq = 0;

  void apply(List<ChatEvent> events) {
    for (final event in events) {
      state = ChatReducer.apply(state, event);
    }
  }

  void record(String type, Map<String, Object?> data) {
    final seq = _seq++;
    apply(
      projection.accept({
        'type': 'event',
        'event': {
          'seq': seq,
          'time': seq * 10,
          'type': type,
          'data': data,
          'surfaceOp': 'append',
        },
      }),
    );
  }

  void user(String text) => record('user/message', {
    'source': {'kind': 'user'},
    'content': [
      {'type': 'text', 'text': text},
    ],
  });

  void start(int turn) => record('turn/start', {'turn': turn});

  void step(
    int turn,
    int step,
    String callId, {
    String reasoning = '',
    String body = '',
    bool failed = false,
    bool pending = false,
    String? output,
  }) {
    record('step/start', {'turn': turn, 'step': step});
    record('assistant/message', {
      'turn': turn,
      'step': step,
      'message': {
        'id': 'assistant-' + turn.toString() + '-' + step.toString(),
        'content': [
          if (reasoning.isNotEmpty) {'type': 'reasoning', 'text': reasoning},
          if (body.isNotEmpty) {'type': 'text', 'text': body},
          {
            'type': 'tool-call',
            'id': callId,
            'name': 'bash',
            'arguments': '{"command":"echo ' + callId + '"}',
          },
        ],
      },
      'stream': <Object?>[],
    });
    record('tool/call', {
      'turn': turn,
      'step': step,
      'callId': callId,
      'name': 'bash',
      'arguments': '{"command":"echo ' + callId + '"}',
    });
    if (!pending) {
      result(turn, step, callId, failed: failed, output: output);
      record('step/end', {'turn': turn, 'step': step});
    }
  }

  void result(
    int turn,
    int step,
    String callId, {
    bool failed = false,
    String? output,
  }) => record('tool/result', {
    'turn': turn,
    'step': step,
    'message': {
      'source': {'kind': 'tool', 'callId': callId},
      'isError': failed,
      'content': [
        {'type': 'text', 'text': output ?? 'RESULT-' + callId},
      ],
    },
    'meta': {
      'stdout': output ?? 'OUTPUT-' + callId,
      'stderr': failed ? 'FAILED-' + callId : '',
      'exitCode': failed ? 7 : 0,
    },
  });

  void answer(int turn, int step, String text) {
    record('step/start', {'turn': turn, 'step': step});
    record('assistant/message', {
      'turn': turn,
      'step': step,
      'message': {
        'id': 'answer-' + turn.toString(),
        'content': [
          {'type': 'text', 'text': text},
        ],
      },
      'stream': <Object?>[],
    });
    record('step/end', {'turn': turn, 'step': step});
    record('turn/end', {
      'turn': turn,
      'reason': {'kind': 'completed'},
    });
  }
}

_StackJournal _multiStepJournal() => _StackJournal()
  ..user('PROJECTED-PROMPT')
  ..start(1)
  ..step(1, 1, 'projected-one')
  ..step(1, 2, 'projected-two', reasoning: 'STEP-2-THOUGHT', failed: true)
  ..step(1, 3, 'projected-three', reasoning: 'STEP-3-THOUGHT')
  ..answer(1, 4, 'PROJECTED-FINAL');

String _longThought() => [
  'THOUGHT-1',
  'THOUGHT-2',
  'THOUGHT-3',
  'THOUGHT-4',
  'THOUGHT-5',
  'THOUGHT-6',
].join('\n');

Finder _thought(_StackJournal journal, String text) => find.byKey(
  ValueKey((
    'reasoning',
    journal.state.messages
        .singleWhere((message) => message.reasoning == text)
        .id,
  )),
);

Future<void> _pumpJournal(
  WidgetTester tester,
  _StackJournal journal, {
  List<Widget> trailing = const [],
  (int, String)? pendingApproval,
  int? historyPageSize,
}) => _pumpFlow(
  tester,
  messages: journal.state.messages,
  tools: journal.state.tools,
  trailing: trailing,
  pendingApproval: pendingApproval,
  historyPageSize: historyPageSize,
);

void main() {
  testWidgets(
    'a single structured tool skips the redundant execution disclosure',
    (tester) async {
      final journal = _StackJournal()
        ..user('SINGLE-TOOL-PROMPT')
        ..start(1)
        ..step(1, 1, 'single-tool', output: 'SINGLE-TOOL-OUTPUT')
        ..answer(1, 2, 'SINGLE-TOOL-FINAL');
      await _pumpJournal(tester, journal);

      expect(find.byType(ExecutionGroupHeader), findsNothing);
      expect(_card('single-tool'), findsOneWidget);
      final command = find.byKey(const ValueKey('process-command-single-tool'));
      expect(command, findsOneWidget);
      expect(find.text('命令'), findsNothing);
      expect(find.text('bash'), findsOneWidget);

      await tester.tap(command);
      await tester.pumpAndSettle();
      expect(find.text('SINGLE-TOOL-OUTPUT'), findsOneWidget);
      expect(find.text('SINGLE-TOOL-FINAL'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('compact command rows and expanded output keep tight spacing', (
    tester,
  ) async {
    final journal = _StackJournal()
      ..user('DENSITY-PROMPT')
      ..start(1)
      ..step(1, 1, 'density-one', output: 'OUTPUT-density-one')
      ..step(1, 2, 'density-two', output: 'OUTPUT-density-two')
      ..answer(1, 3, 'DENSITY-FINAL');
    await _pumpJournal(tester, journal);
    await _openGroups(tester);
    await tester.pump(const Duration(milliseconds: 200));
    final first = find.byKey(const ValueKey('process-command-density-one'));
    final second = find.byKey(const ValueKey('process-command-density-two'));
    expect(first, findsOneWidget);
    expect(second, findsOneWidget);
    final commandLabel = find.descendant(
      of: first,
      matching: find.text('bash'),
    );
    expect(tester.widget<Text>(commandLabel).style?.fontSize, 12);
    final summary = find.text('已调用工具，已运行命令');
    expect(tester.widget<Text>(summary).style?.fontSize, 12);
    expect(tester.getSize(first).height, greaterThanOrEqualTo(48));
    expect(tester.getSize(second).height, greaterThanOrEqualTo(48));
    expect(
      tester.getTopLeft(second).dy - tester.getTopLeft(first).dy,
      lessThanOrEqualTo(50),
    );

    await tester.tap(first);
    await tester.pumpAndSettle();
    final command = find.byKey(
      const ValueKey('process-output-density-one-command'),
    );
    final stdout = find.byKey(
      const ValueKey('process-output-density-one-stdout'),
    );
    expect(command, findsOneWidget);
    expect(stdout, findsOneWidget);
    expect(tester.widget<Text>(command).data, 'echo density-one');
    expect(tester.widget<Text>(command).style?.fontSize, 12);
    expect(tester.widget<Text>(stdout).data, 'OUTPUT-density-one');
    expect(tester.widget<Text>(stdout).style?.fontSize, 12);
    final firstCard = _card('density-one');
    final secondCard = _card('density-two');
    for (final card in [firstCard, secondCard]) {
      expect(
        find.descendant(
          of: card,
          matching: find.byWidgetPredicate(
            (widget) => widget is Icon && widget.semanticLabel == '已完成',
          ),
        ),
        findsOneWidget,
      );
    }
    expect(
      tester.getTopLeft(stdout).dy - tester.getBottomLeft(command).dy,
      lessThanOrEqualTo(30),
    );
    final stdoutSection = find.byKey(
      const ValueKey('process-output-section-density-one-stdout'),
    );
    expect(stdoutSection, findsOneWidget);
    expect(tester.getSize(stdoutSection).height, lessThanOrEqualTo(56));
    expect(
      find.byKey(const ValueKey('process-copy-density-one-stdout')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('process-menu-density-one-command')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('process-output-density-one-reasoning')),
      findsNothing,
    );
    expect(
      tester.getRect(_card('density-one')).bottom -
          tester.getBottomLeft(stdout).dy,
      lessThanOrEqualTo(8),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'projected multi-step calls open all tools and thoughts with one disclosure',
    (tester) async {
      final journal = _multiStepJournal();
      // Guard the real projection shape that the old synthetic fixture missed.
      expect(
        journal.state.tools.first.messageId,
        journal.state.messages.first.id,
      );
      expect(journal.state.messages.first.role, ChatRole.user);
      expect(journal.state.tools[1].status, ToolStatus.failed);
      expect(journal.state.tools.first.content!.reasoning, contains('参数'));
      expect(journal.state.tools.first.content!.command, isNotNull);
      expect(journal.state.messages.last.isFinalAnswer, isTrue);
      await _pumpJournal(tester, journal);

      expect(find.byType(ExecutionGroupHeader), findsOneWidget);
      final header = tester.widget<ExecutionGroupHeader>(
        find.byType(ExecutionGroupHeader),
      );
      expect(header.tools.map((tool) => tool.id), [
        'projected-one',
        'projected-two',
        'projected-three',
      ]);
      expect(find.textContaining('1项失败'), findsOneWidget);
      expect(_card('projected-one'), findsNothing);
      expect(_thought(journal, 'STEP-2-THOUGHT'), findsNothing);
      expect(_thought(journal, 'STEP-3-THOUGHT'), findsNothing);
      final toggle = find.byKey(ValueKey('execution-toggle-' + header.groupId));
      await tester.tap(toggle);
      await tester.pumpAndSettle();

      for (final id in ['projected-one', 'projected-two', 'projected-three']) {
        expect(_card(id), findsOneWidget);
      }
      expect(_thought(journal, 'STEP-2-THOUGHT'), findsNothing);
      expect(_thought(journal, 'STEP-3-THOUGHT'), findsNothing);
      expect(
        tester.widget<ToolRunCard>(_card('projected-two')).executionReasoning,
        'STEP-2-THOUGHT',
      );
      expect(
        tester.widget<ToolRunCard>(_card('projected-three')).executionReasoning,
        'STEP-3-THOUGHT',
      );
      expect(
        tester.getRect(_card('projected-one')).bottom,
        lessThanOrEqualTo(tester.getRect(_card('projected-two')).top),
      );
      expect(
        tester.getRect(_card('projected-three')).bottom,
        lessThan(tester.getRect(find.text('PROJECTED-FINAL')).top),
      );
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(find.byType(ToolRunCard), findsNothing);
      expect(_thought(journal, 'STEP-2-THOUGHT'), findsNothing);
      expect(_thought(journal, 'STEP-3-THOUGHT'), findsNothing);
      expect(find.text('PROJECTED-FINAL'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'projected step boundaries add no more than 4dp inside the expanded stack',
    (tester) async {
      final journal = _multiStepJournal();
      await _pumpJournal(tester, journal);
      // Deliberately open all old summaries so this is a geometry assertion, not
      // an early failure caused by an unopened second group.
      await _openGroups(tester);
      _expectGap(tester, _card('projected-one'), _card('projected-two'));
      _expectGap(tester, _card('projected-two'), _card('projected-three'));
      expect(_thought(journal, 'STEP-2-THOUGHT'), findsNothing);
      expect(_thought(journal, 'STEP-3-THOUGHT'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'projected commands use one compact 48dp row before output disclosure',
    (tester) async {
      await _pumpJournal(tester, _multiStepJournal());
      await _openGroups(tester);
      for (final id in ['projected-one', 'projected-two', 'projected-three']) {
        expect(_card(id), findsOneWidget);
        expect(tester.widget<ToolRunCard>(_card(id)).compact, isTrue);
        final height = tester.getSize(_card(id)).height;
        expect(
          height,
          greaterThanOrEqualTo(48),
          reason: 'Keep an accessible touch target',
        );
        expect(
          height,
          lessThanOrEqualTo(48),
          reason: 'Raw parameter/result reasoning must not add a second row',
        );
      }
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'execution-stack thoughts use the compact preview and typography',
    (tester) async {
      final journal = _StackJournal()
        ..user('COMPACT-THOUGHT-PROMPT')
        ..start(1)
        ..step(1, 1, 'compact-thought-tool', reasoning: _longThought())
        ..answer(1, 2, 'COMPACT-THOUGHT-FINAL');
      await _pumpJournal(tester, journal);
      await _openGroups(tester);

      await tester.tap(
        find.byKey(const ValueKey('process-command-compact-thought-tool')),
      );
      await tester.pumpAndSettle();
      final thought = find.byKey(
        const ValueKey(
          'process-output-compact-thought-tool-execution-reasoning',
        ),
      );
      expect(thought, findsOneWidget);
      expect(tester.widget<Text>(thought).maxLines, 3);
      expect(tester.widget<Text>(thought).style?.fontSize, 12);
      expect(find.text('THOUGHT-4'), findsNothing);
      final command = find.byKey(
        const ValueKey('process-output-compact-thought-tool-command'),
      );
      expect(
        tester.getTopLeft(command).dy - tester.getBottomLeft(thought).dy,
        inInclusiveRange(0, 4),
        reason: 'Read-only previews must not reserve space for action rows',
      );
      final menu = find.byKey(
        const ValueKey('process-menu-compact-thought-tool-command'),
      );
      expect(tester.getSize(menu).shortestSide, greaterThanOrEqualTo(48));
      await tester.tap(menu);
      await tester.pumpAndSettle();
      expect(find.text('复制思考过程'), findsOneWidget);
      await tester.tap(find.text('查看完整思考过程'));
      await tester.pumpAndSettle();
      final fullThought = find.byWidgetPredicate(
        (widget) =>
            widget is SelectableText &&
            widget.data?.contains('THOUGHT-4') == true,
      );
      expect(fullThought, findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'projected prose and real turn boundaries keep separate execution summaries',
    (tester) async {
      final journal = _StackJournal()
        ..user('FIRST-TURN')
        ..start(1)
        ..step(1, 1, 'before-prose')
        ..step(1, 2, 'after-prose', body: 'VISIBLE-PROSE')
        ..step(1, 3, 'after-prose-step', reasoning: 'AFTER-PROSE-THOUGHT')
        ..answer(1, 4, 'FIRST-FINAL')
        ..user('SECOND-TURN')
        ..start(2)
        ..step(2, 1, 'next-turn')
        ..answer(2, 2, 'SECOND-FINAL');
      await _pumpJournal(tester, journal);
      expect(find.byType(ExecutionGroupHeader), findsOneWidget);
      expect(
        tester
            .widgetList<ExecutionGroupHeader>(find.byType(ExecutionGroupHeader))
            .map((group) => group.tools.map((tool) => tool.id).toList())
            .toList(),
        [
          ['after-prose', 'after-prose-step'],
        ],
      );
      for (final text in [
        'VISIBLE-PROSE',
        'FIRST-FINAL',
        'SECOND-TURN',
        'SECOND-FINAL',
      ]) {
        expect(find.text(text), findsOneWidget);
      }
      await _openGroups(tester);
      expect(
        tester.getRect(_card('before-prose')).bottom,
        lessThan(tester.getRect(find.text('VISIBLE-PROSE')).top),
      );
      expect(
        tester.getRect(find.text('VISIBLE-PROSE')).bottom,
        lessThan(tester.getRect(_card('after-prose')).top),
      );
      expect(
        tester.getRect(_card('after-prose-step')).bottom,
        lessThan(tester.getRect(find.text('FIRST-FINAL')).top),
      );
      expect(
        tester.getRect(find.text('SECOND-TURN')).bottom,
        lessThan(tester.getRect(_card('next-turn')).top),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'projected live steps keep disclosure identity and pending approval actionable',
    (tester) async {
      final journal = _StackJournal()
        ..user('LIVE-PROMPT')
        ..start(1)
        ..step(1, 1, 'live-one');
      await _pumpJournal(tester, journal);
      expect(find.byType(ExecutionGroupHeader), findsNothing);
      expect(_card('live-one'), findsOneWidget);

      journal.step(1, 2, 'live-two', reasoning: 'LIVE-THOUGHT', pending: true);
      const approval = ActionRequest(
        requestId: 'stack-approval',
        title: 'Allow live-two',
        detail: 'Read-only command',
      );
      journal.apply(journal.projection.interaction(approval));
      var approvals = 0;
      await _pumpJournal(
        tester,
        journal,
        pendingApproval: (13, approval.requestId),
        trailing: [
          ApprovalCard(
            request: approval,
            onApprove: () => approvals++,
            onStop: () {},
          ),
        ],
      );
      // A running progress indicator never settles: use finite pumps, not
      // pumpAndSettle, and always dispose the live tree at test teardown.
      addTearDown(() async {
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
      });
      await tester.pump(const Duration(milliseconds: 250));
      expect(journal.state.status, ChatStatus.waitingAction);
      expect(journal.state.tools.last.status, ToolStatus.running);
      expect(find.byType(ExecutionGroupHeader), findsOneWidget);
      final updated = tester.widget<ExecutionGroupHeader>(
        find.byType(ExecutionGroupHeader),
      );
      expect(updated.groupId, startsWith('message-'));
      expect(updated.expanded, isFalse);
      expect(updated.tools.map((tool) => tool.id), ['live-one', 'live-two']);
      final originalToggle = find.byKey(
        ValueKey('execution-toggle-' + updated.groupId),
      );
      await tester.tap(originalToggle);
      await tester.pump(const Duration(milliseconds: 250));
      expect(_card('live-two'), findsOneWidget);
      expect(find.textContaining('正在调用工具'), findsOneWidget);
      await tester.tap(originalToggle);
      await tester.pump(const Duration(milliseconds: 250));
      expect(find.byType(ToolRunCard), findsNothing);
      expect(find.byKey(const ValueKey('approval-card')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('approval-approve')));
      expect(approvals, 1);
      journal.apply(journal.projection.interaction(null));
      journal.result(1, 2, 'live-two');
      journal.record('step/end', {'turn': 1, 'step': 2});
      journal.answer(1, 3, 'LIVE-FINAL');
      await _pumpJournal(tester, journal);
      await tester.pump(const Duration(milliseconds: 250));
      expect(find.byKey(const ValueKey('approval-card')), findsNothing);
      expect(journal.state.status, ChatStatus.ready);
      expect(
        tester
            .widget<ExecutionGroupHeader>(find.byType(ExecutionGroupHeader))
            .expanded,
        isFalse,
      );
      expect(find.text('LIVE-FINAL'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'paged projected stack keeps surviving tool and detail open when user exits',
    (tester) async {
      final journal = _StackJournal()
        ..user('WINDOW-U')
        ..start(1)
        ..step(1, 1, 'window-a')
        ..step(1, 2, 'window-b', reasoning: 'WINDOW-R1');
      final user = journal.state.messages.first;
      final reasoning = journal.state.messages.last;
      expect(journal.state.messages.length, 2);
      expect(user.role, ChatRole.user);
      expect(journal.state.tools[0].messageId, user.id);
      expect(journal.state.tools[1].messageId, reasoning.id);
      await _pumpJournal(tester, journal, historyPageSize: 2);
      await tester.pumpAndSettle();
      final original = tester.widget<ExecutionGroupHeader>(
        find.byType(ExecutionGroupHeader),
      );
      expect(original.groupId, 'message-' + user.id);
      await tester.tap(
        find.byKey(ValueKey('execution-toggle-' + original.groupId)),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('process-command-window-b')));
      await tester.pumpAndSettle();
      expect(find.text('WINDOW-R1'), findsOneWidget);
      final toolState = tester.state(_card('window-b'));
      final output = find.byKey(
        const ValueKey('process-output-window-b-stdout'),
      );
      expect(output, findsOneWidget);
      expect(find.text('OUTPUT-window-b'), findsOneWidget);
      expect(
        tester
            .widget<ListView>(find.byType(ListView))
            .controller!
            .position
            .extentAfter,
        lessThanOrEqualTo(80),
        reason: 'The live page must slide rather than freeze a reading page',
      );

      journal.record('step/start', {'turn': 1, 'step': 3});
      journal.record('assistant/message', {
        'turn': 1,
        'step': 3,
        'message': {
          'id': 'window-r2',
          'content': [
            {'type': 'reasoning', 'text': 'WINDOW-R2'},
          ],
        },
        'stream': <Object?>[],
      });
      expect(journal.state.messages.length, 3);
      await _pumpJournal(tester, journal, historyPageSize: 2);
      await tester.pumpAndSettle();

      expect(find.text('WINDOW-U'), findsNothing);
      expect(_card('window-a'), findsNothing);
      expect(find.byType(ExecutionGroupHeader), findsNothing);
      expect(_card('window-b'), findsOneWidget);
      expect(_thought(journal, 'WINDOW-R1'), findsNothing);
      expect(find.text('WINDOW-R1'), findsOneWidget);
      expect(tester.state(_card('window-b')), same(toolState));
      expect(_thought(journal, 'WINDOW-R2'), findsOneWidget);
      expect(output, findsOneWidget);
      expect(find.text('OUTPUT-window-b'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'projected file reads label tool results separately from thinking',
    (tester) async {
      final journal = _StackJournal()
        ..user('READ-PROMPT')
        ..start(1)
        ..record('tool/call', {
          'turn': 1,
          'step': 1,
          'callId': 'read-file',
          'name': 'read',
          'arguments': '{"file_path":"fixture.txt"}',
        })
        ..result(1, 1, 'read-file', output: 'FILE-OUTPUT')
        ..answer(1, 2, 'READ-FINAL');
      await _pumpJournal(tester, journal);
      await _openGroups(tester);
      final row = _card('read-file');
      expect(
        find.descendant(of: row, matching: find.text('read')),
        findsOneWidget,
      );
      expect(find.descendant(of: row, matching: find.text('思考')), findsNothing);
      await tester.tap(
        find.byKey(const ValueKey('process-reasoning-read-file')),
      );
      await tester.pumpAndSettle();
      expect(find.text('参数与结果'), findsOneWidget);
      expect(
        tester
            .widget<Text>(
              find.byKey(const ValueKey('process-output-read-file-reasoning')),
            )
            .data,
        contains('FILE-OUTPUT'),
      );
    },
  );

  testWidgets(
    'projected grouped command keeps complete long output reachable',
    (tester) async {
      final output = List.generate(
        200,
        (index) => 'LONG-OUTPUT-' + index.toString(),
      ).join('\n');
      final journal = _StackJournal()
        ..user('LONG-PROMPT')
        ..start(1)
        ..step(1, 1, 'long-one')
        ..step(1, 2, 'long-two', reasoning: 'LONG-THOUGHT', output: output)
        ..answer(1, 3, 'LONG-FINAL');
      await _pumpJournal(tester, journal);
      await _openGroups(tester);
      await tester.tap(find.byKey(const ValueKey('process-command-long-two')));
      await tester.pumpAndSettle();
      final preview = tester.widget<Text>(
        find.byKey(const ValueKey('process-output-long-two-stdout')),
      );
      expect(preview.data, startsWith('LONG-OUTPUT-0\n'));
      expect(preview.data, isNot(contains('LONG-OUTPUT-199')));
      final full = find.byKey(const ValueKey('process-full-long-two-stdout'));
      final transcript = _transcriptScroll(tester);
      for (var i = 0; i < 6 && full.evaluate().isEmpty; i++) {
        await tester.drag(transcript, const Offset(0, -320));
        await tester.pumpAndSettle();
      }
      expect(full, findsNothing);
      await tester.tap(
        find.byKey(const ValueKey('process-menu-long-two-command')),
      );
      await tester.pumpAndSettle();
      await tester.tap(
        find.ancestor(
          of: find.text('查看完整输出'),
          matching: find.byType(PopupMenuItem<String>),
        ),
      );
      await tester.pumpAndSettle();
      final fullList = find.byKey(
        const ValueKey('process-full-list-long-two-command-full'),
      );
      expect(fullList, findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('LONG-OUTPUT-199'),
        500,
        scrollable: find.descendant(
          of: fullList,
          matching: find.byType(Scrollable),
        ),
        maxScrolls: 30,
      );
      expect(find.text('LONG-OUTPUT-199'), findsOneWidget);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<ConversationFlow>(find.byType(ConversationFlow))
            .tools
            .last
            .content!
            .command!
            .stdout,
        output,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'projected appended step preserves an older visible reading anchor',
    (tester) async {
      final journal = _StackJournal();
      for (var turn = 1; turn <= 20; turn++) {
        journal.user('HISTORY-PROMPT-' + turn.toString());
        journal.start(turn);
        journal.answer(turn, 1, 'HISTORY-ANSWER-' + turn.toString());
      }
      journal.user('ANCHOR-LIVE');
      journal.start(21);
      journal.step(21, 1, 'anchor-one', reasoning: 'ANCHOR-THOUGHT');
      await _pumpJournal(tester, journal);
      final scrollable = _transcriptScroll(tester);
      final anchor = find.text('HISTORY-ANSWER-10');
      await tester.scrollUntilVisible(
        anchor,
        300,
        scrollable: scrollable,
        maxScrolls: 30,
      );
      await tester.pumpAndSettle();
      final before = tester.getTopLeft(anchor).dy;
      final position = tester.state<ScrollableState>(scrollable).position;
      expect(
        position.extentAfter,
        greaterThan(80),
        reason: 'The reader must be away from the live tail',
      );
      journal.step(21, 2, 'anchor-two', reasoning: 'APPENDED-THOUGHT');
      await _pumpJournal(tester, journal);
      await tester.pump(const Duration(milliseconds: 250));
      expect(anchor, findsOneWidget);
      expect(tester.getTopLeft(anchor).dy, closeTo(before, 1));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'consecutive tool messages have at most 4dp gaps and no empty rows',
    (tester) async {
      await _pumpFlow(
        tester,
        messages: const [
          ChatMessage(id: 'first', role: ChatRole.assistant, text: ''),
          ChatMessage(
            id: 'empty-between',
            role: ChatRole.assistant,
            text: '  ',
          ),
          ChatMessage(id: 'second', role: ChatRole.assistant, text: ''),
          ChatMessage(id: 'third', role: ChatRole.assistant, text: ''),
        ],
        tools: [
          _command('one', 'first'),
          _command('two', 'second'),
          _command('three', 'third'),
        ],
      );
      await _openGroups(tester);

      _expectGap(tester, _card('one'), _card('two'));
      _expectGap(tester, _card('two'), _card('three'));
      expect(find.byType(ConversationMessage), findsNothing);
      expect(find.byType(ExecutionGroupHeader), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'step reasoning shares its tool row instead of adding a second 48dp row',
    (tester) async {
      await _pumpFlow(
        tester,
        messages: const [
          ChatMessage(
            id: 'read-one-message',
            role: ChatRole.assistant,
            text: '',
            reasoning: 'ASSISTANT-THOUGHT-ONE',
          ),
          ChatMessage(
            id: 'read-two-message',
            role: ChatRole.assistant,
            text: '',
            reasoning: 'ASSISTANT-THOUGHT-TWO',
          ),
          ChatMessage(
            id: 'answer',
            role: ChatRole.assistant,
            text: 'FINAL-ANSWER',
            isFinalAnswer: true,
          ),
        ],
        tools: [
          ToolRun(
            id: 'read-one',
            name: 'read',
            messageId: 'read-one-message',
            status: ToolStatus.complete,
            content: ToolContent(
              title: 'read',
              reasoning: 'TOOL-DETAIL-read-one',
            ),
          ),
          const ToolRun(
            id: 'read-two',
            name: 'read',
            messageId: 'read-two-message',
            status: ToolStatus.complete,
            detail: 'TOOL-DETAIL-read-two',
          ),
        ],
      );
      await _openGroups(tester);

      expect(
        find.byKey(const ValueKey(('reasoning', 'read-one-message'))),
        findsNothing,
      );
      expect(
        tester.getRect(_card('read-one')).height,
        greaterThanOrEqualTo(48),
      );
      expect(tester.getRect(_card('read-one')).height, lessThanOrEqualTo(48));
      expect(
        tester.getRect(_card('read-two')).top -
            tester.getRect(_card('read-one')).top,
        lessThanOrEqualTo(52),
        reason: 'A step thought must not add a second collapsed row',
      );

      await tester.tap(
        find.byKey(const ValueKey('process-reasoning-read-one')),
      );
      await tester.pumpAndSettle();
      expect(find.text('ASSISTANT-THOUGHT-ONE'), findsOneWidget);
      expect(find.text('TOOL-DETAIL-read-one'), findsOneWidget);
      expect(
        tester.getTopLeft(_card('read-two')).dy -
            tester.getBottomLeft(find.text('ASSISTANT-THOUGHT-ONE')).dy,
        inInclusiveRange(0, 4),
      );
      await tester.tap(find.byKey(const ValueKey('tool-card-read-two')));
      await tester.pumpAndSettle();
      expect(find.text('ASSISTANT-THOUGHT-TWO'), findsOneWidget);
      expect(find.text('TOOL-DETAIL-read-two'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'reasoning stays between its real tool calls without new summaries',
    (tester) async {
      await _pumpFlow(
        tester,
        messages: const [
          ChatMessage(
            id: 'first',
            role: ChatRole.assistant,
            text: '',
            reasoning: 'FIRST-THOUGHT',
          ),
          ChatMessage(
            id: 'between',
            role: ChatRole.assistant,
            text: '',
            reasoning: 'BETWEEN-THOUGHT',
          ),
          ChatMessage(id: 'second', role: ChatRole.assistant, text: ''),
          ChatMessage(
            id: 'answer',
            role: ChatRole.assistant,
            text: 'FINAL-ANSWER',
            isFinalAnswer: true,
          ),
        ],
        tools: [_command('one', 'first'), _command('two', 'second')],
      );
      await _openGroups(tester);
      final betweenThought = find.byKey(
        const ValueKey(('reasoning', 'between')),
      );
      expect(find.byKey(const ValueKey(('reasoning', 'first'))), findsNothing);
      expect(
        tester.widget<ToolRunCard>(_card('one')).executionReasoning,
        'FIRST-THOUGHT',
      );
      _expectGap(tester, _card('one'), betweenThought);
      _expectGap(tester, betweenThought, _card('two'));
      expect(
        tester.getRect(_card('two')).bottom,
        lessThan(tester.getRect(find.text('FINAL-ANSWER')).top),
      );
      expect(find.byType(ExecutionGroupHeader), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('expanded command output ends directly above the next tool row', (
    tester,
  ) async {
    await _pumpFlow(
      tester,
      messages: const [
        ChatMessage(id: 'first', role: ChatRole.assistant, text: ''),
        ChatMessage(id: 'second', role: ChatRole.assistant, text: ''),
      ],
      tools: [
        _command('one', 'first'),
        const ToolRun(
          id: 'two',
          name: 'read',
          messageId: 'second',
          status: ToolStatus.complete,
          detail: 'READ-OUTPUT',
        ),
      ],
    );
    await _openGroups(tester);
    await tester.tap(find.byKey(const ValueKey('process-command-one')));
    await tester.pumpAndSettle();
    expect(find.text('OUTPUT-one'), findsOneWidget);
    _expectGap(tester, _card('one'), _card('two'));
    await tester.tap(find.byKey(const ValueKey('tool-card-two')));
    await tester.pumpAndSettle();
    expect(find.text('READ-OUTPUT'), findsOneWidget);
    _expectGap(tester, _card('one'), _card('two'));
    expect(tester.takeException(), isNull);
  });

  testWidgets('a user turn or a real answer terminates the execution stack', (
    tester,
  ) async {
    await _pumpFlow(
      tester,
      messages: const [
        ChatMessage(id: 'first', role: ChatRole.assistant, text: ''),
        ChatMessage(id: 'answer', role: ChatRole.assistant, text: 'ANSWER'),
        ChatMessage(id: 'second', role: ChatRole.assistant, text: ''),
        ChatMessage(id: 'user', role: ChatRole.user, text: 'NEXT-TURN'),
        ChatMessage(id: 'third', role: ChatRole.assistant, text: ''),
      ],
      tools: [
        _command('one', 'first'),
        _command('two', 'second'),
        _command('three', 'third'),
      ],
    );
    expect(find.byType(ExecutionGroupHeader), findsNothing);
    expect(find.byType(ToolRunCard), findsNWidgets(3));
    expect(find.text('ANSWER'), findsOneWidget);
    expect(find.text('NEXT-TURN'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
