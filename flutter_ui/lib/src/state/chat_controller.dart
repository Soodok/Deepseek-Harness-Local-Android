import 'dart:async';

import 'package:flutter/foundation.dart';

import '../model/chat_model.dart';
import '../transport/dsh_transport.dart';
import 'chat_reducer.dart';
import 'chat_state.dart';

class ChatController extends ChangeNotifier {
  ChatController(this.transport);

  final DshTransport transport;
  ChatState _state = ChatState.empty();
  StreamSubscription<ChatEvent>? _eventsSubscription;
  bool _started = false;
  bool _disposed = false;
  bool _resyncInFlight = false;

  ChatState get state => _state;

  bool get canSend =>
      _state.status == ChatStatus.ready ||
      _state.status == ChatStatus.streaming ||
      _state.status == ChatStatus.waitingAction;

  bool get canDispatchQueued => _state.status == ChatStatus.ready;

  Future<void> start() async {
    if (_started || _disposed) return;
    _started = true;
    _eventsSubscription = transport.events.listen(
      _onEvent,
      onError: (Object error, StackTrace stack) => _setError(error.toString()),
    );
    _setState(_state.copyWith(status: ChatStatus.connecting, clearError: true));
    try {
      await transport.connect();
    } on Object catch (error) {
      _setError(error.toString());
    }
  }

  Future<void> sendPrompt(String text) async {
    final prompt = text.trim();
    if (prompt.isEmpty || _disposed) return;
    if (_state.status == ChatStatus.streaming ||
        _state.status == ChatStatus.waitingAction) {
      await queuePrompt(prompt);
      return;
    }
    try {
      await transport.sendPrompt(prompt);
    } on Object catch (error) {
      _setError(error.toString());
    }
  }

  Future<void> queuePrompt(String text) async {
    final prompt = text.trim();
    if (prompt.isEmpty || _disposed) return;
    _setState(
      _state.copyWith(queuedPrompts: [..._state.queuedPrompts, prompt]),
    );
  }

  Future<void> sendNextQueued() async {
    if (_state.queuedPrompts.isEmpty || !canDispatchQueued || _disposed) return;
    final prompt = _state.queuedPrompts.first;
    _setState(_state.copyWith(queuedPrompts: _state.queuedPrompts.sublist(1)));
    await sendPrompt(prompt);
  }

  Future<void> stop() async {
    if (_disposed) return;
    try {
      await transport.stop();
    } on Object catch (error) {
      _setError(error.toString());
    }
  }

  Future<void> approve(String requestId) async {
    if (_disposed) return;
    try {
      await transport.approve(requestId);
    } on Object catch (error) {
      _setError(error.toString());
    }
  }

  Future<void> answer(String requestId, String answer) async {
    if (_disposed) return;
    final value = answer.trim();
    if (value.isEmpty) return;
    try {
      await transport.answer(requestId, value);
    } on Object catch (error) {
      _setError(error.toString());
    }
  }

  Future<void> reconnect() async {
    if (_disposed || _resyncInFlight) return;
    _setState(
      _state.copyWith(status: ChatStatus.reconnecting, clearError: true),
    );
    try {
      await transport.reconnect();
    } on Object catch (error) {
      _setError(error.toString());
    }
  }

  void _onEvent(ChatEvent event) {
    final next = ChatReducer.apply(_state, event);
    if (identical(next, _state)) return;
    _setState(next);
    if (next.needsResync) unawaited(_resync());
  }

  Future<void> _resync() async {
    if (_resyncInFlight || _disposed) return;
    _resyncInFlight = true;
    try {
      await transport.reconnect();
    } on Object catch (error) {
      _setError(error.toString());
    } finally {
      _resyncInFlight = false;
    }
  }

  void _setError(String message) {
    _setState(_state.copyWith(status: ChatStatus.error, error: message));
  }

  void _setState(ChatState next) {
    if (_disposed) return;
    _state = next;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    unawaited(_eventsSubscription?.cancel());
    unawaited(transport.close());
    super.dispose();
  }
}
