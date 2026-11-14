import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/engine_session.dart';

void main() {
  test('catalog preserves real root identity and disables migration rows', () {
    final rows = EngineSession.fromResponse({
      'items': [
        {
          'sessionId': 'root',
          'updatedAt': 2,
          'running': true,
          'cwd': '/workspace',
        },
        {
          'sessionId': 'child',
          'updatedAt': 3,
          'origin': 'subagent',
          'parentSessionId': 'root',
        },
        {
          'sessionId': 'old',
          'updatedAt': 1,
          'formatStatus': 'migration-required',
        },
      ],
    });
    expect(rows.map((r) => r.id), ['root', 'old']);
    expect(rows.first.running, isTrue);
    expect(rows.first.cwd, '/workspace');
    expect(rows.first.openable, isTrue);
    expect(rows.last.openable, isFalse);
  });
  test('malformed catalog cannot be reported as an empty success', () {
    expect(
      () => EngineSession.fromResponse({
        'items': [
          {'id': 'invented'},
        ],
      }),
      throwsFormatException,
    );
    expect(() => EngineSession.fromResponse({}), throwsFormatException);
  });
}
