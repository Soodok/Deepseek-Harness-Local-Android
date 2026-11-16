import 'dart:async';

import '../model/settings_model.dart';
import '../state/settings_controller.dart';
import 'dsh_rpc_connection.dart';
import 'local_usage_reader.dart';

/// Client-safe account RPC only. Platform credentials remain in the DSH Host.
class NativeSettingsService
    implements SettingsService, CancelableSignIn, AccountUsageLink {
  NativeSettingsService({
    required this.rpc,
    required this.callbackOrigin,
    required this.openBrowser,
    this.locale = 'zh-CN',
    this.clientVersion = '1.6.0',
    this.signInTimeout = const Duration(minutes: 5),
  });
  final DshRpcConnection rpc;
  final Uri callbackOrigin;
  final Future<bool> Function(Uri) openBrowser;
  final String locale, clientVersion;
  final Duration signInTimeout;
  StreamIterator<Map<String, Object?>>? _updates;
  String? _attemptId;
  Future<void>? _login;
  bool _closed = false;
  int _loginEpoch = 0;
  @override
  bool get isDemo => false;
  Map<String, Object?> get _client => {
    'version': clientVersion,
    'locale': locale,
    'timezoneOffsetSeconds': DateTime.now().timeZoneOffset.inSeconds,
  };
  static Map _state(Object? raw) {
    if (raw is! Map ||
        !['signed-out', 'credential-stored'].contains(raw['status']) ||
        raw['links'] is! Map)
      throw const FormatException('Invalid DSH account state');
    return raw;
  }

  static Uri? _publicUri(Object? value) {
    if (value is! String) return null;
    final uri = Uri.tryParse(value);
    return uri != null &&
            ['http', 'https'].contains(uri.scheme) &&
            uri.host.isNotEmpty &&
            uri.userInfo.isEmpty
        ? uri
        : null;
  }

  @override
  Future<AccountProfile?> loadProfile() async {
    final state = _state(await rpc.call('account/getState', {}));
    if (state['status'] == 'signed-out') return null;
    final raw = await rpc.call('account/getProfile', {'client': _client});
    if (raw == null) return null;
    if (raw is! Map || raw['status'] != 'ready' || raw['value'] is! Map)
      throw StateError('无法读取DeepSeek个人资料，请刷新重试');
    final value = raw['value'] as Map;
    final contact = value['contact'] is String
        ? value['contact'] as String
        : '';
    final name = value['name'] is String ? value['name'] as String : '';
    return AccountProfile(
      id: value['id'] is String
          ? value['id'] as String
          : contact.isEmpty
          ? 'deepseek-account'
          : contact,
      displayName: name.isEmpty
          ? (contact.isEmpty ? 'DeepSeek账号' : contact)
          : name,
      email: contact,
      avatarUrl: _publicUri(value['avatarUrl']),
    );
  }

  @override
  Future<UsageHistory> loadUsage() => readLocalUsage(rpc.call);
  @override
  Future<Uri> usagePage() async {
    final state = _state(await rpc.call('account/getState', {}));
    final uri = _publicUri((state['links'] as Map)['usageUrl']);
    if (uri == null ||
        uri.queryParameters.keys.any((k) => k.toLowerCase().contains('token')))
      throw const FormatException('Invalid account usage destination');
    return uri;
  }

  @override
  Future<void> signIn() {
    final pending = _login;
    if (pending != null) return pending;
    final future = _begin();
    _login = future;
    unawaited(
      future.then<void>(
        (_) {
          if (identical(_login, future)) _login = null;
        },
        onError: (Object _, StackTrace _) {
          if (identical(_login, future)) _login = null;
        },
      ),
    );
    return future;
  }

  Future<void> _begin() async {
    if (_closed) throw StateError('账号连接已关闭');
    if (callbackOrigin.scheme != 'http' ||
        !['localhost', '127.0.0.1', '::1'].contains(callbackOrigin.host) ||
        !callbackOrigin.hasPort ||
        callbackOrigin.port == 0 ||
        callbackOrigin.userInfo.isNotEmpty ||
        callbackOrigin.hasQuery ||
        callbackOrigin.hasFragment ||
        (callbackOrigin.path.isNotEmpty && callbackOrigin.path != '/'))
      throw ArgumentError('登录需要本机HTTP回调地址和明确端口');
    final epoch = ++_loginEpoch;
    final updates = StreamIterator(rpc.follow('account/watch', {}));
    _updates = updates;
    String? id;
    bool succeeded = false, opened = false;
    final deadline = DateTime.now().add(signInTimeout);
    try {
      var state = _state(
        await rpc.call('account/startSignIn', {
          'client': _client,
          'callbackOrigin': callbackOrigin.origin,
          'loginSource': 'web',
        }),
      );
      final attempt = state['attempt'];
      if (attempt is! Map ||
          attempt['id'] is! String ||
          (attempt['id'] as String).isEmpty)
        throw const FormatException('Missing sign-in attempt identity');
      id = attempt['id'] as String;
      _attemptId = id;
      if (_closed || epoch != _loginEpoch) throw StateError('登录已取消');
      while (true) {
        if (_closed || epoch != _loginEpoch) throw StateError('登录已取消');
        final current = state['attempt'];
        if (current is Map && current['id'] == id) {
          final phase = current['phase'];
          if (phase == 'succeeded') {
            if (state['status'] != 'credential-stored')
              throw const FormatException(
                'Login completed without stored credential',
              );
            succeeded = true;
            return;
          }
          if (['cancelled', 'failed', 'expired'].contains(phase))
            throw StateError(
              phase == 'expired'
                  ? '授权已过期，请重试'
                  : phase == 'cancelled'
                  ? '登录已取消'
                  : '浏览器授权失败，请重试',
            );
          if (![
            'initializing',
            'waiting-browser',
            'exchanging',
            'committing',
          ].contains(phase))
            throw const FormatException('Invalid sign-in phase');
          if (phase == 'waiting-browser' && !opened) {
            final uri = _publicUri(current['authorizeUrl']);
            final platform = _publicUri((state['links'] as Map)['usageUrl']);
            if (uri == null ||
                platform == null ||
                uri.origin != platform.origin ||
                uri.path != '/dsh/authorize' ||
                uri.queryParameters.keys.any(
                  (k) => k.toLowerCase().contains('token'),
                ))
              throw const FormatException(
                'Invalid account authorization destination',
              );
            opened = true;
            if (!await openBrowser(uri)) throw StateError('无法打开授权浏览器，请重试');
          }
        }
        final remaining = deadline.difference(DateTime.now());
        if (remaining <= Duration.zero ||
            !await updates.moveNext().timeout(remaining))
          throw StateError('登录连接已结束，请重试');
        final item = updates.current;
        if (!['snapshot', 'credentials-changed'].contains(item['type']))
          throw const FormatException('Invalid account update');
        state = _state(item['state']);
      }
    } finally {
      await updates.cancel();
      if (identical(_updates, updates)) _updates = null;
      if (id != null && _attemptId == id) {
        _attemptId = null;
        if (!succeeded) {
          try {
            await rpc.call('account/cancelSignIn', {'attemptId': id});
          } catch (_) {}
        }
      }
    }
  }

  @override
  Future<void> cancelSignIn() async {
    ++_loginEpoch;
    final id = _attemptId;
    _attemptId = null;
    await _updates?.cancel();
    _updates = null;
    if (id != null) await rpc.call('account/cancelSignIn', {'attemptId': id});
    try {
      await _login;
    } catch (_) {
      /* Cancellation is the expected outcome. */
    }
  }

  /// The UI confirms possible effects on active account tasks before this call.
  @override
  Future<void> signOut() async {
    final running = await rpc.call('account/hasRunningAccountTasks', {});
    if (running is! bool)
      throw const FormatException('Invalid account task state');
    final state = _state(
      await rpc.call('account/signOut', {'client': _client}),
    );
    if (state['status'] != 'signed-out') throw StateError('引擎尚未确认退出，请刷新重试');
  }

  @override
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    try {
      await cancelSignIn();
    } finally {
      await rpc.close();
    }
  }
}
