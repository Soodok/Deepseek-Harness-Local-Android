import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dsh_native_chat/src/transport/manual_ssh_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final python = Platform.environment['DSH_SSH_TEST_PYTHON'];
  test('validates canonical host pins and rejects bad ports', () {
    const pin = 'SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
    expect(isManualSshFingerprint(pin), isTrue);
    expect(isManualSshFingerprint('$pin='), isFalse);
    expect(isManualSshFingerprint('${pin}B'), isFalse);
    expect(isManualSshFingerprint('MD5:00'), isFalse);
    expect(parseManualSshPort('22'), 22);
    expect(parseManualSshPort('65536'), isNull);
    expect(parseManualSshPort('0'), isNull);
  });

  test(
    'authenticates and executes only against an isolated loopback SSH daemon',
    () async {
      final server = await Process.start(python!, [
        'test/fixtures/manual_ssh_server.py',
      ]);
      final errorDrain = server.stderr.drain<void>();
      addTearDown(() async {
        server.kill();
        await server.exitCode;
        await errorDrain;
      });
      final events = StreamIterator(
        server.stdout
            .transform(utf8.decoder)
            .transform(const LineSplitter())
            .map((line) => jsonDecode(line) as Map<String, dynamic>),
      );
      addTearDown(events.cancel);
      Future<Map<String, dynamic>> nextEvent() async {
        if (!await events.moveNext().timeout(const Duration(seconds: 10))) {
          fail('Fixture exited before the next SSH event');
        }
        return events.current;
      }

      final ready = await nextEvent();
      expect(ready['event'], 'ready');
      final service = ManualSshService();
      addTearDown(service.disconnect);
      Future<void> connect({
        String password = 'test-only-password',
        String? pin,
      }) => service.connect(
        host: '127.0.0.1',
        port: ready['port'] as int,
        username: 'fixture',
        password: password,
        fingerprint: pin ?? ready['fingerprint'] as String,
      );

      // A different valid fingerprint must fail before the server sees a password.
      await expectLater(
        connect(pin: 'SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'),
        throwsA(
          isA<ManualSshException>().having(
            (e) => e.failure,
            'failure',
            ManualSshFailure.hostKeyMismatch,
          ),
        ),
      );
      expect((await nextEvent())['event'], 'closed');
      expect(service.connected, isFalse);

      await expectLater(
        connect(password: 'wrong-fixture-password'),
        throwsA(
          isA<ManualSshException>().having(
            (e) => e.failure,
            'failure',
            ManualSshFailure.authentication,
          ),
        ),
      );
      expect((await nextEvent())['event'], 'auth');
      expect((await nextEvent())['event'], 'closed');
      expect(service.connected, isFalse);

      await connect();
      expect((await nextEvent())['event'], 'auth');
      expect(service.connected, isTrue);
      final result = await service.execute('fixture-small');
      expect((await nextEvent())['event'], 'exec');
      expect(result.stdout.trim(), 'local-ssh');
      expect(result.stderr.trim(), 'diagnostic');
      expect(result.exitCode, 7);
      expect(result.truncated, isFalse);
      final large = await service.execute('fixture-large');
      expect((await nextEvent())['event'], 'exec');
      expect(large.stdout, 'o' * ManualSshService.outputLimitPerStream);
      expect(large.stderr, 'e' * ManualSshService.outputLimitPerStream);
      expect(large.truncated, isTrue);
      expect(large.exitCode, 0);

      final pending = service.execute('fixture-hold');
      final cancelled = expectLater(
        pending,
        throwsA(isA<ManualSshException>()),
      );
      expect((await nextEvent())['event'], 'exec');
      service.disconnect();
      await cancelled.timeout(const Duration(seconds: 3));
      expect((await nextEvent())['event'], 'closed');
      expect(service.connected, isFalse);
      await expectLater(
        service.execute('fixture-small'),
        throwsA(isA<ManualSshException>()),
      );
    },
    skip: python == null
        ? 'Set DSH_SSH_TEST_PYTHON to Python with Paramiko.'
        : false,
  );
}
