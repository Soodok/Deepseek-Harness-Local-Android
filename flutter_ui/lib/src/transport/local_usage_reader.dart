import 'dart:async';

import '../model/engine_session.dart';
import '../model/settings_model.dart';

const _safeInteger = 9007199254740991;
int _natural(Object? value) {
  if (value is! int || value < 0 || value > _safeInteger)
    throw const FormatException('Invalid token record integer');
  return value;
}

int _sum(int a, int b) {
  final sum = a + b;
  if (sum > _safeInteger || sum < 0)
    throw const FormatException('Token aggregate exceeds exact integer range');
  return sum;
}

class _Day {
  int input = 0, output = 0, samples = 0;
}

typedef _Sample = ({DateTime date, int input, int output});

/// Bounded main-session journal samples, grouped by device local calendar date.
/// This is neither a Platform account bill nor a claim of complete history.
Future<UsageHistory> readLocalUsage(
  Future<Object?> Function(String, Map<String, Object?>) call, {
  DateTime? now,
}) async {
  final clock = Stopwatch()..start();
  Future<Object?> read(String endpoint, Map<String, Object?> args) {
    final remaining = const Duration(seconds: 12) - clock.elapsed;
    if (remaining <= Duration.zero) throw TimeoutException('本机会话用量读取超时');
    return call(endpoint, args).timeout(remaining);
  }

  final current = (now ?? DateTime.now()).toLocal();
  final end = DateTime(current.year, current.month, current.day);
  final start = DateTime(end.year, end.month, end.day - 83);
  final rows = EngineSession.fromResponse(
    await read('session/list', {'_request': <String, Object?>{}}),
  ).where((row) => row.openable).take(50);
  final totals = <DateTime, _Day>{};
  void add(_Sample sample, int sign) {
    final bucket = totals.putIfAbsent(sample.date, () => _Day());
    bucket.input = sign > 0
        ? _sum(bucket.input, sample.input)
        : bucket.input - sample.input;
    bucket.output = sign > 0
        ? _sum(bucket.output, sample.output)
        : bucket.output - sample.output;
    bucket.samples += sign;
    if (bucket.samples == 0) totals.remove(sample.date);
  }

  for (final row in rows) {
    final page = await read('session/events', {
      'request': {
        'address': {'kind': 'session', 'sessionId': row.id},
        'maxMessages': 500,
        'turnWindow': {'minMessages': 50, 'minTurns': 2},
      },
    });
    if (page is! Map ||
        page['records'] is! List ||
        page['cursor'] is! int ||
        page['hasMore'] is! bool)
      throw const FormatException('Invalid usage history page');
    final cursor = page['cursor'] as int;
    if (cursor < -1 || cursor > _safeInteger)
      throw const FormatException('Invalid history cursor');
    final events = <int, Map>{};
    for (final raw in page['records'] as List) {
      if (raw is! Map || raw['type'] != 'event' || raw['event'] is! Map)
        throw const FormatException('Invalid usage journal record');
      final record = raw['event'] as Map;
      final seq = _natural(record['seq']);
      if (seq > cursor)
        throw const FormatException('Usage record exceeds cursor');
      events.putIfAbsent(seq, () => record);
    }
    final ordered = events.keys.toList()..sort();
    if ((ordered.isEmpty && cursor != -1) ||
        (ordered.isNotEmpty && ordered.last != cursor))
      throw const FormatException('Missing usage history tail');
    for (int i = 1; i < ordered.length; i++) {
      if (ordered[i] != ordered[i - 1] + 1)
        throw const FormatException('Usage history contains an internal gap');
    }
    ({int turn, int step, _Sample sample})? last;
    for (final seq in ordered) {
      final raw = events[seq]!;
      final type = raw['type'];
      final data = raw['data'];
      if (type == 'llm/retry-started') {
        if (data is Map &&
            last?.turn == data['turn'] &&
            last?.step == data['step'])
          last = null;
        continue;
      }
      if (type != 'assistant/message' && type != 'assistant/attempt') continue;
      if (data is! Map)
        throw const FormatException('Invalid assistant usage envelope');
      Object? usage;
      if (type == 'assistant/message' && data['usage'] != null) {
        usage = data['usage'];
      } else if (data['stream'] is List) {
        for (final record in (data['stream'] as List).reversed) {
          if (record is Map &&
              record['type'] == 'chunk' &&
              record['chunk'] is Map &&
              (record['chunk'] as Map)['type'] == 'usage') {
            usage = (record['chunk'] as Map)['usage'];
            break;
          }
        }
      }
      if (usage == null) continue;
      if (usage is! Map)
        throw const FormatException('Invalid provider token sample');
      var input = _natural(usage['inputTokens']);
      final output = _natural(usage['outputTokens']);
      for (final key in ['cacheReadTokens', 'cacheWriteTokens'])
        if (usage.containsKey(key)) input = _sum(input, _natural(usage[key]));
      final turn = _natural(data['turn']), step = _natural(data['step']);
      final time = _natural(raw['time']);
      if (time > 8640000000000000)
        throw const FormatException('Invalid usage timestamp');
      final local = DateTime.fromMillisecondsSinceEpoch(time);
      final date = DateTime(local.year, local.month, local.day);
      final sample = (date: date, input: input, output: output);
      if (last?.turn == turn && last?.step == step) add(last!.sample, -1);
      add(sample, 1);
      last = (turn: turn, step: step, sample: sample);
    }
  }
  final dates =
      totals.keys
          .where((date) => !date.isBefore(start) && !date.isAfter(end))
          .toList()
        ..sort();
  return UsageHistory(
    from: start,
    through: end,
    sourceLabel:
        '主会话最近记录 · 本地时区 · 含缓存输入 · 非账号账单（最多50个会话，每会话最近500条对话消息及相关事件；不含子代理）',
    days: [
      for (final date in dates)
        TokenUsageDay(
          date: date,
          inputTokens: totals[date]!.input,
          outputTokens: totals[date]!.output,
        ),
    ],
  );
}
