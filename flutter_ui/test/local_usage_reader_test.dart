import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/transport/local_usage_reader.dart';

Map<String, Object?> event(
  int seq,
  String type, {
  int input = 10,
  int output = 3,
  int? cache,
  int? time,
  int turn = 0,
  int step = 0,
  bool stream = false,
}) => {
  'seq': seq,
  'time': time ?? DateTime(2026, 11, 16, 12).millisecondsSinceEpoch,
  'type': type,
  'data': {
    'turn': turn,
    'step': step,
    if (type.startsWith('assistant/'))
      if (stream)
        'stream': [
          {
            'type': 'chunk',
            'time': time ?? 0,
            'chunk': {
              'type': 'usage',
              'usage': {
                'inputTokens': input,
                'outputTokens': output,
                'cacheReadTokens': ?cache,
              },
            },
          },
        ]
      else
        'usage': {
          'inputTokens': input,
          'outputTokens': output,
          'cacheReadTokens': ?cache,
        },
  },
};
Future<Object?> Function(String, Map<String, Object?>) carrier(
  List<Map<String, Object?>> records, {
  bool hasMore = false,
}) =>
    (endpoint, args) async => endpoint == 'session/list'
    ? {
        'items': [
          {'sessionId': 's', 'updatedAt': 1},
        ],
      }
    : {
        'records': records,
        'cursor': records.isEmpty ? -1 : records.last['seq'],
        'hasMore': hasMore,
      };
void main() {
  test('replacement settlement dedupes, retry contributes separately and cache is included', () async {
    final a = event(0, 'assistant/message', cache: 2);
    final records = [
      a,
      a,
      event(1, 'assistant/message', input: 12, output: 4, cache: 2),
      event(2, 'llm/retry-started'),
      event(3, 'assistant/attempt', input: 5, output: 1, stream: true),
    ];
    final h = await readLocalUsage(
      carrier(records),
      now: DateTime(2026, 11, 16),
    );
    expect(h.days.single.inputTokens, 19);
    expect(h.days.single.outputTokens, 5);
    expect(h.sourceLabel, contains('缓存'));
  });
  test(
    'missing samples stay unknown while an explicit zero is recorded',
    () async {
      final h = await readLocalUsage(
        carrier([
          event(0, 'user/message'),
          event(1, 'assistant/message', input: 0, output: 0),
        ]),
        now: DateTime(2026, 11, 16),
      );
      expect(h.days, hasLength(1));
      expect(h.days.single.totalTokens, 0);
      final missing = await readLocalUsage(
        carrier([event(0, 'user/message')]),
        now: DateTime(2026, 11, 16),
      );
      expect(missing.days, isEmpty);
    },
  );
  test('bounded suffix is legal and dates use device local time', () async {
    final before = DateTime(2026, 11, 15, 23, 59).millisecondsSinceEpoch;
    final after = DateTime(2026, 11, 16, 0, 1).millisecondsSinceEpoch;
    final h = await readLocalUsage(
      carrier([
        event(10, 'assistant/message', time: before),
        event(11, 'assistant/message', time: after, turn: 1),
      ], hasMore: true),
      now: DateTime(2026, 11, 16),
    );
    expect(h.days.map((d) => d.date.day), [15, 16]);
    expect(h.sourceLabel, contains('本地时区'));
  });
  test('replacement moves its contribution across date boundaries', () async {
    final h = await readLocalUsage(
      carrier([
        event(
          0,
          'assistant/message',
          time: DateTime(2026, 11, 15, 23, 59).millisecondsSinceEpoch,
        ),
        event(
          1,
          'assistant/message',
          input: 20,
          time: DateTime(2026, 11, 16, 0, 1).millisecondsSinceEpoch,
        ),
      ]),
      now: DateTime(2026, 11, 16),
    );
    expect(h.days, hasLength(1));
    expect(h.days.single.date.day, 16);
    expect(h.days.single.inputTokens, 20);
  });
  test(
    'invalid and unsafe counts fail visibly instead of inventing zeros',
    () async {
      for (final bad in [-1, 1.5, 9007199254740992]) {
        final raw = event(0, 'assistant/message');
        final data = raw['data'] as Map;
        data['usage'] = <String, Object?>{
          ...Map<String, Object?>.from(data['usage'] as Map),
          'inputTokens': bad,
        };
        await expectLater(
          readLocalUsage(carrier([raw]), now: DateTime(2026, 11, 16)),
          throwsFormatException,
        );
      }
    },
  );
  test('internal sequence gap and missing tail are rejected', () async {
    await expectLater(
      readLocalUsage(
        carrier([event(1, 'user/message'), event(3, 'assistant/message')]),
      ),
      throwsFormatException,
    );
    await expectLater(
      readLocalUsage(
        (endpoint, args) async => endpoint == 'session/list'
            ? {
                'items': [
                  {'sessionId': 's', 'updatedAt': 1},
                ],
              }
            : {'records': [], 'cursor': 8, 'hasMore': false},
      ),
      throwsFormatException,
    );
  });
  test(
    'read failure is propagated and catalog is bounded to 50 main sessions',
    () async {
      await expectLater(
        readLocalUsage((_, _) async => throw StateError('offline')),
        throwsStateError,
      );
      int reads = 0;
      final h = await readLocalUsage((endpoint, args) async {
        if (endpoint == 'session/list')
          return {
            'items': [
              for (int i = 0; i < 55; i++)
                {'sessionId': 's' + i.toString(), 'updatedAt': i},
            ],
          };
        reads++;
        return {'records': [], 'cursor': -1, 'hasMore': false};
      });
      expect(reads, 50);
      expect(h.days, isEmpty);
    },
  );
}
