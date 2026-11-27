import 'dart:async';

import '../model/settings_model.dart';
import '../state/settings_controller.dart';
import 'dsh_rpc_connection.dart';
import 'local_usage_reader.dart';

/// Platform credentials remain in the DSH Host.
class NativeSettingsService
    implements SettingsService, CancelableSignIn, AccountUsageLink, ModelProviderSettings {
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

  static const _providerNamespace = 'llm-pi-ai';
  static final _credentialPattern = RegExp(r'^[A-Za-z_][A-Za-z0-9_]*$');

  @override
  Future<ProviderSettingsSnapshot?> loadModelProviders() async {
    final raw = await rpc.call('settings/describe', {});
    if (raw is! Map || raw['namespaces'] is! List) {
      throw const FormatException('Invalid Host settings');
    }
    for (final section in raw['namespaces'] as List) {
      if (section is! Map || section['ns'] != _providerNamespace) continue;
      final revision = section['revision'], value = section['value'];
      if (revision is! int || revision < 0 || value is! Map ||
          value['providers'] is! Map) {
        throw const FormatException('Invalid Host provider settings');
      }
      final providers = <ModelProviderProfile>[];
      for (final entry in (value['providers'] as Map).entries) {
        final route = entry.key, fields = entry.value;
        if (route is! String || fields is! Map) {
          throw const FormatException('Invalid Host provider');
        }
        final models = fields['models'];
        final ref = fields['apiKeyEnv'];
        providers.add(ModelProviderProfile(
          route: route,
          api: fields['api'] is String ? fields['api'] as String : '',
          baseUrl: fields['baseURL'] is String ? fields['baseURL'] as String : '',
          models: List.unmodifiable([
            if (models is List)
              for (final entry in models)
                if (entry is Map && entry['id'] is String) entry['id'] as String,
          ]),
          credentialRef: ref is String && _credentialPattern.hasMatch(ref) ? ref : null,
        ));
      }
      return (revision: revision, writable: raw['writable'] == true,
          providers: List<ModelProviderProfile>.unmodifiable(providers));
    }
    return null;
  }

  @override
  Future<void> addModelProvider(
    ProviderSettingsSnapshot snapshot,
    ModelProviderProfile profile,
  ) async {
    final endpoint = _publicUri(profile.baseUrl);
    final ref = profile.credentialRef;
    if (!snapshot.writable || snapshot.providers.any((p) => p.route == profile.route)) {
      throw StateError('Provider cannot be created');
    }
    if (!ModelProviderProfile.routePattern.hasMatch(profile.route) ||
        !ModelProviderProfile.protocols.contains(profile.api) || endpoint == null ||
        endpoint.hasQuery || endpoint.hasFragment ||
        profile.models.isEmpty || profile.models.any((id) => id.trim().isEmpty) ||
        profile.models.toSet().length != profile.models.length ||
        (ref != null && !_credentialPattern.hasMatch(ref))) {
      throw const FormatException('Invalid provider configuration');
    }
    final result = await rpc.call('settings/mutate', {
      'ns': _providerNamespace,
      'expectedRevision': snapshot.revision,
      'ops': [
        {
          'op': 'set',
          'path': ['providers', profile.route],
          'value': {
            'api': profile.api,
            'baseURL': profile.baseUrl,
            'models': [for (final id in profile.models) {'id': id}],
            'apiKeyEnv': ?ref,
          },
        },
      ],
    });
    final value = result is Map ? result['value'] : null;
    final routes = value is Map ? value['providers'] : null;
    if (result is! Map || result['ns'] != _providerNamespace ||
        routes is! Map || !routes.containsKey(profile.route)) {
      throw const FormatException('Host did not confirm the provider');
    }
  }

  Future<Map> _mutateProviders(
    ProviderSettingsSnapshot snapshot,
    List<Map<String, Object?>> ops,
  ) async {
    final result = await rpc.call('settings/mutate', {
      'ns': _providerNamespace,
      'expectedRevision': snapshot.revision,
      'ops': ops,
    });
    final value = result is Map ? result['value'] : null;
    final routes = value is Map ? value['providers'] : null;
    if (result is! Map || result['ns'] != _providerNamespace || routes is! Map) {
      throw const FormatException('Invalid Host provider acknowledgement');
    }
    return routes;
  }

  @override
  Future<void> updateModelProvider(
    ProviderSettingsSnapshot snapshot,
    ModelProviderProfile profile,
  ) async {
    final previous = snapshot.providers.where((p) => p.route == profile.route).firstOrNull;
    if (!snapshot.writable || previous == null) {
      throw StateError('Provider cannot be edited');
    }
    final endpoint = _providerEndpoint(profile.baseUrl);
    final ref = profile.credentialRef;
    if ((profile.baseUrl != previous.baseUrl && endpoint == null) ||
        (profile.api != previous.api && !ModelProviderProfile.protocols.contains(profile.api)) ||
        profile.models.any((id) => id.trim().isEmpty) ||
        profile.models.toSet().length != profile.models.length ||
        (ref != null && !_credentialPattern.hasMatch(ref))) {
      throw const FormatException('Invalid provider configuration');
    }

    // A versioned re-read supplies exact model records, not just the IDs the form shows.
    final current = await rpc.call('settings/describe', {});
    final sections = current is Map ? current['namespaces'] : null;
    final section = sections is List
        ? sections.whereType<Map>().where((n) => n['ns'] == _providerNamespace).firstOrNull
        : null;
    final value = section?['value'];
    final routes = value is Map ? value['providers'] : null;
    final fields = routes is Map ? routes[profile.route] : null;
    if (current is! Map || current['writable'] != true ||
        section?['revision'] != snapshot.revision || fields is! Map) {
      throw const DshRpcException('settings/conflict', 'Provider settings changed');
    }
    final changesRef = ref != null && ref != previous.credentialRef;
    final oldEndpoint = _providerEndpoint(previous.baseUrl);
    final changesDestination = (profile.baseUrl != previous.baseUrl &&
        (oldEndpoint == null || endpoint?.origin != oldEndpoint.origin)) ||
        profile.api != previous.api;
    if ((changesRef && (endpoint == null || !_canSendProviderKey(endpoint))) ||
        (changesDestination &&
            ((fields['headers'] is Map && (fields['headers'] as Map).isNotEmpty) ||
                (fields['apiKeyEnv'] != null && !changesRef)))) {
      throw const DshRpcException(
        'settings/provider-destination', 'Existing authorization cannot be sent to the new destination',
      );
    }
    final storedModels = fields['models'];
    final records = <String, Map>{
      if (storedModels is List)
        for (final record in storedModels.whereType<Map>())
          if (record['id'] is String) record['id'] as String: record,
    };
    final values = <String, Object?>{
      if (profile.api != previous.api) 'api': profile.api,
      if (profile.baseUrl != previous.baseUrl) 'baseURL': profile.baseUrl,
      if (profile.models.length != previous.models.length ||
          !profile.models.asMap().entries.every((e) => previous.models[e.key] == e.value))
        'models': [for (final id in profile.models) records[id] ?? {'id': id}],
      if (changesRef) 'apiKeyEnv': ref,
    };
    if (values.isEmpty) return;
    final updated = await _mutateProviders(snapshot, [
      for (final entry in values.entries)
        {'op': 'set', 'path': ['providers', profile.route, entry.key], 'value': entry.value},
    ]);
    if (updated[profile.route] is! Map) {
      throw const FormatException('Host did not confirm the provider edit');
    }
  }

  @override
  Future<void> removeModelProvider(ProviderSettingsSnapshot snapshot, String route) async {
    if (!snapshot.writable || !snapshot.providers.any((p) => p.route == route)) {
      throw StateError('Provider cannot be removed');
    }
    // The reference can be shared by another provider or namespace.
    final routes = await _mutateProviders(snapshot, [
      {'op': 'unset', 'path': ['providers', route]},
    ]);
    if (routes.containsKey(route)) {
      throw const FormatException('Host did not confirm provider removal');
    }
  }

  static Uri? _providerEndpoint(String url) {
    final endpoint = _publicUri(url);
    return endpoint == null || endpoint.hasQuery || endpoint.hasFragment ? null : endpoint;
  }

  static bool _canSendProviderKey(Uri endpoint) => endpoint.scheme == 'https' ||
      const ['127.0.0.1', '::1', 'localhost'].contains(endpoint.host);

  @override
  Future<List<String>> discoverProviderModels({
    required String baseUrl,
    required String api,
    String? apiKey,
  }) async {
    final endpoint = _providerEndpoint(baseUrl);
    if (endpoint == null || !ModelProviderProfile.protocols.contains(api) ||
        (apiKey != null && (apiKey.isEmpty || !_canSendProviderKey(endpoint)))) {
      throw const FormatException('Invalid model discovery configuration');
    }
    final raw = await rpc.call('llm/discoverModels', {
      'settingsNs': _providerNamespace,
      'request': {'baseURL': baseUrl, 'api': api, 'apiKey': ?apiKey},
    });
    if (raw is! List || raw.length > 10000) {
      throw const FormatException('Invalid model directory');
    }
    final ids = <String>{};
    for (final item in raw) {
      final id = item is Map ? item['id'] : null;
      if (id is! String || id.trim().isEmpty || id.trim() != id ||
          id.contains(RegExp(r'[,，\r\n]'))) {
        throw const FormatException('Invalid model directory entry');
      }
      ids.add(id);
    }
    return ids.toList(growable: false);
  }

  @override
  Future<void> setModelProviderKey(String reference, String value) async {
    if (!_credentialPattern.hasMatch(reference) || value.isEmpty) {
      throw const FormatException('Invalid credential input');
    }
    if (await rpc.call('credentials/set', {'ref': reference, 'value': value}) != null) {
      throw const FormatException('Invalid credential acknowledgement');
    }
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
