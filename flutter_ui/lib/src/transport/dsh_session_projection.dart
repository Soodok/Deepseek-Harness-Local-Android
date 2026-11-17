import 'dart:convert';

import '../model/chat_model.dart';
import '../model/tool_content.dart';
import '../model/turn_notice.dart';
import 'dsh_rpc_connection.dart';

/// Pure adapter from an addressed DSH journal to the existing immutable UI model.
/// Durable cursors, assistant revisions and client event sequences are independent.
class DshSessionProjection {
  DshSessionProjection({required this.sessionId, required this.generation});

  final String sessionId;
  final int generation;
  int cursor = -1;
  int _sequence = 0;
  ActionRequest? _interaction;
  bool _interactionIsQuestion = false;
  int _assistantRevision = 0;
  bool _opened = false;
  bool _poisoned = false;
  bool _idle = false;
  final _records = <int, Map<String, Object?>>{};
  final _projectionValues = <String, Object?>{};
  final _projectionCuts = <String, int>{};
  List<AgentTask> _agents = const [];
  _Attempt? _attempt;
  _View? _published;

  bool get opened => _opened;
  List<ChatMessage> get messages => List.unmodifiable(_view().messages);

  List<ChatEvent> accept(Map<String, Object?> frame) {
    try {
      if (frame['type'] == 'snapshot') return _snapshot(frame);
      if (!_opened || _poisoned) throw _protocol;
      switch (frame['type']) {
        case 'event':
          final event = _event(frame);
          final seq = event['seq'] as int;
          if (seq <= cursor) return const [];
          if (seq != cursor + 1) throw _durableGap;
          _records[seq] = event;
          cursor = seq;
          final data = _object(event['data']);
          if (event['type'] == 'turn/start') {
            _idle = false;
            _setProjection('todos', null, seq);
          }
          if (event['type'] == 'todo/write')
            _setProjection('todos', data?['todos'], seq);
          if (event['type'] == 'assistant/message' ||
              event['type'] == 'assistant/attempt') {
            final attempt = _attempt;
            if (attempt != null && attempt.matches(event))
              attempt.settledSeq = seq;
          }
          final events = _publish();
          if (event['type'] == 'turn/end') {
            final reason = _object(data?['reason']);
            // A terminal turn failure is durable conversation content, not a
            // transport failure: it becomes its own card in the transcript and
            // never raises the connection banner or invents a retry.
            if (reason?['kind'] == 'completed') {
              events.add(
                ChatEvent.turnComplete(
                  generation: generation,
                  sequence: _sequence++,
                ),
              );
            }
          }
          return events;
        case 'assistant-stream':
          return _assistant(_requiredObject(frame['frame']));
        default:
          throw _protocol;
      }
    } on DshRpcException {
      _poisoned = true;
      rethrow;
    }
  }

  /// A state read may trail follow; it cannot settle a newer presentation.
  /// Idle is not durable success and deliberately emits no turnComplete event.
  List<ChatEvent> idle({required int throughSeq}) {
    if (!_opened || _poisoned || throughSeq < cursor || _idle) return const [];
    _idle = true;
    return _publish(forceSnapshot: true);
  }

  List<ChatEvent> interaction(ActionRequest? action, {bool question = false}) {
    final old = _interaction;
    _interaction = action;
    _interactionIsQuestion = question;
    if (!_opened || _poisoned || old?.requestId == action?.requestId)
      return const [];
    if (action == null)
      return [
        ChatEvent.actionWithdrawn(
          generation: generation,
          sequence: _sequence++,
          requestId: old!.requestId,
        ),
      ];
    return [_interactionEvent()];
  }

  ChatEvent _interactionEvent() {
    final action = _interaction!;
    return _interactionIsQuestion
        ? ChatEvent.question(
            generation: generation,
            sequence: _sequence++,
            requestId: action.requestId,
            title: action.title,
            detail: action.detail,
            choices: action.choices,
            multiSelect: action.multiSelect,
            draftKey: action.draftKey,
          )
        : ChatEvent.approval(
            generation: generation,
            sequence: _sequence++,
            requestId: action.requestId,
            title: action.title,
            detail: action.detail,
          );
  }

  List<ChatEvent> catalog(List<AgentTask> agents) {
    _agents = List.unmodifiable(agents);
    return !_opened || _poisoned ? const [] : _publish();
  }

  /// Whole projection values compare cuts per key; omitted keys stay unchanged.
  List<ChatEvent> projections(
    Map<String, Object?> values, {
    required int asOfSeq,
  }) {
    for (final entry in values.entries) {
      _setProjection(entry.key, entry.value, asOfSeq);
    }
    return !_opened || _poisoned ? const [] : _publish();
  }

  ChatEvent error(String detail) => ChatEvent.error(
    generation: generation,
    sequence: _sequence++,
    detail: detail,
  );
  ChatEvent disconnected() =>
      ChatEvent.disconnected(generation: generation, sequence: _sequence++);

  List<ChatEvent> page(Map<String, Object?> page) => accept({
    'type': 'snapshot',
    'header': {'id': sessionId},
    'cursor': page['cursor'],
    'records': page['records'],
    'hasMore': page['hasMore'],
    'projections': {'asOfSeq': page['cursor'], 'values': <String, Object?>{}},
  });

  List<ChatEvent> _snapshot(Map<String, Object?> frame) {
    if (_object(frame['header'])?['id'] != sessionId ||
        frame['hasMore'] is! bool)
      throw _protocol;
    final cut = _cursor(frame['cursor']);
    final raw = frame['records'];
    if (raw is! List) throw _protocol;
    final events = raw.map(_event).toList()
      ..sort((a, b) => (a['seq'] as int).compareTo(b['seq'] as int));
    final fresh = <int, Map<String, Object?>>{};
    int? previous;
    for (final event in events) {
      final seq = event['seq'] as int;
      if (seq > cut) throw _protocol;
      if (seq == previous) continue;
      if (previous != null && seq != previous + 1) throw _durableGap;
      previous = seq;
      fresh[seq] = event;
    }
    if ((previous != null && previous != cut) ||
        (previous == null && cut != -1))
      throw _durableGap;
    final baseline = _object(frame['assistantStream']);
    final revision = baseline == null ? 0 : _natural(baseline['revision']);
    final active = _object(baseline?['activeAttempt']);
    final attempt = active == null ? null : _Attempt.baseline(active);
    final projection = _requiredObject(frame['projections']);
    final values = _requiredObject(projection['values']);
    final projectionCut = _cursor(projection['asOfSeq'] ?? projection['seq']);
    if (projectionCut > cut) throw _protocol;
    _records
      ..clear()
      ..addAll(fresh);
    cursor = cut;
    _attempt = attempt;
    _assistantRevision = revision;
    _projectionValues.clear();
    _projectionCuts.clear();
    // Reconstruct keys even if a page has no projection capability baseline.
    for (final event in events) {
      if (event['type'] == 'turn/start')
        _setProjection('todos', null, event['seq'] as int);
      if (event['type'] == 'todo/write')
        _setProjection(
          'todos',
          _object(event['data'])?['todos'],
          event['seq'] as int,
        );
    }
    for (final entry in values.entries) {
      _setProjection(entry.key, entry.value, projectionCut);
    }
    _opened = true;
    _poisoned = false;
    _idle = false;
    return _publish(forceSnapshot: true);
  }

  void _setProjection(String key, Object? value, int cut) {
    if (cut < (_projectionCuts[key] ?? -2)) return;
    _projectionValues[key] = value;
    _projectionCuts[key] = cut;
  }

  List<ChatEvent> _assistant(Map<String, Object?> frame) {
    final revision = _natural(frame['revision']);
    if (revision <= _assistantRevision) return const [];
    if (revision != _assistantRevision + 1) throw _assistantGap;
    final id = _string(frame['attemptId']);
    if (id == null) throw _protocol;
    switch (frame['type']) {
      case 'start':
        if (_attempt != null && !_attempt!.ended) throw _assistantGap;
        _attempt = _Attempt.start(frame);
        _idle = false;
      case 'chunk':
        final attempt = _attempt;
        // A new observer can miss start. Await the durable settlement, just as Web does.
        if (attempt != null && attempt.id == id) {
          if (attempt.ended || _natural(frame['index']) != attempt.nextIndex)
            throw _assistantGap;
          attempt.chunk(frame['chunk']);
          attempt.nextIndex++;
        }
      case 'end':
        final attempt = _attempt;
        if (attempt != null && attempt.id == id) {
          if (_natural(frame['index']) != attempt.nextIndex)
            throw _assistantGap;
          final outcome = _requiredObject(frame['outcome']);
          if (outcome['kind'] == 'abandoned') {
            if (attempt.settledSeq != null) throw _assistantGap;
            _attempt = null;
          } else if (outcome['kind'] == 'committed') {
            final seq = _natural(outcome['seq']);
            final settlement = _records[seq];
            if (settlement == null ||
                !attempt.matches(settlement) ||
                settlement['type'] != outcome['eventType'])
              throw _assistantGap;
            attempt.settledSeq = seq;
            attempt.ended = true;
          } else {
            throw _protocol;
          }
        }
      default:
        throw _protocol;
    }
    _assistantRevision = revision;
    return _publish();
  }

  _View _view() {
    final surface = <int, Map<String, Object?>>{};
    bool running = false;
    for (final entry in _records.entries) {
      final event = entry.value;
      if (event['type'] == 'turn/start') running = true;
      if (event['type'] == 'turn/end') running = false;
      final op = _object(event['surfaceOp']);
      if (op != null) {
        if (op['op'] != 'replace') throw _protocol;
        final start = _natural(op['startSeq']), end = _natural(op['endSeq']);
        if (start > end || end >= entry.key) throw _protocol;
        surface.removeWhere((seq, _) => seq >= start && seq <= end);
      }
      surface[entry.key] = event;
    }
    final messages = <ChatMessage>[];
    final tools = <String, ToolRun>{};
    final callData = <String, Map<String, Object?>>{};
    final callTimes = <String, int>{};
    final attempt = _attempt;
    for (final event in surface.values) {
      final type = event['type'];
      final data = _object(event['data']);
      final seq = event['seq'] as int;
      if (data == null) continue;
      final id = sessionId + ':event:' + seq.toString();
      switch (type) {
        case 'user/message':
          // Web shows a transcript row only for source.kind=user. Injected context
          // (approval notices, instructions, runtime/skill/time context, plugin and
          // model notes) is a collapsible context node there, never human speech.
          // A compaction checkpoint is the summary that replaced the surface, so it
          // stays visible as a system row. The journal keeps every record either way.
          final kind = _string(_object(data['source'])?['kind']);
          if (kind != 'user' && kind != 'compact-checkpoint') break;
          final text = _blocksText(data['content']);
          if (text.isNotEmpty)
            messages.add(
              ChatMessage(
                id: id,
                role: kind == 'user' ? ChatRole.user : ChatRole.system,
                text: text,
                seq: seq,
              ),
            );
        case 'assistant/message':
          final message = _requiredObject(data['message']);
          final text = _blocksText(message['content']);
          if (text.isNotEmpty)
            messages.add(
              ChatMessage(
                id: id,
                role: ChatRole.assistant,
                text: text,
                seq: seq,
                messageId: _string(message['id']),
              ),
            );
        case 'system/message':
        case 'developer/message':
          // System prompts and developer context are model input, not conversation.
          // Validate the envelope so a malformed frame still fails the projection.
          _requiredObject(data['message']);
        case 'turn/end':
          // The card is built from the durable reason only. A completion is
          // reported by turnComplete, a user/parent stop leaves no error card,
          // and every remaining ending keeps its structured facts.
          final notice = _turnEndNotice(_object(data['reason']), data);
          if (notice != null)
            messages.add(
              ChatMessage(
                id: id,
                role: ChatRole.notice,
                text: notice.message,
                notice: notice,
              ),
            );
        case 'assistant/attempt':
          final stream = data['stream'];
          if (stream is! List) throw _protocol;
          final retained = _Attempt(
            'durable-' + seq.toString(),
            -1,
            _integerOr(data['turn']),
            _integerOr(data['step']),
          );
          retained.expand(stream);
          final text = _blocksText(retained.content);
          if (text.isNotEmpty)
            messages.add(
              ChatMessage(
                id: id,
                role: ChatRole.system,
                text: '未提交的模型输出\n' + text,
              ),
            );
        case 'tool/call':
          final callId = _string(data['callId']), name = _string(data['name']);
          if (callId == null ||
              name == null ||
              data['arguments'] is! String ||
              tools.containsKey(callId))
            throw _protocol;
          callData[callId] = data;
          callTimes[callId] = _integerOr(event['time']);
          tools[callId] = ToolRun(
            id: callId,
            name: name,
            messageId: messages.lastOrNull?.id ?? sessionId + ':activity',
            content: _toolContent(name, data, null),
          );
        case 'tool/result':
          final message = _requiredObject(data['message']);
          final callId = _string(_object(message['source'])?['callId']);
          if (callId == null) throw _protocol;
          final previous = tools[callId];
          if (previous == null) {
            // A bounded page may start after the call. Keep its exact source, never guess a name.
            tools[callId] = ToolRun(
              id: callId,
              name: '未知工具',
              messageId: messages.lastOrNull?.id ?? sessionId + ':activity',
              status: message['isError'] == true
                  ? ToolStatus.failed
                  : ToolStatus.complete,
              detail: _resultDetail(data),
              content: _toolContent('未知工具', null, data),
            );
          } else {
            final elapsed =
                _integerOr(event['time']) - (callTimes[callId] ?? 0);
            tools[callId] = previous.copyWith(
              status: message['isError'] == true
                  ? ToolStatus.failed
                  : ToolStatus.complete,
              detail: _resultDetail(data),
              elapsedMs: elapsed < 0 ? null : elapsed,
              content: _toolContent(previous.name, callData[callId], data),
            );
          }
        default:
          break;
      }
    }
    if (attempt != null && attempt.settledSeq == null) {
      final settled = _records.values.any(attempt.matches);
      if (!settled) {
        final text = _blocksText(attempt.content);
        if (text.isNotEmpty)
          messages.add(
            ChatMessage(
              id: sessionId + ':attempt:' + attempt.id,
              role: ChatRole.assistant,
              text: text,
              isStreaming: !_idle && !attempt.ended,
            ),
          );
        if (!attempt.ended) running = true;
      }
    }
    final todos = _projectionValues['todos'];
    if (todos != null) {
      if (todos is! List) throw _protocol;
      final steps = <PlanStep>[];
      for (var i = 0; i < todos.length; i++) {
        final todo = _requiredObject(todos[i]);
        final title = _string(todo['content']);
        if (title == null) throw _protocol;
        final status = switch (todo['status']) {
          'pending' => StepState.pending,
          'in_progress' => StepState.running,
          'completed' => StepState.completed,
          _ => throw _protocol,
        };
        steps.add(PlanStep(id: i.toString(), title: title, status: status));
      }
      if (steps.isNotEmpty)
        tools[sessionId + ':todos'] = ToolRun(
          id: sessionId + ':todos',
          name: '任务计划',
          status: ToolStatus.complete,
          messageId: messages.lastOrNull?.id ?? sessionId + ':activity',
          content: ToolContent(title: '任务计划', steps: steps),
        );
    }
    if (_agents.isNotEmpty)
      tools[sessionId + ':subagents'] = ToolRun(
        id: sessionId + ':subagents',
        name: '子智能体',
        status: ToolStatus.complete,
        messageId: messages.lastOrNull?.id ?? sessionId + ':activity',
        content: ToolContent(title: '子智能体', agents: _agents),
      );
    running = running && !_idle;
    if (messages.isEmpty && (tools.isNotEmpty || running)) {
      messages.add(
        ChatMessage(
          id: sessionId + ':activity',
          role: ChatRole.system,
          text: '',
          isStreaming: running,
        ),
      );
    }
    if (running && messages.isNotEmpty && !messages.any((m) => m.isStreaming)) {
      // The live cursor belongs to model or activity output only. A turn card is
      // a closed record of a past turn and a user bubble is not "being written",
      // so when neither exists the running turn gets its own activity row.
      final tail = messages.lastIndexWhere(
        (m) => m.role == ChatRole.assistant || m.role == ChatRole.system,
      );
      if (tail >= 0) {
        messages[tail] = messages[tail].copyWith(isStreaming: true);
      } else if (!messages.any((m) => m.id == sessionId + ':activity')) {
        messages.add(
          ChatMessage(
            id: sessionId + ':activity',
            role: ChatRole.system,
            text: '',
            isStreaming: true,
          ),
        );
      }
    }
    if (!running) {
      for (var i = 0; i < messages.length; i++) {
        messages[i] = messages[i].copyWith(isStreaming: false);
      }
    }
    return _View(messages, tools.values.toList(), running);
  }

  List<ChatEvent> _publish({bool forceSnapshot = false}) {
    final next = _view();
    final old = _published;
    var replace = forceSnapshot || old == null;
    if (old != null && !replace) {
      if (old.messages.length > next.messages.length ||
          old.tools.any(
            (t) => !next.tools.any(
              (n) =>
                  n.id == t.id &&
                  n.name == t.name &&
                  n.messageId == t.messageId,
            ),
          ))
        replace = true;
      for (var i = 0; !replace && i < old.messages.length; i++) {
        final a = old.messages[i], b = next.messages[i];
        if (a.id != b.id || a.role != b.role || !b.text.startsWith(a.text))
          replace = true;
      }
    }
    final events = <ChatEvent>[];
    if (replace) {
      events.add(
        ChatEvent.snapshot(
          generation: generation,
          sequence: _sequence++,
          messages: List.unmodifiable(next.messages),
        ),
      );
    } else {
      for (var i = 0; i < next.messages.length; i++) {
        final message = next.messages[i];
        final previous = i < old!.messages.length ? old.messages[i] : null;
        if (previous == message) continue;
        events.add(
          ChatEvent.delta(
            generation: generation,
            sequence: _sequence++,
            messageId: message.id,
            role: message.role,
            text: message.text.substring(previous?.text.length ?? 0),
            done: !message.isStreaming,
            notice: message.notice,
          ),
        );
      }
    }
    for (final tool in next.tools) {
      final previous = replace
          ? null
          : old!.tools.where((t) => t.id == tool.id).firstOrNull;
      if (previous == null) {
        events.add(
          ChatEvent.toolStarted(
            generation: generation,
            sequence: _sequence++,
            toolId: tool.id,
            name: tool.name,
            messageId: tool.messageId,
            content: tool.content,
          ),
        );
      } else if (_toolSignature(previous) != _toolSignature(tool)) {
        events.add(
          ChatEvent.toolUpdated(
            generation: generation,
            sequence: _sequence++,
            toolId: tool.id,
            content: tool.content!,
          ),
        );
      }
      if (tool.status != ToolStatus.running &&
          (previous == null ||
              _toolSignature(previous) != _toolSignature(tool))) {
        events.add(
          ChatEvent.toolFinished(
            generation: generation,
            sequence: _sequence++,
            toolId: tool.id,
            status: tool.status,
            detail: tool.detail,
            elapsedMs: tool.elapsedMs,
            content: tool.content,
          ),
        );
      }
    }
    // Tool-start changes reducer status. Reassert actual run state without inventing turn success.
    if (events.isNotEmpty &&
        next.messages.isNotEmpty &&
        (replace ||
            old?.running != next.running ||
            events.any((e) => e.kind == ChatEventKind.toolStarted))) {
      // Reassert on the row that can legitimately stream, never on a turn card
      // or a user bubble: those are closed records, not live output. When no
      // such row exists the real state is already published by the snapshot or
      // the tool events, so nothing is invented here.
      final streamable = next.messages.lastIndexWhere(
        (m) => m.role == ChatRole.assistant || m.role == ChatRole.system,
      );
      // No streamable row is fine while running (the activity row owns the
      // cursor); when the turn is over, the closing delta must still land so a
      // reducer that was told "streaming" returns to ready.
      final index = streamable >= 0
          ? streamable
          : (next.running ? -1 : next.messages.length - 1);
      if (index >= 0) {
        final message = next.messages[index];
        events.add(
          ChatEvent.delta(
            generation: generation,
            sequence: _sequence++,
            messageId: message.id,
            role: message.role,
            text: '',
            done: !next.running,
          ),
        );
      }
    }
    if (_interaction != null) events.add(_interactionEvent());
    _published = next;
    return events;
  }
}

const _protocol = DshRpcException(
  'session/protocol',
  'Invalid session journal response',
);
const _durableGap = DshRpcException(
  'session/sequence-gap',
  'Durable session sequence gap; reconnect for a complete snapshot',
);
const _assistantGap = DshRpcException(
  'session/assistant-gap',
  'Assistant presentation gap; reconnect for a complete snapshot',
);
Map<String, Object?>? _object(Object? value) =>
    value is Map<String, Object?> ? value : null;
Map<String, Object?> _requiredObject(Object? value) =>
    _object(value) ?? (throw _protocol);
String? _string(Object? value) =>
    value is String && value.isNotEmpty ? value : null;
int _natural(Object? value) {
  if (value is! int || value < 0) throw _protocol;
  return value;
}

int _cursor(Object? value) {
  if (value is! int || value < -1) throw _protocol;
  return value;
}

int _integerOr(Object? value) => value is int ? value : 0;

/// Codes whose payload can echo a credential, a masked key or an account token.
/// Their detail is reduced to the code itself: the details panel is selectable
/// and copyable, so it must not carry what [_failureText] already refuses to
/// repeat.
const _credentialFailureCodes = <String>{
  'MISSING_CREDENTIAL',
  'NO_CREDENTIAL_STORE',
  'INVALID_CREDENTIAL',
  'ACCOUNT_TOKEN_INVALID',
  'AUTH',
  'ACCOUNT_SIGN_IN_REQUIRED',
};

/// The expandable payload for a failure. Credential failures keep only their
/// code; every other cause keeps its full diagnostics so it stays debuggable.
String _failureDetail(Map<String, Object?> failure) {
  final code = _string(failure['code']);
  if (code != null && _credentialFailureCodes.contains(code)) {
    return jsonEncode({'code': code});
  }
  return jsonEncode(failure);
}

/// Names a known credential failure in the user's terms instead of echoing a
/// provider diagnostic, while leaving every other cause intact.
String _failureText(Map<String, Object?> failure) {
  final code = _string(failure['code']);
  final message = _string(failure['message']);
  switch (code) {
    case 'MISSING_CREDENTIAL':
    case 'NO_CREDENTIAL_STORE':
      return '模型尚未配置：当前模型提供方没有可用凭据。请登录账号或添加 API Key 后重试。';
    case 'INVALID_CREDENTIAL':
    case 'ACCOUNT_TOKEN_INVALID':
      return '凭据不可用：已保存的 API Key 或账号令牌无效。请在设置中更新后重试。';
    case 'AUTH':
    case 'ACCOUNT_SIGN_IN_REQUIRED':
      // Auth diagnostics may echo a masked credential, so never repeat them.
      return '账号未登录或授权已失效：请登录后重试。';
    case 'QUOTA':
    case 'ACCOUNT_QUOTA':
      return '额度已用完：' + (message ?? '请检查账号额度或余额后重试。');
    case 'RATE_LIMIT':
      return '请求过于频繁：' + (message ?? '请稍后重试。');
    case 'CONTEXT_WINDOW_EXCEEDED':
      return '上下文超出模型窗口：请压缩历史或新建会话后重试。';
    case 'MODEL_UNSUPPORTED':
    case 'UNKNOWN_MODEL':
      return '模型不可用：' + (message ?? '请重新选择模型后重试。');
  }
  if (code == null) return message ?? jsonEncode(failure);
  return message == null ? code : message + '（' + code + '）';
}

/// Terminal turn endings as structured cards, mirroring the DSH rules: a
/// recorded error, a sign-out cancellation and a crash-interrupted turn are
/// failures; a user or parent stop is only a stop; completions add nothing.
TurnNotice? _turnEndNotice(
  Map<String, Object?>? reason,
  Map<String, Object?>? data,
) {
  switch (reason?['kind']) {
    case 'error':
      final failure = _object(reason?['error']) ?? data ?? const {};
      return TurnNotice(
        kind: TurnNoticeKind.failed,
        message: _failureText(failure),
        code: _string(failure['code']),
        detail: _failureDetail(failure),
      );
    case 'aborted':
      final cause = _object(reason?['reason']);
      if (cause?['kind'] == 'hook' &&
          cause?['reason'] == 'deepseek-account/signed-out')
        return const TurnNotice(
          kind: TurnNoticeKind.signedOut,
          message: '账号已退出登录，这一轮已停止。',
          code: 'ACCOUNT_SIGNED_OUT',
        );
      return TurnNotice(
        kind: TurnNoticeKind.stopped,
        message: switch (cause?['kind']) {
          'parent' => '父会话结束了这一轮。',
          'user' => '你停止了这一轮。',
          _ => '这一轮已停止。',
        },
        detail: cause == null ? '' : jsonEncode(cause),
      );
    case 'interrupted':
      return const TurnNotice(
        kind: TurnNoticeKind.interrupted,
        message: 'DSH 进程在这一轮结束前退出。',
        code: 'INTERRUPTED',
      );
    case 'max-tokens':
      return const TurnNotice(
        kind: TurnNoticeKind.maxTokens,
        message: '已达到本次输出的令牌上限，可以继续输入。',
      );
    case 'blocked':
      return const TurnNotice(
        kind: TurnNoticeKind.blocked,
        message: '这一轮被阻止，没有继续执行。',
      );
    case 'forked':
      return const TurnNotice(
        kind: TurnNoticeKind.forked,
        message: '这一轮已在分叉点关闭。',
      );
    case 'completed':
      return null;
    default:
      // An ending this client does not know still gets a neutral row instead of
      // disappearing; the raw reason stays available in the details.
      final payload = reason ?? data;
      return TurnNotice(
        kind: TurnNoticeKind.other,
        message: '这一轮已结束（' + (_string(reason?['kind']) ?? 'unknown') + '）。',
        detail: payload == null ? '' : jsonEncode(payload),
      );
  }
}

Map<String, Object?> _event(Object? raw) {
  final record = _requiredObject(raw), event = _requiredObject(record['event']);
  if (record['type'] != 'event' || _string(event['type']) == null)
    throw _protocol;
  _natural(event['seq']);
  return event;
}

class _View {
  _View(this.messages, this.tools, this.running);
  final List<ChatMessage> messages;
  final List<ToolRun> tools;
  final bool running;
}

class _Attempt {
  _Attempt(this.id, this.startedAfterSeq, this.turn, this.step);
  final String id;
  final int startedAfterSeq, turn, step;
  int nextIndex = 0;
  int? settledSeq;
  bool ended = false;
  final _blocks = <int, Map<String, Object?>>{};
  final _unknown = <Map<String, Object?>>[];
  factory _Attempt.start(Map<String, Object?> frame) {
    final id = _string(frame['attemptId']);
    if (id == null) throw _protocol;
    return _Attempt(
      id,
      _cursor(frame['startedAfterSeq']),
      _natural(frame['turn']),
      _natural(frame['step']),
    );
  }
  factory _Attempt.baseline(Map<String, Object?> frame) {
    final attempt = _Attempt.start(frame), stream = frame['stream'];
    if (stream is! List) throw _protocol;
    final count = attempt.expand(stream);
    attempt.nextIndex = _natural(frame['nextIndex']);
    if (count != attempt.nextIndex) throw _assistantGap;
    return attempt;
  }
  bool matches(Map<String, Object?> event) {
    final data = _object(event['data']);
    return (event['type'] == 'assistant/message' ||
            event['type'] == 'assistant/attempt') &&
        (event['seq'] as int) > startedAfterSeq &&
        data?['turn'] == turn &&
        data?['step'] == step;
  }

  int expand(List<Object?> records) {
    var count = 0;
    for (final raw in records) {
      final record = _requiredObject(raw), type = record['type'];
      if (type == 'chunk') {
        chunk(record['chunk']);
        count++;
        continue;
      }
      if (type == 'batch') {
        final members = record['chunks'];
        if (members is! List) throw _protocol;
        for (final member in members) {
          chunk(_object(member)?['chunk'] ?? member);
          count++;
        }
        continue;
      }
      if (type != 'text-chunks' &&
          type != 'reasoning-chunks' &&
          type != 'tool-call-chunks' &&
          type != 'code-chunks') {
        _unknown.add(record);
        count++;
        continue;
      }
      final members = record[type == 'tool-call-chunks' ? 'args' : 'texts'];
      final gaps = record['dt'];
      if (members is! List ||
          members.isEmpty ||
          members.any((x) => x is! String) ||
          gaps is! List ||
          gaps.length != members.length - 1 ||
          gaps.any((x) => x is! int) ||
          record['time0'] is! int)
        throw _protocol;
      for (final member in members) {
        chunk({
          'type': type == 'text-chunks'
              ? 'text-delta'
              : type == 'reasoning-chunks'
              ? 'reasoning-delta'
              : type == 'code-chunks'
              ? 'code-delta'
              : 'tool-call-delta',
          'index': record['index'],
          if (type == 'tool-call-chunks') ...{
            'id': record['id'],
            if (record['name'] != null) 'name': record['name'],
            'argumentsDelta': member,
          } else
            'text': member,
          if (record['language'] != null) 'language': record['language'],
        });
        count++;
      }
    }
    return count;
  }

  void chunk(Object? raw) {
    final chunk = _requiredObject(raw), type = chunk['type'];
    if (_string(type) == null) throw _protocol;
    if (type == 'usage' || type == 'finish') return;
    if (type == 'batch') {
      final chunks = chunk['chunks'];
      if (chunks is! List) throw _protocol;
      for (final member in chunks) {
        this.chunk(member);
      }
      return;
    }
    if (chunk['index'] is! int) {
      _unknown.add(chunk);
      return;
    }
    final index = _natural(chunk['index']);
    switch (type) {
      case 'block-start':
        _blocks[index] = {'type': chunk['blockType'], 'text': ''};
      case 'text-delta':
      case 'reasoning-delta':
      case 'code-delta':
        final blockType = type == 'text-delta'
            ? 'text'
            : type == 'reasoning-delta'
            ? 'reasoning'
            : 'code';
        final text = chunk['text'] ?? chunk['code'];
        if (text is! String) throw _protocol;
        final old = _blocks[index];
        _blocks[index] = {
          'type': blockType,
          'text': (old?['text'] is String ? old!['text'] as String : '') + text,
          if (chunk['language'] != null) 'language': chunk['language'],
        };
      case 'tool-call-delta':
        final delta = chunk['argumentsDelta'];
        if (delta is! String) throw _protocol;
        final old = _blocks[index];
        _blocks[index] = {
          'type': 'tool-call',
          'id': chunk['id'] ?? old?['id'],
          'name': chunk['name'] ?? old?['name'],
          'arguments':
              (old?['arguments'] is String ? old!['arguments'] as String : '') +
              delta,
        };
      case 'block-end':
        _blocks[index] = _requiredObject(chunk['block']);
      default:
        _unknown.add(chunk);
    }
  }

  List<Map<String, Object?>> get content {
    final keys = _blocks.keys.toList()..sort();
    return [for (final key in keys) _blocks[key]!, ..._unknown];
  }
}

String _blocksText(Object? raw) {
  if (raw is! List) return raw == null ? '' : jsonEncode(raw);
  final parts = <String>[];
  final fence = String.fromCharCode(96) * 3;
  for (final value in raw) {
    final block = _object(value);
    if (block?['type'] == 'text' && block?['text'] is String) {
      parts.add(block!['text'] as String);
    } else if (block?['type'] == 'reasoning' && block?['text'] is String) {
      parts.add('思考：\n' + (block!['text'] as String) + '\n\n');
    } else if (block?['type'] == 'code') {
      final text = block?['code'] ?? block?['text'];
      if (text is String)
        parts.add(
          '\n' +
              fence +
              (_string(block?['language']) ?? '') +
              '\n' +
              text +
              '\n' +
              fence +
              '\n',
        );
      else {
        parts.add(jsonEncode(value));
      }
    } else {
      parts.add('\n' + jsonEncode(value) + '\n');
    }
  }
  return parts.join('');
}

String _resultDetail(Map<String, Object?> data) {
  final error = _object(data['error']);
  return _string(error?['reason']) ??
      _blocksText(_object(data['message'])?['content']);
}

ToolContent _toolContent(
  String name,
  Map<String, Object?>? call,
  Map<String, Object?>? result,
) {
  Map<String, Object?>? arguments;
  final rawArguments = call?['arguments'];
  if (rawArguments is String) {
    try {
      arguments = _object(jsonDecode(rawArguments));
    } on FormatException {
      /* Original text remains visible below. */
    }
  }
  final message = _object(result?['message']);
  final meta = _object(result?['meta']);
  final text = _blocksText(message?['content']);
  final reasoning = [
    if (rawArguments is String) '参数\n' + rawArguments,
    if (text.isNotEmpty) '结果\n' + text,
    if (result?['error'] != null) '错误\n' + jsonEncode(result!['error']),
    if (result?['meta'] != null) '元数据\n' + jsonEncode(result!['meta']),
  ].join('\n\n');
  final commandText = _string(arguments?['command']);
  final command = commandText == null
      ? null
      : CommandResult(
          command: commandText,
          cwd:
              _string(arguments?['workdir']) ??
              _string(arguments?['cwd']) ??
              '',
          stdout: meta?['stdout'] is String ? meta!['stdout'] as String : '',
          stderr: meta?['stderr'] is String ? meta!['stderr'] as String : '',
          exitCode: meta?['exitCode'] is int ? meta!['exitCode'] as int : null,
        );
  final edits = <CodeEdit>[];
  final path = _string(arguments?['file_path']) ?? _string(arguments?['path']);
  if (path != null &&
      (arguments?['old_string'] is String ||
          arguments?['new_string'] is String)) {
    edits.add(
      CodeEdit(
        path: path,
        before: arguments?['old_string'] as String? ?? '',
        after: arguments?['new_string'] as String? ?? '',
      ),
    );
  }
  final resources = <OutputResource>[];
  final content = message?['content'];
  if (content is List) {
    for (var i = 0; i < content.length; i++) {
      final block = _object(content[i]);
      final kind = switch (block?['type']) {
        'image' => ResourceKind.image,
        'file' => ResourceKind.file,
        'link' => ResourceKind.link,
        _ => null,
      };
      if (kind == null) continue;
      final attachment = _object(block?['attachment']);
      final uri =
          _string(block?['url']) ??
          _string(block?['uri']) ??
          _string(attachment?['url']) ??
          _string(attachment?['path']);
      if (uri == null) continue;
      resources.add(
        OutputResource(
          id: i.toString(),
          title:
              _string(block?['title']) ?? _string(attachment?['name']) ?? uri,
          uri: uri,
          kind: kind,
          mediaType: _string(attachment?['mediaType']) ?? '',
        ),
      );
    }
  }
  return ToolContent(
    title: name,
    reasoning: reasoning,
    command: command,
    edits: edits,
    resources: resources,
  );
}

String _toolSignature(ToolRun tool) {
  final content = tool.content, command = content?.command;
  return jsonEncode([
    tool.status.name,
    tool.detail,
    tool.elapsedMs,
    content?.title,
    content?.reasoning,
    if (command != null)
      [
        command.command,
        command.cwd,
        command.stdout,
        command.stderr,
        command.exitCode,
      ],
    [
      for (final s in content?.steps ?? <PlanStep>[])
        [s.id, s.title, s.status.name],
    ],
    [
      for (final a in content?.agents ?? <AgentTask>[])
        [a.id, a.name, a.task, a.status.name, a.detail, a.address?.toWire()],
    ],
    [
      for (final e in content?.edits ?? <CodeEdit>[])
        [e.path, e.before, e.after, e.unifiedDiff],
    ],
    [
      for (final r in content?.resources ?? <OutputResource>[])
        [r.id, r.title, r.uri, r.kind.name],
    ],
  ]);
}