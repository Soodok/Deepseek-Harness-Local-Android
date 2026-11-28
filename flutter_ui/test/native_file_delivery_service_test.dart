import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/native_file_delivery_service.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class FileRpc extends DshRpcConnection {
  FileRpc() : super(webUrl: 'http://localhost/?token=test');
  final calls = <Map<String, Object?>>[];
  String absolute = '/work/报告 (1).dart';
  Object? data = Uint8List.fromList([0, 255, 128, 1]);
  bool eof = true;
  @override
  Future<Object?> call(String endpoint, Map<String, Object?> args) async {
    calls.add({'endpoint': endpoint, ...args});
    return {
      'absolutePath': absolute,
      'version': 'v1',
      'bytes': 4,
      if (endpoint.endsWith('/read')) 'text': 'const n = 1;',
      if (endpoint.endsWith('/readBytes')) 'data': data,
      'eof': eof,
    };
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late FileRpc rpc;
  late NativeFileDeliveryService service;
  setUp(() {
    rpc = FileRpc();
    service = NativeFileDeliveryService(
      rpc: rpc,
      sessionId: 's1',
      workspaceRoot: '/work',
    );
  });
  test(
    'literal percent filenames and query suffixes keep a usable path and MIME',
    () async {
      rpc.absolute = '/work/100% code.dart';
      final preview = await service.preview(
        displayName: 'code',
        target: '100% code.dart',
      );
      expect(preview.text, 'const n = 1;');
      expect(rpc.calls.first['path'], '100% code.dart');
      expect(
        NativeFileDeliveryService.mimeFor('report.pdf?download=1'),
        'application/pdf',
      );
    },
  );
  test(
    'session file resource decodes exact Chinese name and reads real scope',
    () async {
      final preview = await service.preview(
        displayName: '报告',
        target: 'dsh-resource://file/session/s1/%E6%8A%A5%E5%91%8A%20(1).dart',
      );
      expect(preview.text, 'const n = 1;');
      expect(rpc.calls.first['workspaceFileScopeId'], 's1');
      expect(rpc.calls.first['path'], '报告 (1).dart');
    },
  );
  test(
    'absolute resources and file URIs resolve to same workspace path',
    () async {
      for (final target in [
        'dsh-resource://file/absolute/work/%E6%8A%A5%E5%91%8A%20(1).dart',
        'file:///work/%E6%8A%A5%E5%91%8A%20(1).dart',
      ]) {
        expect(
          (await service.preview(
            displayName: '报告',
            target: target,
          )).absolutePath,
          '/work/报告 (1).dart',
        );
      }
    },
  );
  test(
    'Windows paths do not duplicate drive and POSIX paths stay case sensitive',
    () async {
      rpc.absolute = 'C:/work/code.py';
      service = NativeFileDeliveryService(
        rpc: rpc,
        sessionId: 's1',
        workspaceRoot: r'C:\work',
      );
      expect(
        (await service.preview(
          displayName: 'code',
          target: r'C:\work\code.py',
        )).absolutePath,
        'C:/work/code.py',
      );
      service = NativeFileDeliveryService(
        rpc: rpc,
        sessionId: 's1',
        workspaceRoot: '/work',
      );
      await expectLater(
        service.preview(displayName: 'x', target: '/Work/code.py'),
        throwsFormatException,
      );
    },
  );
  test(
    'traversal, cross-session, network schemes and NUL never reach RPC',
    () async {
      for (final path in [
        '../secret.txt',
        '/work2/secret.txt',
        'dsh-resource://file/session/other/x.txt',
        'https://evil.test/x.png',
        'javascript:x.txt',
        'bad\u0000.txt',
      ]) {
        await expectLater(
          service.preview(displayName: 'x', target: path),
          throwsFormatException,
        );
      }
      expect(rpc.calls, isEmpty);
    },
  );
  test('image receives decoded binary and rejects incomplete bytes', () async {
    rpc.absolute = '/work/x.png';
    final preview = await service.preview(displayName: 'x', target: 'x.png');
    expect(preview.bytes, orderedEquals([0, 255, 128, 1]));
    rpc.eof = false;
    await expectLater(
      service.preview(displayName: 'x', target: 'x.png'),
      throwsA(isA<DshRpcException>()),
    );
  });
  test(
    'canonical path outside workspace is rejected before presenting',
    () async {
      rpc.absolute = '/private/secret.txt';
      await expectLater(
        service.preview(displayName: 'x', target: 'link/secret.txt'),
        throwsFormatException,
      );
    },
  );
  test(
    'unsupported preview falls back via caller, not a fake preview',
    () async {
      await expectLater(
        service.preview(displayName: 'pdf', target: 'report.pdf'),
        throwsUnsupportedError,
      );
    },
  );
  test(
    'share of unsupported format passes bytes and canonical basename to native',
    () async {
      rpc.absolute = '/work/report.zip';
      const channel = MethodChannel('app.dsh.mobile/native_chat');
      final calls = <MethodCall>[];
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return true;
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      expect(
        await service.share(target: 'report.zip', mime: 'application/zip'),
        isTrue,
      );
      expect(calls.single.method, 'shareFileBytes');
      expect(calls.single.arguments['name'], 'report.zip');
      expect(calls.single.arguments['bytes'], orderedEquals([0, 255, 128, 1]));
    },
  );
}
