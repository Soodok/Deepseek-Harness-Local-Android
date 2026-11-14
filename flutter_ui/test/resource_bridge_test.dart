import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'resource open is explicit, validated and reports host failures',
    () async {
      const channel = MethodChannel('app.dsh.mobile/native_chat');
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (c) async {
            calls.add(c);
            return true;
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );
      const bad = OutputResource(
        id: 'bad',
        title: 'bad',
        uri: 'file:///private/token',
        kind: ResourceKind.file,
      );
      expect(await HostBridge.openResource(bad), isFalse);
      expect(calls, isEmpty);
      const good = OutputResource(
        id: 'ok',
        title: 'report',
        uri: 'https://example.test/report.md',
        kind: ResourceKind.file,
      );
      expect(await HostBridge.openResource(good), isTrue);
      expect(calls.single.method, 'openResource');
      expect(calls.single.arguments, {'uri': good.uri});
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            channel,
            (c) async => throw PlatformException(code: 'blocked'),
          );
      expect(await HostBridge.openResource(good), isFalse);
    },
  );
}
