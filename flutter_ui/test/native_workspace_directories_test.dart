import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/bridge/native_workspace_directories.dart';

const host = HostInfo(webUrl: 'http://127.0.0.1:3080', state: 'Healthy',
    available: true, workspaceDomain: 'domain', workspaceGeneration: 'boot');
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('app.dsh.mobile/native_workspace');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('private list and creation carry the running Host scope', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'listPrivateWorkspaces') {
        return {'domain': 'domain', 'generation': 'boot', 'root': '/app/private',
          'path': '/app/private', 'directories': [{'path': '/app/private/alpha', 'name': 'alpha'}]};
      }
      return {'domain': 'domain', 'generation': 'boot', 'kind': 'private',
        'path': '/app/private/new', 'name': 'new'};
    });
    final service = NativeWorkspaceDirectories(host);
    expect((await service.listPrivate()).directories.single.path, '/app/private/alpha');
    expect((await service.createPrivate('/app/private', 'new')).path, '/app/private/new');
    expect(calls.map((call) => call.method), ['listPrivateWorkspaces', 'createPrivateWorkspace']);
    for (final call in calls) {
      expect(call.arguments['domain'], 'domain');
      expect(call.arguments['generation'], 'boot');
    }
  });

  test('cancelled system selection creates no directory target', () async {
    messenger.setMockMethodCallHandler(channel, (_) async => null);
    expect(await NativeWorkspaceDirectories(host).pickShared(), isNull);
  });

  test('URI stays separate and is never accepted as a filesystem path', () async {
    messenger.setMockMethodCallHandler(channel, (_) async => {
      'domain': 'domain', 'generation': 'boot', 'kind': 'shared',
      'path': 'content://provider/tree/primary:Documents', 'name': 'Documents',
      'uri': 'content://provider/tree/primary:Documents',
    });
    await expectLater(NativeWorkspaceDirectories(host).pickShared(), throwsFormatException);
  });

  test('stale Host receipts and unsupported responses fail closed', () async {
    for (final value in [null, {'domain': 'domain', 'generation': 'old-boot',
      'kind': 'private', 'path': '/app/private/new', 'name': 'new'}]) {
      messenger.setMockMethodCallHandler(channel, (_) async => value);
      await expectLater(NativeWorkspaceDirectories(host).createPrivate('/app/private', 'new'),
          throwsA(isA<FormatException>()));
    }
  });

  test('unavailable directory errors are retained instead of falling back', () async {
    messenger.setMockMethodCallHandler(channel, (_) async {
      throw PlatformException(code: 'workspace/unreachable', message: '系统已授权 URI，但引擎无法直接访问');
    });
    await expectLater(NativeWorkspaceDirectories(host).pickShared(), throwsA(
      isA<PlatformException>().having((e) => e.code, 'code', 'workspace/unreachable')));
  });
}
