import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/subagent_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/state/chat_reducer.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/dsh_session_projection.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, Object?> record(
  int seq,
  String type,
  Map<String, Object?> data, {
  Object surfaceOp = 'append',
}) => {
  'type': 'event',
  'event': {
    'seq': seq,
    'time': seq * 10,
    'type': type,
    'data': data,
    'surfaceOp': surfaceOp,
  },
};
Map<String, Object?> user(int seq, String text) => record(seq, 'user/message', {
  'id': 'u$seq',
  'source': {'kind': 'user'},
  'content': [
    {'type': 'text', 'text': text},
  ],
});
Map<String, Object?> assistant(int seq, String text, {int step = 1}) =>
    record(seq, 'assistant/message', {
      'turn': 1,
      'step': step,
      'message': {
        'id': 'a$seq',
        'content': [
          {'type': 'text', 'text': text},
        ],
      },
      'stream': [],
    });
Map<String, Object?> snapshot(
  int cursor,
  List<Map<String, Object?>> records, {
  Map<String, Object?> values = const {},
  Map<String, Object?>? live,
}) => {
  'type': 'snapshot',
  'header': {'id': 'parent', 'version': 2, 'createdAt': 0, 'isSeeded': false},
  'cursor': cursor,
  'records': records,
  'hasMore': false,
  'projections': {'asOfSeq': cursor, 'values': values},
  'assistantStream': live ?? {'revision': 0},
};
Map<String, Object?> liveFrame(Map<String, Object?> frame) => {
  'type': 'assistant-stream',
  'frame': frame,
};

void main() {
  late DshSessionProjection projection;
  late ChatState state;
  late List<ChatEvent> seen;
  void apply(List<ChatEvent> events) {
    for (final event in events) {
      state = ChatReducer.apply(state, event);
      seen.add(event);
    }
  }

  setUp(() {
    projection = DshSessionProjection(sessionId: 'parent', generation: 3);
    state = ChatState.empty();
    seen = [];
  });

  test('internal model context stays out of conversation while user and turn errors remain visible', () {
    final records = <Map<String, Object?>>[
      record(0, 'system/message', {
        'message': {
          'content': [
            {'type': 'text', 'text': 'internal system seed'},
          ],
          'source': {'kind': 'system-prompt'},
        },
      }),
      record(1, 'developer/message', {
        'message': {
          'content': [
            {'type': 'text', 'text': 'internal developer seed'},
          ],
        },
      }),
      for (final kind in [
        'user-approval',
        'agent-instructions',
        'runtime-context',
        'skill-catalog',
        'time-context',
      ])
        record(
          2 +
              [
                'user-approval',
                'agent-instructions',
                'runtime-context',
                'skill-catalog',
                'time-context',
              ].indexOf(kind),
          'user/message',
          {
            'source': {'kind': kind},
            'content': [
              {'type': 'text', 'text': 'internal ' + kind},
            ],
          },
        ),
      user(7, 'Please reply NATIVE_UI_OK'),
      record(8, 'turn/end', {
        'turn': 1,
        'reason': {
          'kind': 'error',
          'error': {'code': 'MISSING_CREDENTIAL', 'message': 'no API key'},
        },
      }),
    ];
    apply(projection.accept(snapshot(8, records)));
    expect(projection.cursor, 8);
    expect(
      state.messages.where((m) => m.text.startsWith('internal ')),
      isEmpty,
    );
    expect(
      state.messages.where((m) => m.role == ChatRole.user).single.text,
      'Please reply NATIVE_UI_OK',
    );
    expect(state.messages.last.text, contains('模型尚未配置'));
    expect(state.messages.last.text, contains('API Key'));
    expect(seen.where((e) => e.kind == ChatEventKind.turnComplete), isEmpty);
  });

  test(
    'snapshot sorts exact real envelopes and maps tools by source.callId',
    () {
      apply(
        projection.accept(
          snapshot(5, [
            record(5, 'tool/result', {
              'turn': 1,
              'step': 1,
              'message': {
                'source': {'kind': 'tool', 'callId': 'call-1'},
                'content': [
                  {'type': 'text', 'text': 'output unique-tail'},
                ],
              },
              'meta': {
                'exitCode': 7,
                'stdout': 'typed stdout',
                'stderr': 'typed stderr',
              },
            }),
            record(4, 'tool/call', {
              'turn': 1,
              'step': 1,
              'callId': 'call-1',
              'name': 'bash',
              'arguments': r'{"command":"echo test","workdir":"/project"}',
            }),
            assistant(3, 'run'),
            user(2, 'hello'),
          ]),
        ),
      );
      expect(state.messages.map((m) => m.text), ['hello', 'run']);
      expect(state.tools.single.id, 'call-1');
      expect(state.tools.single.messageId, state.messages.last.id);
      expect(state.tools.single.status, ToolStatus.complete);
      expect(
        state.tools.single.content!.reasoning,
        contains('output unique-tail'),
      );
      expect(state.tools.single.content!.command!.command, 'echo test');
      expect(state.tools.single.content!.command!.exitCode, 7);
      expect(state.tools.single.content!.command!.stdout, 'typed stdout');
      expect(state.status, ChatStatus.ready);
      expect(seen.map((e) => e.sequence), List.generate(seen.length, (i) => i));
      expect(seen.every((e) => e.generation == 3), isTrue);
      expect(state.needsResync, isFalse);
    },
  );

  test('durable duplicates consume no client sequence and gaps fail until rebaseline', () {
    apply(projection.accept(snapshot(2, [user(2, 'before')])));
    final count = seen.length;
    expect(projection.accept(user(2, 'duplicate')), isEmpty);
    expect(
      () => projection.accept(assistant(4, 'gap')),
      throwsA(
        isA<DshRpcException>().having(
          (e) => e.code,
          'code',
          'session/sequence-gap',
        ),
      ),
    );
    expect(projection.cursor, 2);
    expect(
      () => projection.accept(assistant(3, 'late repair')),
      throwsA(isA<DshRpcException>()),
    );
    expect(seen, hasLength(count));
    apply(projection.accept(snapshot(4, [user(4, 'replacement')])));
    expect(state.messages.single.text, 'replacement');
    apply(projection.accept(assistant(5, 'fresh')));
    expect(state.messages.map((m) => m.text), ['replacement', 'fresh']);
    expect(state.needsResync, isFalse);
  });

  test('reasoning, text and raw code blocks fold compact records without duplication', () {
    apply(
      projection.accept(
        snapshot(
          0,
          [user(0, 'question')],
          live: {
            'revision': 10,
            'activeAttempt': {
              'attemptId': 'attempt',
              'turn': 1,
              'step': 1,
              'startedAfterSeq': 0,
              'nextIndex': 4,
              'stream': [
                {
                  'type': 'reasoning-chunks',
                  'time0': 1,
                  'index': 0,
                  'dt': [],
                  'texts': ['think'],
                },
                {
                  'type': 'text-chunks',
                  'time0': 2,
                  'index': 1,
                  'dt': [1],
                  'texts': ['hel', 'lo'],
                },
                {
                  'type': 'chunk',
                  'time': 4,
                  'chunk': {
                    'type': 'block-end',
                    'index': 2,
                    'block': {
                      'type': 'code',
                      'language': 'dart',
                      'code': 'print(1);',
                    },
                  },
                },
              ],
            },
          },
        ),
      ),
    );
    expect(state.messages.last.text, contains('hello'));
    expect(state.messages.last.text, contains('print(1);'));
    expect(state.messages.last.text, contains('think'));
    expect(state.messages.last.isStreaming, isTrue);
    final appended = projection.accept(
      liveFrame({
        'type': 'chunk',
        'attemptId': 'attempt',
        'revision': 11,
        'index': 4,
        'time': 5,
        'chunk': {'type': 'text-delta', 'index': 1, 'text': '!'},
      }),
    );
    apply(appended);
    expect(state.messages.last.text, contains('hello!'));
    expect(
      projection.accept(
        liveFrame({
          'type': 'chunk',
          'attemptId': 'attempt',
          'revision': 11,
          'index': 4,
          'time': 5,
          'chunk': {'type': 'text-delta', 'index': 1, 'text': '!'},
        }),
      ),
      isEmpty,
    );
    apply(
      projection.accept(
        record(1, 'assistant/message', {
          'turn': 1,
          'step': 1,
          'message': {
            'id': 'settled',
            'content': [
              {'type': 'reasoning', 'text': 'think'},
              {'type': 'text', 'text': 'hello!'},
              {'type': 'code', 'language': 'dart', 'code': 'print(1);'},
            ],
          },
          'stream': [],
        }),
      ),
    );
    apply(
      projection.accept(
        liveFrame({
          'type': 'end',
          'attemptId': 'attempt',
          'revision': 12,
          'index': 5,
          'outcome': {
            'kind': 'committed',
            'seq': 1,
            'eventType': 'assistant/message',
          },
        }),
      ),
    );
    expect(
      state.messages.where((m) => m.role == ChatRole.assistant),
      hasLength(1),
    );
    expect(state.messages.last.isStreaming, isFalse);
    expect(state.messages.last.text, contains('hello!'));
    expect(seen.where((e) => e.kind == ChatEventKind.turnComplete), isEmpty);
  });

  test(
    'live prefix growth emits deltas without replaying the history window',
    () {
      apply(projection.accept(snapshot(0, [user(0, 'prompt')])));
      apply(
        projection.accept(
          liveFrame({
            'type': 'start',
            'revision': 1,
            'attemptId': 'a',
            'startedAfterSeq': 0,
            'turn': 1,
            'step': 1,
          }),
        ),
      );
      apply(
        projection.accept(
          liveFrame({
            'type': 'chunk',
            'revision': 2,
            'attemptId': 'a',
            'index': 0,
            'time': 1,
            'chunk': {'type': 'text-delta', 'index': 0, 'text': 'par'},
          }),
        ),
      );
      final events = projection.accept(
        liveFrame({
          'type': 'chunk',
          'revision': 3,
          'attemptId': 'a',
          'index': 1,
          'time': 2,
          'chunk': {'type': 'text-delta', 'index': 0, 'text': 'tial'},
        }),
      );
      expect(events.where((e) => e.kind == ChatEventKind.snapshot), isEmpty);
      apply(events);
      expect(state.messages.last.text, 'partial');
    },
  );

  test('assistant revisions and dense indexes detect gaps separately from durable seq', () {
    apply(projection.accept(snapshot(-1, [])));
    expect(
      () => projection.accept(
        liveFrame({
          'type': 'start',
          'revision': 2,
          'attemptId': 'a',
          'startedAfterSeq': -1,
          'turn': 1,
          'step': 1,
        }),
      ),
      throwsA(
        isA<DshRpcException>().having(
          (e) => e.code,
          'code',
          'session/assistant-gap',
        ),
      ),
    );
    apply(projection.accept(snapshot(-1, [])));
    apply(
      projection.accept(
        liveFrame({
          'type': 'start',
          'revision': 1,
          'attemptId': 'a',
          'startedAfterSeq': -1,
          'turn': 1,
          'step': 1,
        }),
      ),
    );
    expect(
      () => projection.accept(
        liveFrame({
          'type': 'chunk',
          'revision': 2,
          'attemptId': 'a',
          'index': 1,
          'time': 1,
          'chunk': {'type': 'text-delta', 'index': 0, 'text': 'gap'},
        }),
      ),
      throwsA(isA<DshRpcException>()),
    );
  });

  test(
    'idle clears streaming without completing an interrupted tool or turn',
    () {
      apply(
        projection.accept(
          snapshot(2, [
            record(0, 'turn/start', {'turn': 1}),
            user(1, 'run'),
            record(2, 'tool/call', {
              'turn': 1,
              'step': 1,
              'callId': 'pending',
              'name': 'unknown',
              'arguments': '{}',
            }),
          ]),
        ),
      );
      expect(state.status, ChatStatus.streaming);
      apply(projection.idle(throughSeq: 1));
      expect(state.status, ChatStatus.streaming);
      apply(projection.idle(throughSeq: 2));
      expect(state.status, ChatStatus.ready);
      expect(state.tools.single.status, ToolStatus.running);
      expect(state.messages.every((m) => !m.isStreaming), isTrue);
      expect(seen.where((e) => e.kind == ChatEventKind.turnComplete), isEmpty);
    },
  );

  test(
    'todos baseline, whole writes and turn/start project as visible steps',
    () {
      apply(
        projection.accept(
          snapshot(
            0,
            [user(0, 'plan')],
            values: {
              'todos': [
                {'content': 'first', 'status': 'in_progress'},
                {'content': 'second', 'status': 'pending'},
              ],
            },
          ),
        ),
      );
      expect(state.tools.single.content!.steps.map((s) => s.status), [
        StepState.running,
        StepState.pending,
      ]);
      apply(
        projection.accept(
          record(1, 'todo/write', {
            'todos': [
              {'content': 'first', 'status': 'completed'},
              {'content': 'second', 'status': 'completed'},
            ],
          }),
        ),
      );
      expect(
        state.tools.single.content!.steps.every(
          (s) => s.status == StepState.completed,
        ),
        isTrue,
      );
      apply(projection.accept(record(2, 'turn/start', {'turn': 2})));
      expect(state.tools.where((t) => t.content!.steps.isNotEmpty), isEmpty);
    },
  );

  test('unknown tool output and failure identity stay lossless and do not become actions', () {
    apply(projection.accept(snapshot(0, [user(0, 'question')])));
    apply(
      projection.accept(
        record(1, 'tool/call', {
          'turn': 1,
          'step': 1,
          'callId': 'x',
          'name': 'future_tool',
          'arguments': '{invalid-json',
        }),
      ),
    );
    apply(
      projection.accept(
        record(2, 'tool/result', {
          'turn': 1,
          'step': 1,
          'message': {
            'source': {'callId': 'x'},
            'isError': true,
            'content': [
              {
                'type': 'future-block',
                'payload': {'tail': 'opaque-尾部'},
              },
              {'type': 'text', 'text': 'ask_user_question is only text'},
            ],
          },
          'error': {'name': 'Failure', 'code': 'X', 'reason': 'raw reason'},
          'meta': {
            'anything': [1, 2],
          },
        }),
      ),
    );
    final tool = state.tools.single;
    expect(tool.name, 'future_tool');
    expect(tool.status, ToolStatus.failed);
    expect(tool.content!.reasoning, contains('{invalid-json'));
    expect(tool.content!.reasoning, contains('opaque-尾部'));
    expect(tool.content!.reasoning, contains('anything'));
    expect(tool.detail, contains('raw reason'));
    expect(
      seen.where(
        (e) =>
            e.kind == ChatEventKind.question ||
            e.kind == ChatEventKind.approval,
      ),
      isEmpty,
    );
  });

  test('surface replacement removes the addressed rows and keeps non-human context distinct', () {
    apply(
      projection.accept(
        snapshot(1, [user(0, 'old'), assistant(1, 'old reply')]),
      ),
    );
    apply(
      projection.accept(
        record(
          2,
          'user/message',
          {
            'id': 'checkpoint',
            'source': {'kind': 'compact-checkpoint'},
            'content': [
              {'type': 'text', 'text': 'summary'},
            ],
          },
          surfaceOp: {'op': 'replace', 'startSeq': 0, 'endSeq': 1},
        ),
      ),
    );
    expect(state.messages.single.text, 'summary');
    expect(state.messages.single.role, ChatRole.system);
    expect(state.needsResync, isFalse);
  });

  test('only completed turn/end reports complete; failure and cancellation remain explicit', () {
    for (final kind in [
      'error',
      'aborted',
      'interrupted',
      'max-tokens',
      'blocked',
      'completed',
    ]) {
      projection = DshSessionProjection(sessionId: 'parent', generation: 3);
      state = ChatState.empty();
      seen.clear();
      apply(projection.accept(snapshot(0, [user(0, 'prompt')])));
      apply(
        projection.accept(
          record(1, 'turn/end', {
            'turn': 1,
            'reason': {
              'kind': kind,
              if (kind == 'error')
                'error': {'code': 'PROVIDER', 'message': 'provider failed'},
            },
          }),
        ),
      );
      expect(
        seen.any((e) => e.kind == ChatEventKind.turnComplete),
        kind == 'completed',
      );
      // A turn ending is durable conversation content, never a transport
      // failure: the connection banner and its retry stay out of it.
      expect(state.error, isNull);
      expect(seen.where((e) => e.kind == ChatEventKind.error), isEmpty);
      if (kind == 'completed') {
        expect(state.messages.where((m) => m.role == ChatRole.notice), isEmpty);
      } else {
        final notice = state.messages.last.notice!;
        expect(state.messages.last.role, ChatRole.notice);
        expect(notice.kind.name, isNotEmpty);
        if (kind == 'error') {
          expect(notice.code, 'PROVIDER');
          expect(notice.message, contains('provider failed'));
          expect(notice.isFailure, isTrue);
        }
      }
      expect(state.messages.every((m) => !m.isStreaming), isTrue);
    }
  });

  test('catalog refresh preserves true child addresses and one-shot read-only mode', () {
    apply(projection.accept(snapshot(0, [user(0, 'delegate')])));
    const address = SubagentAddress(
      parentSessionId: 'parent',
      childSessionId: 'child',
      mode: SubagentMode.oneShot,
    );
    apply(
      projection.catalog([
        const AgentTask(
          id: 'child',
          name: 'worker',
          task: 'work',
          status: AgentState.waiting,
          address: address,
        ),
      ]),
    );
    expect(state.tools.single.content!.agents.single.address, address);
    expect(state.tools.single.content!.agents.single.address!.canSend, isFalse);
    expect(
      () => state.tools.single.content!.agents.add(
        const AgentTask(id: 'bad', name: '', task: ''),
      ),
      throwsUnsupportedError,
    );
  });

  test(
    'invalid snapshot identity and missing tail never publish partial windows',
    () {
      expect(
        () => projection.accept({
          ...snapshot(-1, []),
          'header': {'id': 'other'},
        }),
        throwsA(isA<DshRpcException>()),
      );
      expect(
        () => projection.accept(snapshot(2, [user(0, 'missing tail')])),
        throwsA(isA<DshRpcException>()),
      );
      expect(seen, isEmpty);
    },
  );
}
