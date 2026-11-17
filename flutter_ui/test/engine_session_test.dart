import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/engine_session.dart';
import 'package:dsh_native_chat/src/bridge/engine_sessions_native.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';

import 'dsh_rpc_connection_test.dart' show LoopbackDshHost;

void main() {
  test(
    'create sends only the real request cwd and trusts the returned identity',
    () async {
      final host = LoopbackDshHost();
      await host.start();
      final rpc = DshRpcConnection(webUrl: host.webUrl);
      final sessions = EngineSessionService(rpc);
      addTearDown(() async {
        await rpc.close();
        await host.close();
      });
      host.onCall = (r) async => {
        'sessionId': 'host-minted',
        'agentPreset': 'default',
      };
      expect(await sessions.create('/data/project'), 'host-minted');
      expect(host.requests.single.endpoint, 'session/create');
      expect((host.requests.single.envelope['payload'] as Map)['args'], {
        'request': {'cwd': '/data/project'},
      });
    },
  );
  test('invalid local cwd never contacts the engine; bad create response is an error', () async {
    final host = LoopbackDshHost();
    await host.start();
    final rpc = DshRpcConnection(webUrl: host.webUrl);
    final sessions = EngineSessionService(rpc);
    addTearDown(() async {
      await rpc.close();
      await host.close();
    });
    for (final cwd in [
      '',
      'relative/path',
      '~/project',
      '/project\u0000invalid',
    ]) {
      await expectLater(sessions.create(cwd), throwsArgumentError);
    }
    expect(host.exchanges, isEmpty);
    host.onCall = (r) async => {'id': 'invented'};
    await expectLater(sessions.create('/data/project'), throwsFormatException);
    host.onCall = (r) async => {'sessionId': ''};
    await expectLater(sessions.create('/data/project'), throwsFormatException);
  });
  test('catalog preserves real root identity and disables migration rows', () {
    final rows = EngineSession.fromResponse({
      'items': [
        {
          'sessionId': 'root',
          'updatedAt': 2,
          'running': true,
          'cwd': '/workspace',
        },
        {
          'sessionId': 'child',
          'updatedAt': 3,
          'origin': 'subagent',
          'parentSessionId': 'root',
        },
        {
          'sessionId': 'old',
          'updatedAt': 1,
          'formatStatus': 'migration-required',
        },
      ],
    });
    expect(rows.map((r) => r.id), ['root', 'old']);
    expect(rows.first.running, isTrue);
    expect(rows.first.cwd, '/workspace');
    expect(rows.first.openable, isTrue);
    expect(rows.last.openable, isFalse);
  });
  test('malformed catalog cannot be reported as an empty success', () {
    expect(
      () => EngineSession.fromResponse({
        'items': [
          {'id': 'invented'},
        ],
      }),
      throwsFormatException,
    );
    expect(() => EngineSession.fromResponse({}), throwsFormatException);
  });

  test('catalog reads the durable title projection and never exposes ids as names', () {
    final rows = EngineSession.fromResponse({
      'items': [
        {
          'sessionId': 'session-abc',
          'updatedAt': 3,
          'cwd': '/data/project',
          'blank': false,
          'projections': {
            'kind': 'sequenced',
            'asOfSeq': 12,
            'values': {'title': '  整理本周作业  '},
          },
        },
        {
          'sessionId': 'session-empty',
          'updatedAt': 2,
          'blank': true,
          'projections': {'values': {'title': null}},
        },
        {'sessionId': 'session-untitled', 'updatedAt': 1},
      ],
    });
    expect(rows.map((r) => r.displayName), ['整理本周作业', '新的对话', '未命名对话']);
    // 名字里不允许出现会话标识或工作目录。
    for (final row in rows) {
      expect(row.displayName.contains('session-'), isFalse);
      expect(row.displayName.contains('/data'), isFalse);
      expect(row.displayName.contains('project'), isFalse);
    }
  });
  test('a missing or malformed title projection is not invented', () {
    expect(EngineSession.readTitle(null), isNull);
    expect(EngineSession.readTitle({'values': {}}), isNull);
    expect(EngineSession.readTitle({'values': {'title': 7}}), isNull);
    expect(EngineSession.readTitle({'values': {'title': '   '}}), isNull);
    expect(EngineSession.readTitle({'title': 'flat'}), isNull);
  });
}