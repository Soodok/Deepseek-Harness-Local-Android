import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dartssh2/dartssh2.dart';

final _fingerprintShape = RegExp(r'^SHA256:[A-Za-z0-9+/]{43}$');
final _decimalPort = RegExp(r'^[0-9]{1,5}$');

bool isManualSshFingerprint(String value) {
  if (value.length != 50 || !_fingerprintShape.hasMatch(value)) return false;
  final digest = value.substring(7);
  try {
    return base64.encode(base64.decode('$digest=')) == '$digest=';
  } on FormatException {
    return false;
  }
}

int? parseManualSshPort(String value) {
  if (!_decimalPort.hasMatch(value)) return null;
  final port = int.tryParse(value);
  return port != null && port > 0 && port <= 65535 ? port : null;
}

enum ManualSshFailure {
  invalidInput,
  busy,
  notConnected,
  hostKeyMismatch,
  authentication,
  timeout,
  disconnected,
  connection,
  command,
}

class ManualSshException implements Exception {
  const ManualSshException(this.failure);

  final ManualSshFailure failure;
}

class ManualSshResult {
  const ManualSshResult({
    required this.stdout,
    required this.stderr,
    required this.exitCode,
    required this.truncated,
  });

  final String stdout;
  final String stderr;
  final int? exitCode;
  final bool truncated;
}

/// One manually opened SSH session. No config, credentials, or output is saved.
class ManualSshService {
  ManualSshService({this.onDisconnected});

  static const outputLimitPerStream = 32 * 1024;
  static const commandLimit = 4096;
  static const _connectTimeout = Duration(seconds: 10);
  static const _commandTimeout = Duration(seconds: 20);

  final void Function()? onDisconnected;
  SSHClient? _client;
  int _generation = 0;
  bool _connecting = false;
  bool _connected = false;
  bool _running = false;

  bool get connected => _connected;

  Future<void> connect({
    required String host,
    required int port,
    required String username,
    required String password,
    required String fingerprint,
  }) async {
    if (host.isEmpty ||
        host.length > 253 ||
        host.contains(RegExp(r'\s|[/@?#]')) ||
        username.isEmpty ||
        username.length > 128 ||
        username.contains('\x00') ||
        password.isEmpty ||
        password.length > 1024 ||
        port < 1 ||
        port > 65535 ||
        !isManualSshFingerprint(fingerprint)) {
      throw const ManualSshException(ManualSshFailure.invalidInput);
    }
    if (_connecting || _client != null) {
      throw const ManualSshException(ManualSshFailure.busy);
    }
    final generation = ++_generation;
    _connecting = true;
    var pinMatched = false;
    var pinRejected = false;
    var passwordSent = false;
    String? oneUsePassword = password;
    try {
      final socket = await SSHSocket.connect(
        host,
        port,
        timeout: _connectTimeout,
      );
      if (generation != _generation) {
        socket.destroy();
        throw const ManualSshException(ManualSshFailure.disconnected);
      }
      final SSHClient client;
      try {
        client = SSHClient(
          socket,
          username: username,
          keepAliveInterval: null,
          handshakeTimeout: _connectTimeout,
          authTimeout: _connectTimeout,
          onVerifyHostKey: (_, actual) {
            pinMatched =
                generation == _generation &&
                fingerprint == ascii.decode(actual, allowInvalid: true);
            pinRejected = !pinMatched;
            return pinMatched;
          },
          onPasswordRequest: () {
            if (!pinMatched || generation != _generation) return null;
            final secret = oneUsePassword;
            oneUsePassword = null;
            passwordSent = secret != null;
            return secret;
          },
        );
      } on Object {
        socket.destroy();
        rethrow;
      }
      _client = client;
      unawaited(
        client.done.then<void>(
          (_) => _peerClosed(client),
          onError: (Object _) => _peerClosed(client),
        ),
      );
      await client.authenticated.timeout(_connectTimeout);
      if (generation != _generation || !identical(client, _client)) {
        throw const ManualSshException(ManualSshFailure.disconnected);
      }
      if (!pinMatched) {
        throw const ManualSshException(ManualSshFailure.hostKeyMismatch);
      }
      if (!passwordSent) {
        throw const ManualSshException(ManualSshFailure.authentication);
      }
      _connected = true;
    } on Object catch (error) {
      if (generation != _generation) {
        throw const ManualSshException(ManualSshFailure.disconnected);
      }
      disconnect();
      if (pinRejected) {
        throw const ManualSshException(ManualSshFailure.hostKeyMismatch);
      }
      if (error is ManualSshException) rethrow;
      if (error is SSHAuthFailError || error is SSHAuthAbortError) {
        throw const ManualSshException(ManualSshFailure.authentication);
      }
      if (error is TimeoutException) {
        throw const ManualSshException(ManualSshFailure.timeout);
      }
      throw const ManualSshException(ManualSshFailure.connection);
    } finally {
      oneUsePassword = null;
      if (generation == _generation) _connecting = false;
    }
  }

  Future<ManualSshResult> execute(String command) async {
    final client = _client;
    if (!_connected || client == null) {
      throw const ManualSshException(ManualSshFailure.notConnected);
    }
    if (_running) throw const ManualSshException(ManualSshFailure.busy);
    if (command.trim().isEmpty ||
        command.length > commandLimit ||
        command.contains('\x00')) {
      throw const ManualSshException(ManualSshFailure.invalidInput);
    }
    _running = true;
    try {
      final result = await _execute(client, command).timeout(_commandTimeout);
      if (!identical(client, _client)) {
        throw const ManualSshException(ManualSshFailure.disconnected);
      }
      return result;
    } on TimeoutException {
      if (identical(client, _client)) disconnect();
      throw const ManualSshException(ManualSshFailure.timeout);
    } on Object {
      if (!identical(client, _client)) {
        throw const ManualSshException(ManualSshFailure.disconnected);
      }
      throw const ManualSshException(ManualSshFailure.command);
    } finally {
      if (identical(client, _client)) _running = false;
    }
  }

  Future<ManualSshResult> _execute(SSHClient client, String command) async {
    final session = await client.execute(command);
    final stdout = _BoundedSshOutput();
    final stderr = _BoundedSshOutput();
    try {
      await Future.wait<void>([
        session.stdout.forEach(stdout.add),
        session.stderr.forEach(stderr.add),
        session.done,
      ], eagerError: true);
      if (!identical(client, _client)) {
        throw const ManualSshException(ManualSshFailure.disconnected);
      }
      return ManualSshResult(
        stdout: stdout.text,
        stderr: stderr.text,
        exitCode: session.exitCode,
        truncated: stdout.truncated || stderr.truncated,
      );
    } finally {
      session.close();
    }
  }

  void _peerClosed(SSHClient client) {
    if (!identical(client, _client) || !_connected) return;
    disconnect();
    onDisconnected?.call();
  }

  void disconnect() {
    _generation++;
    final client = _client;
    _client = null;
    _connected = _connecting = _running = false;
    if (client != null) {
      unawaited(client.close().catchError((Object _) {}));
    }
  }
}

class _BoundedSshOutput {
  final _bytes = BytesBuilder(copy: false);
  bool truncated = false;

  void add(Uint8List data) {
    final available = ManualSshService.outputLimitPerStream - _bytes.length;
    if (data.length > available) {
      truncated = true;
      if (available > 0) _bytes.add(data.sublist(0, available));
    } else if (data.isNotEmpty) {
      _bytes.add(data);
    }
  }

  String get text => utf8.decode(_bytes.takeBytes(), allowMalformed: true);
}
