import 'dart:async';
import 'dart:typed_data';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/composer_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
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
    approval('reject-me');
    await until((s) => s.approval != null);
    await live.rejectApproval(state.approval!.requestId);
    await until((s) => s.approval == null);
    expect(
      args(host.requests.lastWhere((r) => r.endpoint == r'$events/result')),
      containsPair('outcome', {'kind': 'result', 'value': 'rejected'}),
    );
    expect(host.requests.where((r) => r.endpoint == 'session/cancel'), isEmpty);
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

  test('reopening a real read-only session displays its model and never upgrades on send', () async {
    final original = host.onCall!;
    host.onCall = (r) async => r.endpoint == 'session/modelCatalog'
        ? {
            'default': {'provider': 'provider', 'model': 'catalog-default'},
            'groups': [
              {
                'id': 'provider',
                'models': [
                  {'id': 'catalog-default', 'name': 'Catalog Default'},
                  {'id': 'm', 'name': 'Restored Session Model'},
                ],
              },
            ],
            'failures': [],
          }
        : await original(r);
    host.onFrame = (r) {
      if (r.frame['type'] == 'open') {
        host.item(
          r,
          snapshot(
            0,
            [user(0, 'initial')],
            values: {
              'modelSelection': {
                'lastUsed': {
                  'provider': 'provider',
                  'model': 'catalog-default',
                },
                'next': {'provider': 'provider', 'model': 'm'},
              },
              'permissions': {'currentValue': 'read-only'},
            },
          ),
        );
      }
    };
    final live = NativeSessionTransport(rpc: rpc, sessionId: 'parent');
    final controller = ChatController(live);
    addTearDown(controller.dispose);
    await controller.start();
    await Future<void>.delayed(Duration.zero);
    expect(controller.selectedModelId, '["provider","m"]');
    expect(controller.modelLabel, 'Restored Session Model');
    expect(controller.permission, ComposerPermission.readOnly);
    expect(await controller.sendPrompt('保留 Host 只读权限'), isTrue);
    expect(
      host.requests.where((r) => r.endpoint == 'session/prompt'),
      hasLength(1),
    );
    expect(
      host.requests.where((r) => r.endpoint == 'commands/execute'),
      isEmpty,
    );
    expect(
      host.requests.where((r) => r.endpoint == 'session/selectModel'),
      isEmpty,
    );
    await live.close();
  });

  test(
    'opening snapshot restores next model and current session permission',
    () async {
      host.onFrame = (r) {
        if (r.frame['type'] == 'open') {
          host.item(
            r,
            snapshot(
              0,
              [user(0, 'initial')],
              values: {
                'modelSelection': {
                  'lastUsed': {
                    'provider': 'old-provider',
                    'model': 'old-model',
                  },
                  'next': {
                    'provider': 'provider',
                    'model': 'm',
                    'reasoningEffort': 'high',
                  },
                },
                'permissions': {'currentValue': 'read-only'},
              },
            ),
          );
        }
      };
      await transport.connect();
      expect(transport.composerConfiguration.modelId, '["provider","m"]');
      expect(
        transport.composerConfiguration.permission,
        ComposerPermission.readOnly,
      );
      expect(transport.composerConfiguration.permissionPreset, 'read-only');
      expect(
        host.requests.where((r) => r.endpoint == 'commands/execute'),
        isEmpty,
      );
      expect(
        host.requests.where((r) => r.endpoint == 'session/selectModel'),
        isEmpty,
      );
    },
  );

  test(
    'a restored model remains identifiable when its catalog is unavailable',
    () async {
      final original = host.onCall!;
      host.onCall = (r) async => r.endpoint == 'session/modelCatalog'
          ? {'groups': [], 'failures': []}
          : await original(r);
      host.onFrame = (r) {
        if (r.frame['type'] == 'open') {
          host.item(
            r,
            snapshot(
              0,
              [user(0, 'initial')],
              values: {
                'modelSelection': {
                  'lastUsed': null,
                  'next': {'provider': 'host-provider', 'model': 'host-model'},
                },
                'permissions': {'currentValue': 'read-only'},
              },
            ),
          );
        }
      };
      final live = NativeSessionTransport(rpc: rpc, sessionId: 'parent');
      final controller = ChatController(live);
      addTearDown(controller.dispose);
      await controller.start();
      expect(controller.modelLabel, 'host-model');
      await live.close();
    },
  );

  test('inherited unknown permission is refused before prompt or configuration writes', () async {
    host.onFrame = (r) {
      if (r.frame['type'] == 'open')
        host.item(
          r,
          snapshot(
            0,
            [user(0, 'initial')],
            values: {
              'permissions': {'currentValue': 'custom'},
            },
          ),
        );
    };
    await transport.connect();
    await expectLater(
      transport.sendSubmission(
        PromptSubmission(
          text: 'must not run',
          modelId: null,
          permission: ComposerPermission.readOnly,
          applyPermission: false,
          applyModel: false,
        ),
      ),
      throwsA(isA<DshRpcException>()),
    );
    expect(
      host.requests.where(
        (r) =>
            r.endpoint == 'session/prompt' ||
            r.endpoint == 'commands/execute' ||
            r.endpoint == 'session/selectModel',
      ),
      isEmpty,
    );
  });

  test('unknown permission received during model selection refuses prompt admission', () async {
    host.onFrame = (r) {
      if (r.frame['type'] == 'open')
        host.item(
          r,
          snapshot(
            0,
            [user(0, 'initial')],
            values: {
              'permissions': {'currentValue': 'read-only'},
            },
          ),
        );
    };
    await transport.connect();
    await transport.refreshCatalog();
    final original = host.onCall!;
    final entered = Completer<void>(), release = Completer<Object?>();
    host.onCall = (r) async {
      if (r.endpoint == 'session/selectModel') {
        entered.complete();
        return release.future;
      }
      if (r.endpoint == 'session/projections')
        return {
          'kind': 'sequenced',
          'asOfSeq': 1,
          'values': {
            'permissions': {'currentValue': 'custom'},
          },
        };
      return await original(r);
    };
    final sending = transport.sendSubmission(
      PromptSubmission(
        text: 'must remain blocked',
        modelId: transport.models.single.id,
        permission: ComposerPermission.readOnly,
        applyModel: true,
        applyPermission: false,
      ),
    );
    await entered.future;
    await transport.refreshConfiguration();
    expect(transport.composerConfiguration.permission, isNull);
    final rejected = expectLater(sending, throwsA(isA<DshRpcException>()));
    release.complete({
      'selected': {'provider': 'provider', 'model': 'm'},
    });
    await rejected;
    expect(host.requests.where((r) => r.endpoint == 'session/prompt'), isEmpty);
    expect(
      host.requests.where((r) => r.endpoint == 'commands/execute'),
      isEmpty,
    );
  });

  test('inherited submissions ignore catalog defaults and never write configuration', () async {
    host.onFrame = (r) {
      if (r.frame['type'] == 'open')
        host.item(
          r,
          snapshot(
            0,
            [user(0, 'initial')],
            values: {
              'permissions': {'currentValue': 'read-only'},
            },
          ),
        );
    };
    await transport.connect();
    await transport.sendSubmission(
      PromptSubmission(
        text: 'inherit the host',
        modelId: 'not-a-catalog-route',
        permission: ComposerPermission.workspaceWrite,
        applyModel: false,
        applyPermission: false,
      ),
    );
    expect(
      host.requests.where((r) => r.endpoint == 'session/prompt'),
      hasLength(1),
    );
    expect(
      host.requests.where((r) => r.endpoint == 'commands/execute'),
      isEmpty,
    );
    expect(
      host.requests.where((r) => r.endpoint == 'session/selectModel'),
      isEmpty,
    );
  });

  test(
    'configuration refresh uses real request shape and ignores cached hints',
    () async {
      host.onFrame = (r) {
        if (r.frame['type'] == 'open') {
          host.item(
            r,
            snapshot(
              0,
              [user(0, 'initial')],
              values: {
                'permissions': {'currentValue': 'read-only'},
              },
            ),
          );
        }
      };
      await transport.connect();
      await transport.refreshCatalog();
      final original = host.onCall!;
      host.onCall = (r) async => r.endpoint == 'session/projections'
          ? {
              'kind': 'migration-required',
              'values': {
                'permissions': {'currentValue': 'danger-full-access'},
              },
            }
          : await original(r);
      await expectLater(
        transport.refreshConfiguration(),
        throwsA(isA<DshRpcException>()),
      );
      expect(
        transport.composerConfiguration.permission,
        ComposerPermission.readOnly,
      );
      final reads = host.requests.where(
        (r) => r.endpoint == 'session/projections',
      );
      expect(request(reads.last), {'sessionId': 'parent'});
    },
  );

  test(
    'late configuration read cannot undo an acknowledged permission change',
    () async {
      await transport.connect();
      await transport.refreshCatalog();
      final original = host.onCall!;
      final entered = Completer<void>(), release = Completer<Object?>();
      host.onCall = (r) async {
        if (r.endpoint == 'session/projections') {
          if (!entered.isCompleted) entered.complete();
          return release.future;
        }
        return await original(r);
      };
      final pending = transport.refreshConfiguration();
      await entered.future;
      await transport.sendSubmission(
        PromptSubmission(
          text: 'explicitly read only',
          modelId: null,
          permission: ComposerPermission.readOnly,
          applyModel: false,
        ),
      );
      release.complete({
        'kind': 'sequenced',
        'asOfSeq': 0,
        'values': {
          'permissions': {'currentValue': 'danger-full-access'},
        },
      });
      await pending;
      expect(
        transport.composerConfiguration.permission,
        ComposerPermission.readOnly,
      );
    },
  );

  test('follow configuration events refresh through exact projections and reject older cuts', () async {
    host.onFrame = (r) {
      if (r.frame['type'] == 'open') {
        host.item(
          r,
          snapshot(
            0,
            [user(0, 'initial')],
            values: {
              'permissions': {'currentValue': 'danger-full-access'},
            },
          ),
        );
      }
    };
    await transport.connect();
    await transport.refreshCatalog();
    final original = host.onCall!;
    var cut = 0;
    final entered = Completer<void>();
    host.onCall = (r) async {
      if (r.endpoint != 'session/projections') return await original(r);
      if (!entered.isCompleted) entered.complete();
      return {
        'kind': 'sequenced',
        'asOfSeq': cut,
        'values': {
          'permissions': {'currentValue': 'read-only'},
          'subagentCatalog': [],
        },
      };
    };
    final channel = await host.waitFrame(
      (r) =>
          r.frame['type'] == 'open' && r.frame['endpoint'] == 'session/follow',
    );
    host.item(channel, {
      'type': 'event',
      'event': {
        'seq': 1,
        'time': 10,
        'type': 'permission/preset',
        'data': {'preset': 'read-only'},
      },
    });
    await entered.future;
    await transport.refreshConfiguration();
    expect(
      transport.composerConfiguration.permission,
      ComposerPermission.fullAccess,
    );
    cut = 1;
    await transport.refreshConfiguration();
    expect(
      transport.composerConfiguration.permission,
      ComposerPermission.readOnly,
    );
  });

  test(
    'reconnect restores the new Host choice instead of the previous projection',
    () async {
      var permission = 'danger-full-access';
      host.onFrame = (r) {
        if (r.frame['type'] == 'open') {
          host.item(
            r,
            snapshot(
              0,
              [user(0, 'initial')],
              values: {
                'modelSelection': {
                  'lastUsed': null,
                  'next': {'provider': 'provider', 'model': 'm'},
                },
                'permissions': {'currentValue': permission},
              },
            ),
          );
        }
      };
      await transport.connect();
      expect(
        transport.composerConfiguration.permission,
        ComposerPermission.fullAccess,
      );
      permission = 'read-only';
      await transport.reconnect();
      expect(
        transport.composerConfiguration.permission,
        ComposerPermission.readOnly,
      );
      expect(
        host.requests.where((r) => r.endpoint == 'commands/execute'),
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
        'agentId': 'parent',
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
      final cancelled = await host.waitFrame(
        (r) => r.frame['type'] == 'cancel',
      );
      expect(cancelled.frame['type'], 'cancel');
    },
  );
}
