import 'dart:async';
import 'dart:convert';

import '../model/chat_model.dart';
import 'dsh_rpc_connection.dart';

/// Wire vocabulary from dsh-user-approval/src/types.ts.
enum NativeApprovalDecision { allowOnce, reject, cancel }

enum NativeInteractionKind { approval, question, unsupported }

/// [NativeInteractionStatus.delivered] means only that the result RPC completed.
/// The gateway also acknowledges obsolete deliveries; this is not proof that
/// the tool ran, a grant was persisted, or an answer entered the Session log.
enum NativeInteractionStatus {
  pending,
  submitting,
  delivered,
  delegated,
  cancelled,
  disconnected,
}

class NativeQuestionOption {
  const NativeQuestionOption({required this.label, this.description});

  final String label;
  final String? description;
}

class NativeQuestion {
  NativeQuestion._({
    required this.id,
    required this.question,
    required this.options,
    required this.multiSelect,
    this.header,
    this.detail,
  });

  final String id;
  final String question;
  final String? header;
  final String? detail;
  final List<NativeQuestionOption> options;
  final bool multiSelect;
}

/// An explicit answer to the one supported question, without text heuristics.
/// Empty selected and absent custom explicitly skip that question.
class NativeQuestionReply {
  NativeQuestionReply({
    required this.questionId,
    List<String> selected = const [],
    this.custom,
  }) : selected = List<String>.unmodifiable(selected);

  final String questionId;
  final List<String> selected;
  final String? custom;

  Map<String, Object?> toWire() => {
    'id': questionId,
    'selected': selected,
    if (custom != null) 'custom': custom,
  };
}

/// Immutable view of one event delivery. requestId is local and generation-bound;
/// eventId and clientId are the Host correlation identities, never UI ids.
class NativeInteraction {
  const NativeInteraction._({
    required this.requestId,
    required this.eventId,
    required this.clientId,
    required this.generation,
    required this.agentId,
    required this.event,
    required this.kind,
    this.status = NativeInteractionStatus.pending,
    this.action,
    this.question,
    this.toolCallId,
    this.unsupportedReason,
    this.textDraft,
    this.answerDraft,
    this.approvalDraft,
    this.error,
    this.errorCode,
  });

  final String requestId;
  final String eventId;
  final String clientId;
  final int generation;
  final String agentId;
  final String event;
  final NativeInteractionKind kind;
  final NativeInteractionStatus status;
  final ActionRequest? action;
  final NativeQuestion? question;
  final String? toolCallId;
  final String? unsupportedReason;
  final String? textDraft;
  final NativeQuestionReply? answerDraft;
  final NativeApprovalDecision? approvalDraft;
  final String? error;
  final String? errorCode;

  bool get isPending =>
      status == NativeInteractionStatus.pending ||
      status == NativeInteractionStatus.submitting;

  NativeInteraction _copy({
    NativeInteractionStatus? status,
    String? textDraft,
    NativeQuestionReply? answerDraft,
    NativeApprovalDecision? approvalDraft,
    String? error,
    String? errorCode,
    bool clearError = false,
    bool clearDraft = false,
    bool clearTextDraft = false,
    bool clearAnswerDraft = false,
  }) => NativeInteraction._(
    requestId: requestId,
    eventId: eventId,
    clientId: clientId,
    generation: generation,
    agentId: agentId,
    event: event,
    kind: kind,
    status: status ?? this.status,
    action: action,
    question: question,
    toolCallId: toolCallId,
    unsupportedReason: unsupportedReason,
    textDraft: clearDraft || clearTextDraft
        ? null
        : textDraft ?? this.textDraft,
    answerDraft: clearDraft || clearAnswerDraft
        ? null
        : answerDraft ?? this.answerDraft,
    approvalDraft: clearDraft ? null : approvalDraft ?? this.approvalDraft,
    error: clearError ? null : error ?? this.error,
    errorCode: clearError ? null : errorCode ?? this.errorCode,
  );
}

class NativeInteractionsState {
  NativeInteractionsState._({
    required this.generation,
    required this.ready,
    required this.closed,
    required Iterable<NativeInteraction> interactions,
    this.clientId,
    this.hostHome,
    this.error,
    this.errorCode,
  }) : interactions = List<NativeInteraction>.unmodifiable(interactions);

  final int generation;
  final bool ready;
  final bool closed;
  final String? clientId;
  final String? hostHome;
  final String? error;
  final String? errorCode;

  /// Includes withdrawn/unsupported entries so consumers can remove cards and
  /// retain drafts. Only [pending] should be projected to actionable ChatEvents.
  final List<NativeInteraction> interactions;

  List<NativeInteraction> get pending => List<NativeInteraction>.unmodifiable(
    interactions.where(
      (item) =>
          item.isPending && item.kind != NativeInteractionKind.unsupported,
    ),
  );
}

/// One selected parent's Remote Event answerer, borrowing an owner RPC whose
/// Peer was authenticated with interactions=answer. This service owns only its
/// events subscription; close() never closes the shared DshRpcConnection.
///
/// Protocol sources: dsh-api-gateway/src/stream-protocol.ts and
/// client/remote-events.ts; dsh-user-approval/src/types.ts and
/// dsh-user-questions/src/types.ts. In particular, frame.agentId is the scoped
/// identity. frame.request must contain neither agent nor signal.
///
/// The caller subscribes to updates, connects the RPC, and awaits start(). A
/// reconnect invalidates every old requestId; the Host replays outstanding
/// deliveries under its fresh clientId. No automatic reconnect or resubmission
/// sends a human decision. Invoke delegate() for explicit Web hand-off.
class NativeInteractions {
  NativeInteractions({required this.rpc, required this.sessionId}) {
    if (sessionId.trim().isEmpty) {
      throw const DshRpcException(
        'interactions/invalid-address',
        'A selected parent Session is required',
      );
    }
    _publish();
  }

  final DshRpcConnection rpc;
  final String sessionId;
  static int _nextOwner = 0;
  final int _owner = ++_nextOwner;
  final _updates = StreamController<NativeInteractionsState>.broadcast();
  final _entries = <String, _Entry>{};
  final _byEvent = <String, _Entry>{};
  StreamSubscription<Map<String, Object?>>? _subscription;
  Completer<void>? _opening;
  Future<void>? _closing;
  Timer? _readyTimer;
  int _generation = 0;
  bool _active = false;
  bool _ready = false;
  bool _closed = false;
  String? _clientId;
  String? _home;
  String? _error;
  String? _errorCode;
  late NativeInteractionsState _state;

  NativeInteractionsState get state => _state;

  /// Broadcast updates are full immutable snapshots; read state for the initial
  /// value. They deliberately contain no ChatEvent sequence numbers.
  Stream<NativeInteractionsState> get updates => _updates.stream;

  Future<void> start() {
    if (_closed) return Future<void>.error(_closedError);
    if (_ready) return Future<void>.value();
    final opening = _opening;
    if (opening != null) return opening.future;
    return _begin();
  }

  Future<void> reconnect() {
    if (_closed) return Future<void>.error(_closedError);
    return _begin();
  }

  Future<void> _begin() {
    final old = _subscription;
    _subscription = null;
    final previous = _opening;
    if (previous != null && !previous.isCompleted) {
      previous.completeError(_staleError);
    }
    _readyTimer?.cancel();
    _interrupt(_staleError);
    final generation = ++_generation;
    _active = true;
    _ready = false;
    _clientId = null;
    _home = null;
    _error = null;
    _errorCode = null;
    _byEvent.clear();
    final opening = Completer<void>();
    _opening = opening;
    _publish();
    unawaited(() async {
      try {
        await old?.cancel();
        if (!_current(generation)) return;
        _readyTimer = Timer(const Duration(seconds: 20), () {
          _fail(
            generation,
            const DshRpcException(
              'interactions/ready-timeout',
              'Remote event stream did not become ready',
            ),
          );
        });
        final subscription = rpc
            .follow(r'$events', <String, Object?>{})
            .listen(
              (frame) => _receive(generation, frame),
              onError: (Object error) => _fail(generation, _asError(error)),
              onDone: () => _fail(
                generation,
                const DshRpcException(
                  'interactions/disconnected',
                  'Remote event stream ended',
                ),
              ),
            );
        // A synchronous source may fail or be closed by a listener during listen.
        if (_current(generation)) {
          _subscription = subscription;
        } else {
          unawaited(subscription.cancel());
        }
      } on Object catch (error) {
        _fail(generation, _asError(error));
      }
    }());
    return opening.future;
  }

  void _receive(int generation, Map<String, Object?> frame) {
    if (!_current(generation)) return;
    try {
      if (!_ready) {
        final host = _map(frame['host']);
        if (!_keys(frame, ['type', 'clientId', 'host']) ||
            frame['type'] != 'ready' ||
            !_id(frame['clientId']) ||
            host == null ||
            !_keys(host, ['home']) ||
            host['home'] is! String) {
          throw _protocolError;
        }
        _clientId = frame['clientId'] as String;
        _home = host['home'] as String;
        _ready = true;
        _readyTimer?.cancel();
        _readyTimer = null;
        final opening = _opening;
        _opening = null;
        _publish();
        if (opening != null && !opening.isCompleted) opening.complete();
        return;
      }
      switch (frame['type']) {
        case 'cancel':
          if (!_keys(frame, ['type', 'eventId']) || !_id(frame['eventId'])) {
            throw _protocolError;
          }
          final entry = _byEvent[frame['eventId']];
          if (entry != null && entry.view.isPending) {
            entry.view = entry.view._copy(
              status: NativeInteractionStatus.cancelled,
            );
            _withdraw(entry, _cancelledError);
            _publish();
          }
          return;
        case 'emit':
          if (!_keys(frame, ['type', 'event', 'args']) ||
              !_id(frame['event']) ||
              frame['args'] is! List ||
              !_json(frame['args'])) {
            throw _protocolError;
          }
          // Notifications do not await a result; Session projections own them.
          return;
        case 'waterfall':
          if (!_keys(frame, [
                'type',
                'event',
                'eventId',
                'agentId',
                'request',
              ]) ||
              !_id(frame['event']) ||
              !_id(frame['eventId']) ||
              !_id(frame['agentId'])) {
            throw _protocolError;
          }
          final request = _map(frame['request']);
          if (request == null ||
              request.containsKey('agent') ||
              request.containsKey('signal') ||
              !_json(request)) {
            throw _protocolError;
          }
          _waterfall(frame, request);
          return;
        default:
          throw _protocolError;
      }
    } on Object catch (error) {
      _fail(generation, _asError(error));
    }
  }

  void _waterfall(Map<String, Object?> frame, Map<String, Object?> request) {
    final eventId = frame['eventId'] as String;
    final existing = _byEvent[eventId];
    if (existing != null) {
      if (!_sameJson(existing.frame, frame)) throw _protocolError;
      return;
    }
    final requestId = 'native-interaction-$_owner:$_generation:$eventId';
    final agentId = frame['agentId'] as String;
    final event = frame['event'] as String;
    ActionRequest? action;
    NativeQuestion? question;
    String? callId;
    String? unsupported;
    var kind = NativeInteractionKind.unsupported;
    if (agentId != sessionId) {
      unsupported =
          'Remote event belongs to another Agent; delegated to DSH Web';
    } else {
      try {
        if (event == 'approval/request') {
          action = _approvalAction(requestId, request);
          callId = request['callId'] as String?;
          kind = NativeInteractionKind.approval;
        } else if (event == 'user-questions/request') {
          question = _parseQuestion(request);
          final wait = _map(request['wait']);
          callId = wait?['callId'] as String?;
          action = _questionAction(
            requestId,
            question,
            draftKey: jsonEncode([agentId, eventId, _canonicalJson(request)]),
          );
          kind = NativeInteractionKind.question;
        } else {
          unsupported = 'Unsupported Remote event $event; delegated to DSH Web';
        }
      } on _Unsupported catch (error) {
        unsupported = error.message;
      }
    }
    final view = NativeInteraction._(
      requestId: requestId,
      eventId: eventId,
      clientId: _clientId!,
      generation: _generation,
      agentId: agentId,
      event: event,
      kind: kind,
      action: action,
      question: question,
      toolCallId: callId,
      unsupportedReason: unsupported,
    );
    final entry = _Entry(view, _freezeMap(frame));
    _entries[requestId] = entry;
    _byEvent[eventId] = entry;
    _publish();
    if (kind == NativeInteractionKind.unsupported) {
      // Contain reply failures in the immutable state; never auto-retry them.
      unawaited(
        _send(entry, const {
          'kind': 'next',
        }, NativeInteractionStatus.delegated).catchError((Object _) {}),
      );
    }
  }

  Future<void> approve(String requestId) =>
      decideApproval(requestId, NativeApprovalDecision.allowOnce);

  Future<void> decideApproval(
    String requestId,
    NativeApprovalDecision decision,
  ) => _attempt(() {
    final entry = _require(requestId, NativeInteractionKind.approval);
    if (entry.inFlight == null) {
      entry.view = entry.view._copy(approvalDraft: decision, clearError: true);
      entry.draftRevision++;
    }
    final value = switch (decision) {
      NativeApprovalDecision.allowOnce => 'allowed-once',
      NativeApprovalDecision.reject => 'rejected',
      NativeApprovalDecision.cancel => 'cancelled',
    };
    return _send(entry, {
      'kind': 'result',
      'value': value,
    }, NativeInteractionStatus.delivered);
  });

  /// Compatibility with the current card: no-options text becomes custom;
  /// offered labels must be exact and in display order, joined by one newline.
  /// A custom answer alongside options needs answerStructured(), since the
  /// current text callback has already discarded the selection boundaries.
  Future<void> answer(String requestId, String text) => _attempt(() {
    final entry = _require(requestId, NativeInteractionKind.question);
    if (entry.inFlight == null) _textDraft(entry, text);
    try {
      final reply = _decodeText(entry.view.question!, text);
      return _answer(entry, reply);
    } on DshRpcException catch (error) {
      if (entry.inFlight == null) _recordError(entry, error);
      rethrow;
    }
  });

  Future<void> answerStructured(String requestId, NativeQuestionReply reply) =>
      _attempt(() {
        final entry = _require(requestId, NativeInteractionKind.question);
        return _answer(entry, reply, clearTextDraft: true);
      });

  Future<void> _answer(
    _Entry entry,
    NativeQuestionReply reply, {
    bool clearTextDraft = false,
  }) {
    try {
      _validateAnswer(entry.view.question!, reply);
    } on DshRpcException catch (error) {
      if (entry.inFlight == null) _recordError(entry, error);
      rethrow;
    }
    if (entry.inFlight == null) {
      entry.view = entry.view._copy(
        answerDraft: reply,
        clearTextDraft: clearTextDraft,
        clearError: true,
      );
      entry.draftRevision++;
    }
    return _send(entry, {
      'kind': 'result',
      'value': {
        'answers': [reply.toWire()],
      },
    }, NativeInteractionStatus.delivered);
  }

  /// Local draft edits may continue while a receipt is pending. The receipt
  /// clears only the revision actually submitted, never a newer edit.
  void setTextDraft(String requestId, String text) {
    _textDraft(_require(requestId, NativeInteractionKind.question), text);
  }

  void _textDraft(_Entry entry, String text) {
    entry.view = entry.view._copy(
      textDraft: text,
      clearAnswerDraft: true,
      clearError: true,
    );
    entry.draftRevision++;
    _publish();
  }

  void setAnswerDraft(String requestId, NativeQuestionReply reply) {
    final entry = _require(requestId, NativeInteractionKind.question);
    _validateAnswer(entry.view.question!, reply);
    entry.view = entry.view._copy(
      answerDraft: reply,
      clearTextDraft: true,
      clearError: true,
    );
    entry.draftRevision++;
    _publish();
  }

  /// Explicitly leave this Client's waterfall without granting or answering it.
  Future<void> delegate(String requestId) => _attempt(
    () => _send(_require(requestId), const {
      'kind': 'next',
    }, NativeInteractionStatus.delegated),
  );

  /// Human cancellation is an outcome, unlike a Host cancel notification.
  Future<void> cancel(String requestId) => _attempt(() {
    final entry = _require(requestId);
    if (entry.view.kind == NativeInteractionKind.approval) {
      return decideApproval(requestId, NativeApprovalDecision.cancel);
    }
    if (entry.view.kind != NativeInteractionKind.question) {
      return delegate(requestId);
    }
    return _send(entry, const {
      'kind': 'rejected',
      'error': {
        'name': 'UserQuestionError',
        'message': 'the user cancelled ask_user_question',
        'code': 'ASK_CANCELLED',
      },
    }, NativeInteractionStatus.delivered);
  });

  _Entry _require(String requestId, [NativeInteractionKind? kind]) {
    if (_closed) throw _closedError;
    final entry = _entries[requestId];
    if (entry == null) {
      throw const DshRpcException(
        'interactions/unknown-request',
        'Unknown interaction identity',
      );
    }
    if (entry.view.generation != _generation) throw _staleError;
    if (entry.view.status == NativeInteractionStatus.cancelled)
      throw _cancelledError;
    if (!_ready || !_active) {
      throw const DshRpcException(
        'interactions/disconnected',
        'Remote event stream is not ready',
      );
    }
    if (!entry.view.isPending) {
      throw const DshRpcException(
        'interactions/not-pending',
        'Interaction is no longer pending',
      );
    }
    if (kind != null && entry.view.kind != kind) {
      throw const DshRpcException(
        'interactions/wrong-kind',
        'Wrong reply type for this interaction',
      );
    }
    return entry;
  }

  Future<void> _send(
    _Entry entry,
    Map<String, Object?> outcome,
    NativeInteractionStatus delivered,
  ) {
    final active = entry.inFlight;
    if (active != null) {
      if (_sameJson(entry.outcome, outcome)) return active;
      return Future<void>.error(
        const DshRpcException(
          'interactions/submission-busy',
          'A different result is already being submitted',
        ),
      );
    }
    final generation = entry.view.generation;
    final revision = entry.draftRevision;
    final completion = Completer<void>();
    entry.inFlight = completion.future;
    entry.completion = completion;
    entry.outcome = _freezeMap(outcome);
    entry.view = entry.view._copy(
      status: NativeInteractionStatus.submitting,
      clearError: true,
    );
    _publish();
    unawaited(() async {
      try {
        _checkDelivery(entry, generation);
        final response = await rpc.call(r'$events/result', {
          'clientId': entry.view.clientId,
          'eventId': entry.view.eventId,
          'outcome': entry.outcome,
        });
        _checkDelivery(entry, generation);
        // Gateway dispatchRpc returns {ok:true,value:undefined}; a synthetic
        // {accepted:true} is not an event-result acknowledgement contract.
        if (response != null) throw _protocolError;
        entry.view = entry.view._copy(
          status: delivered,
          clearDraft: revision == entry.draftRevision,
          clearError: true,
        );
        _publish();
        if (!completion.isCompleted) completion.complete();
      } on Object catch (error, stack) {
        final failure = _asError(error);
        if (_current(generation) && entry.view.isPending) {
          entry.view = entry.view._copy(
            status: NativeInteractionStatus.pending,
            error: failure.message,
            errorCode: failure.code,
          );
          _publish();
        }
        if (!completion.isCompleted) completion.completeError(failure, stack);
      } finally {
        entry.inFlight = null;
        entry.completion = null;
        entry.outcome = null;
      }
    }());
    return completion.future;
  }

  void _checkDelivery(_Entry entry, int generation) {
    if (_closed) throw _closedError;
    if (entry.view.status == NativeInteractionStatus.cancelled)
      throw _cancelledError;
    if (!_current(generation) || !_ready || _clientId != entry.view.clientId) {
      throw _staleError;
    }
  }

  void _recordError(_Entry entry, DshRpcException error) {
    entry.view = entry.view._copy(error: error.message, errorCode: error.code);
    _publish();
  }

  bool _current(int generation) =>
      !_closed && _active && generation == _generation;

  void _withdraw(_Entry entry, DshRpcException error) {
    final completion = entry.completion;
    if (completion != null && !completion.isCompleted)
      completion.completeError(error);
  }

  void _interrupt(DshRpcException error) {
    for (final entry in _entries.values) {
      if (entry.view.isPending) {
        entry.view = entry.view._copy(
          status: NativeInteractionStatus.disconnected,
          error: error.message,
          errorCode: error.code,
        );
        _withdraw(entry, error);
      }
    }
  }

  void _fail(int generation, DshRpcException error) {
    if (!_current(generation)) return;
    _active = false;
    _ready = false;
    _readyTimer?.cancel();
    _readyTimer = null;
    _error = error.message;
    _errorCode = error.code;
    _interrupt(error);
    final subscription = _subscription;
    _subscription = null;
    if (subscription != null) unawaited(subscription.cancel());
    final opening = _opening;
    _opening = null;
    _publish();
    if (opening != null && !opening.isCompleted) opening.completeError(error);
  }

  void _publish() {
    _state = NativeInteractionsState._(
      generation: _generation,
      ready: _ready,
      closed: _closed,
      clientId: _clientId,
      hostHome: _home,
      error: _error,
      errorCode: _errorCode,
      interactions: _entries.values.map((entry) => entry.view),
    );
    if (!_updates.isClosed) _updates.add(_state);
  }

  Future<void> close() {
    final closing = _closing;
    if (closing != null) return closing;
    _closed = true;
    _active = false;
    _ready = false;
    _readyTimer?.cancel();
    _readyTimer = null;
    _interrupt(_closedError);
    final opening = _opening;
    _opening = null;
    if (opening != null && !opening.isCompleted)
      opening.completeError(_closedError);
    final subscription = _subscription;
    _subscription = null;
    _publish();
    _closing = () async {
      await subscription?.cancel();
      unawaited(_updates.close());
    }();
    return _closing!;
  }
}

class _Entry {
  _Entry(this.view, this.frame);

  NativeInteraction view;
  final Map<String, Object?> frame;
  int draftRevision = 0;
  Future<void>? inFlight;
  Completer<void>? completion;
  Map<String, Object?>? outcome;
}

class _Unsupported implements Exception {
  const _Unsupported(this.message);
  final String message;
}

ActionRequest _approvalAction(String requestId, Map<String, Object?> request) {
  if (!_keys(request, ['toolName'], ['callId', 'reason', 'displayReason']) ||
      !_id(request['toolName']) ||
      !_optional(request, 'callId', _id) ||
      !_optional(request, 'reason', (value) => value is String)) {
    throw const _Unsupported(
      'Unsupported approval request schema; use DSH Web',
    );
  }
  final reasons = <String>[];
  if (request.containsKey('reason')) reasons.add(request['reason'] as String);
  if (request.containsKey('displayReason')) {
    final display = _map(request['displayReason']);
    if (display == null ||
        display['en'] is! String ||
        !display.values.every((value) => value is String)) {
      throw const _Unsupported(
        'Unsupported localized approval reason; use DSH Web',
      );
    }
    final english = display['en'] as String;
    if (!reasons.contains(english)) reasons.add(english);
  }
  if (request['callId'] != null)
    reasons.add('Tool call: ' + (request['callId'] as String));
  return ActionRequest(
    requestId: requestId,
    title: 'Approval: ' + (request['toolName'] as String),
    detail: reasons.join('\n\n'),
  );
}

NativeQuestion _parseQuestion(Map<String, Object?> request) {
  if (!_keys(request, ['questions'], ['wait'])) {
    throw const _Unsupported(
      'Unsupported question request schema; use DSH Web',
    );
  }
  if (request.containsKey('wait')) {
    final wait = _map(request['wait']);
    if (wait == null ||
        !_keys(wait, ['callId'], ['timed']) ||
        !_id(wait['callId']) ||
        !_optional(wait, 'timed', (value) => value is bool)) {
      throw const _Unsupported('Unsupported question wait schema; use DSH Web');
    }
    if (wait['timed'] == true) {
      throw const _Unsupported(
        'Timed questions require a foreground wait claim; use DSH Web',
      );
    }
  }
  final questions = request['questions'];
  if (questions is! List || questions.length != 1) {
    throw const _Unsupported(
      'Native cards require exactly one question; use DSH Web',
    );
  }
  final item = _map(questions.single);
  if (item == null ||
      !_keys(
        item,
        ['id', 'question'],
        ['header', 'detail', 'options', 'multiSelect', 'intent'],
      ) ||
      !_id(item['id']) ||
      item['question'] is! String ||
      !_optional(item, 'header', (value) => value is String) ||
      !_optional(item, 'detail', (value) => value is String) ||
      !_optional(item, 'multiSelect', (value) => value is bool)) {
    throw const _Unsupported('Unsupported question item schema; use DSH Web');
  }
  if (item.containsKey('intent')) {
    throw const _Unsupported(
      'Question intents need a dedicated decision card; use DSH Web',
    );
  }
  final options = <NativeQuestionOption>[];
  final labels = <String>{};
  if (item.containsKey('options')) {
    final offered = item['options'];
    if (offered is! List) {
      throw const _Unsupported('Unsupported question options; use DSH Web');
    }
    for (final raw in offered) {
      final option = _map(raw);
      if (option == null ||
          !_keys(option, ['label'], ['description']) ||
          !_id(option['label']) ||
          !_optional(option, 'description', (value) => value is String)) {
        throw const _Unsupported(
          'Unsupported question option schema; use DSH Web',
        );
      }
      final label = option['label'] as String;
      // Current QuestionCard trims labels and flattens selected labels with
      // newlines. Only this subset has a reversible compatibility encoding.
      if (label.trim() != label ||
          label.contains('\n') ||
          label.contains('\r') ||
          !labels.add(label)) {
        throw const _Unsupported(
          'Option labels cannot round-trip through this card; use DSH Web',
        );
      }
      options.add(
        NativeQuestionOption(
          label: label,
          description: option['description'] as String?,
        ),
      );
    }
  }
  return NativeQuestion._(
    id: item['id'] as String,
    question: item['question'] as String,
    header: item['header'] as String?,
    detail: item['detail'] as String?,
    options: List<NativeQuestionOption>.unmodifiable(options),
    multiSelect: item['multiSelect'] == true,
  );
}

Object? _canonicalJson(Object? value) {
  if (value is List) return value.map(_canonicalJson).toList(growable: false);
  if (value is Map<String, Object?>) {
    final keys = value.keys.toList()..sort();
    return {for (final key in keys) key: _canonicalJson(value[key])};
  }
  return value;
}

ActionRequest _questionAction(
  String requestId,
  NativeQuestion question, {
  required String draftKey,
}) {
  final details = <String>[
    question.question,
    if (question.detail != null) question.detail!,
    for (final option in question.options)
      if (option.description != null) option.label + ': ' + option.description!,
  ];
  return ActionRequest(
    requestId: requestId,
    title: question.header?.isNotEmpty == true
        ? question.header!
        : question.question,
    detail: details.join('\n\n'),
    choices: List<String>.unmodifiable(
      question.options.map((option) => option.label),
    ),
    multiSelect: question.multiSelect,
    draftKey: draftKey,
  );
}

NativeQuestionReply _decodeText(NativeQuestion question, String text) {
  if (question.options.isEmpty) {
    return NativeQuestionReply(questionId: question.id, custom: text);
  }
  final parts = text.split('\n');
  final selected = <String>[
    for (final option in question.options)
      if (parts.contains(option.label)) option.label,
  ];
  if (selected.isEmpty ||
      selected.join('\n') != text ||
      (!question.multiSelect && selected.length != 1)) {
    throw const DshRpcException(
      'interactions/structured-answer-required',
      'Text with options is ambiguous; provide selected labels and custom text separately',
    );
  }
  return NativeQuestionReply(questionId: question.id, selected: selected);
}

void _validateAnswer(NativeQuestion question, NativeQuestionReply reply) {
  final labels = question.options.map((option) => option.label).toSet();
  if (reply.questionId != question.id ||
      reply.selected.toSet().length != reply.selected.length ||
      !reply.selected.every(labels.contains) ||
      (!question.multiSelect && reply.selected.length > 1) ||
      (!question.multiSelect &&
          reply.selected.isNotEmpty &&
          reply.custom != null &&
          reply.custom!.isNotEmpty)) {
    throw const DshRpcException(
      'interactions/invalid-answer',
      'Reply does not match the offered question',
    );
  }
}

Future<void> _attempt(Future<void> Function() operation) {
  try {
    return operation();
  } on Object catch (error, stack) {
    return Future<void>.error(error, stack);
  }
}

bool _keys(
  Map<String, Object?> value,
  List<String> required, [
  List<String> optional = const [],
]) =>
    required.every(value.containsKey) &&
    value.keys.every((key) => required.contains(key) || optional.contains(key));

bool _optional(
  Map<String, Object?> value,
  String key,
  bool Function(Object?) test,
) => !value.containsKey(key) || test(value[key]);

bool _id(Object? value) => value is String && value.isNotEmpty;
Map<String, Object?>? _map(Object? value) =>
    value is Map<String, Object?> ? value : null;

bool _json(Object? value) {
  if (value == null || value is String || value is bool || value is int)
    return true;
  if (value is double) return value.isFinite;
  if (value is List) return value.every(_json);
  final map = _map(value);
  return map != null && map.values.every(_json);
}

Map<String, Object?> _freezeMap(Map<String, Object?> value) =>
    Map<String, Object?>.unmodifiable(
      value.map((key, item) => MapEntry(key, _freeze(item))),
    );
Object? _freeze(Object? value) {
  if (value is List) return List<Object?>.unmodifiable(value.map(_freeze));
  final map = _map(value);
  return map == null ? value : _freezeMap(map);
}

bool _sameJson(Object? a, Object? b) {
  if (a is Map && b is Map) {
    return a.length == b.length &&
        a.keys.every((key) => b.containsKey(key) && _sameJson(a[key], b[key]));
  }
  if (a is List && b is List) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (!_sameJson(a[i], b[i])) return false;
    }
    return true;
  }
  return a == b;
}

DshRpcException _asError(Object error) => error is DshRpcException
    ? error
    : DshRpcException('interactions/failure', error.toString());

const _protocolError = DshRpcException(
  'interactions/protocol',
  'Invalid forwarded Remote event protocol',
);
const _closedError = DshRpcException(
  'interactions/closed',
  'Native interactions are closed',
);
const _staleError = DshRpcException(
  'interactions/stale-generation',
  'Interaction belongs to an old event-stream generation',
);
const _cancelledError = DshRpcException(
  'interactions/cancelled',
  'Remote event was cancelled by the Host',
);
