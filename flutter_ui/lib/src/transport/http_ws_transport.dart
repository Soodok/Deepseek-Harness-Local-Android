import 'dart:convert';
import 'dart:io';

import '../model/chat_model.dart';
import 'dsh_transport.dart';

class HttpWsTransport implements DshTransport {
  HttpWsTransport({required this.webUrl});

  final String webUrl;
  final EventStreamController _events = EventStreamController();
  WebSocket? _socket;
  bool _connected = false;

  @override
  Stream<ChatEvent> get events => _events.stream;

  @override
  bool get isFixture => false;

  @override
  Future<void> connect() async {
    if (_connected) return;
    final uri = Uri.tryParse(webUrl);
    if (uri == null || uri.host.isEmpty) {
      throw const TransportException('engine web URL is invalid');
    }
    final wsUri = uri.replace(
      scheme: uri.scheme == 'https' ? 'wss' : 'ws',
      path: '/api/remote.mux',
    );
    try {
      _socket = await WebSocket.connect(wsUri.toString());
      _socket!.listen(
        _onSocketMessage,
        onDone: () {
          _connected = false;
          _events.add(const ChatEvent.disconnected(generation: 0, sequence: 0));
        },
        onError: (_) {
          _connected = false;
        },
      );
      _connected = true;
    } on Object catch (error) {
      throw TransportException(
        'engine websocket unavailable: ' + error.toString(),
      );
    }
  }

  @override
  Future<void> reconnect() async {
    await _socket?.close();
    _socket = null;
    _connected = false;
    await connect();
  }

  @override
  Future<void> sendPrompt(String text) =>
      _post('session/prompt', {'text': text});

  @override
  Future<void> stop() => _post('session/cancel', const {});

  @override
  Future<void> approve(String requestId) =>
      _post('session/approval', {'requestId': requestId, 'approved': true});

  @override
  Future<void> answer(String requestId, String answer) =>
      _post('session/question', {'requestId': requestId, 'answer': answer});

  @override
  Future<void> close() async {
    await _socket?.close();
    _socket = null;
    _connected = false;
    await _events.close();
  }

  Future<void> _post(String endpoint, Map<String, Object?> body) async {
    final uri = Uri.parse(webUrl).replace(path: '/api/' + endpoint);
    final client = HttpClient();
    try {
      final request = await client.postUrl(uri);
      request.headers.contentType = ContentType.json;
      request.add(utf8.encode(jsonEncode(body)));
      final response = await request.close();
      if (response.statusCode >= 400) {
        throw TransportException(
          'engine returned HTTP ' + response.statusCode.toString(),
        );
      }
    } on TransportException {
      rethrow;
    } on Object catch (error) {
      throw TransportException('engine request failed: ' + error.toString());
    } finally {
      client.close(force: true);
    }
  }

  void _onSocketMessage(Object? raw) {
    if (raw is! String) return;
    final decoded = jsonDecode(raw);
    if (decoded is! Map<String, Object?>) return;
    final event = decoded['event'];
    if (event is! Map<String, Object?>) return;
    final type = event['type'];
    if (type == 'turn/end') {
      _events.add(const ChatEvent.turnComplete(generation: 0, sequence: 0));
    }
  }
}
