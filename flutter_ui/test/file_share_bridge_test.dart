import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('app.dsh.mobile/native_chat');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'binary file share hands actual bytes and type to native bridge',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return true;
      });
      final bytes = Uint8List.fromList([0, 255, 128, 1]);
      expect(
        await HostBridge.shareFileBytes(
          bytes,
          name: '报告.zip',
          mime: 'application/zip',
        ),
        isTrue,
      );
      expect(calls.single.method, 'shareFileBytes');
      expect(calls.single.arguments['bytes'], orderedEquals(bytes));
      expect(calls.single.arguments['name'], '报告.zip');
      expect(calls.single.arguments['mime'], 'application/zip');
      calls.clear();
      expect(
        await HostBridge.openFileBytes(
          bytes,
          name: '报告.pdf',
          mime: 'application/pdf',
        ),
        isTrue,
      );
      expect(calls.single.method, 'openFileBytes');
    },
  );

  test('native false and platform errors remain failures', () async {
    final bytes = Uint8List.fromList([1]);
    messenger.setMockMethodCallHandler(channel, (_) async => false);
    expect(
      await HostBridge.shareFileBytes(bytes, name: 'x.txt', mime: 'text/plain'),
      isFalse,
    );
    messenger.setMockMethodCallHandler(
      channel,
      (_) async => throw PlatformException(code: 'no-handler'),
    );
    expect(
      await HostBridge.openFileBytes(bytes, name: 'x.txt', mime: 'text/plain'),
      isFalse,
    );
  });
}
