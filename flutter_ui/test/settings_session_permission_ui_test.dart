import 'dart:async';

import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/transport/native_session_transport.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_detail_pages.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'dsh_session_projection_test.dart' show snapshot, user;

/// F01 的界面判据：设置页那一行显示宿主**现在**持有的权限；
/// 点选后立即写入当前会话（一次命令、不发消息、不换模型），
/// 成功才关闭并可重进回读，失败留在原页说明宿主保留了哪个值。
class PermissionRpc extends DshRpcConnection {
  PermissionRpc() : super(webUrl: 'http://127.0.0.1/?token=test-only');
  final calls = <({String endpoint, Map<String, Object?> args})>[];
  final streams = <StreamController<Map<String, Object?>>>[];
  String preset = 'danger-full-access';
  bool reject = false;
  bool ignoreWrite = false;
  int cut = 0;

  @override
  Future<void> connect() async {}

  @override
  Future<Object?> call(String endpoint, Map<String, Object?> args) async {
    calls.add((endpoint: endpoint, args: args));
    switch (endpoint) {
      case 'session/modelCatalog':
        return {
          'default': {'provider': 'provider', 'model': 'm'},
          'groups': [
            {
              'id': 'provider',
              'models': [
                {'id': 'm', 'name': 'Visible Model'},
              ],
            },
          ],
          'failures': [],
        };
      case 'permissionPresets/catalog':
        return {
          'options': [
            for (final value in const [
              'read-only',
              'workspace-write',
              'danger-full-access',
            ])
              {'value': value, 'name': value},
          ],
          'defaultOptions': [],
          'defaultPreset': 'danger-full-access',
        };
      case 'commands/execute':
        final line = args['line'];
        if (line is! String || !line.startsWith('/permission ')) return null;
        if (reject) {
          return {
            'commandId': 'command-1',
            'result': {'kind': 'error', 'text': '宿主拒绝了这个模式'},
          };
        }
        if (!ignoreWrite) {
          preset = line.replaceFirst('/permission ', '');
          cut += 1;
        }
        return {
          'commandId': 'command-1',
          'result': {'kind': 'success', 'text': 'ok'},
        };
      case 'session/projections':
        return {
          'kind': 'sequenced',
          'asOfSeq': cut,
          'values': {
            'permissions': {'currentValue': preset},
            'subagentCatalog': [],
          },
        };
      case 'session/state':
        return {
          'cursor': cut,
          'active': true,
          'running': false,
          'queue': [],
          'pendingInput': [],
        };
      case 'session/prompt':
        return {'accepted': true};
      case 'session/selectModel':
        return {
          'selected': {'provider': 'provider', 'model': 'm'},
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
      final frame = snapshot(
        0,
        [user(0, 'initial')],
        values: {
          'permissions': {'currentValue': preset},
          'subagentCatalog': [],
        },
      );
      final request = args['request'] as Map;
      (frame['header'] as Map)['id'] = (request['address'] as Map)['sessionId'];
      stream.add(frame);
    }
    return stream.stream;
  }

  Iterable<String> get written => calls
      .where((c) => c.endpoint == 'commands/execute')
      .map((c) => c.args['line'] as String);
  int get prompts => calls.where((c) => c.endpoint == 'session/prompt').length;

  @override
  Future<void> close() async {
    for (final stream in streams) {
      unawaited(stream.close());
    }
  }
}

Future<void> frames(WidgetTester tester, [int count = 6]) async {
  for (var i = 0; i < count; i++) {
    await tester.pump(const Duration(milliseconds: 60));
  }
}

Future<ChatController> pumpPage(
  WidgetTester tester,
  PermissionRpc permissionHost, {
  bool waitForHost = true,
}) async {
  final transport = NativeSessionTransport(
    rpc: permissionHost,
    sessionId: 'parent',
  );
  final controller = ChatController(transport);
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: PermissionsSettingsPage(controller: controller),
    ),
  );
  unawaited(controller.start());
  await frames(tester);
  // The Host value arrives asynchronously; waiting for it keeps the geometry
  // assertions from racing the first snapshot.
  for (var i = 0; waitForHost && controller.sessionPermission == null; i++) {
    if (i >= 40) fail('Host permission never reached the page');
    await frames(tester, 1);
  }
  return controller;
}

/// The transport owns a 3s poll timer, so the tree is unmounted and the
/// controller disposed inside the test body: a timer still pending when the
/// body ends fails the widget test even if a tearDown would cancel it later.
Future<void> closePage(WidgetTester tester, ChatController controller) async {
  await tester.pumpWidget(const SizedBox.shrink());
  controller.dispose();
}

void main() {
  testWidgets('the row names the host value and a tap writes it once', (
    tester,
  ) async {
    final rpc = PermissionRpc();
    final controller = await pumpPage(tester, rpc);
    final row = find.byKey(const ValueKey('settings-session-permission'));
    expect(row, findsOneWidget);
    expect(
      find.descendant(of: row, matching: find.text('完全访问')),
      findsOneWidget,
    );
    await tester.tap(row);
    await frames(tester);
    expect(find.text('立即写入当前会话，选择后即生效，不会发送消息。'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('permission-option-readOnly')));
    await frames(tester);
    expect(rpc.written, ['/permission read-only']);
    expect(rpc.prompts, 0);
    expect(
      rpc.calls.where((c) => c.endpoint == 'session/selectModel'),
      isEmpty,
    );
    expect(
      rpc.calls
          .singleWhere((c) => c.endpoint == 'commands/execute')
          .args['agentId'],
      'parent',
    );
    expect(controller.permissionWriteError, isNull);
    expect(find.text('立即写入当前会话，选择后即生效，不会发送消息。'), findsNothing);
    expect(find.descendant(of: row, matching: find.text('只读')), findsOneWidget);
    await closePage(tester, controller);
  });

  testWidgets('a refused write keeps the sheet and the host value', (
    tester,
  ) async {
    final rpc = PermissionRpc()..reject = true;
    final controller = await pumpPage(tester, rpc);
    await tester.tap(find.byKey(const ValueKey('settings-session-permission')));
    await frames(tester);
    await tester.tap(find.byKey(const ValueKey('permission-option-readOnly')));
    await frames(tester);
    expect(
      find.byKey(const ValueKey('session-permission-status')),
      findsOneWidget,
    );
    expect(find.text('宿主拒绝了这个模式'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('permission-option-readOnly')),
      findsOneWidget,
    );
    expect(rpc.preset, 'danger-full-access');
    final row = find.byKey(const ValueKey('settings-session-permission'));
    expect(
      find.descendant(of: row, matching: find.text('完全访问')),
      findsOneWidget,
    );
    await closePage(tester, controller);
  });

  testWidgets('an unconfirmed write is reported instead of shown as saved', (
    tester,
  ) async {
    final rpc = PermissionRpc()..ignoreWrite = true;
    final controller = await pumpPage(tester, rpc);
    await tester.tap(find.byKey(const ValueKey('settings-session-permission')));
    await frames(tester);
    await tester.tap(find.byKey(const ValueKey('permission-option-readOnly')));
    await frames(tester);
    expect(find.textContaining('宿主回读的仍是其它模式'), findsWidgets);
    expect(controller.sessionPermission?.wireValue, 'danger-full-access');
    expect(rpc.prompts, 0);
    await closePage(tester, controller);
  });

  testWidgets('an engine without the capability says so and never lies', (
    tester,
  ) async {
    final controller = ChatController(FixtureTransport());
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: PermissionsSettingsPage(controller: controller),
      ),
    );
    unawaited(controller.start());
    await frames(tester);
    final row = find.byKey(const ValueKey('settings-session-permission'));
    expect(
      find.descendant(of: row, matching: find.text('当前引擎不支持在此修改，请使用 Web 版')),
      findsOneWidget,
    );
    await tester.tap(row);
    await frames(tester);
    expect(find.byType(SnackBar), findsOneWidget);
    expect(find.textContaining('请使用 Web 版'), findsWidgets);
    // Let the snack bar's own dismiss timer finish, otherwise the widget test
    // reports a pending timer after the body ends.
    await tester.pump(const Duration(seconds: 5));
    await tester.pump(const Duration(seconds: 1));
    await closePage(tester, controller);
  });
}
