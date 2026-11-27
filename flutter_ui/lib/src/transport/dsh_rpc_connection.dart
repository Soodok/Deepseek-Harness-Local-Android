import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'rpc_multipart.dart';

class DshRpcException implements Exception {
  const DshRpcException(this.code, this.message, [this.details = const {}]);
  final String code;
  final String message;
  final Map<String, Object?> details;
  @override
  String toString() => 'DshRpcException(' + code + '): ' + message;
}

/// Native, memory-only Peer connection to one DSH deployment.
class DshRpcConnection {
  DshRpcConnection({required String webUrl}) {
    final uri = Uri.tryParse(webUrl);
    if (uri == null ||
        !['http', 'https'].contains(uri.scheme) ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty) {
      throw const DshRpcException(
        'connection/invalid-url',
        'Invalid engine web URL',
      );
    }
    final tokens = uri.queryParametersAll['token'];
    if (tokens == null || tokens.length != 1 || tokens.single.isEmpty) {
      throw const DshRpcException(
        'connection/invalid-url',
        'Engine launch token is missing or ambiguous',
      );
    }
    _token = tokens.single;
    _base = uri.resolve('./');
  }

  static const _timeout = Duration(seconds: 20);
  static const _maxBytes = 64 * 1024 * 1024;
  static const _closedError = DshRpcException(
    'connection/closed',
    'Connection is closed',
  );
  static const _lostError = DshRpcException(
    'connection/disconnected',
    'Engine connection was lost',
  );
  static const _protocolError = DshRpcException(
    'connection/protocol',
    'Invalid engine response',
  );
  late final Uri _base;
  late final String _token;
  HttpClient? _http;
  WebSocket? _socket;
  Future<void>? _connecting;
  Future<void>? _closing;
  String? _cookie;
  int _generation = 0;
  int _serial = 0;
  bool _closed = false;
  final _streams = <String, _RpcFollow>{};
  final _follows = <_RpcFollow>{};

  Future<void> connect() {
    if (_closed) return Future.error(_closedError);
    if (_socket?.readyState == WebSocket.open) return Future.value();
    final pending = _connecting;
    if (pending != null) return pending;
    if (_socket != null) _lose(_generation, _lostError);
    final future = _open();
    _connecting = future;
    unawaited(
      future.then<void>(
        (_) {
          if (identical(_connecting, future)) _connecting = null;
        },
        onError: (Object _, StackTrace _) {
          if (identical(_connecting, future)) _connecting = null;
        },
      ),
    );
    return future;
  }

  Future<void> _open() async {
    final generation = ++_generation;
    final client = HttpClient()..connectionTimeout = _timeout;
    _http = client;
    WebSocket? socket;
    try {
      final cookie = await _exchange(client).timeout(_timeout);
      _check(generation);
      final target = _base.resolve('api/remote.mux');
      final socketFuture = WebSocket.connect(
        target
            .replace(scheme: target.scheme == 'https' ? 'wss' : 'ws')
            .toString(),
        headers: {HttpHeaders.cookieHeader: cookie},
        customClient: client,
      );
      socket = await socketFuture.timeout(
        _timeout,
        onTimeout: () {
          unawaited(
            socketFuture.then<void>((lateSocket) {
              unawaited(lateSocket.close());
            }, onError: (Object _, StackTrace _) {}),
          );
          throw const DshRpcException(
            'connection/timeout',
            'Engine connection timed out',
          );
        },
      );
      _check(generation);
      _cookie = cookie;
      _socket = socket;
      socket.listen(
        (raw) => _receive(generation, raw),
        onDone: () => _lose(generation, _lostError),
        onError: (Object _) => _lose(generation, _lostError),
        cancelOnError: true,
      );
    } on Object catch (error) {
      if (socket != null) unawaited(socket.close());
      client.close(force: true);
      if (generation == _generation) {
        _socket = null;
        _http = null;
        _cookie = null;
      }
      if (_closed) throw _closedError;
      if (error is DshRpcException) rethrow;
      if (error is TimeoutException)
        throw const DshRpcException(
          'connection/timeout',
          'Engine connection timed out',
        );
      throw const DshRpcException(
        'connection/unavailable',
        'Engine connection is unavailable',
      );
    }
  }

  Future<String> _exchange(HttpClient client) async {
    final request = await client.getUrl(
      _base
          .resolve('connect')
          .replace(
            queryParameters: {'token': _token, 'interactions': 'answer'},
          ),
    );
    request.followRedirects = false;
    final response = await request.close();
    if (response.statusCode != HttpStatus.found) {
      throw DshRpcException(
        'connection/authentication',
        'Engine authentication failed (HTTP ' +
            response.statusCode.toString() +
            ')',
      );
    }
    final location = response.headers.value(HttpHeaders.locationHeader);
    final target = location == null
        ? null
        : _base.resolve('connect').resolve(location);
    final expected = _base.resolve('api/remote.mux');
    if (target == null ||
        target.scheme != _base.scheme ||
        target.authority != _base.authority ||
        target.userInfo.isNotEmpty ||
        target.path != expected.path ||
        target.hasQuery ||
        target.hasFragment) {
      throw const DshRpcException(
        'connection/authentication',
        'Engine returned an invalid Peer redirect',
      );
    }
    final cookies = response.cookies
        .where((cookie) {
          final domain = cookie.domain;
          final path = cookie.path;
          return (domain == null ||
                  domain.isEmpty ||
                  domain.toLowerCase() == _base.host.toLowerCase()) &&
              (!cookie.secure || _base.scheme == 'https') &&
              (path == null ||
                  path.isEmpty ||
                  expected.path.startsWith(path)) &&
              (cookie.expires == null ||
                  cookie.expires!.isAfter(DateTime.now())) &&
              cookie.maxAge != 0;
        })
        .map((cookie) => cookie.name + '=' + cookie.value)
        .toList();
    await _readBody(response);
    if (cookies.isEmpty)
      throw const DshRpcException(
        'connection/authentication',
        'Engine did not issue a Peer Cookie',
      );
    return cookies.join('; ');
  }

  Future<Object?> call(String endpoint, Map<String, Object?> args) async {
    _validateEndpoint(endpoint);
    await connect();
    final generation = _generation;
    final client = _http!;
    final rpcId = _id('rpc');
    HttpClientRequest? request;
    try {
      final operation = () async {
        request = await client.postUrl(_base.resolve('api/' + endpoint));
        _check(generation);
        request!.followRedirects = false;
        request!.headers.contentType = ContentType.json;
        request!.headers.set(HttpHeaders.cookieHeader, _cookie!);
        final body = utf8.encode(
          jsonEncode({
            'type': 'client-request',
            'rpcId': rpcId,
            'method': endpoint,
            'payload': {'args': args},
          }),
        );
        if (body.length > _maxBytes)
          throw const DshRpcException(
            'connection/too-large',
            'RPC request exceeds 64 MiB',
          );
        request!.add(body);
        final response = await request!.close();
        if (response.statusCode < 200 || response.statusCode >= 300) {
          throw DshRpcException(
            'connection/http',
            'Engine returned HTTP ' + response.statusCode.toString(),
          );
        }
        final bytes = await _readBody(response);
        _check(generation);
        final envelope = await decodeRpcBody(
          bytes,
          response.headers.contentType,
        );
        if (envelope['type'] != 'server-response' || envelope['rpcId'] != rpcId)
          throw _protocolError;
        final result = _map(envelope['result']);
        if (result == null) throw _protocolError;
        if (result['ok'] == true) return result['value'];
        if (result['ok'] != false) throw _protocolError;
        throw _failure(result['error']);
      }();
      return await operation.timeout(
        _timeout,
        onTimeout: () {
          request?.abort();
          throw const DshRpcException(
            'connection/timeout',
            'Engine RPC timed out',
          );
        },
      );
    } on DshRpcException {
      rethrow;
    } on FormatException {
      throw _protocolError;
    } on Object {
      if (_closed) throw _closedError;
      throw const DshRpcException(
        'connection/unavailable',
        'Engine RPC is unavailable',
      );
    } finally {
      request?.abort();
    }
  }

  Stream<Map<String, Object?>> follow(
    String endpoint,
    Map<String, Object?> args,
  ) {
    late final _RpcFollow entry;
    final controller = StreamController<Map<String, Object?>>(
      onListen: () {
        _follows.add(entry);
        unawaited(_start(entry, endpoint, args));
      },
      onCancel: () => _cancel(entry),
    );
    entry = _RpcFollow(_id('stream'), controller);
    return controller.stream;
  }

  Future<void> _start(
    _RpcFollow entry,
    String endpoint,
    Map<String, Object?> args,
  ) async {
    try {
      _validateEndpoint(endpoint);
      await connect();
      if (entry.finished) return;
      entry.generation = _generation;
      // Register before send: a host may answer the open frame immediately.
      _streams[entry.id] = entry;
      _socket!.add(
        jsonEncode({
          'type': 'open',
          'streamId': entry.id,
          'endpoint': endpoint,
          'payload': {'args': args},
        }),
      );
    } on Object catch (error) {
      if (!entry.finished)
        _finish(
          entry,
          error is DshRpcException
              ? error
              : const DshRpcException(
                  'connection/unavailable',
                  'Engine stream is unavailable',
                ),
        );
    }
  }

  void _receive(int generation, Object? raw) {
    if (_closed || generation != _generation) return;
    try {
      if (raw is! String || utf8.encode(raw).length > _maxBytes)
        throw _protocolError;
      final frame = _map(jsonDecode(raw));
      if (frame == null || frame['streamId'] is! String) throw _protocolError;
      final entry = _streams[frame['streamId']];
      if (entry == null || entry.finished || entry.generation != generation)
        return;
      switch (frame['type']) {
        case 'item':
          final value = _map(frame['value']);
          if (value == null) {
            _finish(entry, _protocolError);
            return;
          }
          entry.controller.add(value);
        case 'error':
          _finish(entry, _failure(frame['error']));
        case 'end':
          _finish(entry);
        default:
          throw _protocolError;
      }
    } on Object {
      _lose(generation, _protocolError);
    }
  }

  void _cancel(_RpcFollow entry) {
    if (entry.finished) return;
    if (entry.generation == _generation &&
        _socket?.readyState == WebSocket.open &&
        _streams.containsKey(entry.id)) {
      try {
        _socket!.add(jsonEncode({'type': 'cancel', 'streamId': entry.id}));
      } on Object {
        /* Loss is handled by the socket listener. */
      }
    }
    _finish(entry);
  }

  void _finish(_RpcFollow entry, [DshRpcException? error]) {
    if (entry.finished) return;
    entry.finished = true;
    if (identical(_streams[entry.id], entry)) _streams.remove(entry.id);
    _follows.remove(entry);
    if (error != null) entry.controller.addError(error);
    unawaited(entry.controller.close());
  }

  void _lose(int generation, DshRpcException error) {
    if (_closed || generation != _generation) return;
    ++_generation;
    final socket = _socket;
    _socket = null;
    _cookie = null;
    _http?.close(force: true);
    _http = null;
    if (socket != null) unawaited(socket.close());
    for (final entry in _follows.toList()) {
      if (entry.generation == generation) _finish(entry, error);
    }
  }

  Future<void> close() {
    if (_closing != null) return _closing!;
    _closed = true;
    ++_generation;
    final socket = _socket;
    _socket = null;
    _cookie = null;
    _http?.close(force: true);
    _http = null;
    for (final entry in _follows.toList()) {
      _finish(entry);
    }
    return _closing = () async {
      if (socket != null) {
        try {
          await socket.close().timeout(_timeout);
        } on Object {
          /* Local closure already invalidated every callback. */
        }
      }
    }();
  }

  void _check(int generation) {
    if (_closed) throw _closedError;
    if (generation != _generation) throw _lostError;
  }

  String _id(String prefix) =>
      prefix +
      '-' +
      DateTime.now().microsecondsSinceEpoch.toString() +
      '-' +
      (++_serial).toString();

  void _validateEndpoint(String endpoint) {
    final segments = endpoint.split('/');
    final allowed = RegExp(r'^[A-Za-z0-9_$.-]+$');
    if (segments.any(
      (s) => s.isEmpty || s == '.' || s == '..' || !allowed.hasMatch(s),
    )) {
      throw const DshRpcException(
        'connection/invalid-endpoint',
        'Invalid RPC endpoint',
      );
    }
  }

  Future<List<int>> _readBody(HttpClientResponse response) async {
    if (response.contentLength > _maxBytes)
      throw const DshRpcException(
        'connection/too-large',
        'Engine response exceeds 64 MiB',
      );
    final bytes = BytesBuilder(copy: false);
    await for (final chunk in response) {
      if (bytes.length + chunk.length > _maxBytes)
        throw const DshRpcException(
          'connection/too-large',
          'Engine response exceeds 64 MiB',
        );
      bytes.add(chunk);
    }
    return bytes.takeBytes();
  }

  DshRpcException _failure(Object? raw) {
    final error = _map(raw);
    final details = _map(error?['details']);
    if (error == null ||
        error['code'] is! String ||
        error['message'] is! String ||
        details == null)
      return _protocolError;
    return DshRpcException(
      _redact(error['code'] as String),
      _redact(error['message'] as String),
      _sanitize(details) as Map<String, Object?>,
    );
  }

  String _redact(String text) => text
      .replaceAll(_token, '[redacted]')
      .replaceAll(Uri.encodeComponent(_token), '[redacted]')
      .replaceAll(
        RegExp(r'https?://[^\s<>"\x27]*[?&]token=[^\s<>"\x27]*'),
        '[redacted URL]',
      );

  Object? _sanitize(Object? value) {
    if (value is String) return _redact(value);
    if (value is List) return value.map(_sanitize).toList();
    if (value is Map<String, Object?>)
      return value.map((key, val) => MapEntry(_redact(key), _sanitize(val)));
    return value;
  }
}

Map<String, Object?>? _map(Object? value) =>
    value is Map<String, Object?> ? value : null;

class _RpcFollow {
  _RpcFollow(this.id, this.controller);
  final String id;
  final StreamController<Map<String, Object?>> controller;
  int generation = -1;
  bool finished = false;
}
