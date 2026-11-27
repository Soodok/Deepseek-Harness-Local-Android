import 'dart:async';

import 'package:dsh_native_chat/src/bridge/engine_sessions_native.dart';
import 'package:dsh_native_chat/src/bridge/engine_sessions_stub.dart' as stub;
import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/state/settings_controller.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/native_session_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/native_workspace_picker.dart';
import 'package:dsh_native_chat/src/ui/overflow_title.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_page.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_scope.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'dsh_session_projection_test.dart' show record, snapshot;
import 'settings_controller_test.dart' show FakeService;

class _Rpc extends DshRpcConnection {
  _Rpc() : super(webUrl: 'http://127.0.0.1/?token=test-only');
  final calls = <({String endpoint, Map<String, Object?> args})>[];
  final streams = <StreamController<Map<String, Object?>>>[];
  Future<Object?>? catalog;
  Future<Object?>? creation;
  List<Map<String, Object?>>? workspaceItems;
  StreamController<Map<String, Object?>>? workspaceStream;
  StreamController<Map<String, Object?>>? sessionStream;
  List<Map<String, Object?>> presets = const [];
  Future<String>? selectedPreset;
  bool failSelectionLocked = false;
  bool closed = false;
  bool failCreation = false;
  bool failCatalog = false;
  bool holdWorkspaceBaseline = false;

  void emitWorkspaceBaseline() => workspaceStream!.add({
    'type': 'baseline',
    'value': {
      'items': workspaceItems ?? [],
      'archivedSessionIds': [],
      'pinnedSessionIds': [],
    },
  });

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
      case 'turnRewind/list':
        return {
          'ok': true,
          'value': {'turns': []},
        };
      case 'agentPresets/list':
        return {'presets': presets};
      case 'agentPresets/select':
        if (failSelectionLocked) {
          throw const DshRpcException('agent-preset/locked', 'already started');
        }
        return selectedPreset == null
            ? args['agentPreset']
            : await selectedPreset!;
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
    if (endpoint == 'workspace/follow') {
      calls.add((endpoint: endpoint, args: args));
      workspaceStream = stream;
      if (!holdWorkspaceBaseline) emitWorkspaceBaseline();
    } else if (endpoint == r'$events') {
      stream.add({
        'type': 'ready',
        'clientId': 'owner',
        'host': {'home': '/home'},
      });
    } else {
      sessionStream = stream;
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

HostInfo _host(
  String token, {
  bool available = true,
  String domain = 'local-native-domain',
  String generation = 'boot-1',
}) => HostInfo(
  webUrl: 'http://127.0.0.1/?token=$token',
  state: available ? 'healthy' : 'starting',
  available: available,
  workspaceDomain: domain,
  workspaceGeneration: generation,
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

Map<String, Object?> _workspace(String id, String title, List<String> rows) => {
  'workspaceId': id,
  'path': '/engine/' + id,
  'title': title,
  'sessionIds': rows,
  'createdAt': '2026-11-22T00:00:00Z',
  'updatedAt': '2026-11-22T00:00:00Z',
};

void _workspacePhone(WidgetTester tester) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(393, 851);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

class _WorkspacePrefs {
  final disk = <String, Object?>{};
  final writes = <Map<Object?, Object?>>[];
  final clears = <Map<Object?, Object?>>[];
  final reads = <Map<Object?, Object?>>[];
  bool failReads = false, failWrites = false, mismatchReadback = false;
  Future<Object?>? pendingRead;
  Object? forcedReceipt;
  Future<Object?> handle(MethodCall call) async {
    if (call.method != 'readDefaultWorkspace' &&
        call.method != 'clearDefaultWorkspace' &&
        call.method != 'writeDefaultWorkspace')
      return null;
    final args = call.arguments as Map;
    final domain = args['domain'] as String;
    if (call.method == 'writeDefaultWorkspace') {
      writes.add(Map<Object?, Object?>.from(args));
      if (failWrites) return false;
      if (!mismatchReadback) disk[domain] = args['target'];
      return true;
    }
    if (call.method == 'clearDefaultWorkspace') {
      clears.add(Map<Object?, Object?>.from(args));
      if (failWrites) return false;
      if (!mismatchReadback) disk.remove(domain);
      return true;
    }
    reads.add(Map<Object?, Object?>.from(args));
    if (failReads) throw PlatformException(code: 'test/read-failed');
    if (pendingRead != null) return await pendingRead!;
    return forcedReceipt ??
        {
          'domain': domain,
          'generation': args['generation'],
          'target': disk[domain],
        };
  }
}

List<Map<String, Object?>> _workspaceRequests(_Rpc rpc) => rpc.calls
    .where((c) => c.endpoint == 'session/create')
    .map((c) => c.args)
    .toList();
const _homeRequest = {'request': <String, Object?>{}};
const _alphaRequest = {
  'request': {'workspaceId': 'alpha'},
};
const _bravoRequest = {
  'request': {'workspaceId': 'bravo'},
};
Future<void> _chooseWorkspace(WidgetTester tester, String label) async {
  final capsule = find.byKey(const ValueKey('welcome-workspace-capsule'));
  await tester.tap(capsule);
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 350));
  await tester.tap(find.text(label));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 350));
  await _settle(tester);
}

Future<void> _ordinaryWorkspaceCreate(WidgetTester tester) async {
  if (find.text('新建会话').hitTestable().evaluate().isEmpty) {
    await tester.tap(find.byKey(const ValueKey('session-menu')));
    await _settle(tester);
  }
  await tester.tap(find.text('新建会话'));
  await _settle(tester);
}

Future<void> _groupWorkspaceCreate(WidgetTester tester, String id) async {
  await tester.tap(find.byKey(const ValueKey('session-menu')));
  await _settle(tester);
  final button = find.byKey(ValueKey('new-session-workspace-' + id));
  await tester.ensureVisible(button);
  await tester.tap(button);
  await _settle(tester);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const nativeChannel = MethodChannel('app.dsh.mobile/native_chat');
  final nativeMessenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  setUp(() {
    nativeMessenger.setMockMethodCallHandler(
      nativeChannel,
      (call) async => call.method == 'readDefaultWorkspace'
          ? {
              'domain': 'local-native-domain',
              'generation': 'boot-1',
              'target': null,
            }
          : null,
    );
  });
  tearDown(() {
    nativeMessenger.setMockMethodCallHandler(nativeChannel, null);
  });

  testWidgets(
    'workspace feed orders groups and rows and creates in that group',
    (tester) async {
      final catalog = _Rpc()
        ..workspaceItems = [
          _workspace('second', 'Design', ['old', 'new']),
          _workspace('first', 'Dev', ['dev']),
        ]
        ..catalog = Future.value({
          'items': [
            _row('new', title: 'Newest', updatedAt: 30),
            _row('dev', title: 'Build', updatedAt: 20),
            _row('old', title: 'Older', updatedAt: 10),
          ],
        });
      var opens = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('workspaces'),
            createRpc: (_) => opens++ == 0 ? catalog : _Rpc(),
          ),
        ),
      );
      await _settle(tester);
      expect(
        catalog.calls.singleWhere((c) => c.endpoint == 'workspace/follow').args,
        isEmpty,
      );
      await tester.tap(find.byKey(const ValueKey('session-menu')));
      await _settle(tester);
      double top(Key key) => tester.getTopLeft(find.byKey(key)).dy;
      expect(
        top(const ValueKey('session-row-old')),
        lessThan(top(const ValueKey('session-row-new'))),
      );
      expect(
        tester.getTopLeft(find.text('Design')).dy,
        lessThan(tester.getTopLeft(find.text('Dev')).dy),
      );
      catalog.workspaceStream!.add({
        'type': 'order',
        'workspaceIds': ['first', 'second'],
      });
      await _settle(tester);
      expect(
        tester.getTopLeft(find.text('Dev')).dy,
        lessThan(tester.getTopLeft(find.text('Design')).dy),
      );
      await tester.tap(
        find.byKey(const ValueKey('new-session-workspace-first')),
      );
      await _settle(tester);
      expect(
        catalog.calls.singleWhere((c) => c.endpoint == 'session/create').args,
        {
          'request': {'workspaceId': 'first'},
        },
      );
      expect(_transport(tester).sessionId, 'parent');
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'explicit retry reconnects failed catalogue without waiting for backoff',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs();
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..catalog = Future.value({
          'items': [_row('existing', blank: true)],
        });
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('retry-catalogue'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      catalog.workspaceStream!.addError(
        StateError('catalogue temporarily offline'),
      );
      await tester.pump();
      expect(
        catalog.calls.where((c) => c.endpoint == 'workspace/follow'),
        hasLength(1),
      );
      tester
          .widget<NativeWorkspacePicker>(find.byType(NativeWorkspacePicker))
          .onRetry!();
      await tester.pump();
      expect(
        catalog.calls.where((c) => c.endpoint == 'workspace/follow'),
        hasLength(2),
      );
      expect(_workspaceRequests(catalog), isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'pending preference never locks empty bootstrap explicit Home recovery',
    (tester) async {
      _workspacePhone(tester);
      final wait = Completer<Object?>();
      final prefs = _WorkspacePrefs()..pendingRead = wait.future;
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..creation = Future.value({'sessionId': 'pending-explicit-home'});
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('pending-preference'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      expect(_workspaceRequests(catalog), isEmpty);
      expect(
        find.byKey(const ValueKey('workspace-once-home')),
        findsOneWidget,
        reason: 'Unknown preferences must leave an explicit Home escape before timeout',
      );
      await tester.tap(find.byKey(const ValueKey('workspace-once-home')));
      await _settle(tester);
      expect(_workspaceRequests(catalog), [_homeRequest]);
      expect(_transport(tester).sessionId, 'pending-explicit-home');
      wait.complete({
        'domain': 'local-native-domain',
        'generation': 'boot-1',
        'target': {'workspaceId': 'alpha', 'path': '/engine/alpha'},
      });
      await _settle(tester);
      expect(_workspaceRequests(catalog), [_homeRequest]);
      expect(_transport(tester).sessionId, 'pending-explicit-home');
      catalog.creation = Future.value({'sessionId': 'after-pending-default'});
      await _ordinaryWorkspaceCreate(tester);
      expect(_workspaceRequests(catalog), [_homeRequest, _alphaRequest]);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('unknown preference cannot implicitly save displayed Home', (
    tester,
  ) async {
    _workspacePhone(tester);
    final prefs = _WorkspacePrefs()..failReads = true;
    nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
    final catalog = _Rpc()
      ..catalog = Future.value({
        'items': [_row('existing', blank: true)],
      });
    await tester.pumpWidget(
      MaterialApp(
        home: EngineHome(
          host: _host('unknown-save'),
          createRpc: (_) => catalog,
        ),
      ),
    );
    await _settle(tester);
    final save = tester
        .widget<NativeWorkspacePicker>(find.byType(NativeWorkspacePicker))
        .onSaveDefault;
    await _chooseWorkspace(tester, '设为默认');
    if (save != null) await save();
    await _settle(tester);
    expect(
      prefs.writes,
      isEmpty,
      reason: 'Unknown receipt plus display Home is not explicit selection',
    );
    expect(prefs.clears, isEmpty);
    expect(_workspaceRequests(catalog), isEmpty);
    expect(_transport(tester).sessionId, 'existing');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'explicit Home after failed read can save exactly one Home target',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs()..failReads = true;
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..catalog = Future.value({
          'items': [_row('existing', blank: true)],
        });
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('explicit-home-save'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      await _chooseWorkspace(tester, 'Home');
      expect(prefs.writes, isEmpty);
      prefs.failReads = false;
      await _chooseWorkspace(tester, '设为默认');
      expect(prefs.writes, [
        {
          'domain': 'local-native-domain',
          'generation': 'boot-1',
          'target': {'home': true},
        },
      ]);
      expect(prefs.clears, isEmpty);
      expect(prefs.disk['local-native-domain'], {'home': true});
      expect(_workspaceRequests(catalog), isEmpty);
      expect(_transport(tester).sessionId, 'existing');
      expect(find.text('默认工作区已保存并回读确认'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'normal clear removes only current domain and preserves one-time target and session',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs()
        ..disk['local-native-domain'] = {
          'workspaceId': 'alpha',
          'path': '/engine/alpha',
        }
        ..disk['other-domain'] = {'home': true};
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..workspaceItems = [
          _workspace('alpha', 'Alpha', []),
          _workspace('bravo', 'Bravo', []),
        ]
        ..catalog = Future.value({
          'items': [_row('existing', blank: true)],
        });
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('clear-default'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      final before = _transport(tester);
      await _chooseWorkspace(tester, 'Bravo');
      final readsBefore = prefs.reads.length;
      await tester.tap(find.byKey(const ValueKey('welcome-workspace-capsule')));
      await _settle(tester);
      expect(find.text('清除默认'), findsOneWidget);
      await tester.tap(find.text('清除默认'));
      await _settle(tester);
      expect(prefs.clears, [
        {'domain': 'local-native-domain', 'generation': 'boot-1'},
      ]);
      expect(prefs.writes, isEmpty);
      expect(prefs.reads.length, readsBefore + 1);
      expect(prefs.disk, {
        'other-domain': {'home': true},
      });
      expect(find.text('默认工作区未设置，已回读确认'), findsOneWidget);
      expect(
        tester
            .widget<NativeWorkspacePicker>(find.byType(NativeWorkspacePicker))
            .selected,
        'bravo',
      );
      expect(identical(_transport(tester), before), isTrue);
      expect(_transport(tester).sessionId, 'existing');
      expect(_workspaceRequests(catalog), isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  for (final failure in [
    'commit',
    'unchanged',
    'Home',
    'old-generation',
    'other-domain',
    'read-error',
    'missing-target',
  ]) {
    testWidgets('clear rejects $failure without claiming unset', (
      tester,
    ) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs()
        ..disk['local-native-domain'] = {
          'workspaceId': 'alpha',
          'path': '/engine/alpha',
        }
        ..disk['other-domain'] = {'home': true};
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..catalog = Future.value({
          'items': [_row('existing', blank: true)],
        });
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('clear-failure'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      prefs.failWrites = failure == 'commit';
      prefs.mismatchReadback = failure == 'unchanged';
      prefs.failReads = failure == 'read-error';
      if ([
        'Home',
        'old-generation',
        'other-domain',
        'missing-target',
      ].contains(failure)) {
        prefs.forcedReceipt = {
          'domain': failure == 'other-domain'
              ? 'other-domain'
              : 'local-native-domain',
          'generation': failure == 'old-generation' ? 'old-boot' : 'boot-1',
          if (failure != 'missing-target')
            'target': failure == 'Home' ? {'home': true} : null,
        };
      }
      await tester.tap(find.byKey(const ValueKey('welcome-workspace-capsule')));
      await _settle(tester);
      expect(find.text('清除默认'), findsOneWidget);
      await tester.tap(find.text('清除默认'));
      await _settle(tester);
      expect(prefs.clears, [
        {'domain': 'local-native-domain', 'generation': 'boot-1'},
      ]);
      expect(prefs.writes, isEmpty);
      expect(prefs.disk['other-domain'], {'home': true});
      expect(find.text('默认工作区未设置，已回读确认'), findsNothing);
      expect(find.text('默认工作区已保存并回读确认'), findsNothing);
      expect(_transport(tester).sessionId, 'existing');
      expect(_workspaceRequests(catalog), isEmpty);
      await _ordinaryWorkspaceCreate(tester);
      expect(
        _workspaceRequests(catalog),
        isEmpty,
        reason: 'Unconfirmed clear cannot turn unknown into Home',
      );
      await tester.pumpWidget(const SizedBox.shrink());
    });
  }

  testWidgets(
    'empty list clear stays recoverable and never auto creates a session',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs()
        ..disk['local-native-domain'] = {
          'workspaceId': 'alpha',
          'path': '/old/alpha',
        };
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..creation = Future.value({'sessionId': 'explicit-recovery'});
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('empty-clear'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      expect(_workspaceRequests(catalog), isEmpty);
      await _chooseWorkspace(tester, '清除默认');
      expect(prefs.clears, [
        {'domain': 'local-native-domain', 'generation': 'boot-1'},
      ]);
      expect(prefs.writes, isEmpty);
      expect(prefs.disk.containsKey('local-native-domain'), isFalse);
      expect(_workspaceRequests(catalog), isEmpty);
      expect(
        find.byKey(const ValueKey('engine-bootstrap-retry')),
        findsOneWidget,
      );
      expect(find.byKey(const ValueKey('workspace-once-home')), findsOneWidget);
      expect(
        tester
            .widget<NativeWorkspacePicker>(find.byType(NativeWorkspacePicker))
            .onRetry,
        isNotNull,
      );
      await tester.tap(find.byKey(const ValueKey('workspace-once-home')));
      await _settle(tester);
      expect(_workspaceRequests(catalog), [_homeRequest]);
      expect(_transport(tester).sessionId, 'explicit-recovery');
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'late clear readback from replaced Host cannot claim unset or replace new default',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs()
        ..disk['local-native-domain'] = {
          'workspaceId': 'alpha',
          'path': '/engine/alpha',
        };
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final old = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..catalog = Future.value({
          'items': [_row('existing', blank: true)],
        });
      final next = _Rpc()
        ..workspaceItems = [_workspace('bravo', 'Bravo', [])]
        ..catalog = Future.value({
          'items': [_row('new-host-session', blank: true)],
        });
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('old-clear-host'),
            createRpc: (_) => old,
          ),
        ),
      );
      await _settle(tester);
      final wait = Completer<Object?>();
      prefs.pendingRead = wait.future;
      await _chooseWorkspace(tester, '清除默认');
      expect(prefs.clears.length, 1);
      expect(find.text('默认工作区未设置，已回读确认'), findsNothing);
      prefs.pendingRead = null;
      prefs.disk['local-native-domain'] = {
        'workspaceId': 'bravo',
        'path': '/engine/bravo',
      };
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('new-clear-host', generation: 'boot-2'),
            createRpc: (_) => next,
          ),
        ),
      );
      await _settle(tester);
      wait.complete({
        'domain': 'local-native-domain',
        'generation': 'boot-1',
        'target': null,
      });
      await _settle(tester);
      expect(find.text('默认工作区未设置，已回读确认'), findsNothing);
      expect(
        tester
            .widget<NativeWorkspacePicker>(find.byType(NativeWorkspacePicker))
            .selected,
        'bravo',
      );
      expect(_transport(tester).sessionId, 'new-host-session');
      expect(_workspaceRequests(old), isEmpty);
      expect(_workspaceRequests(next), isEmpty);
      expect(prefs.writes, isEmpty);
      expect(prefs.clears, [
        {'domain': 'local-native-domain', 'generation': 'boot-1'},
      ]);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'native default saves with readback and cold bootstrap retains it',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs();
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..catalog = Future.value({
          'items': [_row('existing', blank: true)],
        });
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('save-default'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      await _chooseWorkspace(tester, 'Alpha');
      expect(_workspaceRequests(catalog), isEmpty);
      expect(_transport(tester).sessionId, 'existing');
      await _chooseWorkspace(tester, '设为默认');
      expect(prefs.writes, [
        {
          'domain': 'local-native-domain',
          'generation': 'boot-1',
          'target': {'workspaceId': 'alpha', 'path': '/engine/alpha'},
        },
      ]);
      expect(find.text('默认工作区已保存并回读确认'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
      final cold = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..creation = Future.value({'sessionId': 'cold-default'});
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('cold-token', generation: 'boot-2'),
            createRpc: (_) => cold,
          ),
        ),
      );
      await _settle(tester);
      expect(_workspaceRequests(cold), [_alphaRequest]);
      expect(_transport(tester).sessionId, 'cold-default');
      cold.creation = Future.value({'sessionId': 'cold-second'});
      await _ordinaryWorkspaceCreate(tester);
      expect(_workspaceRequests(cold), [_alphaRequest, _alphaRequest]);
      expect(_transport(tester).sessionId, 'cold-second');
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'one-time Home failure retains override while explicit groups never consume it',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs()
        ..disk['local-native-domain'] = {
          'workspaceId': 'alpha',
          'path': '/engine/alpha',
        };
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..workspaceItems = [
          _workspace('alpha', 'Alpha', []),
          _workspace('bravo', 'Bravo', []),
        ]
        ..catalog = Future.value({
          'items': [_row('existing', blank: true)],
        });
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('home-override'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      await _chooseWorkspace(tester, 'Home');
      catalog.creation = Future.value({'sessionId': 'group-bravo'});
      await _groupWorkspaceCreate(tester, 'bravo');
      expect(_transport(tester).sessionId, 'group-bravo');
      catalog.failCreation = true;
      await _ordinaryWorkspaceCreate(tester);
      expect(_workspaceRequests(catalog), [_bravoRequest, _homeRequest]);
      expect(_transport(tester).sessionId, 'group-bravo');
      catalog.failCreation = false;
      catalog.creation = Future.value({'sessionId': 'once-home'});
      await _ordinaryWorkspaceCreate(tester);
      expect(_transport(tester).sessionId, 'once-home');
      catalog.creation = Future.value({'sessionId': 'back-to-default'});
      await _ordinaryWorkspaceCreate(tester);
      expect(_workspaceRequests(catalog), [
        _bravoRequest,
        _homeRequest,
        _homeRequest,
        _alphaRequest,
      ]);
      expect(prefs.disk['local-native-domain'], {
        'workspaceId': 'alpha',
        'path': '/engine/alpha',
      });
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'explicit drawer Home exceeds workspace override without consuming it',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs()
        ..disk['local-native-domain'] = {
          'workspaceId': 'bravo',
          'path': '/engine/bravo',
        };
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..workspaceItems = [
          _workspace('alpha', 'Alpha', []),
          _workspace('bravo', 'Bravo', []),
        ]
        ..catalog = Future.value({
          'items': [_row('existing', blank: true)],
        });
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('drawer-home'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      await _chooseWorkspace(tester, 'Alpha');
      catalog.creation = Future.value({'sessionId': 'explicit-home'});
      await _groupWorkspaceCreate(tester, 'ungrouped');
      expect(_workspaceRequests(catalog), [_homeRequest]);
      catalog.creation = Future.value({'sessionId': 'retained-alpha'});
      await _ordinaryWorkspaceCreate(tester);
      catalog.creation = Future.value({'sessionId': 'default-bravo'});
      await _ordinaryWorkspaceCreate(tester);
      expect(_workspaceRequests(catalog), [
        _homeRequest,
        _alphaRequest,
        _bravoRequest,
      ]);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'unknown preference empty bootstrap exposes retry reselect and explicit Home',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs()..failReads = true;
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..creation = Future.value({'sessionId': 'recovered-home'});
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('unknown-prefs'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      expect(_workspaceRequests(catalog), isEmpty);
      expect(
        find.byKey(const ValueKey('engine-bootstrap-retry')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('welcome-workspace-capsule')),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const ValueKey('workspace-once-home')));
      await _settle(tester);
      expect(_workspaceRequests(catalog), [_homeRequest]);
      expect(_transport(tester).sessionId, 'recovered-home');
      // An explicit Home recovery does not turn the failed preference into unset.
      await _ordinaryWorkspaceCreate(tester);
      expect(_workspaceRequests(catalog), [_homeRequest]);
      prefs.failReads = false;
      await tester.tap(find.byTooltip('关闭会话抽屉'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 350));
      await _chooseWorkspace(tester, '重试读取工作区与默认');
      catalog.creation = Future.value({'sessionId': 'retried-home'});
      await _ordinaryWorkspaceCreate(tester);
      expect(_workspaceRequests(catalog), [_homeRequest, _homeRequest]);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'pending catalogue never emits empty bootstrap and resolves when baseline arrives',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs()
        ..disk['local-native-domain'] = {
          'workspaceId': 'alpha',
          'path': '/engine/alpha',
        };
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..holdWorkspaceBaseline = true
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..creation = Future.value({'sessionId': 'after-baseline'});
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('pending-catalogue'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      expect(_workspaceRequests(catalog), isEmpty);
      expect(find.byKey(const ValueKey('workspace-once-home')), findsOneWidget);
      catalog.emitWorkspaceBaseline();
      await _settle(tester);
      expect(_workspaceRequests(catalog), [_alphaRequest]);
      expect(_transport(tester).sessionId, 'after-baseline');
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'removed or repathed saved target requires reselect without fallback request',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs()
        ..disk['local-native-domain'] = {
          'workspaceId': 'alpha',
          'path': '/old-alpha',
        };
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final catalog = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..creation = Future.value({'sessionId': 'reselected-alpha'});
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(host: _host('repathed'), createRpc: (_) => catalog),
        ),
      );
      await _settle(tester);
      expect(_workspaceRequests(catalog), isEmpty);
      await _chooseWorkspace(tester, 'Alpha');
      await _settle(tester);
      expect(_workspaceRequests(catalog), [_alphaRequest]);
      expect(_transport(tester).sessionId, 'reselected-alpha');
      // Save is unchanged until explicitly requested; next implicit create blocks again.
      await _ordinaryWorkspaceCreate(tester);
      expect(_workspaceRequests(catalog), [_alphaRequest]);
      catalog.workspaceStream!.add({'type': 'remove', 'workspaceId': 'alpha'});
      await _settle(tester);
      await _ordinaryWorkspaceCreate(tester);
      expect(_workspaceRequests(catalog), [_alphaRequest]);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('failed save and mismatched readback never display saved', (
    tester,
  ) async {
    _workspacePhone(tester);
    final prefs = _WorkspacePrefs()..failWrites = true;
    nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
    final catalog = _Rpc()
      ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
      ..catalog = Future.value({
        'items': [_row('existing', blank: true)],
      });
    await tester.pumpWidget(
      MaterialApp(
        home: EngineHome(host: _host('failed-save'), createRpc: (_) => catalog),
      ),
    );
    await _settle(tester);
    await _chooseWorkspace(tester, 'Alpha');
    await _chooseWorkspace(tester, '设为默认');
    expect(find.text('默认工作区已保存并回读确认'), findsNothing);
    expect(prefs.disk, isEmpty);
    prefs.failWrites = false;
    prefs.mismatchReadback = true;
    await _chooseWorkspace(tester, '设为默认');
    expect(find.text('默认工作区已保存并回读确认'), findsNothing);
    expect(prefs.disk, isEmpty);
    expect(prefs.writes, hasLength(2));
    expect(_workspaceRequests(catalog), isEmpty);
    expect(_transport(tester).sessionId, 'existing');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'same workspace ID never crosses data domains and old generation receipt is ignored',
    (tester) async {
      _workspacePhone(tester);
      final prefs = _WorkspacePrefs()
        ..disk['domain-a'] = {'workspaceId': 'alpha', 'path': '/engine/alpha'};
      final late = Completer<Object?>();
      prefs.pendingRead = late.future;
      nativeMessenger.setMockMethodCallHandler(nativeChannel, prefs.handle);
      final old = _Rpc()..workspaceItems = [_workspace('alpha', 'Alpha', [])];
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('old-host', domain: 'domain-a'),
            createRpc: (_) => old,
          ),
        ),
      );
      await tester.pump();
      prefs.pendingRead = null;
      prefs.disk['domain-b'] = {'workspaceId': 'alpha', 'path': '/other-alpha'};
      final current = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])];
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('new-host', domain: 'domain-b', generation: 'boot-2'),
            createRpc: (_) => current,
          ),
        ),
      );
      await _settle(tester);
      late.complete({
        'domain': 'domain-a',
        'generation': 'boot-1',
        'target': {'workspaceId': 'alpha', 'path': '/engine/alpha'},
      });
      await _settle(tester);
      expect(_workspaceRequests(old), isEmpty);
      expect(_workspaceRequests(current), isEmpty);
      expect(find.byKey(const ValueKey('workspace-once-home')), findsOneWidget);
      current.creation = Future.value({'sessionId': 'explicit-new-domain'});
      await _chooseWorkspace(tester, 'Alpha');
      await _settle(tester);
      expect(_workspaceRequests(current), [_alphaRequest]);
      expect(_transport(tester).sessionId, 'explicit-new-domain');
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('persistent default drives two distinct ordinary creations', (
    tester,
  ) async {
    nativeMessenger.setMockMethodCallHandler(nativeChannel, (call) async {
      if (call.method == 'readDefaultWorkspace') {
        return {
          'domain': 'local-native-domain',
          'generation': 'boot-1',
          'target': {'workspaceId': 'alpha', 'path': '/engine/alpha'},
        };
      }
      return null;
    });
    final catalog = _Rpc()
      ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
      ..catalog = Future.value({
        'items': [_row('existing', blank: true)],
      });
    await tester.pumpWidget(
      MaterialApp(
        home: EngineHome(
          host: _host('persistent-default'),
          createRpc: (_) => catalog,
        ),
      ),
    );
    await _settle(tester);
    for (final id in ['default-first', 'default-second']) {
      catalog.creation = Future.value({'sessionId': id});
      await tester.tap(find.byKey(const ValueKey('session-menu')));
      await _settle(tester);
      await tester.tap(find.text('新建会话'));
      await _settle(tester);
      expect(_transport(tester).sessionId, id);
    }
    expect(
      catalog.calls
          .where((c) => c.endpoint == 'session/create')
          .map((c) => c.args)
          .toList(),
      [
        {
          'request': {'workspaceId': 'alpha'},
        },
        {
          'request': {'workspaceId': 'alpha'},
        },
      ],
      reason: 'Saved native default must survive the first successful create',
    );
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'welcome workspace selection applies only to the next new session',
    (tester) async {
      final catalog = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..catalog = Future.value({'items': []})
        ..creation = Future.value({'sessionId': 'initial'});
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('welcome-workspace'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);

      List<Map<String, Object?>> creates() => catalog.calls
          .where((call) => call.endpoint == 'session/create')
          .map((call) => call.args)
          .toList();
      const homeRequest = {'request': <String, Object?>{}};
      const alphaRequest = {
        'request': {'workspaceId': 'alpha'},
      };
      expect(find.text('工作区：Home'), findsOneWidget);
      expect(creates(), [homeRequest]);
      await tester.tap(find.byKey(const ValueKey('welcome-workspace-capsule')));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('welcome-workspace-glass')),
        findsOneWidget,
      );
      await tester.tap(find.text('Alpha'));
      await tester.pumpAndSettle();
      expect(find.text('工作区：Alpha'), findsOneWidget);
      expect(_transport(tester).sessionId, 'initial');
      expect(creates(), [homeRequest]);

      Future<void> createFromDrawer(String id) async {
        catalog.creation = Future.value({'sessionId': id});
        await tester.tap(find.byKey(const ValueKey('session-menu')));
        await _settle(tester);
        await tester.tap(find.text('新建会话'));
        await _settle(tester);
      }

      await createFromDrawer('created-in-alpha');
      expect(_transport(tester).sessionId, 'created-in-alpha');
      expect(find.text('工作区：Home'), findsOneWidget);
      expect(creates(), [homeRequest, alphaRequest]);
      await createFromDrawer('created-in-home');
      expect(_transport(tester).sessionId, 'created-in-home');
      expect(creates(), [homeRequest, alphaRequest, homeRequest]);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'successful workspace creation is consumed after navigation changes',
    (tester) async {
      final pendingCreate = Completer<Object?>();
      final catalog = _Rpc()
        ..workspaceItems = [_workspace('alpha', 'Alpha', [])]
        ..catalog = Future.value({
          'items': [
            _row('initial-blank', blank: true, updatedAt: 2),
            _row('existing', title: 'Existing session'),
          ],
        })
        ..creation = pendingCreate.future;
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(
            host: _host('workspace-create-navigation'),
            createRpc: (_) => catalog,
          ),
        ),
      );
      await _settle(tester);
      expect(_transport(tester).sessionId, 'initial-blank');
      await tester.tap(find.byKey(const ValueKey('welcome-workspace-capsule')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Alpha'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('session-menu')));
      await _settle(tester);
      await tester.tap(find.text('新建会话'));
      await _settle(tester);

      List<Map<String, Object?>> creates() => catalog.calls
          .where((call) => call.endpoint == 'session/create')
          .map((call) => call.args)
          .toList();
      const alphaRequest = {
        'request': {'workspaceId': 'alpha'},
      };
      expect(creates(), [alphaRequest]);
      final existingRow = find.byKey(const ValueKey('session-row-existing'));
      if (existingRow.hitTestable().evaluate().isEmpty) {
        await tester.tap(find.byKey(const ValueKey('session-menu')));
        await _settle(tester);
      }
      await tester.ensureVisible(existingRow);
      await tester.tap(existingRow);
      await _settle(tester);
      expect(_transport(tester).sessionId, 'existing');
      pendingCreate.complete({'sessionId': 'created-in-alpha'});
      await _settle(tester);
      expect(_transport(tester).sessionId, 'existing');

      catalog.creation = Future.value({'sessionId': 'created-in-home'});
      await tester.tap(find.byKey(const ValueKey('session-menu')));
      await _settle(tester);
      await tester.tap(find.text('新建会话'));
      await _settle(tester);
      expect(creates(), [
        alphaRequest,
        {'request': <String, Object?>{}},
      ]);
      expect(_transport(tester).sessionId, 'created-in-home');
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('blank mode reads the roster and commits a host selection', (
    tester,
  ) async {
    final catalog = _Rpc()
      ..presets = [
        {'id': 'normal', 'name': 'Host default', 'isDefault': true},
        {'id': 'creative', 'name': 'Creative', 'isDefault': false},
      ]
      ..catalog = Future.value({
        'items': [_row('blank', blank: true)],
      });
    final chat = _Rpc();
    var opens = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: EngineHome(
          host: _host('preset'),
          createRpc: (_) => opens++ == 0 ? catalog : chat,
        ),
      ),
    );
    await _settle(tester);
    expect(
      catalog.calls.singleWhere((c) => c.endpoint == 'agentPresets/list').args,
      isEmpty,
    );
    ChatShell shell() => tester.widget<ChatShell>(find.byType(ChatShell));
    expect(shell().agentPresets.map((row) => row.label), [
      'Host default',
      'Creative',
    ]);
    expect(shell().selectedAgentPresetId, 'normal');
    expect(shell().agentPresetLocked, isFalse);
    // The ready frame arrives after the shell has mounted and subscribed.
    await tester.pumpAndSettle();
    expect(chat.sessionStream, isNotNull);
    expect(shell().onSelectAgentPreset, isNotNull);
    final select = shell().onSelectAgentPreset!;
    expect(await select('not-advertised'), isFalse);
    final committed = Completer<String>();
    chat.selectedPreset = committed.future;
    final picker = find.byKey(const ValueKey('welcome-agent-preset'));
    expect(find.text('对话模式：Host default'), findsOneWidget);
    final capsule = tester.widget<Container>(
      find.byKey(const ValueKey('welcome-agent-capsule')),
    );
    expect((capsule.decoration! as BoxDecoration).border, isNotNull);
    await tester.ensureVisible(picker);
    await tester.tap(picker);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('welcome-agent-glass')), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('welcome-agent-glass')),
        matching: find.byType(BackdropFilter),
      ),
      findsOneWidget,
    );
    expect(find.text('Host default'), findsOneWidget);
    await tester.tap(find.text('Creative'));
    await tester.pumpAndSettle();
    expect(shell().selectedAgentPresetId, 'normal');
    expect(shell().onSelectAgentPreset, isNull);
    committed.complete('creative');
    await _settle(tester);
    expect(find.text('对话模式：Creative'), findsOneWidget);
    expect(
      chat.calls.singleWhere((c) => c.endpoint == 'agentPresets/select').args,
      {'agentId': 'blank', 'agentPreset': 'creative'},
    );
    expect(shell().selectedAgentPresetId, 'creative');
    chat.sessionStream!.add(record(0, 'turn/start', {'turn': 1}));
    await _settle(tester);
    expect(shell().agentPresetLocked, isTrue);
    expect(shell().onSelectAgentPreset, isNull);
    expect(await select('normal'), isFalse);
    expect(
      chat.calls.where((c) => c.endpoint == 'agentPresets/select'),
      hasLength(1),
    );
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('late draft hydration keeps text typed in the mounted composer', (
    tester,
  ) async {
    const channel = MethodChannel('app.dsh.mobile/native_chat');
    final read = Completer<String>();
    final writes = <Map<Object?, Object?>>[];
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'readSessionDraft') return read.future;
      if (call.method == 'writeSessionDraft') {
        writes.add(call.arguments as Map<Object?, Object?>);
      }
      return true;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    final peer = _Rpc()
      ..catalog = Future.value({
        'items': [_row('draft-session', title: 'Draft session')],
      });
    await tester.pumpWidget(
      MaterialApp(
        home: EngineHome(host: _host('draft'), createRpc: (_) => peer),
      ),
    );
    await _settle(tester);
    final input = find.byKey(const ValueKey('composer-input'));
    await tester.enterText(input, 'Fresh draft');
    await tester.pump();
    read.complete('Saved draft');
    await _settle(tester);
    expect(find.text('Fresh draft'), findsOneWidget);
    expect(writes, [
      {'id': 'draft-session', 'text': 'Fresh draft'},
    ]);
    await tester.pumpWidget(const SizedBox.shrink());
  });

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
      expect(peer.calls.lastWhere((c) => c.endpoint == 'session/create').args, {
        'request': <String, Object?>{},
      });
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
      expect(peer.calls.where((c) => c.endpoint == 'session/create'), isEmpty);
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
        (label) =>
            label.contains('session') || label.contains('/engine/project'),
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

  testWidgets(
    'an unreadable catalogue retries automatically and invents no Session',
    (tester) async {
      final peer = _Rpc()..failCatalog = true;
      await tester.pumpWidget(
        MaterialApp(
          home: EngineHome(host: _host('broken'), createRpc: (_) => peer),
        ),
      );
      await _settle(tester);
      expect(find.byType(ChatShell), findsNothing);
      expect(find.textContaining('会话列表读取失败'), findsOneWidget);
      expect(peer.calls.where((c) => c.endpoint == 'session/create'), isEmpty);
      peer.failCatalog = false;
      expect(
        find.byKey(const ValueKey('engine-bootstrap-retry')),
        findsNothing,
      );
      await tester.pump(const Duration(seconds: 1));
      await _settle(tester);
      expect(find.byType(ChatShell), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

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
    DshRpcConnection factory(String url) => url.contains('old') ? old : fresh;
    Widget app(HostInfo host) => MaterialApp(
      home: EngineHome(host: host, createRpc: factory),
    );
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
    expect(peer.calls.where((c) => c.endpoint == 'session/create'), isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
    settings.dispose();
  });

  testWidgets('delayed handoff keeps the exact initial session', (
    tester,
  ) async {
    const channel = MethodChannel('app.dsh.mobile/native_chat');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    var initialSessionCalls = 0;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'initialSession') {
        initialSessionCalls++;
        return Future<String>.delayed(
          const Duration(milliseconds: 650),
          () => 'A',
        );
      }
      return null;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));

    final peer = _Rpc()
      ..catalog = Future.value({
        'items': [
          _row('B', title: 'Other session'),
          _row('A', title: 'Handed-off session'),
        ],
      });
    await tester.pumpWidget(
      MaterialApp(
        home: EngineHome(
          host: _host('delayed-handoff'),
          createRpc: (_) => peer,
        ),
      ),
    );
    // At 600ms the timed-out read must leave the home pending; the handoff
    // resolves at 650ms and is consumed by the scheduled retry.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));
    expect(find.byType(ChatShell), findsNothing);
    await tester.pump(const Duration(milliseconds: 1100));
    await tester.pumpAndSettle();

    expect(_transport(tester).sessionId, 'A');
    expect(initialSessionCalls, 1);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('stub home explicitly requires Android', (tester) async {
    await tester.pumpWidget(
      MaterialApp(home: stub.buildEngineHome(HostInfo.preview)),
    );
    expect(find.textContaining('Android'), findsWidgets);
    expect(find.byType(ChatShell), findsNothing);
  });
}
