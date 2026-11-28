import 'dart:convert';
import 'dart:io';

import 'package:dsh_native_chat/src/transport/rpc_multipart.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  List<int> body({
    Object? path = const ['data'],
    String codec = 'bytes',
    bool omitBytes = false,
  }) {
    const boundary = '----formdata-undici-041176403900';
    final metadata = jsonEncode({
      'type': 'server-response',
      'rpcId': 'rpc-test',
      'result': {
        'ok': true,
        'value': {'data': null, 'eof': true},
      },
      'attachments': [
        {'path': path, 'codec': codec, 'part': 'bytes-0'},
      ],
    });
    return <int>[
      if (!omitBytes)
        ...utf8.encode(
          '--$boundary\r\nContent-Disposition: form-data; name="bytes-0"; filename="blob"\r\nContent-Type: application/octet-stream\r\n\r\n',
        ),
      if (!omitBytes) ...[0, 255, 128, 13, 10, 45, 45, 1],
      if (!omitBytes) ...utf8.encode('\r\n'),
      ...utf8.encode(
        '--$boundary\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n$metadata\r\n--$boundary--\r\n',
      ),
    ];
  }

  final type = ContentType.parse(
    'multipart/form-data; boundary=----formdata-undici-041176403900',
  );

  test(
    'DSH FormData byte attachment hydrates null data without UTF8 conversion',
    () async {
      final result = await decodeRpcBody(body(), type);
      expect(
        (result['result'] as Map)['value']['data'],
        orderedEquals([0, 255, 128, 13, 10, 45, 45, 1]),
      );
      expect((result['result'] as Map)['value']['eof'], isTrue);
    },
  );
  test('ordinary JSON stays unchanged', () async {
    final result = await decodeRpcBody(
      utf8.encode(
        '{"type":"server-response","rpcId":"j","result":{"ok":true,"value":"text"}}',
      ),
      ContentType.json,
    );
    expect((result['result'] as Map)['value'], 'text');
  });
  test(
    'missing parts, invalid codec and invalid replacement paths fail closed',
    () async {
      for (final bytes in [
        body(omitBytes: true),
        body(codec: 'url'),
        body(path: ['absent']),
        body(path: ['__proto__', 'x']),
      ]) {
        await expectLater(decodeRpcBody(bytes, type), throwsFormatException);
      }
      await expectLater(
        decodeRpcBody(body(), ContentType.parse('multipart/form-data')),
        throwsFormatException,
      );
    },
  );
}
