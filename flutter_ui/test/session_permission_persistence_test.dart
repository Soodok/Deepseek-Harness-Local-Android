import 'dart:async';

import 'package:dsh_native_chat/src/model/composer_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/native_session_transport.dart';
import 'package:flutter_test/flutter_test.dart';

import 'dsh_rpc_connection_test.dart' show LoopbackDshHost, RpcRequest;
import 'dsh_session_projection_test.dart' show snapshot, user;

Map<String, Object?> args(RpcRequest r) =>
    Map<String, Object?>.from((r.envelope['payload'] as Map)['args'] as Map);

/// F01 的判据：设置页选择权限必须命中**当前真实会话**，立即由宿主落库，
/// 用真实命令写入并经宿主回读确认；不能顺手发出一条消息、切换模型，
/// 也不能把回执当成生效证据。
class PermissionHost {
  late final LoopbackDshHost host;
  String preset = 'danger-full-access';
  int cut = 0;
  int prompts = 0;
  int modelSelections = 0;
  bool rejectCommand = false;
  bool ignoreWrite = false;
  Completer<void>? gate;
  final commands = <String>[];
  List<String> advertised = const [
    'read-only',
    'workspace-write',
    'danger-full-access',
  ];

  Future<void> start() async {
    host = LoopbackDshHost();
    await host.start();
    host.onCall = (r) async {
      switch (r.endpoint) {
        case 'session/modelCatalog':
          return {
            'default': {'provider': 'provider', 'model': 'm'},
            'groups': [
              {
                'id': 'provider',
                'name': 'Provider',
                'models': [
                  {
                    'id': 'm',
                    'name': 'Visible Model',
                    'description': 'catalog',
                  },
                ],
              },
            ],
            'failures': <Object?>[],
          };
        case 'permissionPresets/catalog':
          return {
            'options': [
              for (final value in advertised) {'value': value, 'name': value},
            ],
            'defaultOptions': <Object?>[],
            'defaultPreset': 'danger-full-access',
          };
        case 'commands/execute':
          return _execute(r);
        case 'session/selectModel':
          modelSelections++;
          return {
            'selected': {'provider': 'provider', 'model': 'm'},
          };
        case 'session/projections':
          return {
            'kind': 'sequenced',
            'asOfSeq': cut,
            'values': {
              'permissions': {'currentValue': preset},
              'subagentCatalog': <Object?>[],
            },
          };
        case 'session/state':
          return {
            'cursor': cut,
            'active': true,
            'running': false,
            'queue': <Object?>[],
            'pendingInput': <Object?>[],
          };
        case 'session/prompt':
          prompts++;
          return {'accepted': true};
        default:
          return null;
      }
    };
    host.onFrame = (r) {
      if (r.frame['type'] != 'open') return;
      if (r.frame['endpoint'] == r'$events') {
        host.item(r, {
          'type': 'ready',
          'clientId': 'owner',
          'host': {'home': '/home'},
        });
        return;
      }
      host.item(
        r,
        snapshot(
          0,
          [user(0, 'initial')],
          values: {
            'permissions': {'currentValue': preset},
            'subagentCatalog': [],
          },
        ),
      );
    };
  }

  Future<Object?> _execute(RpcRequest r) async {
    final line = args(r)['line'];
    if (line is! String) return null;
    commands.add(line);
    final gate = this.gate;
    if (gate != null) await gate.future;
    if (rejectCommand) {
      return {
        'commandId': 'command-1',
        'result': {'kind': 'error', 'text': 'host refused'},
      };
    }
    if (!ignoreWrite) {
      preset = line.replaceFirst('/permission ', '');
      cut += 1;
    }
    return {
      'commandId': 'command-1',
      'result': {'kind': 'success', 'text': 'preset selected'},
    };
  }
}

void main() {
  late PermissionHost fixture;
  late DshRpcConnection rpc;
  late NativeSessionTransport transport;

  /// [started] stays false when the ChatController must be the one that
  /// opens the session: connecting first would leave its own status in
  /// "connecting" with no later snapshot to settle it.
  Future<void> open({bool started = true}) async {
    fixture = PermissionHost();
    await fixture.start();
    rpc = DshRpcConnection(webUrl: fixture.host.webUrl);
    transport = NativeSessionTransport(rpc: rpc, sessionId: 'parent');
    addTearDown(() async {
      await transport.close();
      await rpc.close();
      await fixture.host.close();
    });
    if (started) await transport.connect();
  }

  Future<void> until(bool Function() test) async {
    for (var i = 0; i < 200 && !test(); i++) {
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
    if (!test()) fail('condition never became true');
  }

  test('a settings write reaches the bound session without a prompt', () async {
    await open();
    expect(
      transport.composerConfiguration.permission,
      ComposerPermission.fullAccess,
    );
    await transport.applySessionPermission(ComposerPermission.readOnly);
    expect(fixture.preset, 'read-only');
    expect(fixture.commands, ['/permission read-only']);
    expect(fixture.prompts, 0);
    expect(fixture.modelSelections, 0);
    final command = args(
      fixture.host.requests.singleWhere(
        (r) => r.endpoint == 'commands/execute',
      ),
    );
    expect(command, {
      'agentId': 'parent',
      'line': '/permission read-only',
      'submittedAttachments': <Object?>[],
    });
    expect(
      transport.composerConfiguration.permission,
      ComposerPermission.readOnly,
    );
    expect(
      fixture.host.requests.where(
        (r) => [
          'session/prompt',
          'session/selectModel',
          'session/cancel',
        ].contains(r.endpoint),
      ),
      isEmpty,
    );
  });

  test(
    'a preset the host never advertised is refused before any write',
    () async {
      await open();
      fixture.advertised = const ['read-only', 'workspace-write'];
      await transport.refreshCapabilities();
      await expectLater(
        transport.applySessionPermission(ComposerPermission.fullAccess),
        throwsA(
          isA<SessionPermissionException>().having(
            (e) => e.code,
            'code',
            'permission/unavailable',
          ),
        ),
      );
      expect(fixture.commands, isEmpty);
      expect(fixture.preset, 'danger-full-access');
    },
  );

  test(
    'a receipt without a host read-back is reported as not applied',
    () async {
      await open();
      fixture.ignoreWrite = true;
      await expectLater(
        transport.applySessionPermission(ComposerPermission.readOnly),
        throwsA(
          isA<SessionPermissionException>().having(
            (e) => e.code,
            'code',
            'permission/unconfirmed',
          ),
        ),
      );
      expect(
        transport.composerConfiguration.permission,
        ComposerPermission.fullAccess,
      );
      expect(fixture.prompts, 0);
    },
  );

  test(
    'a refused command surfaces the host reason and keeps the old value',
    () async {
      await open();
      fixture.rejectCommand = true;
      await expectLater(
        transport.applySessionPermission(ComposerPermission.readOnly),
        throwsA(
          isA<SessionPermissionException>().having(
            (e) => e.code,
            'code',
            'permission/rejected',
          ),
        ),
      );
      expect(
        transport.composerConfiguration.permission,
        ComposerPermission.fullAccess,
      );
      expect(fixture.preset, 'danger-full-access');
    },
  );

  test(
    'the same preset twice writes once and never a duplicate command',
    () async {
      await open();
      final first = transport.applySessionPermission(
        ComposerPermission.readOnly,
      );
      final second = transport.applySessionPermission(
        ComposerPermission.readOnly,
      );
      await Future.wait([first, second]);
      expect(fixture.commands, ['/permission read-only']);
      await transport.applySessionPermission(ComposerPermission.readOnly);
      expect(fixture.commands, ['/permission read-only']);
    },
  );

  test('a conflicting write while one runs is refused instead of queued', () async {
    await open();
    fixture.gate = Completer<void>();
    final running = transport.applySessionPermission(
      ComposerPermission.readOnly,
    );
    // Wait for the command to actually reach the Host; a fixed delay could let
    // the second call race a write that had not started yet.
    await until(() => fixture.commands.isNotEmpty);
    await expectLater(
      transport.applySessionPermission(ComposerPermission.workspaceWrite),
      throwsA(
        isA<SessionPermissionException>().having(
          (e) => e.code,
          'code',
          'permission/busy',
        ),
      ),
    );
    fixture.gate!.complete();
    await running;
    expect(fixture.commands, ['/permission read-only']);
    expect(fixture.preset, 'read-only');
  });

  test('a reconnect while writing invalidates the unconfirmed write', () async {
    await open();
    // The Host value stays put so the assertion after the reconnect can only
    // fail because of the generation, not because the delayed command landed.
    fixture.ignoreWrite = true;
    fixture.gate = Completer<void>();
    final running = transport.applySessionPermission(
      ComposerPermission.readOnly,
    );
    await until(() => fixture.commands.isNotEmpty);
    await transport.reconnect();
    // Attach the expectation before releasing the Host, so the failure can
    // never surface as an unhandled error between the two statements.
    final expectation = expectLater(
      running,
      throwsA(
        isA<SessionPermissionException>().having(
          (e) => e.code,
          'code',
          'permission/stale',
        ),
      ),
    );
    fixture.gate!.complete();
    await expectation;
    // The reopened session reads its own value; the refused write must not
    // have replaced it with the requested preset.
    await transport.refreshConfiguration();
    expect(
      transport.composerConfiguration.permission,
      ComposerPermission.fullAccess,
    );
  });

  test('the composer draft stays a draft until it is sent', () async {
    await open(started: false);
    final controller = ChatController(transport);
    addTearDown(controller.dispose);
    await controller.start();
    await until(() => controller.canSend);
    controller.selectPermission(ComposerPermission.readOnly);
    expect(fixture.commands, isEmpty);
    expect(controller.sessionPermission, ComposerPermission.fullAccess);
    expect(
      await controller.applySessionPermission(ComposerPermission.readOnly),
      isTrue,
    );
    expect(fixture.commands, ['/permission read-only']);
    expect(controller.permissionWriteError, isNull);
    expect(controller.sessionPermission, ComposerPermission.readOnly);
    // The confirmed write means the next send inherits instead of writing twice.
    await controller.sendPrompt('继续');
    expect(fixture.prompts, 1);
    expect(fixture.commands, ['/permission read-only']);
  });

  test('a failed immediate write is shown in product language', () async {
    await open(started: false);
    fixture.ignoreWrite = true;
    final controller = ChatController(transport);
    addTearDown(controller.dispose);
    await controller.start();
    await Future<void>.delayed(Duration.zero);
    expect(
      await controller.applySessionPermission(ComposerPermission.readOnly),
      isFalse,
    );
    expect(controller.permissionWriteError, contains('未生效'));
    expect(controller.sessionPermission, ComposerPermission.fullAccess);
  });
}
