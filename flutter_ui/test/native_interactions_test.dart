import 'dart:async';

import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/native_interactions.dart';
import 'package:flutter_test/flutter_test.dart';

import 'dsh_rpc_connection_test.dart' show LoopbackDshHost;

typedef _Call = ({String endpoint, Map<String, Object?> args});

class _MemoryRpc extends DshRpcConnection {
  _MemoryRpc() : super(webUrl: 'http://127.0.0.1:1/?token=unused-test-only');

  final channels = <StreamController<Map<String, Object?>>>[];
  final calls = <_Call>[];
  final opens = <_Call>[];
  Future<Object?> Function(_Call)? onCall;
  int cancellations = 0;
  bool ownerClosed = false;

  @override
  Stream<Map<String, Object?>> follow(
    String endpoint,
    Map<String, Object?> args,
  ) {
    opens.add((endpoint: endpoint, args: args));
    final channel = StreamController<Map<String, Object?>>(
      onCancel: () {
        cancellations++;
      },
    );
    channels.add(channel);
    return channel.stream;
  }

  @override
  Future<Object?> call(String endpoint, Map<String, Object?> args) async {
    final request = (endpoint: endpoint, args: args);
    calls.add(request);
    return onCall == null ? null : await onCall!(request);
  }

  void send(Map<String, Object?> frame) => channels.last.add(frame);

  @override
  Future<void> close() async {
    ownerClosed = true;
  }

  void disposeChannels() {
    for (final channel in channels) {
      unawaited(channel.close());
    }
  }
}

Map<String, Object?> _ready(String clientId) => {
  'type': 'ready',
  'clientId': clientId,
  'host': {'home': '/test-home'},
};

Map<String, Object?> _approval(String eventId, {String agent = 'parent'}) => {
  'type': 'waterfall',
  'event': 'approval/request',
  'eventId': eventId,
  'agentId': agent,
  'request': {
    'toolName': 'write',
    'callId': 'tool-call',
    'reason': 'Write a file',
    'displayReason': {'en': 'Write permission', 'zh': '写入权限'},
  },
};

Map<String, Object?> _question(
  String eventId, {
  List<Object?>? questions,
  Map<String, Object?>? wait,
}) => {
  'type': 'waterfall',
  'event': 'user-questions/request',
  'eventId': eventId,
  'agentId': 'parent',
  'request': {
    'questions':
        questions ??
        [
          {
            'id': 'q1',
            'header': 'Choose',
            'question': 'Which targets?',
            'detail': 'Select the intended targets.',
            'multiSelect': true,
            'options': [
              {'label': 'Alpha', 'description': 'First target'},
              {'label': 'Beta', 'description': 'Second target'},
            ],
          },
        ],
    'wait': ?wait,
  },
};

Matcher _code(String code) =>
    isA<DshRpcException>().having((error) => error.code, 'code', code);

void main() {
  late _MemoryRpc rpc;
  late NativeInteractions service;

  Future<NativeInteractionsState> until(
    bool Function(NativeInteractionsState) predicate,
  ) => predicate(service.state)
      ? Future.value(service.state)
      : service.updates
            .firstWhere(predicate)
            .timeout(const Duration(seconds: 3));

  Future<void> start() async {
    final opening = service.start();
    // The service establishes the subscription asynchronously.
    await Future<void>.delayed(Duration.zero);
    rpc.send(_ready('client-1'));
    await opening;
  }

  Future<NativeInteraction> deliver(Map<String, Object?> frame) async {
    rpc.send(frame);
    final state = await until(
      (s) => s.interactions.any(
        (item) =>
            item.generation == s.generation && item.eventId == frame['eventId'],
      ),
    );
    return state.interactions.lastWhere(
      (item) => item.eventId == frame['eventId'],
    );
  }

  setUp(() {
    rpc = _MemoryRpc();
    service = NativeInteractions(rpc: rpc, sessionId: 'parent');
  });
  tearDown(() async {
    await service.close();
    rpc.disposeChannels();
  });

  test(
    'starts exactly one empty events follow and waits for strict ready',
    () async {
      var opened = false;
      final first = service.start();
      final second = service.start();
      unawaited(
        first.then((_) {
          opened = true;
        }),
      );
      await Future<void>.delayed(Duration.zero);
      expect(rpc.opens, hasLength(1));
      expect(rpc.opens.single.endpoint, r'$events');
      expect(rpc.opens.single.args, <String, Object?>{});
      expect(opened, isFalse);
      rpc.send(_ready('client-1'));
      await Future.wait([first, second]);
      expect(service.state.ready, isTrue);
      expect(service.state.clientId, 'client-1');
    },
  );

  test('rejects waterfall before ready without sending any result', () async {
    final opening = service.start();
    final failure = expectLater(
      opening,
      throwsA(_code('interactions/protocol')),
    );
    await Future<void>.delayed(Duration.zero);
    rpc.send(_approval('early'));
    await failure;
    expect(service.state.ready, isFalse);
    expect(rpc.calls, isEmpty);
  });

  test('rejects ready with missing host or additional keys', () async {
    final opening = service.start();
    final failure = expectLater(
      opening,
      throwsA(_code('interactions/protocol')),
    );
    await Future<void>.delayed(Duration.zero);
    rpc.send({
      'type': 'ready',
      'clientId': 'client-1',
      'host': {'home': '/', 'extra': true},
    });
    await failure;
    expect(rpc.calls, isEmpty);
  });

  test(
    'approval stays pending until an explicit decision and sends real outcome',
    () async {
      await start();
      final item = await deliver(_approval('approve-1'));
      expect(item.kind, NativeInteractionKind.approval);
      expect(item.action!.title, contains('write'));
      expect(item.action!.detail, contains('Write a file'));
      expect(item.action!.detail, contains('Write permission'));
      expect(item.toolCallId, 'tool-call');
      expect(rpc.calls, isEmpty);
      await service.approve(item.requestId);
      expect(rpc.calls.single.endpoint, r'$events/result');
      expect(rpc.calls.single.args, {
        'clientId': 'client-1',
        'eventId': 'approve-1',
        'outcome': {'kind': 'result', 'value': 'allowed-once'},
      });
      expect(
        service.state.interactions.single.status,
        NativeInteractionStatus.delivered,
      );
      expect(item.status, NativeInteractionStatus.pending);
    },
  );

  test('reject and cancel are closed approval vocabulary values', () async {
    await start();
    final reject = await deliver(_approval('reject'));
    await service.decideApproval(
      reject.requestId,
      NativeApprovalDecision.reject,
    );
    final cancel = await deliver(_approval('cancel'));
    await service.cancel(cancel.requestId);
    expect(rpc.calls.map((call) => call.args['outcome']), [
      {'kind': 'result', 'value': 'rejected'},
      {'kind': 'result', 'value': 'cancelled'},
    ]);
  });

  test(
    'foreign parent and unknown events explicitly delegate with next',
    () async {
      await start();
      await deliver(_approval('child', agent: 'child'));
      await until(
        (s) => s.interactions.last.status == NativeInteractionStatus.delegated,
      );
      await deliver({
        'type': 'waterfall',
        'event': 'future/request',
        'eventId': 'unknown',
        'agentId': 'parent',
        'request': {'message': 'please approve'},
      });
      await until(
        (s) => s.interactions.last.status == NativeInteractionStatus.delegated,
      );
      expect(service.state.pending, isEmpty);
      expect(
        service.state.interactions.every((item) => item.action == null),
        isTrue,
      );
      expect(rpc.calls.map((call) => call.args['outcome']), [
        {'kind': 'next'},
        {'kind': 'next'},
      ]);
    },
  );

  test('only a real approval schema can create an approval card', () async {
    await start();
    await deliver({
      'type': 'waterfall',
      'event': 'approval/request',
      'eventId': 'bad-schema',
      'agentId': 'parent',
      'request': {'message': 'write requires approval'},
    });
    await until(
      (s) => s.interactions.last.status == NativeInteractionStatus.delegated,
    );
    expect(service.state.interactions.single.unsupportedReason, isNotEmpty);
    expect(
      service.state.interactions.single.kind,
      NativeInteractionKind.unsupported,
    );
    expect(rpc.calls.single.args['outcome'], {'kind': 'next'});
  });

  test(
    'single question preserves labels and descriptions as immutable data',
    () async {
      await start();
      final item = await deliver(_question('question'));
      expect(item.action!.choices, ['Alpha', 'Beta']);
      expect(item.action!.multiSelect, isTrue);
      expect(item.action!.detail, contains('First target'));
      expect(item.question!.options.first.description, 'First target');
      expect(() => item.action!.choices.add('changed'), throwsUnsupportedError);
      expect(() => item.question!.options.clear(), throwsUnsupportedError);
      expect(() => service.state.interactions.clear(), throwsUnsupportedError);
      expect(rpc.calls, isEmpty);
    },
  );

  test(
    'legacy newline selection maps only complete canonical labels',
    () async {
      await start();
      final item = await deliver(_question('multi'));
      await service.answer(item.requestId, 'Alpha\nBeta');
      expect(rpc.calls.single.args['outcome'], {
        'kind': 'result',
        'value': {
          'answers': [
            {
              'id': 'q1',
              'selected': ['Alpha', 'Beta'],
            },
          ],
        },
      });
    },
  );

  test(
    'structured selection plus custom text keeps their separate wire fields',
    () async {
      await start();
      final item = await deliver(_question('structured'));
      final labels = <String>['Beta'];
      final reply = NativeQuestionReply(
        questionId: 'q1',
        selected: labels,
        custom: 'A separate\ncustom answer',
      );
      labels.clear();
      await service.answerStructured(item.requestId, reply);
      expect(rpc.calls.single.args['outcome'], {
        'kind': 'result',
        'value': {
          'answers': [
            {
              'id': 'q1',
              'selected': ['Beta'],
              'custom': 'A separate\ncustom answer',
            },
          ],
        },
      });
      expect(() => reply.selected.add('Alpha'), throwsUnsupportedError);
    },
  );

  test(
    'ambiguous legacy mixed text is rejected with its draft preserved',
    () async {
      await start();
      final item = await deliver(_question('ambiguous'));
      await expectLater(
        service.answer(item.requestId, 'Alpha\nother'),
        throwsA(_code('interactions/structured-answer-required')),
      );
      expect(rpc.calls, isEmpty);
      expect(service.state.interactions.single.textDraft, 'Alpha\nother');
      expect(
        service.state.interactions.single.status,
        NativeInteractionStatus.pending,
      );
    },
  );

  test(
    'free text is encoded as custom without normalizing its content',
    () async {
      await start();
      final item = await deliver(
        _question(
          'text',
          questions: [
            {'id': 'free', 'question': 'Describe the change'},
          ],
        ),
      );
      await service.answer(item.requestId, '  a\nb  ');
      expect(rpc.calls.single.args['outcome'], {
        'kind': 'result',
        'value': {
          'answers': [
            {'id': 'free', 'selected': [], 'custom': '  a\nb  '},
          ],
        },
      });
    },
  );

  test(
    'invalid structured question identity, labels and cardinality never submit',
    () async {
      await start();
      final item = await deliver(
        _question(
          'validate',
          questions: [
            {
              'id': 'single',
              'question': 'Which?',
              'options': [
                {'label': 'A'},
                {'label': 'B'},
              ],
            },
          ],
        ),
      );
      for (final reply in [
        NativeQuestionReply(questionId: 'wrong', selected: ['A']),
        NativeQuestionReply(questionId: 'single', selected: ['unknown']),
        NativeQuestionReply(questionId: 'single', selected: ['A', 'A']),
        NativeQuestionReply(questionId: 'single', selected: ['A', 'B']),
        NativeQuestionReply(
          questionId: 'single',
          selected: ['A'],
          custom: 'other',
        ),
      ]) {
        await expectLater(
          service.answerStructured(item.requestId, reply),
          throwsA(_code('interactions/invalid-answer')),
        );
      }
      expect(rpc.calls, isEmpty);
    },
  );

  test('multiple questions, timed claim, intents and lossy labels use Web fallback', () async {
    await start();
    final frames = [
      _question(
        'batch',
        questions: [
          {'id': 'a', 'question': 'A?'},
          {'id': 'b', 'question': 'B?'},
        ],
      ),
      _question('timed', wait: {'callId': 'call', 'timed': true}),
      _question(
        'intent',
        questions: [
          {
            'id': 'i',
            'question': 'Plan?',
            'intent': {'kind': 'plan-review', 'approve': 'Yes'},
          },
        ],
      ),
      _question(
        'newline',
        questions: [
          {
            'id': 'n',
            'question': 'Choose',
            'options': [
              {'label': 'a\nb'},
            ],
          },
        ],
      ),
      _question(
        'trim',
        questions: [
          {
            'id': 't',
            'question': 'Choose',
            'options': [
              {'label': ' A '},
            ],
          },
        ],
      ),
      _question(
        'duplicate',
        questions: [
          {
            'id': 'd',
            'question': 'Choose',
            'options': [
              {'label': 'A'},
              {'label': 'A'},
            ],
          },
        ],
      ),
    ];
    for (final frame in frames) {
      await deliver(frame);
      await until(
        (s) => s.interactions.last.status == NativeInteractionStatus.delegated,
      );
    }
    expect(rpc.calls, hasLength(frames.length));
    expect(
      rpc.calls.every(
        (call) => (call.args['outcome'] as Map)['kind'] == 'next',
      ),
      isTrue,
    );
    expect(
      service.state.interactions.every(
        (item) =>
            item.kind == NativeInteractionKind.unsupported &&
            item.unsupportedReason != null,
      ),
      isTrue,
    );
  });

  test(
    'question cancel sends the real UserQuestionError instead of a fake answer',
    () async {
      await start();
      final item = await deliver(_question('question-cancel'));
      await service.cancel(item.requestId);
      expect(rpc.calls.single.args['outcome'], {
        'kind': 'rejected',
        'error': {
          'name': 'UserQuestionError',
          'message': 'the user cancelled ask_user_question',
          'code': 'ASK_CANCELLED',
        },
      });
    },
  );

  test('duplicate in-flight approval shares one send and terminal submit is refused', () async {
    await start();
    final item = await deliver(_approval('double'));
    final ack = Completer<Object?>();
    rpc.onCall = (_) => ack.future;
    final first = service.approve(item.requestId);
    final second = service.approve(item.requestId);
    expect(rpc.calls, hasLength(1));
    expect(
      service.state.interactions.single.status,
      NativeInteractionStatus.submitting,
    );
    await expectLater(
      service.decideApproval(item.requestId, NativeApprovalDecision.reject),
      throwsA(_code('interactions/submission-busy')),
    );
    ack.complete(null);
    await Future.wait([first, second]);
    await expectLater(
      service.approve(item.requestId),
      throwsA(_code('interactions/not-pending')),
    );
    expect(rpc.calls, hasLength(1));
  });

  test('failed asynchronous receipt retains editable draft and permits an explicit retry', () async {
    await start();
    final item = await deliver(_question('failure'));
    rpc.onCall = (_) async =>
        throw const DshRpcException('test/rejected', 'Delivery failed');
    await expectLater(
      service.answer(item.requestId, 'Alpha'),
      throwsA(_code('test/rejected')),
    );
    final failed = service.state.interactions.single;
    expect(failed.status, NativeInteractionStatus.pending);
    expect(failed.textDraft, 'Alpha');
    expect(failed.answerDraft!.selected, ['Alpha']);
    expect(failed.errorCode, 'test/rejected');
    rpc.onCall = (_) async => null;
    await service.answer(item.requestId, 'Beta');
    expect(rpc.calls, hasLength(2));
    expect(
      service.state.interactions.single.status,
      NativeInteractionStatus.delivered,
    );
  });

  test(
    'late acknowledgement cannot erase a draft edited while submitting',
    () async {
      await start();
      final item = await deliver(_question('draft'));
      final ack = Completer<Object?>();
      rpc.onCall = (_) => ack.future;
      final sent = service.answer(item.requestId, 'Alpha');
      service.setTextDraft(item.requestId, 'Beta');
      ack.complete(null);
      await sent;
      expect(service.state.interactions.single.textDraft, 'Beta');
      expect(
        service.state.interactions.single.status,
        NativeInteractionStatus.delivered,
      );
    },
  );

  test(
    'structured draft edits during delivery replace the old text draft',
    () async {
      await start();
      final item = await deliver(_question('structured-draft'));
      final ack = Completer<Object?>();
      rpc.onCall = (_) => ack.future;
      final sent = service.answer(item.requestId, 'Alpha');
      service.setAnswerDraft(
        item.requestId,
        NativeQuestionReply(
          questionId: 'q1',
          selected: ['Beta'],
          custom: 'new draft',
        ),
      );
      ack.complete(null);
      await sent;
      expect(service.state.interactions.single.textDraft, isNull);
      expect(service.state.interactions.single.answerDraft!.selected, ['Beta']);
      expect(
        service.state.interactions.single.answerDraft!.custom,
        'new draft',
      );
    },
  );

  test(
    'host cancel withdraws the card and never posts a reply on its own',
    () async {
      await start();
      final item = await deliver(_approval('host-cancel'));
      rpc.send({'type': 'cancel', 'eventId': 'host-cancel'});
      await until(
        (s) =>
            s.interactions.single.status == NativeInteractionStatus.cancelled,
      );
      await expectLater(
        service.approve(item.requestId),
        throwsA(_code('interactions/cancelled')),
      );
      expect(rpc.calls, isEmpty);
    },
  );

  test('host cancel wins over a pending HTTP receipt', () async {
    await start();
    final item = await deliver(_approval('race'));
    final ack = Completer<Object?>();
    rpc.onCall = (_) => ack.future;
    final sent = service.approve(item.requestId);
    final failure = expectLater(sent, throwsA(_code('interactions/cancelled')));
    rpc.send({'type': 'cancel', 'eventId': 'race'});
    await until(
      (s) => s.interactions.single.status == NativeInteractionStatus.cancelled,
    );
    await failure.timeout(const Duration(seconds: 3));
    ack.complete(null);
    expect(
      service.state.interactions.single.status,
      NativeInteractionStatus.cancelled,
    );
  });

  test('reconnect changes identity and old receipt cannot settle the replayed event', () async {
    await start();
    final old = await deliver(_approval('same-id'));
    final ack = Completer<Object?>();
    rpc.onCall = (_) => ack.future;
    final sent = service.approve(old.requestId);
    final failure = expectLater(
      sent,
      throwsA(_code('interactions/stale-generation')),
    );
    final reconnect = service.reconnect();
    await Future<void>.delayed(Duration.zero);
    rpc.send(_ready('client-2'));
    await reconnect;
    final current = await deliver(_approval('same-id'));
    expect(current.requestId, isNot(old.requestId));
    expect(current.clientId, 'client-2');
    await failure.timeout(const Duration(seconds: 3));
    ack.complete(null);
    expect(service.state.pending.single.requestId, current.requestId);
    expect(
      service.state.pending.single.status,
      NativeInteractionStatus.pending,
    );
    expect(rpc.calls.single.args['clientId'], 'client-1');
  });

  test(
    'question draft identity survives replay and changes with the real payload',
    () async {
      await start();
      final old = await deliver(_question('draft-event'));
      final reconnect = service.reconnect();
      await Future<void>.delayed(Duration.zero);
      rpc.send(_ready('client-2'));
      await reconnect;
      final current = await deliver(_question('draft-event'));
      expect(current.requestId, isNot(old.requestId));
      expect(current.action!.draftKey, isNotNull);
      expect(current.action!.draftKey, old.action!.draftKey);
      expect(rpc.calls, isEmpty);
      final next = service.reconnect();
      await Future<void>.delayed(Duration.zero);
      rpc.send(_ready('client-3'));
      await next;
      final changed = await deliver(
        _question(
          'draft-event',
          questions: [
            {
              'id': 'q1',
              'question': 'Changed question',
              'options': [
                {'label': 'Alpha'},
              ],
            },
          ],
        ),
      );
      expect(changed.action!.draftKey, isNot(old.action!.draftKey));
    },
  );

  test('identical waterfall replay is deduplicated but changed identity payload fails', () async {
    await start();
    final item = await deliver(_approval('repeat'));
    rpc.send(_approval('repeat'));
    rpc.send({
      'type': 'emit',
      'event': 'api-session/status',
      'args': ['parent', <String, Object?>{}],
    });
    await Future<void>.delayed(Duration.zero);
    expect(service.state.pending.single.requestId, item.requestId);
    final bad = _approval('repeat');
    bad['request'] = {'toolName': 'delete'};
    rpc.send(bad);
    await until((s) => !s.ready && s.errorCode == 'interactions/protocol');
    expect(rpc.calls, isEmpty);
  });

  test(
    'forbidden request agent/signal fields fail the frame rather than guessing',
    () async {
      await start();
      final bad = _approval('forbidden');
      bad['request'] = {'toolName': 'write', 'agent': 'parent'};
      rpc.send(bad);
      await until((s) => !s.ready && s.errorCode == 'interactions/protocol');
      expect(service.state.pending, isEmpty);
      expect(rpc.calls, isEmpty);
    },
  );

  test(
    'stream end interrupts pending actions and preserves their drafts',
    () async {
      await start();
      final item = await deliver(_question('end'));
      service.setTextDraft(item.requestId, 'unsent');
      unawaited(rpc.channels.last.close());
      await until((s) => !s.ready);
      expect(
        service.state.interactions.single.status,
        NativeInteractionStatus.disconnected,
      );
      expect(service.state.interactions.single.textDraft, 'unsent');
      expect(rpc.calls, isEmpty);
    },
  );

  test(
    'manual delegate sends next without manufacturing approval or answer',
    () async {
      await start();
      final item = await deliver(_approval('delegate'));
      await service.delegate(item.requestId);
      expect(rpc.calls.single.args['outcome'], {'kind': 'next'});
      expect(
        service.state.interactions.single.status,
        NativeInteractionStatus.delegated,
      );
    },
  );

  test(
    'close invalidates work, is idempotent, and does not own the shared RPC',
    () async {
      await start();
      final item = await deliver(_approval('close'));
      final ack = Completer<Object?>();
      rpc.onCall = (_) => ack.future;
      final sent = service.approve(item.requestId);
      final failure = expectLater(sent, throwsA(_code('interactions/closed')));
      await service.close();
      await service.close();
      await failure.timeout(const Duration(seconds: 3));
      ack.complete(null);
      expect(service.state.closed, isTrue);
      expect(service.state.ready, isFalse);
      expect(rpc.ownerClosed, isFalse);
      await expectLater(service.start(), throwsA(_code('interactions/closed')));
    },
  );

  test(
    'nonvoid acknowledgement is a protocol failure and keeps the draft',
    () async {
      await start();
      final item = await deliver(_question('nonvoid'));
      rpc.onCall = (_) async => {'accepted': true};
      await expectLater(
        service.answer(item.requestId, 'Alpha'),
        throwsA(_code('interactions/protocol')),
      );
      expect(
        service.state.interactions.single.status,
        NativeInteractionStatus.pending,
      );
      expect(service.state.interactions.single.textDraft, 'Alpha');
    },
  );

  test(
    'failed automatic delegation is visible and can be explicitly retried',
    () async {
      await start();
      rpc.onCall = (_) async =>
          throw const DshRpcException('test/delegate', 'Cannot delegate');
      await deliver(_approval('foreign-failure', agent: 'another-parent'));
      await until((s) => s.interactions.single.errorCode == 'test/delegate');
      expect(service.state.pending, isEmpty);
      expect(service.state.interactions.single.unsupportedReason, isNotEmpty);
      rpc.onCall = (_) async => null;
      await service.delegate(service.state.interactions.single.requestId);
      expect(
        service.state.interactions.single.status,
        NativeInteractionStatus.delegated,
      );
      expect(rpc.calls, hasLength(2));
      expect(
        rpc.calls.every(
          (call) => (call.args['outcome'] as Map)['kind'] == 'next',
        ),
        isTrue,
      );
    },
  );

  test('draft snapshots survive host cancel and another consumer cannot mutate them', () async {
    await start();
    final item = await deliver(_question('immutable'));
    service.setAnswerDraft(
      item.requestId,
      NativeQuestionReply(
        questionId: 'q1',
        selected: ['Alpha'],
        custom: 'Note',
      ),
    );
    final snapshot = service.state;
    rpc.send({'type': 'cancel', 'eventId': 'immutable'});
    await until(
      (s) => s.interactions.single.status == NativeInteractionStatus.cancelled,
    );
    expect(
      snapshot.interactions.single.status,
      NativeInteractionStatus.pending,
    );
    expect(service.state.interactions.single.answerDraft!.custom, 'Note');
    expect(() => snapshot.pending.clear(), throwsUnsupportedError);
  });

  test('indefinite wait callId remains metadata and structured empty answer is explicit skip', () async {
    await start();
    final item = await deliver(
      _question('skip', wait: {'callId': 'indefinite'}),
    );
    expect(item.toolCallId, 'indefinite');
    await service.answerStructured(
      item.requestId,
      NativeQuestionReply(questionId: 'q1'),
    );
    expect(rpc.calls.single.args['outcome'], {
      'kind': 'result',
      'value': {
        'answers': [
          {'id': 'q1', 'selected': []},
        ],
      },
    });
  });

  test(
    'an additional ready after opening ends the current generation',
    () async {
      await start();
      rpc.send(_ready('replacement-without-new-stream'));
      await until((s) => !s.ready && s.errorCode == 'interactions/protocol');
      expect(rpc.calls, isEmpty);
    },
  );

  test(
    'real loopback carrier uses exact mux and HTTP event-result envelopes',
    () async {
      final host = LoopbackDshHost();
      await host.start();
      final realRpc = DshRpcConnection(webUrl: host.webUrl);
      final real = NativeInteractions(rpc: realRpc, sessionId: 'parent');
      host.onCall = (_) async => null;
      host.onFrame = (frame) {
        if (frame.frame['type'] == 'open' &&
            frame.frame['endpoint'] == r'$events') {
          host.item(frame, _ready('real-client'));
          host.item(frame, _approval('real-event'));
        }
      };
      try {
        await realRpc.connect();
        await real.start();
        if (real.state.pending.isEmpty) {
          await real.updates
              .firstWhere((s) => s.pending.isNotEmpty)
              .timeout(const Duration(seconds: 3));
        }
        await real.approve(real.state.pending.single.requestId);
        final open = host.frames.singleWhere(
          (frame) => frame.frame['type'] == 'open',
        );
        expect(open.frame['payload'], {'args': {}});
        final call = host.requests.single;
        expect(call.endpoint, r'$events/result');
        expect(call.envelope['method'], r'$events/result');
        expect(call.envelope['payload'], {
          'args': {
            'clientId': 'real-client',
            'eventId': 'real-event',
            'outcome': {'kind': 'result', 'value': 'allowed-once'},
          },
        });
        expect(host.exchanges.single.queryParameters['interactions'], 'answer');
      } finally {
        await real.close();
        await realRpc.close();
        await host.close();
      }
    },
  );
}
