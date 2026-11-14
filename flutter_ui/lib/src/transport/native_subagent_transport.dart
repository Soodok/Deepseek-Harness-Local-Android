import 'dart:convert';
import 'dart:math' as math;

import '../model/subagent_model.dart';
import '../model/tool_content.dart';
import 'dsh_rpc_connection.dart';

/// Passive child browsing and explicit, queued continuation delivery.
class NativeSubagentTransport implements SubagentTransport {
  NativeSubagentTransport(this.rpc);
  final DshRpcConnection rpc;
  final _revisions = <SubagentAddress, int>{};
  final _cursors = <SubagentAddress, int>{};

  Future<List<AgentTask>> readCatalog(String parentId) async {
    if (parentId.isEmpty)
      throw const DshRpcException(
        'subagent/invalid-address',
        'Parent session is required',
      );
    final values = await _projections(parentId);
    final catalog = values['subagentCatalog'];
    if (catalog == null) return const [];
    if (catalog is! List) throw _invalid;
    final tasks = <AgentTask>[];
    for (final value in catalog) {
      final entry = _object(value);
      final id = _string(entry?['id']);
      if (entry == null || id == null) throw _invalid;
      if (entry['mode'] == 'external') {
        tasks.add(
          AgentTask(
            id: id,
            name: _string(entry['name']) ?? _string(entry['label']) ?? id,
            task: _string(entry['label']) ?? '',
            status: AgentState.waiting,
            detail: 'External execution; no local child session',
          ),
        );
        continue;
      }
      final childValues = await _projections(id);
      final state = await _state(id);
      final identity = _object(childValues['subagent']);
      var mode = _mode(entry['mode']);
      if (mode == SubagentMode.unknown) mode = _mode(identity?['mode']);
      final timing = _object(childValues['subagentTiming']);
      final running = state['running'] as bool;
      final completed =
          timing?['lastTurnCompleted'] == true && timing?['active'] == null;
      final label =
          _string(entry['label']) ?? _string(identity?['label']) ?? '';
      tasks.add(
        AgentTask(
          id: id,
          name: _string(entry['name']) ?? (label.isEmpty ? id : label),
          task: label,
          status: running
              ? AgentState.running
              : completed
              ? AgentState.completed
              : AgentState.waiting,
          detail: mode == SubagentMode.unknown
              ? 'Child mode is unresolved'
              : '',
          address: SubagentAddress(
            parentSessionId: parentId,
            childSessionId: id,
            mode: mode,
          ),
        ),
      );
    }
    return List.unmodifiable(tasks);
  }

  @override
  Future<SubagentConversation> readSubagent(SubagentAddress address) async {
    _validate(address);
    final revisionAtStart = _revisions[address] ?? -1;
    final resolved = await _resolve(address);
    final page = _object(
      await rpc.call('session/events', {
        'request': {'address': resolved.toWire(), 'maxMessages': 500},
      }),
    );
    if (page == null) throw _invalid;
    final journal = _ChildJournal(address);
    journal.page(page);
    journal.running =
        (await _state(resolved.childSessionId))['running'] as bool;
    final currentRevision = _revisions[address] ?? -1;
    final currentCursor = _cursors[address] ?? -1;
    // A read started before a live update cannot replace that newer view.
    if (journal.cursor < currentCursor ||
        (currentRevision > revisionAtStart &&
            journal.cursor <= currentCursor)) {
      return SubagentConversation(
        address: address,
        messages: journal.messages(),
        revision: math.min(revisionAtStart, currentRevision - 1),
        running: journal.running,
      );
    }
    return _conversation(journal);
  }

  @override
  Stream<SubagentConversation> watchSubagent(SubagentAddress address) async* {
    _validate(address);
    final resolved = await _resolve(address);
    final state = await _state(resolved.childSessionId);
    final journal = _ChildJournal(address)..running = state['running'] as bool;
    await for (final frame in rpc.follow('session/follow', {
      'request': {
        'address': resolved.toWire(),
        'assistantStream': true,
        'maxMessages': 500,
        'turnWindow': {'minMessages': 50, 'minTurns': 2},
      },
    })) {
      if (journal.accept(frame)) yield _conversation(journal);
    }
  }

  @override
  Future<SubagentReceipt> sendSubagent(
    SubagentAddress address,
    String text, {
    required String requestId,
  }) async {
    _validate(address);
    if (!address.canSend)
      throw const DshRpcException(
        'subagent/not-resumable',
        'This child is read only',
      );
    if (requestId.isEmpty || text.trim().isEmpty)
      throw const DshRpcException(
        'subagent/invalid-prompt',
        'Prompt and request identity are required',
      );
    final receipt = _object(
      await rpc.call('subagents/prompt', {
        'request': {
          'requestId': requestId,
          'childSessionId': address.childSessionId,
          'delivery': 'queue',
          'content': [
            {'type': 'text', 'text': text},
          ],
        },
      }),
    );
    if (_string(receipt?['messageId']) == null) throw _invalid;
    return SubagentReceipt(requestId: requestId, delivery: 'queue');
  }

  Future<Map<String, Object?>> _projections(String id) async {
    final projection = _object(
      await rpc.call('session/projections', {
        'request': {'sessionId': id},
      }),
    );
    final values = _object(projection?['values']);
    if (values == null) throw _invalid;
    return values;
  }

  Future<Map<String, Object?>> _state(String id) async {
    final state = _object(
      await rpc.call('session/state', {
        'request': {'sessionId': id},
      }),
    );
    if (state == null || state['running'] is! bool) throw _invalid;
    return state;
  }

  Future<SubagentAddress> _resolve(SubagentAddress address) async {
    if (address.mode != SubagentMode.unknown) return address;
    final identity = _object(
      (await _projections(address.childSessionId))['subagent'],
    );
    return SubagentAddress(
      parentSessionId: address.parentSessionId,
      childSessionId: address.childSessionId,
      mode: _mode(identity?['mode']),
    );
  }

  SubagentConversation _conversation(_ChildJournal journal) {
    final revision = math.max(
      (_revisions[journal.address] ?? -1) + 1,
      journal.cursor,
    );
    _revisions[journal.address] = revision;
    _cursors[journal.address] = journal.cursor;
    return SubagentConversation(
      address: journal.address,
      messages: journal.messages(),
      revision: revision,
      running: journal.running,
    );
  }

  void _validate(SubagentAddress address) {
    if (address.parentSessionId.isEmpty ||
        address.childSessionId.isEmpty ||
        address.parentSessionId == address.childSessionId) {
      throw const DshRpcException(
        'subagent/invalid-address',
        'A distinct parent and child session are required',
      );
    }
  }
}

const _invalid = DshRpcException(
  'subagent/protocol',
  'Invalid child session response',
);
const _gap = DshRpcException(
  'subagent/sequence-gap',
  'Child session stream has a sequence gap',
);
Map<String, Object?>? _object(Object? value) =>
    value is Map<String, Object?> ? value : null;
String? _string(Object? value) =>
    value is String && value.isNotEmpty ? value : null;
int _integer(Object? value) {
  if (value is! int || value < 0) throw _invalid;
  return value;
}

int _cursor(Object? value) {
  if (value is! int || value < -1) throw _invalid;
  return value;
}

SubagentMode _mode(Object? value) => switch (value) {
  'continuable' => SubagentMode.continuable,
  'one-shot' => SubagentMode.oneShot,
  _ => SubagentMode.unknown,
};

class _ChildJournal {
  _ChildJournal(this.address);
  final SubagentAddress address;
  final _events = <int, Map<String, Object?>>{};
  int cursor = 0;
  int? _assistantRevision;
  _LiveAttempt? _attempt;
  bool opened = false;
  bool running = false;

  void page(Map<String, Object?> page) {
    cursor = _cursor(page['cursor']);
    _replaceRecords(page['records']);
    opened = true;
  }

  bool accept(Map<String, Object?> frame) {
    switch (frame['type']) {
      case 'snapshot':
        final header = _object(frame['header']);
        if (header?['id'] != address.childSessionId) throw _invalid;
        cursor = _cursor(frame['cursor']);
        _replaceRecords(frame['records']);
        final baseline = _object(frame['assistantStream']);
        if (baseline == null) throw _invalid;
        _assistantRevision = _integer(baseline['revision']);
        final active = _object(baseline['activeAttempt']);
        _attempt = active == null ? null : _LiveAttempt.baseline(active);
        opened = true;
        return true;
      case 'event':
        if (!opened) throw _invalid;
        final event = _readEvent(frame);
        final seq = event['seq'] as int;
        if (seq <= cursor) return false;
        if (seq != cursor + 1) throw _gap;
        _events[seq] = event;
        cursor = seq;
        _runningEvent(event);
        return true;
      case 'assistant-stream':
        if (!opened) throw _invalid;
        return _assistant(frame['frame']);
      default:
        throw _invalid;
    }
  }

  Map<String, Object?> _readEvent(Object? raw) {
    final record = _object(raw);
    final event = _object(record?['event']);
    if (record?['type'] != 'event' ||
        event == null ||
        _string(event['type']) == null)
      throw _invalid;
    _integer(event['seq']);
    return event;
  }

  void _replaceRecords(Object? raw) {
    if (raw is! List) throw _invalid;
    final events = raw.map(_readEvent).toList()
      ..sort((a, b) => (a['seq'] as int).compareTo(b['seq'] as int));
    _events.clear();
    int? previous;
    for (final event in events) {
      final seq = event['seq'] as int;
      if (seq > cursor) throw _invalid;
      if (seq == previous) continue;
      if (previous != null && seq != previous + 1) throw _gap;
      previous = seq;
      _events[seq] = event;
      _runningEvent(event);
    }
    if (previous != null && previous != cursor) throw _gap;
  }

  void _runningEvent(Map<String, Object?> event) {
    if (event['type'] == 'turn/start') running = true;
    if (event['type'] == 'turn/end') running = false;
  }

  bool _assistant(Object? raw) {
    final frame = _object(raw);
    if (frame == null) throw _invalid;
    final revision = _integer(frame['revision']);
    final previous = _assistantRevision;
    if (previous == null) throw _invalid;
    if (revision <= previous) return false;
    if (revision != previous + 1) throw _gap;
    _assistantRevision = revision;
    if (frame['type'] == 'start') {
      _attempt = _LiveAttempt.start(frame);
      return true;
    }
    final attempt = _attempt;
    if (attempt == null || frame['attemptId'] != attempt.id) throw _invalid;
    if (_integer(frame['index']) != attempt.nextIndex) throw _gap;
    if (frame['type'] == 'chunk') {
      attempt.nextIndex++;
      attempt.chunk(frame['chunk']);
    } else if (frame['type'] == 'end') {
      final outcome = _object(frame['outcome']);
      if (outcome?['kind'] == 'abandoned')
        _attempt = null;
      else if (outcome?['kind'] == 'committed')
        attempt.committedSeq = _integer(outcome?['seq']);
      else
        throw _invalid;
    } else {
      throw _invalid;
    }
    return true;
  }

  List<SubagentMessage> messages() {
    final visible = <int, List<SubagentMessage>>{};
    final seqs = _events.keys.toList()..sort();
    for (final seq in seqs) {
      final event = _events[seq]!;
      final op = _object(event['surfaceOp']);
      if (op?['op'] == 'replace') {
        final start = _integer(op?['startSeq']), end = _integer(op?['endSeq']);
        if (start > end || end >= seq) throw _invalid;
        visible.removeWhere((key, _) => key >= start && key <= end);
      }
      visible[seq] = _eventMessages(event);
    }
    final result = visible.values.expand((messages) => messages).toList();
    final attempt = _attempt;
    if (attempt != null) {
      final settled =
          attempt.committedSeq != null &&
              _events.containsKey(attempt.committedSeq) ||
          _events.values.any(
            (event) =>
                event['type'] == 'assistant/message' &&
                (event['seq'] as int) > attempt.startedAfterSeq,
          );
      if (!settled)
        result.addAll(
          _content('stream:' + attempt.id, 'assistant', attempt.content),
        );
    }
    return result;
  }

  List<SubagentMessage> _eventMessages(Map<String, Object?> event) {
    final data = _object(event['data']);
    final fallbackId = 'event:' + event['seq'].toString();
    switch (event['type']) {
      case 'user/message':
        final id = _string(data?['id']);
        if (id == null) break;
        final source = _object(data?['source']);
        return _content(
          id,
          source?['kind'] == 'user' ? 'user' : 'context',
          data?['content'],
        );
      case 'assistant/message':
        final message = _object(data?['message']);
        final id = _string(message?['id']);
        if (id == null) break;
        return _content(id, 'assistant', message?['content']);
      case 'developer/message':
        final message = _object(data?['message']);
        return _content(
          _string(message?['id']) ?? fallbackId,
          'context',
          message?['content'],
        );
      case 'turn/start':
      case 'turn/end':
      case 'step/start':
      case 'step/end':
        return const [];
    }
    return [
      SubagentMessage(id: fallbackId, role: 'event', text: jsonEncode(event)),
    ];
  }
}

List<SubagentMessage> _content(String id, String role, Object? raw) {
  if (raw is! List)
    return [SubagentMessage(id: id, role: 'event', text: jsonEncode(raw))];
  final text = <String>[];
  final extra = <SubagentMessage>[];
  for (var index = 0; index < raw.length; index++) {
    final block = _object(raw[index]);
    if (block?['type'] == 'text' && block?['text'] is String) {
      text.add(block!['text'] as String);
    } else if (block?['type'] == 'reasoning' && block?['text'] is String) {
      extra.add(
        SubagentMessage(
          id: id + ':reasoning:' + index.toString(),
          role: 'reasoning',
          text: block!['text'] as String,
        ),
      );
    } else {
      extra.add(
        SubagentMessage(
          id: id + ':opaque:' + index.toString(),
          role: 'event',
          text: jsonEncode(raw[index]),
        ),
      );
    }
  }
  return [
    if (text.isNotEmpty)
      SubagentMessage(id: id, role: role, text: text.join('')),
    ...extra,
  ];
}

class _LiveAttempt {
  _LiveAttempt(this.id, this.startedAfterSeq, this.nextIndex);
  final String id;
  final int startedAfterSeq;
  int nextIndex;
  int? committedSeq;
  final _blocks = <int, Map<String, Object?>>{};

  factory _LiveAttempt.start(Map<String, Object?> frame) {
    final id = _string(frame['attemptId']);
    if (id == null) throw _invalid;
    return _LiveAttempt(id, _cursor(frame['startedAfterSeq']), 0);
  }

  factory _LiveAttempt.baseline(Map<String, Object?> frame) {
    final attempt = _LiveAttempt.start(frame);
    final stream = frame['stream'];
    if (stream is! List) throw _invalid;
    var count = 0;
    for (final raw in stream) {
      final record = _object(raw);
      if (record == null) throw _invalid;
      if (record['type'] == 'chunk') {
        attempt.chunk(record['chunk']);
        count++;
      } else if (record['type'] == 'text-chunks' ||
          record['type'] == 'reasoning-chunks') {
        final texts = record['texts'];
        if (texts is! List || texts.any((text) => text is! String))
          throw _invalid;
        attempt.chunk({
          'type': record['type'] == 'text-chunks'
              ? 'text-delta'
              : 'reasoning-delta',
          'index': record['index'],
          'text': texts.join(''),
        });
        count += texts.length;
      } else if (record['type'] == 'tool-call-chunks') {
        final args = record['args'];
        if (args is! List) throw _invalid;
        attempt.chunk({
          'type': 'tool-call-delta',
          'index': record['index'],
          'id': record['id'],
          'name': record['name'],
          'arguments': args.join(''),
        });
        count += args.length;
      } else {
        throw _invalid;
      }
    }
    attempt.nextIndex = _integer(frame['nextIndex']);
    if (attempt.nextIndex != count) throw _gap;
    return attempt;
  }

  void chunk(Object? raw) {
    final value = _object(raw);
    if (value == null || _string(value['type']) == null) throw _invalid;
    if (['usage', 'finish', 'error'].contains(value['type'])) return;
    if (value['index'] is! int) return;
    final index = _integer(value['index']);
    switch (value['type']) {
      case 'block-start':
        _blocks[index] = {
          'type': value['blockType'],
          if (['text', 'reasoning'].contains(value['blockType'])) 'text': '',
        };
      case 'text-delta':
      case 'reasoning-delta':
        final type = value['type'] == 'text-delta' ? 'text' : 'reasoning';
        if (value['text'] is! String) throw _invalid;
        final previous = _blocks[index];
        final prefix = previous?['type'] == type && previous?['text'] is String
            ? previous!['text'] as String
            : '';
        _blocks[index] = {
          'type': type,
          'text': prefix + (value['text'] as String),
        };
      case 'block-end':
        final block = _object(value['block']);
        if (block == null) throw _invalid;
        _blocks[index] = block;
      default:
        _blocks[index] = value;
    }
  }

  List<Map<String, Object?>> get content {
    final indices = _blocks.keys.toList()..sort();
    return indices.map((index) => _blocks[index]!).toList();
  }
}
