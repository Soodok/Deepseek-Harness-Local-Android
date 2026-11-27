import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/state/chat_reducer.dart';
import 'package:dsh_native_chat/src/transport/dsh_session_projection.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';
import 'package:dsh_native_chat/src/state/composer_draft.dart';
import 'package:dsh_native_chat/src/state/conversation_actions.dart';
import 'package:dsh_native_chat/src/state/message_actions.dart';
import 'package:dsh_native_chat/src/state/turn_plan.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/transport/native_action_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/composer.dart';
import 'package:dsh_native_chat/src/ui/conversation_flow.dart';
import 'package:dsh_native_chat/src/ui/retract_dialog.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';

TurnChangeSummary _turn(int turn, int startSeq, int files) => TurnChangeSummary(
  turn: turn,
  startSeq: startSeq,
  files: files,
  created: files,
  modified: 0,
  deleted: 0,
  incomplete: false,
  truncated: false,
);

ChatMessage _user(String id, String text, {int? seq}) =>
    ChatMessage(id: id, role: ChatRole.user, text: text, seq: seq);

ChatMessage _answer(
  String id,
  String text, {
  int? seq,
  String? messageId,
  bool streaming = false,
}) => ChatMessage(
  id: id,
  role: ChatRole.assistant,
  text: text,
  isStreaming: streaming,
  seq: seq,
  messageId: messageId,
  isFinalAnswer: messageId != null && !streaming,
);

const _rewound = RewindOutcome(
  status: 'rewound',
  childSessionId: 'fork-1',
  turn: 0,
);

/// One MessageActions wired to a scripted backend, a real draft and a real
/// fork opener, so the assertions read the same state the shell holds.
class _Harness {
  _Harness({
    StaticConversationActions? backend,
    String draft = '',
    this.onNotice,
  }) : backend = backend ?? StaticConversationActions(),
       draft = ComposerDraft() {
    if (draft.isNotEmpty) this.draft.replace(draft);
    actions = MessageActions(
      backend: this.backend,
      draft: this.draft,
      onOpenFork: (sessionId, prompt) async {
        opened.add((sessionId: sessionId, prompt: prompt));
        return true;
      },
      onNotice: (text, {bool isError = false}) =>
          onNotice?.call((isError ? '!' : '') + text),
    );
  }

  final StaticConversationActions backend;
  final ComposerDraft draft;
  final void Function(String text)? onNotice;
  final opened = <({String sessionId, String prompt})>[];
  late final MessageActions actions;
}

/// A backend whose rewind only completes when the test lets it.
class _GateActions extends StaticConversationActions {
  _GateActions();

  final gate = Completer<RewindOutcome>();

  @override
  Future<RewindOutcome> rewind({
    required int seq,
    required bool includeFiles,
    required String requestId,
    bool force = false,
  }) {
    rewinds.add((
      seq: seq,
      includeFiles: includeFiles,
      requestId: requestId,
      force: force,
    ));
    return gate.future;
  }
}

Widget _app(Widget child) => MaterialApp(
  theme: DshTheme.light(),
  home: Scaffold(body: child),
);

/// Row callbacks shaped exactly like the ones ChatShell builds, so the widgets
/// under test act through the production contract rather than a stub.
MessageActionBindings _bindings(
  _Harness harness, {
  String? editing,
  VoidCallback? onCancelEdit,
}) => MessageActionBindings(
  copy: (message) => unawaitedAction(harness.actions.copy(message.text)),
  retract: (message) => unawaitedAction(
    harness.actions.retract(message, includeFiles: false, replaceDraft: true),
  ),
  edit: harness.actions.beginEdit,
  like: (message) =>
      unawaitedAction(harness.actions.rate(message.messageId!, true)),
  dislike: (message) =>
      unawaitedAction(harness.actions.rate(message.messageId!, false)),
  regenerate: null,
  share: (message) => unawaitedAction(harness.actions.share(message.text)),
  ratingOf: harness.actions.ratingOf,
  isBusy: (messageId) => harness.actions.busyMessageId == messageId,
  wasCopied: (messageId) => harness.actions.lastCopiedId == messageId,
  editingMessageId: editing,
  onCancelEdit: onCancelEdit,
);

void unawaitedAction(Future<void> work) {
  // The row is not allowed to await: a tap must return immediately. Errors are
  // surfaced by MessageActions itself, so nothing is swallowed here.
  work.ignore();
}

/// One controller reused by the composer widgets under test.
final ChatController _controller = ChatController(FixtureTransport());

void _disposeControllers() {}

Future<RetractChoice?> _openDialog(BuildContext context, TurnPlan plan) =>
    showRetractDialog(context, plan: plan, hasDraft: true);

class _HistoricalEditController extends ChatController {
  _HistoricalEditController() : super(FixtureTransport());
  int ordinarySends = 0;

  @override
  ChatState get state => ChatState.empty().copyWith(
    status: ChatStatus.ready,
    messages: [_user('historical', 'Original question', seq: 7)],
  );

  @override
  bool get canSend => true;

  @override
  Future<bool> sendPrompt(String text) async {
    ordinarySends++;
    return true;
  }
}

void main() {
  testWidgets(
    'editing sends from the original sequence, never as a new prompt',
    (tester) async {
      final controller = _HistoricalEditController();
      final backend = StaticConversationActions(
        changes: [_turn(0, 1, 0)],
        rewindResult: _rewound,
      );
      final opened = <(String, String)>[];
      final writes = <String>[];
      final draft = ComposerDraft(
        initialText: 'Unsent draft',
        onTextChanged: writes.add,
      );
      addTearDown(draft.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: ChatShell(
            controller: controller,
            actions: backend,
            draft: draft,
            onOpenForkSession: (id, prompt) async {
              opened.add((id, prompt));
              return true;
            },
          ),
        ),
      );
      await tester.pump();
      final bubble = find.byKey(const ValueKey('message-bubble-historical'));
      expect(
        find.descendant(of: bubble, matching: find.byType(SelectableText)),
        findsNothing,
      );
      await tester.tap(bubble);
      await tester.pump();
      await tester.pump();
      expect(find.byKey(const ValueKey('edit-dimmed-chrome')), findsOneWidget);
      final input = tester.widget<TextField>(
        find.byKey(const ValueKey('composer-input')),
      );
      expect(input.focusNode!.hasFocus, isTrue);
      await tester.enterText(
        find.byKey(const ValueKey('composer-input')),
        'Edited question',
      );
      await tester.tap(find.byKey(const ValueKey('composer-send')));
      await tester.pumpAndSettle();
      expect(controller.ordinarySends, 0);
      await tester.tap(find.byKey(const ValueKey('retract-confirm')));
      await tester.pumpAndSettle();
      expect(backend.rewinds.single.seq, 7);
      expect(opened, [('fork-1', 'Edited question')]);
      expect(draft.text, 'Unsent draft');
      expect(writes, isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );

  testWidgets(
    'an answered turn shows all five actions and a question shows two',
    (tester) async {
      final harness = _Harness();
      await tester.pumpWidget(
        _app(
          ConversationFlow(
            conversationIdentity: 'test',
            messages: [
              _user('u1', '你好', seq: 1),
              _answer('a1', '在', seq: 2, messageId: 'm-1'),
            ],
            actions: _bindings(harness),
          ),
        ),
      );
      for (final key in [
        'answer-like',
        'answer-dislike',
        'answer-copy',
        'answer-regenerate',
        'answer-share',
      ]) {
        expect(find.byKey(ValueKey(key + '-a1')), findsOneWidget, reason: key);
      }
      expect(find.byKey(const ValueKey('message-copy-u1')), findsOneWidget);
      expect(find.byKey(const ValueKey('message-retract-u1')), findsOneWidget);
    },
  );

  testWidgets('only the final assistant reply gets actions after a durable turn end', (
    tester,
  ) async {
    final harness = _Harness();
    final projection = DshSessionProjection(sessionId: 'parent', generation: 1);
    var state = ChatState.empty();
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(800, 1400);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    var seq = -1;
    final records = <Map<String, Object?>>[];
    Map<String, Object?> record(String type, Map<String, Object?> data) {
      final frame = <String, Object?>{
        'type': 'event',
        'event': {'seq': ++seq, 'time': seq, 'type': type, 'data': data},
      };
      records.add(frame);
      return frame;
    }
    Map<String, Object?> reply(int turn, int step, String text) =>
        record('assistant/message', {
          'turn': turn,
          'step': step,
          'message': {
            'id': 'assistant-$turn-$step',
            'content': [{'type': 'text', 'text': text}],
          },
          'stream': [],
        });
    Map<String, Object?> end(int turn) => record('turn/end', {
      'turn': turn,
      'reason': {'kind': 'completed'},
    });
    void accept(Map<String, Object?> frame) {
      for (final event in projection.accept(frame)) {
        state = ChatReducer.apply(state, event);
      }
    }
    Future<void> render() => tester.pumpWidget(_app(ConversationFlow(
      conversationIdentity: 'parent',
      messages: state.messages,
      actions: _bindings(harness),
    )));
    Finder replyAction(String text) => find.descendant(
      of: find.byWidgetPredicate(
        (w) => w is ConversationMessage && w.message.text == text,
      ),
      matching: find.byWidgetPredicate(
        (w) => w is IconButton &&
            (w.key as ValueKey?)?.value.toString().startsWith('answer-like-') == true,
      ),
    );

    void rebaseline() => accept({
      'type': 'snapshot',
      'header': {'id': 'parent'},
      'cursor': seq,
      'records': records,
      'hasMore': false,
      'projections': {'asOfSeq': seq, 'values': <String, Object?>{}},
    });
    reply(1, 1, 'previous answer');
    end(1);
    rebaseline();
    await render();
    expect(replyAction('previous answer'), findsOneWidget);

    accept(record('turn/start', {'turn': 2}));
    for (final step in [1, 2]) {
      accept(reply(2, step, 'intermediate $step'));
      final call = 'read-$step';
      accept(record('tool/call', {
        'turn': 2, 'step': step, 'callId': call,
        'name': 'read', 'arguments': '{}',
      }));
      accept(record('tool/result', {
        'turn': 2, 'step': step,
        'message': {
          'source': {'kind': 'tool', 'callId': call},
          'content': [{'type': 'text', 'text': 'read complete'}],
        },
      }));
      accept(record('step/end', {'turn': 2, 'step': step}));
    }
    // Reload while the third step is pending. Both committed rows must be
    // visible, but neither can act as the completed answer.
    accept(record('step/start', {'turn': 2, 'step': 3}));
    rebaseline();
    await render();
    expect(state.status, ChatStatus.streaming);
    expect(replyAction('previous answer'), findsOneWidget);
    expect(find.text('intermediate 1', findRichText: true), findsOneWidget);
    expect(find.text('intermediate 2', findRichText: true), findsOneWidget);
    expect(replyAction('intermediate 1'), findsNothing);
    expect(replyAction('intermediate 2'), findsNothing);

    accept(reply(2, 3, 'final answer'));
    await render();
    expect(replyAction('final answer'), findsNothing);
    accept(end(2));
    await render();
    expect(state.status, ChatStatus.ready);
    expect(replyAction('previous answer'), findsOneWidget);
    expect(replyAction('intermediate 1'), findsNothing);
    expect(replyAction('intermediate 2'), findsNothing);
    expect(replyAction('final answer'), findsOneWidget);
  });

  testWidgets(
    'a streaming answer offers no action that would act on part of it',
    (tester) async {
      final harness = _Harness();
      await tester.pumpWidget(
        _app(
          ConversationFlow(
            conversationIdentity: 'test',
            messages: [
              _answer('a1', '正在', seq: 2, messageId: 'm-1', streaming: true),
            ],
            actions: _bindings(harness),
          ),
        ),
      );
      expect(find.byKey(const ValueKey('answer-copy-a1')), findsNothing);
      expect(find.byKey(const ValueKey('answer-like-a1')), findsNothing);
      expect(find.byKey(const ValueKey('answer-regenerate-a1')), findsNothing);
    },
  );

  testWidgets('a message the Host never recorded keeps copy but not retract', (
    tester,
  ) async {
    final harness = _Harness();
    await tester.pumpWidget(
      _app(
        ConversationFlow(
          conversationIdentity: 'test',
          messages: [_user('u1', '没有记录的一条')],
          actions: _bindings(harness),
        ),
      ),
    );
    expect(find.byKey(const ValueKey('message-copy-u1')), findsOneWidget);
    final retract = tester.widget<IconButton>(
      find.byKey(const ValueKey('message-retract-u1')),
    );
    expect(
      retract.onPressed,
      isNull,
      reason: 'no seq means the Host cannot be asked to retract it',
    );
  });

  testWidgets('tapping a bubble moves its text into the composer for editing', (
    tester,
  ) async {
    final harness = _Harness(draft: '没写完的草稿');
    await tester.pumpWidget(
      _app(
        ConversationFlow(
          conversationIdentity: 'test',
          messages: [_user('u1', '原来的问题', seq: 7)],
          actions: _bindings(harness),
        ),
      ),
    );
    await tester.tap(find.byKey(const ValueKey('message-bubble-u1')));
    await tester.pump();
    expect(harness.actions.editingText, '原来的问题');
    expect(harness.draft.text, '没写完的草稿');
    expect(harness.actions.editing?.messageId, 'u1');
  });

  testWidgets('the bubble under edit is marked and cannot start a second edit', (
    tester,
  ) async {
    final harness = _Harness();
    await tester.pumpWidget(
      _app(
        ConversationFlow(
          conversationIdentity: 'test',
          messages: [_user('u1', '原来的问题', seq: 7), _user('u2', '另一条', seq: 9)],
          actions: _bindings(harness, editing: 'u1'),
          editingMessageId: 'u1',
        ),
      ),
    );
    final box = tester.widget<Container>(
      find.byKey(const ValueKey('message-bubble-u1')),
    );
    expect((box.decoration! as BoxDecoration).border, isNotNull);
    final other = tester.widget<Container>(
      find.byKey(const ValueKey('message-bubble-u2')),
    );
    expect((other.decoration! as BoxDecoration).border, isNull);
    // Only the edited bubble is marked; a tap on it cannot start a second edit.
    await tester.tap(find.byKey(const ValueKey('message-bubble-u1')));
    await tester.pump();
    expect(harness.actions.editing, isNull);
  });

  testWidgets(
    'a Host conflict touches no file and names the conflicting paths',
    (tester) async {
      final notices = <String>[];
      final harness = _Harness(
        backend: StaticConversationActions(
          changes: [_turn(0, 1, 2)],
          rewindResult: const RewindOutcome(
            status: 'conflict',
            conflicts: ['lib/a.dart', 'lib/b.dart'],
          ),
        ),
        onNotice: notices.add,
      );
      await harness.actions.retract(
        _user('u1', '改了两个文件', seq: 3),
        includeFiles: true,
        replaceDraft: true,
      );
      expect(notices.single.startsWith('!'), isTrue);
      expect(notices.single.contains('lib/a.dart'), isTrue);
      expect(harness.backend.rewinds.single.includeFiles, isTrue);
      expect(
        harness.draft.text,
        isEmpty,
        reason: 'a refused retract keeps no text',
      );
      expect(harness.backend.opened, isEmpty);
    },
  );

  testWidgets('a successful retract refills the composer and opens the fork', (
    tester,
  ) async {
    final harness = _Harness(
      backend: StaticConversationActions(
        changes: [_turn(0, 1, 1)],
        rewindResult: _rewound,
      ),
    );
    await harness.actions.retract(
      _user('u1', '要把这句送回去', seq: 2),
      includeFiles: false,
      replaceDraft: true,
    );
    expect(harness.draft.text, '要把这句送回去');
    expect(harness.opened.single.sessionId, 'fork-1');
    expect(harness.opened.single.prompt, isEmpty);
    expect(harness.backend.rewinds.single.seq, 2);
    expect(harness.backend.rewinds.single.includeFiles, isFalse);
  });

  testWidgets('an existing draft survives when the person asked to keep it', (
    tester,
  ) async {
    final harness = _Harness(
      backend: StaticConversationActions(
        changes: [_turn(0, 1, 0)],
        rewindResult: _rewound,
      ),
      draft: '我先写了一半',
    );
    await harness.actions.retract(
      _user('u1', '撤回的这句', seq: 2),
      includeFiles: false,
      replaceDraft: false,
    );
    expect(harness.draft.text.startsWith('我先写了一半'), isTrue);
    expect(harness.draft.text.contains('撤回的这句'), isTrue);
  });

  testWidgets('cancelling an edit returns the earlier draft untouched', (
    tester,
  ) async {
    final harness = _Harness(draft: '写到一半的草稿');
    harness.actions.beginEdit(_user('u1', '要改的句子', seq: 4));
    expect(harness.actions.editingText, '要改的句子');
    expect(harness.draft.text, '写到一半的草稿');
    harness.actions.cancelEdit();
    expect(harness.draft.text, '写到一半的草稿');
    expect(harness.actions.isEditing, isFalse);
    expect(harness.backend.rewinds, isEmpty);
  });

  testWidgets(
    'sending an edit rewinds its own turn and forwards the new text',
    (tester) async {
      final harness = _Harness(
        backend: StaticConversationActions(
          changes: [_turn(0, 1, 1), _turn(1, 6, 0)],
          rewindResult: const RewindOutcome(
            status: 'rewound',
            childSessionId: 'fork-3',
            turn: 1,
          ),
        ),
      );
      harness.actions.beginEdit(_user('u1', '原来的问法', seq: 7));
      harness.actions.recordEditText('改过的问法');
      await harness.actions.submitEdit(includeFiles: true, replaceDraft: true);
      expect(harness.backend.rewinds.single.seq, 7);
      expect(harness.backend.rewinds.single.includeFiles, isTrue);
      expect(harness.opened.single.sessionId, 'fork-3');
      expect(harness.opened.single.prompt, '改过的问法');
      expect(harness.actions.isEditing, isFalse);
      expect(harness.draft.text, isEmpty);
    },
  );

  testWidgets('a failed edit send keeps the text so it can be retried', (
    tester,
  ) async {
    final harness = _Harness(
      backend: StaticConversationActions(changes: [_turn(0, 1, 0)]),
    );
    harness.actions.beginEdit(_user('u1', '原来的问法', seq: 3));
    harness.actions.recordEditText('改过的问法');
    harness.backend.rewindResult = const RewindOutcome(status: 'busy');
    await harness.actions.submitEdit(includeFiles: false, replaceDraft: true);
    expect(harness.actions.isEditing, isTrue);
    expect(harness.actions.editingText, '改过的问法');
    expect(harness.draft.text, isEmpty);
    expect(harness.opened, isEmpty);
  });

  testWidgets('regenerate restores the files of the turn before asking again', (
    tester,
  ) async {
    final harness = _Harness(
      backend: StaticConversationActions(
        changes: [_turn(0, 1, 1), _turn(1, 5, 2)],
        rewindResult: const RewindOutcome(
          status: 'rewound',
          childSessionId: 'fork-2',
          turn: 1,
        ),
      ),
    );
    await harness.actions.refresh();
    await harness.actions.regenerate(
      _answer('a2', '第二轮的答案', seq: 8, messageId: 'm2'),
      '第二个问题',
    );
    final call = harness.backend.rewinds.single;
    expect(call.seq, 5, reason: 'the rewind starts at the answer turn');
    expect(call.includeFiles, isTrue);
    expect(harness.opened.single.prompt, '第二个问题');
    expect(harness.opened.single.sessionId, 'fork-2');
  });

  testWidgets('a turn finished after the page opened still has its file plan', (
    tester,
  ) async {
    // The Host only records a Turn's snapshot once that Turn is over, so the
    // list read when the page opened cannot contain it. Asking about a Turn
    // that finished afterwards must still see its files, otherwise the person
    // is told "这一段没有改动文件" while the Host holds the snapshot and would
    // restore it.
    final backend = StaticConversationActions(changes: [_turn(0, 1, 0)]);
    final harness = _Harness(backend: backend, onNotice: (_) {});
    await harness.actions.refresh();
    expect(harness.actions.planFor(_user('u2', '写完文件的那一轮', seq: 7)).files, 0);
    // The Turn ends while this page is open; the Host now reports its files.
    backend.changes = [_turn(0, 1, 0), _turn(1, 6, 1)];
    final plan = await harness.actions.planForFresh(
      _user('u2', '写完文件的那一轮', seq: 7),
    );
    expect(plan.turn, 1);
    expect(plan.canRestoreFiles, isTrue, reason: 'the Host records one file');
    expect(describeChanges(plan), '将回滚 1 个文件的改动');
  });

  testWidgets('a stale plan is never shown when the Host cannot be asked', (
    tester,
  ) async {
    // Refreshing before asking is a correctness requirement, not an extra: a
    // dialog that promises "只撤回对话文本" while files exist lies to the
    // person. When the Host is unreachable the caller must learn the plan is
    // unknown rather than receive a reassuring zero.
    final backend = StaticConversationActions(changes: [_turn(0, 1, 0)]);
    final harness = _Harness(backend: backend, onNotice: (_) {});
    await harness.actions.refresh();
    backend.failTurnChanges = true;
    await expectLater(
      harness.actions.planForFresh(_user('u2', '问一句', seq: 7)),
      throwsA(isA<TransportException>()),
    );
  });

  testWidgets('a Host conflict keeps the paths it names in the top level', (
    tester,
  ) async {
    // rc.2's turn-rewind answers a conflict as {status:"conflict", turn,
    // conflicts:[{path, reason}]} with no "files" object. Reading only
    // files.conflicts dropped every name, so the person saw "这些文件在这一轮
    // 之后被改过，没有覆盖：" with nothing after the colon.
    final outcome = RewindOutcome.parse({
      'status': 'conflict',
      'turn': 14,
      'conflicts': [
        {'path': 'conflict-demo.txt', 'reason': 'changed-after-turn'},
      ],
      'turnCount': 1,
    });
    expect(outcome.status, 'conflict');
    expect(outcome.conflicts, ['conflict-demo.txt']);
    expect(outcome.rewound, isFalse);
  });

  testWidgets('a rewound answer still reads the file outcome under files', (
    tester,
  ) async {
    // The rewound shape keeps its results under "files"; both shapes must work.
    final outcome = RewindOutcome.parse({
      'status': 'rewound',
      'childSessionId': 'session-child',
      'turn': 13,
      'files': {
        'applied': true,
        'restored': [
          {'path': 'mod-demo.txt', 'action': 'restored', 'ok': true},
        ],
        'skipped': [],
        'conflicts': [],
      },
    });
    expect(outcome.rewound, isTrue);
    expect(outcome.childSessionId, 'session-child');
    expect(outcome.restored, ['mod-demo.txt']);
    expect(outcome.filesApplied, isTrue);
  });

  testWidgets('regenerate refuses a turn with no recorded snapshot', (
    tester,
  ) async {
    final notices = <String>[];
    final harness = _Harness(
      backend: StaticConversationActions(changes: const []),
      onNotice: notices.add,
    );
    await harness.actions.regenerate(
      _answer('a1', '第一轮的答案', seq: 4, messageId: 'm1'),
      '第一个问题',
    );
    expect(notices.single.startsWith('!'), isTrue);
    expect(harness.backend.rewinds, isEmpty);
    expect(harness.opened, isEmpty);
  });

  testWidgets('a thumbs up is recorded once and can be withdrawn', (
    tester,
  ) async {
    final harness = _Harness();
    await harness.actions.rate('m-9', true);
    expect(harness.backend.ratingCalls.single.positive, isTrue);
    expect(harness.actions.ratingOf('m-9'), isTrue);
    await harness.actions.rate('m-9', null);
    expect(harness.actions.ratingOf('m-9'), isNull);
    expect(harness.backend.ratingCalls.last.positive, isNull);
  });

  testWidgets('preloaded ratings reach the row that owns the message', (
    tester,
  ) async {
    final harness = _Harness(
      backend: StaticConversationActions(ratingsByMessage: const {'m-1': true}),
    );
    await harness.actions.refresh();
    await tester.pumpWidget(
      _app(
        ConversationFlow(
          conversationIdentity: 'test',
          messages: [_answer('a1', '好答案', seq: 2, messageId: 'm-1')],
          actions: _bindings(harness),
        ),
      ),
    );
    final like = tester.widget<IconButton>(
      find.byKey(const ValueKey('answer-like-a1')),
    );
    expect((like.icon as Icon).icon, Icons.thumb_up_rounded);
  });

  testWidgets(
    'the retract dialog states the file outcome and cancels cleanly',
    (tester) async {
      RetractChoice? choice;
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: Builder(
            builder: (context) => Scaffold(
              body: Center(
                child: TextButton(
                  onPressed: () async {
                    choice = await _openDialog(
                      context,
                      const TurnPlan(turn: 0, files: 3, incomplete: false),
                    );
                  },
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      expect(
        tester.widget<Text>(find.byKey(const ValueKey('retract-summary'))).data,
        '将回滚 3 个文件的改动',
      );
      expect(find.byKey(const ValueKey('retract-draft-keep')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('retract-cancel')));
      await tester.pumpAndSettle();
      expect(choice, isNull);
    },
  );

  testWidgets('confirming reports the chosen file and draft behaviour', (
    tester,
  ) async {
    RetractChoice? choice;
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: TextButton(
                onPressed: () async {
                  choice = await _openDialog(
                    context,
                    const TurnPlan(turn: 0, files: 2, incomplete: false),
                  );
                },
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('retract-include-files')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('retract-draft-keep')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('retract-confirm')));
    await tester.pumpAndSettle();
    expect(choice, isNotNull);
    expect(choice!.includeFiles, isFalse);
    expect(choice!.replaceDraft, isFalse);
  });

  testWidgets('a turn without a snapshot offers no file rollback at all', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: TextButton(
                onPressed: () => showRetractDialog(
                  context,
                  plan: const TurnPlan(turn: null, files: 0, incomplete: false),
                  hasDraft: false,
                ),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<Text>(find.byKey(const ValueKey('retract-summary'))).data,
      '这一段没有文件快照，只能撤回对话文本',
    );
    expect(find.byKey(const ValueKey('retract-include-files')), findsNothing);
    expect(find.byKey(const ValueKey('retract-draft-keep')), findsNothing);
  });

  testWidgets('typing writes the draft and a filled draft reaches the field', (
    tester,
  ) async {
    final harness = _Harness();
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: Composer(controller: _controller, draft: harness.draft),
        ),
      ),
    );
    await tester.pump();
    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '正在输入的内容',
    );
    await tester.pump();
    expect(harness.draft.text, '正在输入的内容');

    // An action filling the draft must reach the field without a rebuild race.
    harness.draft.replace('撤回回来的文字');
    await tester.pump();
    final field = tester.widget<TextField>(
      find.byKey(const ValueKey('composer-input')),
    );
    expect(field.controller!.text, '撤回回来的文字');
    _disposeControllers();
  });

  testWidgets(
    'typing after an action keeps the new text, not the action text',
    (tester) async {
      final harness = _Harness();
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: Scaffold(
            body: Composer(controller: _controller, draft: harness.draft),
          ),
        ),
      );
      await tester.pump();
      harness.draft.replace('动作写入的一段');
      await tester.pump();
      await tester.enterText(
        find.byKey(const ValueKey('composer-input')),
        '动作写入的一段，又补了几个字',
      );
      await tester.pump();
      // A late write from an older revision cannot erase the newer typing.
      expect(harness.draft.text, '动作写入的一段，又补了几个字');
      final field = tester.widget<TextField>(
        find.byKey(const ValueKey('composer-input')),
      );
      expect(field.controller!.text, '动作写入的一段，又补了几个字');
      _disposeControllers();
    },
  );

  testWidgets('the edit state is visible and offers a way out', (tester) async {
    final harness = _Harness(draft: '正在改的内容');
    var cancelled = 0;
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: Composer(
            controller: _controller,
            draft: harness.draft,
            editing: true,
            onCancelEdit: () => cancelled++,
          ),
        ),
      ),
    );
    await tester.pump();
    expect(find.byKey(const ValueKey('composer-editing')), findsOneWidget);
    expect(
      tester
          .widget<TextField>(find.byKey(const ValueKey('composer-input')))
          .decoration!
          .hintText,
      '修改这条历史消息',
    );
    await tester.tap(find.byKey(const ValueKey('composer-cancel-edit')));
    await tester.pump();
    expect(cancelled, 1);
    _disposeControllers();
  });

  testWidgets('the shell accepts a Host backend and still renders its input', (
    tester,
  ) async {
    final transport = FixtureTransport();
    final controller = ChatController(transport);
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(
          controller: controller,
          actions: StaticConversationActions(),
          onOpenForkSession: (id, prompt) async => true,
        ),
      ),
    );
    await tester.pump();
    expect(find.byKey(const ValueKey('composer-input')), findsOneWidget);
    controller.dispose();
  });

  testWidgets(
    'a retraction confirmed after the screen is gone changes nothing',
    (tester) async {
      final harness = _Harness(
        backend: StaticConversationActions(changes: [_turn(0, 1, 0)]),
      );
      harness.actions.dispose();
      // The engine restart replaced the page while the confirm dialog was open on
      // the root navigator; confirming must not reach the Host nor throw.
      await harness.actions.retract(
        _user('u1', '问一句', seq: 3),
        includeFiles: false,
        replaceDraft: true,
      );
      expect(harness.backend.rewinds, isEmpty);
      expect(harness.opened, isEmpty);
    },
  );

  testWidgets('a rewind that lands after the screen is gone does not throw', (
    tester,
  ) async {
    final backend = _GateActions();
    final harness = _Harness(backend: backend);
    final pending = harness.actions.retract(
      _user('u1', '问一句', seq: 3),
      includeFiles: false,
      replaceDraft: true,
    );
    await tester.pump();
    harness.actions.dispose();
    backend.gate.complete(
      const RewindOutcome(status: 'rewound', childSessionId: 'fork-9', turn: 0),
    );
    await pending;
    expect(harness.opened, isEmpty);
    expect(harness.draft.text, isEmpty);
  });

  testWidgets('a draft filled by a rewind survives the shell being rebuilt', (
    tester,
  ) async {
    final shared = ComposerDraft();
    final first = ChatController(FixtureTransport());
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(controller: first, draft: shared),
      ),
    );
    await tester.pump();
    shared.replace('撤回回来的原文');
    await tester.pump();
    expect(
      tester
          .widget<TextField>(find.byKey(const ValueKey('composer-input')))
          .controller!
          .text,
      '撤回回来的原文',
    );
    // Opening the Session a rewind produced replaces the shell; the filled text
    // belongs to the screen, not to the controller, so it must still be there.
    final second = ChatController(FixtureTransport());
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(
          key: ObjectKey(second),
          controller: second,
          draft: shared,
        ),
      ),
    );
    await tester.pump();
    expect(
      tester
          .widget<TextField>(find.byKey(const ValueKey('composer-input')))
          .controller!
          .text,
      '撤回回来的原文',
    );
    first.dispose();
    second.dispose();
    shared.dispose();
  });
  testWidgets(
    'a busy Session says so in words, never as a bare session-busy code',
    (tester) async {
      final notices = <String>[];
      final harness = _Harness(
        backend: StaticConversationActions(
          changes: [_turn(0, 1, 1)],
          rewindError: const DshRpcException(
            'rewind/session-busy',
            'session-busy',
          ),
        ),
        onNotice: notices.add,
      );
      await harness.actions.retract(
        _user('u1', '这一轮还在跑', seq: 3),
        includeFiles: false,
        replaceDraft: true,
      );
      final line = notices.single.substring(1);
      expect(line.contains('session-busy'), isFalse);
      expect(line.contains('DshRpcException'), isFalse);
      expect(line.contains('还在进行'), isTrue);
      expect(harness.draft.text, isEmpty);
      expect(harness.backend.opened, isEmpty);
    },
  );

  testWidgets(
    'an unknown failure keeps its raw text instead of inventing a reason',
    (tester) async {
      final notices = <String>[];
      final harness = _Harness(
        backend: StaticConversationActions(
          changes: [_turn(0, 1, 1)],
          rewindError: const DshRpcException(
            'rewind/weird-failure',
            'weird-failure',
          ),
        ),
        onNotice: notices.add,
      );
      await harness.actions.retract(
        _user('u1', '未知失败', seq: 3),
        includeFiles: false,
        replaceDraft: true,
      );
      expect(notices.single.contains('weird-failure'), isTrue);
    },
  );
}
