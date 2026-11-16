import 'dart:async';

import 'package:dsh_native_chat/src/bridge/engine_sessions_native.dart';
import 'package:dsh_native_chat/src/bridge/engine_sessions_stub.dart' as stub;
import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/model/engine_session.dart';
import 'package:dsh_native_chat/src/state/settings_controller.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/native_session_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/engine_session_picker.dart';
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
  @override
  Future<void> connect() async {}
  @override
  Future<Object?> call(String endpoint, Map<String, Object?> args) async {
    calls.add((endpoint: endpoint, args: args));
    switch (endpoint) {
      case 'session/list':
        return catalog == null ? {'items': []} : await catalog!;
      case 'session/create':
        if (failCreation)
          throw const DshRpcException(
            'test/create-failed',
            'directory unavailable',
          );
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
Future<void> _settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 350));
}

Future<void> _create(WidgetTester tester, String cwd) async {
  await tester.tap(find.byKey(const ValueKey('engine-new-session')));
  await _settle(tester);
  await tester.enterText(find.byKey(const ValueKey('engine-cwd')), cwd);
  await tester.tap(find.byKey(const ValueKey('engine-create-confirm')));
  await _settle(tester);
}

void main() {
  testWidgets(
    'empty catalog offers creation and retains the legacy open action',
    (tester) async {
      var created = 0;
      EngineSession? opened;
      await tester.pumpWidget(
        MaterialApp(
          home: EngineSessionPicker(
            load: () async => const [],
            onOpen: (row) async {
              opened = row;
            },
            onCreate: () async {
              created++;
            },
          ),
        ),
      );
      await _settle(tester);
      expect(find.text('暂时没有可打开的主会话'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('engine-new-session')));
      await _settle(tester);
      expect(created, 1);
      expect(opened, isNull);
    },
  );

  testWidgets(
    'unavailable native home never opens RPC and exposes recovery actions',
    (tester) async {
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
      expect(find.byKey(const ValueKey('engine-new-session')), findsNothing);
    },
  );

  testWidgets(
    'first creation requires an absolute engine cwd and opens a real interactive shell',
    (tester) async {
      final peers = <_Rpc>[];
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('first'),
            createRpc: (_) {
              final peer = _Rpc();
              peers.add(peer);
              return peer;
            },
          ),
        ),
      );
      await _settle(tester);
      await tester.tap(find.byKey(const ValueKey('engine-new-session')));
      await _settle(tester);
      expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('engine-cwd')))
            .controller!
            .text,
        isEmpty,
      );
      await tester.enterText(
        find.byKey(const ValueKey('engine-cwd')),
        'relative',
      );
      await tester.tap(find.byKey(const ValueKey('engine-create-confirm')));
      await _settle(tester);
      expect(find.textContaining('绝对路径'), findsWidgets);
      expect(
        peers.single.calls.where((c) => c.endpoint == 'session/create'),
        isEmpty,
      );
      await tester.enterText(
        find.byKey(const ValueKey('engine-cwd')),
        '/data/project',
      );
      await tester.tap(find.byKey(const ValueKey('engine-create-confirm')));
      await _settle(tester);
      expect(
        peers.first.calls.lastWhere((c) => c.endpoint == 'session/create').args,
        {
          'request': {'cwd': '/data/project'},
        },
      );
      final shell = tester.widget<ChatShell>(find.byType(ChatShell));
      final transport = shell.controller.transport as NativeSessionTransport;
      expect(transport.sessionId, 'parent');
      expect(transport.enableInteractions, isTrue);
      expect(shell.disposeController, isTrue);
      expect(shell.onOpenEngineSessions, isNotNull);
      expect(shell.onNewSession, isNotNull);
      // A hosted conversation reaches the session list through its own drawer,
      // never through a back arrow in the top bar.
      await tester.tap(find.byKey(const ValueKey('session-menu')));
      await _settle(tester);
      expect(find.byType(Drawer), findsOneWidget);
      expect(find.text('返回会话列表'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('engine-sessions')));
      await _settle(tester);
      expect(find.byType(EngineSessionPicker), findsOneWidget);
      expect(peers.last.closed, isTrue);
      await tester.pumpWidget(const SizedBox.shrink());
      await _settle(tester);
      expect(peers.every((p) => p.closed), isTrue);
    },
  );

  testWidgets(
    'rotation drops old dialog and late creation without opening a stale session',
    (tester) async {
      final old = _Rpc();
      final fresh = _Rpc();
      final pending = Completer<Object?>();
      old.creation = pending.future;
      DshRpcConnection factory(String url) => url.contains('old') ? old : fresh;
      Widget app(HostInfo host) => MaterialApp(
        home: EngineHome(host: host, createRpc: factory),
      );
      await tester.pumpWidget(app(_host('old')));
      await _settle(tester);
      await _create(tester, '/old/project');
      expect(
        old.calls.where((c) => c.endpoint == 'session/create'),
        hasLength(1),
      );
      await tester.pumpWidget(app(_host('new')));
      await _settle(tester);
      expect(old.closed, isTrue);
      pending.complete({'sessionId': 'stale'});
      await _settle(tester);
      expect(find.byType(ChatShell), findsNothing);
      expect(fresh.calls.where((c) => c.endpoint == 'session/create'), isEmpty);
      await tester.tap(find.byKey(const ValueKey('engine-new-session')));
      await _settle(tester);
      await tester.pumpWidget(app(_host('third', available: false)));
      await _settle(tester);
      expect(find.byKey(const ValueKey('engine-cwd')), findsNothing);
      expect(fresh.closed, isTrue);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('old catalog completion cannot replace the new host catalog', (
    tester,
  ) async {
    final pending = Completer<Object?>();
    final old = _Rpc()..catalog = pending.future;
    final fresh = _Rpc()
      ..catalog = Future.value({
        'items': [
          {'sessionId': 'fresh', 'updatedAt': 1, 'cwd': '/fresh'},
        ],
      });
    DshRpcConnection factory(String url) => url.contains('old') ? old : fresh;
    Widget app(String token) => MaterialApp(
      home: EngineHome(host: _host(token), createRpc: factory),
    );
    await tester.pumpWidget(app('old'));
    await tester.pump();
    await tester.pumpWidget(app('new'));
    await _settle(tester);
    pending.complete({
      'items': [
        {'sessionId': 'stale', 'updatedAt': 2},
      ],
    });
    await _settle(tester);
    expect(find.text('fresh'), findsOneWidget);
    expect(find.text('stale'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'home settings reuse the shared settings page before any session exists',
    (tester) async {
      final peer = _Rpc();
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
      expect(peer.calls.where((c) => c.endpoint == 'session/create'), isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
      settings.dispose();
    },
  );

  testWidgets(
    'existing session opens without create, chat can create again with its cwd',
    (tester) async {
      final catalog = _Rpc()
        ..catalog = Future.value({
          'items': [
            {
              'sessionId': 'existing',
              'updatedAt': 3,
              'cwd': '/existing/project',
            },
          ],
        });
      final peers = <_Rpc>[catalog];
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('chat'),
            createRpc: (_) {
              if (peers.length == 1 && catalog.calls.isEmpty) return catalog;
              final peer = _Rpc();
              peers.add(peer);
              return peer;
            },
          ),
        ),
      );
      await _settle(tester);
      await tester.tap(find.byKey(const ValueKey('engine-session-existing')));
      await _settle(tester);
      final shell = tester.widget<ChatShell>(find.byType(ChatShell));
      expect(
        (shell.controller.transport as NativeSessionTransport).sessionId,
        'existing',
      );
      expect(
        catalog.calls.where((c) => c.endpoint == 'session/create'),
        isEmpty,
      );
      shell.onNewSession!();
      await _settle(tester);
      expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('engine-cwd')))
            .controller!
            .text,
        '/existing/project',
      );
      await tester.tap(find.byKey(const ValueKey('engine-create-confirm')));
      await _settle(tester);
      expect(
        (tester.widget<ChatShell>(find.byType(ChatShell)).controller.transport
                as NativeSessionTransport)
            .sessionId,
        'parent',
      );
      expect(peers[1].closed, isTrue);
      await tester.pumpWidget(const SizedBox.shrink());
      await _settle(tester);
    },
  );

  testWidgets(
    'creation failure retains the directory and allows explicit retry',
    (tester) async {
      final catalog = _Rpc()..failCreation = true;
      var count = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('retry'),
            createRpc: (_) => count++ == 0 ? catalog : _Rpc(),
          ),
        ),
      );
      await _settle(tester);
      await _create(tester, '/project');
      expect(find.byType(ChatShell), findsNothing);
      expect(find.textContaining('directory unavailable'), findsOneWidget);
      expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('engine-cwd')))
            .controller!
            .text,
        '/project',
      );
      catalog.failCreation = false;
      await tester.tap(find.byKey(const ValueKey('engine-create-confirm')));
      await _settle(tester);
      expect(find.byType(ChatShell), findsOneWidget);
      expect(
        catalog.calls.where((c) => c.endpoint == 'session/create'),
        hasLength(2),
      );
      await tester.pumpWidget(const SizedBox.shrink());
      await _settle(tester);
    },
  );

  testWidgets('active conversation closes when the Host becomes unavailable', (
    tester,
  ) async {
    final peers = <_Rpc>[];
    DshRpcConnection factory(String url) {
      final peer = _Rpc();
      peers.add(peer);
      return peer;
    }

    Widget app(HostInfo host) => MaterialApp(
      home: EngineHome(host: host, createRpc: factory),
    );
    await tester.pumpWidget(app(_host('active')));
    await _settle(tester);
    await _create(tester, '/project');
    expect(find.byType(ChatShell), findsOneWidget);
    await tester.pumpWidget(app(_host('active', available: false)));
    await _settle(tester);
    expect(find.byType(ChatShell), findsNothing);
    expect(peers.every((p) => p.closed), isTrue);
    expect(find.textContaining('等待本机引擎'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'host status updates without opening a new connection for the same URL',
    (tester) async {
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
    },
  );

  testWidgets('stub home explicitly requires Android', (tester) async {
    await tester.pumpWidget(
      MaterialApp(home: stub.buildEngineHome(HostInfo.preview)),
    );
    expect(find.textContaining('Android'), findsWidgets);
    expect(find.byType(ChatShell), findsNothing);
  });
}
