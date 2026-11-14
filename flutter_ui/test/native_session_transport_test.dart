import 'dart:async';
import 'dart:typed_data';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/composer_model.dart';
import 'package:dsh_native_chat/src/state/chat_reducer.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/native_session_transport.dart';
import 'package:flutter_test/flutter_test.dart';

import 'dsh_rpc_connection_test.dart' show LoopbackDshHost, RpcRequest;
import 'dsh_session_projection_test.dart'
    show snapshot, user, assistant, record;

Map<String, Object?> args(RpcRequest r) =>
    Map<String, Object?>.from((r.envelope['payload'] as Map)['args'] as Map);
Map<String, Object?> request(RpcRequest r) =>
    Map<String, Object?>.from(args(r)['request'] as Map);

void main() {
  test('native interactions project, reply and withdraw through the parent sequence', () async {
    final host = LoopbackDshHost();
    await host.start();
    final rpc = DshRpcConnection(webUrl: host.webUrl);
    final live = NativeSessionTransport(
      rpc: rpc,
      sessionId: 'parent',
      enableInteractions: true,
    );
    var state = ChatState.empty();
    final changes = StreamController<ChatState>.broadcast(sync: true);
    final events = <ChatEvent>[];
    final sub = live.events.listen((e) {
      events.add(e);
      state = ChatReducer.apply(state, e);
      changes.add(state);
    });
    addTearDown(() async {
      await live.close();
      await sub.cancel();
      await changes.close();
      await host.close();
    });
    Future<void> until(bool Function(ChatState) predicate) async {
      if (!predicate(state))
        await changes.stream
            .firstWhere(predicate)
            .timeout(const Duration(seconds: 3));
    }

    host.onFrame = (r) {
      if (r.frame['type'] != 'open') return;
      if (r.frame['endpoint'] == r'$events') {
        host.item(r, {
          'type': 'ready',
          'clientId': 'owner',
          'host': {'home': '/home'},
        });
      } else {
        host.item(r, snapshot(0, [user(0, 'initial')]));
      }
    };
    host.onCall = (r) async => switch (r.endpoint) {
      'session/state' => {
        'cursor': 0,
        'active': true,
        'running': false,
        'queue': [],
        'pendingInput': [],
      },
      'session/projections' => {
        'kind': 'sequenced',
        'asOfSeq': 0,
        'values': {'subagentCatalog': []},
      },
      _ => null,
    };
    await live.connect();
    await until((s) => s.messages.isNotEmpty);
    final channel = await host.waitFrame(
      (r) => r.frame['type'] == 'open' && r.frame['endpoint'] == r'$events',
    );
    void approval(String id) => host.item(channel, {
      'type': 'waterfall',
      'event': 'approval/request',
      'eventId': id,
      'agentId': 'parent',
      'request': {'toolName': 'write', 'reason': 'Write file'},
    });
    approval('a1');
    await until((s) => s.approval != null);
    await live.approve(state.approval!.requestId);
    await until((s) => s.approval == null);
    expect(
      args(
        host.requests.lastWhere((r) => r.endpoint == r'$events/result'),
      )['outcome'],
      {'kind': 'result', 'value': 'allowed-once'},
    );
    host.item(channel, {
      'type': 'waterfall',
      'event': 'user-questions/request',
      'eventId': 'q1',
      'agentId': 'parent',
      'request': {
        'questions': [
          {
            'id': 'question',
            'question': 'Choose',
            'multiSelect': true,
            'options': [
              {'label': 'Alpha'},
              {'label': 'Beta'},
            ],
          },
        ],
      },
    });
    await until((s) => s.question != null);
    final id = state.question!.requestId;
    expect(state.question!.draftKey, isNotNull);
    await live.answerQuestion(
      id,
      QuestionAnswer(selected: ['Alpha'], custom: 'and check Gamma'),
    );
    await until((s) => s.question == null);
    expect(
      args(
        host.requests.lastWhere((r) => r.endpoint == r'$events/result'),
      )['outcome'],
      {
        'kind': 'result',
        'value': {
          'answers': [
            {
              'id': 'question',
              'selected': ['Alpha'],
              'custom': 'and check Gamma',
            },
          ],
        },
      },
    );
    approval('a2');
    await until((s) => s.approval != null);
    final cancelled = state.approval!.requestId;
    host.item(channel, {'type': 'cancel', 'eventId': 'a2'});
    await until((s) => s.approval == null);
    await expectLater(live.approve(cancelled), throwsA(isA<DshRpcException>()));
    expect(state.needsResync, isFalse);
    expect(events.where((e) => e.kind == ChatEventKind.turnComplete), isEmpty);
  });

  late LoopbackDshHost host;
  late DshRpcConnection rpc;
  late NativeSessionTransport transport;
  late ChatState state;
  late List<ChatEvent> events;
  late StreamController<ChatState> changed;
  late StreamSubscription<ChatEvent> subscription;
  late List<String> presets;
  Future<ChatState> until(bool Function(ChatState) test) => test(state)
      ? Future.value(state)
      : changed.stream.firstWhere(test).timeout(const Duration(seconds: 4));
  setUp(() async {
    host = LoopbackDshHost();
    await host.start();
    rpc = DshRpcConnection(webUrl: host.webUrl);
    transport = NativeSessionTransport(rpc: rpc, sessionId: 'parent');
    state = ChatState.empty();
    events = [];
    changed = StreamController<ChatState>.broadcast(sync: true);
    subscription = transport.events.listen((event) {
      events.add(event);
      state = ChatReducer.apply(state, event);
      changed.add(state);
    });
    presets = ['read-only', 'workspace-write', 'danger-full-access'];
    host.onCall = (r) async => switch (r.endpoint) {
      'session/list' => {
        'items': [
          {'sessionId': 'parent', 'title': 'session'},
        ],
      },
      'session/modelCatalog' => {
        'default': {'provider': 'provider', 'model': 'm'},
        'groups': [
          {
            'id': 'provider',
            'name': 'Provider',
            'models': [
              {
                'id': 'm',
                'name': 'Visible Model',
                'description': 'real catalog model',
              },
            ],
          },
        ],
        'failures': [],
      },
      'permissionPresets/catalog' => {
        'options': [
          for (final p in presets) {'value': p, 'name': p},
        ],
        'defaultOptions': [],
        'defaultPreset': 'workspace-write',
      },
      'commands/execute' => {
        'commandId': 'command-1',
        'result': {'kind': 'success', 'text': 'preset selected'},
      },
      'session/selectModel' => {
        'selected': {'provider': 'provider', 'model': 'm'},
      },
      'session/state' => {
        'cursor': 0,
        'active': true,
        'running': false,
        'queue': [],
        'pendingInput': [],
      },
      'session/projections' => {
        'kind': 'sequenced',
        'asOfSeq': 0,
        'values': {'subagentCatalog': []},
      },
      'session/events' => {
        'cursor': 0,
        'records': [user(0, 'history')],
        'hasMore': false,
      },
      'session/prompt' || 'session/cancel' => {'accepted': true},
      _ => null,
    };
    host.onFrame = (r) {
      if (r.frame['type'] == 'open')
        host.item(r, snapshot(0, [user(0, 'initial')]));
    };
  });
  tearDown(() async {
    await transport.close();
    await subscription.cancel();
    await rpc.close();
    await host.close();
    await changed.close();
  });

  test(
    'list uses _request, follow stays addressed and real events become visible',
    () async {
      final sessions = await NativeSessionTransport.listSessions(rpc);
      expect(sessions.single['sessionId'], 'parent');
      expect(
        args(host.requests.singleWhere((r) => r.endpoint == 'session/list')),
        {'_request': {}},
      );
      await transport.connect();
      await until((s) => s.messages.isNotEmpty);
      final open = host.frames.singleWhere((r) => r.frame['type'] == 'open');
      expect(open.frame['endpoint'], 'session/follow');
      expect(open.frame['payload'], {
        'args': {
          'request': {
            'address': {'kind': 'session', 'sessionId': 'parent'},
            'assistantStream': true,
            'maxMessages': 500,
            'turnWindow': {'minMessages': 50, 'minTurns': 2},
          },
        },
      });
      host.item(open, assistant(1, 'visible reply'));
      await until((s) => s.messages.any((m) => m.text == 'visible reply'));
      expect(state.needsResync, isFalse);
      expect(transport.isFixture, isFalse);
      expect((await transport.readHistory()).single.text, 'history');
      final page = request(
        host.requests.singleWhere((r) => r.endpoint == 'session/events'),
      );
      expect(page, {
        'address': {'kind': 'session', 'sessionId': 'parent'},
        'maxMessages': 500,
        'turnWindow': {'minMessages': 50, 'minTurns': 2},
      });
    },
  );

  test(
    'prompt and stop preserve real RPC arguments and do not invent completion',
    () async {
      await transport.connect();
      await transport.sendPrompt('hello');
      await transport.stop();
      final prompt = request(
        host.requests.singleWhere((r) => r.endpoint == 'session/prompt'),
      );
      expect(prompt, {
        'sessionId': 'parent',
        'requestId': isA<String>(),
        'mode': 'queue',
        'content': [
          {'type': 'text', 'text': 'hello'},
        ],
      });
      expect(
        request(
          host.requests.singleWhere((r) => r.endpoint == 'session/cancel'),
        ),
        {'sessionId': 'parent'},
      );
      expect(
        events.where((e) => e.kind == ChatEventKind.turnComplete),
        isEmpty,
      );
    },
  );

  test('composer exposes models and sends verified permission command before prompt', () async {
    await transport.connect();
    expect(transport.models.single.label, 'Visible Model');
    expect(transport.supportsAttachments, isFalse);
    expect(transport.permissions, ComposerPermission.values);
    expect(transport.models.single.description, contains('real catalog model'));
    expect(() => transport.models.clear(), throwsUnsupportedError);
    for (final permission in ComposerPermission.values) {
      await transport.sendSubmission(
        PromptSubmission(
          text: permission.name,
          modelId: transport.models.single.id,
          permission: permission,
        ),
      );
    }
    final mutations = host.requests
        .where(
          (r) => [
            'commands/execute',
            'session/selectModel',
            'session/prompt',
          ].contains(r.endpoint),
        )
        .toList();
    expect(
      mutations.where((r) => r.endpoint == 'session/prompt'),
      hasLength(3),
    );
    for (final permission in ComposerPermission.values) {
      final command = mutations.singleWhere(
        (r) =>
            r.endpoint == 'commands/execute' &&
            args(r)['line'] == '/permission ' + permission.wireValue,
      );
      expect(args(command), {
        'agent': 'parent',
        'line': '/permission ' + permission.wireValue,
        'submittedAttachments': [],
      });
    }
    expect(
      request(mutations.firstWhere((r) => r.endpoint == 'session/selectModel')),
      {'sessionId': 'parent', 'provider': 'provider', 'model': 'm'},
    );
  });

  test('unsupported submission parts fail before changing model, policy or sending', () async {
    presets.remove('read-only');
    await transport.connect();
    final before = host.requests.length;
    await expectLater(
      transport.sendSubmission(
        PromptSubmission(
          text: 'blocked',
          modelId: null,
          permission: ComposerPermission.readOnly,
        ),
      ),
      throwsA(isA<DshRpcException>()),
    );
    await expectLater(
      transport.sendSubmission(
        PromptSubmission(
          text: 'blocked',
          modelId: 'invented',
          permission: ComposerPermission.workspaceWrite,
        ),
      ),
      throwsA(isA<DshRpcException>()),
    );
    await expectLater(
      transport.sendSubmission(
        PromptSubmission(
          text: 'blocked',
          modelId: null,
          permission: ComposerPermission.workspaceWrite,
          attachments: [
            PromptAttachment(
              id: 'f',
              name: 'data.bin',
              bytes: Uint8List.fromList([1, 2]),
            ),
          ],
        ),
      ),
      throwsA(
        isA<DshRpcException>().having(
          (e) => e.code,
          'code',
          'session/unsupported',
        ),
      ),
    );
    expect(
      host.requests
          .skip(before)
          .where(
            (r) => [
              'session/prompt',
              'session/selectModel',
              'commands/execute',
            ].contains(r.endpoint),
          ),
      isEmpty,
    );
  });

  test('business failures and malformed receipts propagate instead of appearing accepted', () async {
    await transport.connect();
    host.failure = {
      'code': 'session/agent-busy',
      'message': 'busy',
      'details': {'reason': 'real reason'},
    };
    await expectLater(
      transport.sendPrompt('retry'),
      throwsA(
        isA<DshRpcException>().having(
          (e) => e.code,
          'code',
          'session/agent-busy',
        ),
      ),
    );
    host.failure = null;
    host.onCall = (r) async => {'ok': true};
    await expectLater(
      transport.sendPrompt('bad receipt'),
      throwsA(isA<DshRpcException>()),
    );
    final count = host.requests.length;
    await expectLater(
      transport.approve('approval'),
      throwsA(
        isA<DshRpcException>().having(
          (e) => e.code,
          'code',
          'session/unsupported',
        ),
      ),
    );
    await expectLater(
      transport.answer('question', 'answer'),
      throwsA(isA<DshRpcException>()),
    );
    expect(host.requests, hasLength(count));
  });

  test('gap reports an error, then explicit reconnect replaces the window in a new generation', () async {
    await transport.connect();
    await until((s) => s.messages.isNotEmpty);
    final old = host.frames.singleWhere((r) => r.frame['type'] == 'open');
    final oldGeneration = state.generation;
    host.item(old, assistant(2, 'gap'));
    await until((s) => s.status == ChatStatus.error);
    expect(state.error, contains('sequence-gap'));
    host.onFrame = (r) {
      if (r.frame['type'] == 'open')
        host.item(r, snapshot(5, [user(5, 'fresh')]));
    };
    await transport.reconnect();
    await until((s) => s.messages.singleOrNull?.text == 'fresh');
    host.item(old, assistant(1, 'stale generation'));
    expect(state.generation, greaterThan(oldGeneration));
    expect(state.needsResync, isFalse);
    expect(host.frames.where((r) => r.frame['type'] == 'open'), hasLength(2));
    expect(
      events
          .where((e) => e.generation == state.generation)
          .map((e) => e.sequence),
      orderedEquals(
        List.generate(
          events.where((e) => e.generation == state.generation).length,
          (i) => i,
        ),
      ),
    );
  });

  test('passive idle state clears streaming and pending actions direct users to Web', () async {
    await transport.connect();
    await until((s) => s.messages.isNotEmpty);
    final open = host.frames.singleWhere((r) => r.frame['type'] == 'open');
    host.item(open, record(1, 'turn/start', {'turn': 1}));
    await until((s) => s.status == ChatStatus.streaming);
    host.onCall = (r) async => {
      'cursor': 1,
      'active': false,
      'running': false,
      'queue': null,
      'pendingInput': [],
    };
    await transport.refreshState();
    await until((s) => s.status == ChatStatus.ready);
    expect(events.any((e) => e.kind == ChatEventKind.turnComplete), isFalse);
    host.onCall = (r) async => {
      'cursor': 1,
      'active': true,
      'running': true,
      'queue': [],
      'pendingInput': ['question'],
    };
    await expectLater(
      transport.refreshState(),
      throwsA(
        isA<DshRpcException>().having(
          (e) => e.code,
          'code',
          'session/unsupported',
        ),
      ),
    );
    expect(events.any((e) => e.kind == ChatEventKind.question), isFalse);
  });

  test(
    'catalog refresh delegates real child identities and one-shot cannot send',
    () async {
      final original = host.onCall!;
      host.onCall = (r) async {
        if (r.endpoint == 'session/projections')
          return {
            'kind': 'sequenced',
            'asOfSeq': 0,
            'values': request(r)['sessionId'] == 'parent'
                ? {
                    'subagentCatalog': [
                      {
                        'id': 'child',
                        'mode': 'one-shot',
                        'label': 'worker',
                        'createdAt': 0,
                      },
                    ],
                  }
                : {
                    'subagent': {
                      'mode': 'one-shot',
                      'label': 'worker',
                      'seq': 0,
                    },
                    'subagentTiming': {'lastTurnCompleted': false},
                  },
          };
        return original(r);
      };
      await transport.connect();
      await transport.refreshCatalog();
      await until((s) => s.tools.any((t) => t.content!.agents.isNotEmpty));
      final child = state.tools
          .expand((t) => t.content!.agents)
          .single
          .address!;
      expect(child.parentSessionId, 'parent');
      expect(child.childSessionId, 'child');
      expect(child.canSend, isFalse);
      final before = host.requests
          .where((r) => r.endpoint == 'subagents/prompt')
          .length;
      await expectLater(
        transport.sendSubagent(child, 'blocked', requestId: 'req'),
        throwsA(isA<DshRpcException>()),
      );
      expect(
        host.requests.where((r) => r.endpoint == 'subagents/prompt'),
        hasLength(before),
      );
    },
  );

  test('close during opening rejects connect without waiting for a snapshot timeout', () async {
    host.onFrame = (_) {};
    final pending = expectLater(
      transport.connect(),
      throwsA(isA<DshRpcException>()),
    );
    await host.waitFrame((r) => r.frame['type'] == 'open');
    await transport.close();
    await pending.timeout(const Duration(seconds: 2));
  });

  test(
    'permission command failure stops submission before prompt admission',
    () async {
      await transport.connect();
      final original = host.onCall!;
      host.onCall = (r) async => r.endpoint == 'commands/execute'
          ? {
              'commandId': 'permission',
              'result': {'kind': 'error', 'text': 'blocked preset'},
            }
          : await original(r);
      await expectLater(
        transport.sendSubmission(
          PromptSubmission(
            text: 'must not send',
            modelId: null,
            permission: ComposerPermission.workspaceWrite,
          ),
        ),
        throwsA(
          isA<DshRpcException>().having(
            (e) => e.code,
            'code',
            'session/permission-failed',
          ),
        ),
      );
      expect(
        host.requests.where((r) => r.endpoint == 'session/prompt'),
        isEmpty,
      );
    },
  );

  test(
    'close cancels follow and timer and rejects a late catalog result',
    () async {
      await transport.connect();
      await until((s) => s.messages.isNotEmpty);
      final entered = Completer<void>(), release = Completer<Object?>();
      host.onCall = (r) async {
        if (r.endpoint == 'session/projections') {
          if (!entered.isCompleted) entered.complete();
          return release.future;
        }
        return null;
      };
      final pending = transport.refreshCatalog().catchError((Object _) {});
      await entered.future;
      await transport.close();
      final count = events.length;
      release.complete({
        'kind': 'sequenced',
        'asOfSeq': 0,
        'values': {'subagentCatalog': []},
      });
      await pending;
      expect(events, hasLength(count));
      await expectLater(transport.connect(), throwsA(isA<DshRpcException>()));
      await expectLater(
        transport.refreshCatalog(),
        throwsA(isA<DshRpcException>()),
      );
      await expectLater(
        transport.refreshState(),
        throwsA(isA<DshRpcException>()),
      );
      expect(host.frames.any((r) => r.frame['type'] == 'cancel'), isTrue);
    },
  );
}
