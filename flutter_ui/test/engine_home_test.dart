import 'dart:async';

import 'package:dsh_native_chat/src/bridge/engine_sessions_native.dart';
import 'package:dsh_native_chat/src/bridge/engine_sessions_stub.dart' as stub;
import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/state/settings_controller.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/native_session_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/overflow_title.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_page.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_scope.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'dsh_session_projection_test.dart' show snapshot;
import 'settings_controller_test.dart' show FakeService;

class _Rpc extends DshRpcConnection {
  _Rpc() : super(webUrl: 'http://127.0.0.1/?token=test-only');
  final calls = <({String endpoint, Map<String, Object?> args})>[];
  final streams = <StreamController<Map<String, Object?>>>[];
  Future<Object?>? catalog;
  Future<Object?>? creation;
  bool closed = false;
  bool failCreation = false;
  bool failCatalog = false;

  @override
  Future<void> connect() async {}

  @override
  Future<Object?> call(String endpoint, Map<String, Object?> args) async {
    calls.add((endpoint: endpoint, args: args));
    switch (endpoint) {
      case 'session/list':
        if (failCatalog) {
          throw const DshRpcException('test/catalog-failed', 'catalog offline');
        }
        return catalog == null ? {'items': []} : await catalog!;
      case 'session/create':
        if (failCreation) {
          throw const DshRpcException(
            'test/create-failed',
            'directory unavailable',
          );
        }
        return creation == null ? {'sessionId': 'parent'} : await creation!;
      case 'session/state':
        return {
          'cursor': -1,
          'active': true,
          'running': false,
          'queue': [],
          'pendingInput': [],
        };
      case 'session/projections':
        return {
          'kind': 'sequenced',
          'asOfSeq': -1,
          'values': {'subagentCatalog': []},
        };
      default:
        return null;
    }
  }

  @override
  Stream<Map<String, Object?>> follow(
    String endpoint,
    Map<String, Object?> args,
  ) {
    final stream = StreamController<Map<String, Object?>>();
    streams.add(stream);
    if (endpoint == r'$events') {
      stream.add({
        'type': 'ready',
        'clientId': 'owner',
        'host': {'home': '/home'},
      });
    } else {
      final frame = snapshot(-1, []);
      final request = args['request'] as Map;
      (frame['header'] as Map)['id'] = (request['address'] as Map)['sessionId'];
      stream.add(frame);
    }
    return stream.stream;
  }

  @override
  Future<void> close() async {
    closed = true;
    for (final stream in streams) {
      unawaited(stream.close());
    }
  }
}

HostInfo _host(String token, {bool available = true}) => HostInfo(
  webUrl: 'http://127.0.0.1/?token=$token',
  state: available ? 'healthy' : 'starting',
  available: available,
);

Map<String, Object?> _row(
  String id, {
  String? title,
  num updatedAt = 1,
  bool openable = true,
  bool blank = false,
}) => {
  'sessionId': id,
  'updatedAt': updatedAt,
  'cwd': '/engine/project',
  'blank': blank,
  if (!openable) 'formatStatus': 'migration-required',
  if (title != null)
    'projections': {
      'kind': 'sequenced',
      'asOfSeq': 1,
      'values': {'title': title},
    },
};

Future<void> _settle(WidgetTester tester) async {
  await tester.pump();
  // Startup also waits (bounded) for the Session handoff, so settle past it.
  await tester.pump(const Duration(milliseconds: 600));
}

NativeSessionTransport _transport(WidgetTester tester) =>
    tester.widget<ChatShell>(find.byType(ChatShell)).controller.transport
        as NativeSessionTransport;

void main() {
  testWidgets(
    'an empty catalogue starts a blank Session with no directory form',
    (tester) async {
      final peer = _Rpc();
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(host: _host('blank'), createRpc: (_) => peer),
        ),
      );
      await _settle(tester);
      expect(find.byType(ChatShell), findsOneWidget);
      expect(_transport(tester).sessionId, 'parent');
      expect(_transport(tester).enableInteractions, isTrue);
      // The Host owns the working directory: nothing is invented here, so the
      // request carries no cwd and the UI never asks for a path.
      expect(
        peer.calls.lastWhere((c) => c.endpoint == 'session/create').args,
        {
          'request': <String, Object?>{},
        },
      );
      expect(find.byKey(const ValueKey('engine-cwd')), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
      await _settle(tester);
      expect(peer.closed, isTrue);
    },
  );

  testWidgets(
    'the catalogue opens its newest Session directly, without a picker',
    (tester) async {
      final peer = _Rpc()
        ..catalog = Future.value({
          'items': [
            _row('older', title: '旧对话', updatedAt: 1),
            _row('newer', title: '新对话', updatedAt: 9),
          ],
        });
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(host: _host('direct'), createRpc: (_) => peer),
        ),
      );
      await _settle(tester);
      expect(find.byType(ChatShell), findsOneWidget);
      expect(_transport(tester).sessionId, 'newer');
      expect(
        peer.calls.where((c) => c.endpoint == 'session/create'),
        isEmpty,
      );
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('the drawer lists Sessions by title only and switches', (
    tester,
  ) async {
    final catalog = _Rpc()
      ..catalog = Future.value({
        'items': [
          _row('first', title: '整理一下周报', updatedAt: 2),
          _row('second', title: '排查构建失败', updatedAt: 1),
        ],
      });
    final peers = <_Rpc>[catalog];
    await tester.pumpWidget(
      MaterialApp(
        home: EngineHome(
          host: _host('drawer'),
          createRpc: (_) {
            // The home's own catalogue peer is the one prepared above; every
            // conversation gets a fresh peer.
            if (peers.length == 1) return catalog;
            final peer = _Rpc();
            peers.add(peer);
            return peer;
          },
        ),
      ),
    );
    await _settle(tester);
    expect(_transport(tester).sessionId, 'first');
    await tester.tap(find.byKey(const ValueKey('session-menu')));
    await _settle(tester);
    expect(find.byType(Drawer), findsOneWidget);
    // Human names only: rows render a title, never a Session identity or cwd.
    final labels = tester
        .widgetList<OverflowTitle>(find.byType(OverflowTitle))
        .map((title) => title.text)
        .toList();
    expect(labels, containsAll(['整理一下周报', '排查构建失败']));
    expect(
      labels.any(
        (label) => label.contains('session') || label.contains('/engine/project'),
      ),
      isFalse,
    );
    expect(find.textContaining('/engine/project'), findsNothing);
    expect(find.textContaining('session-'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('session-row-second')));
    await _settle(tester);
    expect(_transport(tester).sessionId, 'second');
    expect(peers.first.closed, isTrue);
    await tester.pumpWidget(const SizedBox.shrink());
    await _settle(tester);
    expect(peers.every((p) => p.closed), isTrue);
  });

  testWidgets(
    'sessions that require migration are skipped, never opened blind',
    (tester) async {
      final peer = _Rpc()
        ..catalog = Future.value({
          'items': [_row('locked', title: '待迁移', openable: false)],
        });
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(host: _host('locked'), createRpc: (_) => peer),
        ),
      );
      await _settle(tester);
      expect(_transport(tester).sessionId, 'parent');
      expect(find.byKey(const ValueKey('engine-cwd')), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('an unreadable catalogue offers retry and invents no Session', (
    tester,
  ) async {
    final peer = _Rpc()..failCatalog = true;
    await tester.pumpWidget(
      MaterialApp(
        home: EngineHome(host: _host('broken'), createRpc: (_) => peer),
      ),
    );
    await _settle(tester);
    expect(find.byType(ChatShell), findsNothing);
    expect(find.textContaining('会话列表读取失败'), findsOneWidget);
    expect(
      peer.calls.where((c) => c.endpoint == 'session/create'),
      isEmpty,
    );
    peer.failCatalog = false;
    await tester.tap(find.byKey(const ValueKey('engine-bootstrap-retry')));
    await _settle(tester);
    expect(find.byType(ChatShell), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'a blank Session that cannot be created keeps the failure visible',
    (tester) async {
      final peer = _Rpc()..failCreation = true;
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(host: _host('nofail'), createRpc: (_) => peer),
        ),
      );
      await _settle(tester);
      expect(find.byType(ChatShell), findsNothing);
      expect(find.textContaining('新建会话失败'), findsOneWidget);
      expect(find.byKey(const ValueKey('engine-bootstrap-retry')), findsOne);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('host status updates without opening a new connection', (
    tester,
  ) async {
    var count = 0;
    Widget app(String state) => MaterialApp(
      home: EngineHome(
        host: HostInfo(webUrl: '', state: state, available: false),
        createRpc: (_) {
          count++;
          return _Rpc();
        },
      ),
    );
    await tester.pumpWidget(app('starting'));
    await _settle(tester);
    await tester.pumpWidget(app('failed'));
    await _settle(tester);
    expect(find.text('引擎状态：failed'), findsOneWidget);
    expect(count, 0);
  });

  testWidgets('unavailable native home never opens RPC', (tester) async {
    var opened = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: EngineHome(
          host: _host('unused', available: false),
          createRpc: (_) {
            opened++;
            return _Rpc();
          },
        ),
      ),
    );
    await _settle(tester);
    expect(opened, 0);
    expect(find.textContaining('等待本机引擎'), findsOneWidget);
    expect(find.byKey(const ValueKey('engine-settings')), findsOneWidget);
    expect(find.byKey(const ValueKey('engine-web')), findsOneWidget);
  });

  testWidgets('rotation drops a late catalogue and closes the old peer', (
    tester,
  ) async {
    final pending = Completer<Object?>();
    final old = _Rpc()..catalog = pending.future;
    final fresh = _Rpc()
      ..catalog = Future.value({
        'items': [_row('fresh', title: '新的对话')],
      });
    DshRpcConnection factory(String url) =>
        url.contains('old') ? old : fresh;
    Widget app(HostInfo host) =>
        MaterialApp(home: EngineHome(host: host, createRpc: factory));
    await tester.pumpWidget(app(_host('old')));
    await _settle(tester);
    await tester.pumpWidget(app(_host('new')));
    await _settle(tester);
    pending.complete({
      'items': [_row('stale', title: '过期对话')],
    });
    await _settle(tester);
    expect(old.closed, isTrue);
    expect(_transport(tester).sessionId, 'fresh');
    await tester.pumpWidget(app(_host('third', available: false)));
    await _settle(tester);
    expect(fresh.closed, isTrue);
    expect(find.byType(ChatShell), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('home settings reuse the shared settings page', (tester) async {
    final peer = _Rpc()..failCatalog = true;
    final settings = SettingsController(FakeService());
    await tester.pumpWidget(
      MaterialApp(
        home: SettingsScope(
          controller: settings,
          child: EngineHome(host: _host('settings'), createRpc: (_) => peer),
        ),
      ),
    );
    await _settle(tester);
    await tester.tap(find.byKey(const ValueKey('engine-settings')));
    await _settle(tester);
    expect(find.byType(SettingsPage), findsOneWidget);
    expect(
      peer.calls.where((c) => c.endpoint == 'session/create'),
      isEmpty,
    );
    await tester.pumpWidget(const SizedBox.shrink());
    settings.dispose();
  });

  testWidgets('stub home explicitly requires Android', (tester) async {
    await tester.pumpWidget(
      MaterialApp(home: stub.buildEngineHome(HostInfo.preview)),
    );
    expect(find.textContaining('Android'), findsWidgets);
    expect(find.byType(ChatShell), findsNothing);
  });
}