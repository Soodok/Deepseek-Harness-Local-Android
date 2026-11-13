import 'dart:async';

import '../model/chat_model.dart';

abstract interface class DshTransport {
  Stream<ChatEvent> get events;
  bool get isFixture;

  Future<void> connect();
  Future<void> reconnect();
  Future<void> sendPrompt(String text);
  Future<void> stop();
  Future<void> approve(String requestId);
  Future<void> answer(String requestId, String answer);
  Future<void> close();
}

class TransportException implements Exception {
  const TransportException(this.message);

  final String message;

  @override
  String toString() => 'TransportException: ' + message;
}

class EventStreamController {
  EventStreamController() : _controller = StreamController<ChatEvent>();

  final StreamController<ChatEvent> _controller;

  Stream<ChatEvent> get stream => _controller.stream;

  void add(ChatEvent event) => _controller.add(event);

  Future<void> close() {
    unawaited(_controller.close());
    return Future<void>.value();
  }
}
