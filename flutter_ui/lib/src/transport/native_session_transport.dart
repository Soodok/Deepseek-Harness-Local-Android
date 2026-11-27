import 'dart:async';
import 'dart:convert';

import '../model/chat_model.dart';
import '../model/composer_model.dart';
import '../model/engine_session.dart';
import '../model/subagent_model.dart';
import 'dsh_rpc_connection.dart';
import 'dsh_session_projection.dart';
import 'dsh_transport.dart';
import 'native_subagent_transport.dart';
import 'native_interactions.dart';

/// Owns one addressed parent follow and the supplied native RPC connection.
/// Child browsing remains passive and is delegated to the existing adapter.
class NativeSessionTransport
    implements
        DshTransport,
        ApprovalRejectionTransport,
        SubagentTransport,
        ComposerTransport,
        ComposerCapabilities,
        SessionComposerTransport,
        SessionPermissionTransport,
        StructuredQuestionTransport {
  NativeSessionTransport({
    required this.rpc,
    required this.sessionId,
    this.enableInteractions = false,
    this.activateBlankSession = false,
    this.onAgentPresetState,
  }) : _subagents = NativeSubagentTransport(rpc) {
    if (sessionId.trim().isEmpty)
      throw const DshRpcException(
        'session/invalid-address',
        'Session identity is required',
      );
  }

  final DshRpcConnection rpc;
  final String sessionId;
  final bool enableInteractions;
  final bool activateBlankSession;
  final void Function(String? presetId, bool started)? onAgentPresetState;
  NativeInteractions? _interactions;
  StreamSubscription<NativeInteractionsState>? _interactionUpdates;
  String? _interactionError;
  final NativeSubagentTransport _subagents;
  final _events = StreamController<ChatEvent>();
  StreamSubscription<Map<String, Object?>>? _follow;
  Timer? _timer;
  DshSessionProjection? _projection;
  Future<void>? _connecting;
  Future<void>? _closing;
  Future<void>? _catalogRead;
  Future<void>? _stateRead;
  Future<void>? _configurationRead;
  Future<void>? _permissionWrite;
  String? _permissionWritePreset;
  int _configurationRevision = 0;
  int _modelConfigurationCut = -2;
  int _permissionConfigurationCut = -2;
  ComposerConfiguration _configuration = const ComposerConfiguration();
  final _configurationChanges =
      StreamController<ComposerConfiguration>.broadcast(sync: true);
  Completer<void>? _opening;
  int _generation = 0;
  int _requestSerial = 0;
  bool _closed = false;
  bool _following = false;
  bool _submissionBusy = false;
  List<ComposerModel> _models = const [];
  final _routes = <String, Map<String, Object?>>{};
  Set<String> _permissions = const {};
  Object? catalogError;
  Object? capabilityError;
  String? _stateError;

  @override
  Stream<ChatEvent> get events => _events.stream;
  @override
  bool get isFixture => false;
  @override
  List<ComposerModel> get models => _models;
  @override
  ComposerConfiguration get composerConfiguration => _configuration;
  @override
  Stream<ComposerConfiguration> get composerConfigurationChanges =>
      _configurationChanges.stream;
  @override
  bool get supportsAttachments => false;
  @override
  List<ComposerPermission> get permissions => List.unmodifiable(
    ComposerPermission.values.where((p) => _permissions.contains(p.wireValue)),
  );

  Map<String, Object?> get _address => {
    'kind': 'session',
    'sessionId': sessionId,
  };
  Map<String, Object?> get _window => {
    'address': _address,
    'maxMessages': 500,
    'turnWindow': {'minMessages': 50, 'minTurns': 2},
  };

  static Future<List<Map<String, Object?>>> listSessions(
    DshRpcConnection rpc,
  ) async {
    final value = _map(
      await rpc.call('session/list', {'_request': <String, Object?>{}}),
    );
    final items = value?['items'];
    if (items is! List) throw _invalid;
    return List.unmodifiable(
      items.map((item) {
        final row = _map(item);
        if (row == null || row['sessionId'] is! String) throw _invalid;
        return Map<String, Object?>.unmodifiable(row);
      }),
    );
  }

  /// A detached events page is never allowed to replace the live follow window.
  Future<List<ChatMessage>> readHistory() async {
    _checkOpen();
    final generation = _generation;
    final page = _map(await rpc.call('session/events', {'request': _window}));
    _check(generation);
    if (page == null) throw _invalid;
    final projection = DshSessionProjection(
      sessionId: sessionId,
      generation: generation,
    );
    projection.page(page);
    return projection.messages;
  }

  @override
  Future<void> connect() {
    if (_closed) return Future.error(_closedError);
    final connecting = _connecting;
    if (connecting != null) return connecting;
    if (_following) return Future.value();
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
    ++_configurationRevision;
    _modelConfigurationCut = -2;
    _permissionConfigurationCut = -2;
    _publishConfiguration(const ComposerConfiguration());
    final projection = DshSessionProjection(
      sessionId: sessionId,
      generation: generation,
    );
    _projection = projection;
    final opened = Completer<void>();
    _opening = opened;
    // Keep early cancellation/failure observed while authentication is still pending.
    unawaited(
      opened.future.then<void>((_) {}, onError: (Object _, StackTrace _) {}),
    );
    await rpc.connect();
    _check(generation);
    if (enableInteractions) {
      await _closeInteractions();
      final interactions = NativeInteractions(rpc: rpc, sessionId: sessionId);
      _interactions = interactions;
      _interactionUpdates = interactions.updates.listen((_) {
        if (_current(generation)) _syncInteractions();
      });
      await interactions.start();
      _check(generation);
    }
    _following = true;
    _follow = rpc
        .follow('session/follow', {
          'request': {
            ..._window,
            'assistantStream': true,
            if (activateBlankSession) 'activate': true,
          },
        })
        .listen(
          (frame) {
            if (!_current(generation) || !_following) return;
            try {
              final before = projection.cursor;
              final events = projection.accept(frame);
              _acceptConfigurationFrame(frame, before: before);
              _acceptAgentPresetFrame(frame, before: before);
              _emit(events);
              _syncInteractions();
              if (projection.opened && !opened.isCompleted) opened.complete();
            } on Object catch (error) {
              _fail(generation, error, opened);
            }
          },
          onError: (Object error, StackTrace _) =>
              _fail(generation, error, opened),
          onDone: () {
            if (_current(generation) && _following) {
              _fail(
                generation,
                const DshRpcException(
                  'session/disconnected',
                  'Session follow ended; reconnect to resume',
                ),
                opened,
              );
            }
          },
        );
    try {
      // Catalogs are optional: a deployment may expose parent history without either service.
      await Future.wait<void>([
        opened.future.timeout(const Duration(seconds: 20)),
        refreshCapabilities(),
      ], eagerError: true);
      if (identical(_opening, opened)) _opening = null;
      _check(generation);
      if (!_following)
        throw const DshRpcException(
          'session/disconnected',
          'Session follow is unavailable',
        );
      _timer?.cancel();
      _timer = Timer.periodic(const Duration(seconds: 3), (_) {
        if (!_current(generation) || !_following) return;
        _backgroundState(generation);
        _backgroundCatalog(generation);
        _backgroundConfiguration(generation);
      });
      _backgroundState(generation);
      _backgroundCatalog(generation);
    } on Object catch (error) {
      if (_current(generation) && _following) _fail(generation, error, opened);
      rethrow;
    }
  }

  void _acceptAgentPresetFrame(
    Map<String, Object?> frame, {
    required int before,
  }) {
    final publish = onAgentPresetState;
    if (publish == null) return;
    bool started(Object? event) =>
        event is Map &&
        (event['type'] == 'turn/start' || event['type'] == 'turn/end');
    if (frame['type'] == 'snapshot') {
      final projections = frame['projections'];
      final values = _map(_map(projections)?['values']);
      final records = frame['records'];
      publish(
        EngineSession.readAgentPreset(projections),
        (records is List && records.any(started)) ||
            _map(values?['sessionListMetadata'])?['blank'] == false,
      );
    } else if (frame['type'] == 'event') {
      final event = _map(frame['event']);
      final seq = event?['seq'];
      if (seq is! int || seq <= before) return;
      if (started(event)) publish(null, true);
      if (event?['type'] == 'agent-preset/selected') {
        final preset = _text(_map(event?['data'])?['agentPreset']);
        if (preset != null) publish(preset, false);
      }
    }
  }

  @override
  Future<void> reconnect() async {
    _checkOpen();
    ++_generation;
    final opening = _opening;
    _opening = null;
    if (opening != null && !opening.isCompleted)
      opening.completeError(
        const DshRpcException(
          'session/stale-generation',
          'Session opening was superseded',
        ),
      );
    _timer?.cancel();
    _timer = null;
    await _closeInteractions();
    _following = false;
    final follow = _follow;
    _follow = null;
    _connecting = null;
    _catalogRead = null;
    _stateRead = null;
    _configurationRead = null;
    await follow?.cancel();
    _checkOpen();
    await connect();
  }

  /// Optional capabilities use separate real RPCs, never prompt-only invented fields.
  Future<void> refreshCapabilities() async {
    _checkOpen();
    final generation = _generation;
    Object? error;
    try {
      final catalog = _map(
        await rpc.call('session/modelCatalog', <String, Object?>{}),
      );
      _check(generation);
      final groups = catalog?['groups'];
      if (groups is! List) throw _invalid;
      final models = <ComposerModel>[];
      final routes = <String, Map<String, Object?>>{};
      for (final raw in groups) {
        final group = _map(raw), provider = _text(_map(raw)?['id']);
        final entries = group?['models'];
        if (provider == null || entries is! List) throw _invalid;
        for (final entry in entries) {
          final model = _map(entry), modelId = _text(_map(entry)?['id']);
          if (model == null || modelId == null) throw _invalid;
          final id = jsonEncode([provider, modelId]);
          if (routes.containsKey(id)) throw _invalid;
          models.add(
            ComposerModel(
              id,
              _text(model['name']) ?? modelId,
              _text(model['description']) ??
                  (_text(group?['name']) ?? provider),
            ),
          );
          routes[id] = Map.unmodifiable({
            'provider': provider,
            'model': modelId,
          });
        }
      }
      _models = List.unmodifiable(models);
      _routes
        ..clear()
        ..addAll(routes);
    } on Object catch (failure) {
      _check(generation);
      _models = const [];
      _routes.clear();
      error = failure;
    }
    try {
      final catalog = _map(
        await rpc.call('permissionPresets/catalog', <String, Object?>{}),
      );
      _check(generation);
      final options = catalog?['options'];
      if (options is! List) throw _invalid;
      final values = <String>{};
      for (final option in options) {
        final value = _text(_map(option)?['value']);
        if (value == null) throw _invalid;
        values.add(value);
      }
      _permissions = Set.unmodifiable(values);
    } on Object catch (failure) {
      _check(generation);
      _permissions = const {};
      error ??= failure;
    }
    capabilityError = error;
    // Catalog completion can change labels and enabled options without changing selection.
    if (!_closed) _configurationChanges.add(_configuration);
  }

  void _publishConfiguration(ComposerConfiguration configuration) {
    if (_closed) return;
    if (_configuration.modelId == configuration.modelId &&
        _configuration.modelLabel == configuration.modelLabel &&
        _configuration.permissionPreset == configuration.permissionPreset)
      return;
    _configuration = configuration;
    _configurationChanges.add(configuration);
  }

  void _acceptConfigurationFrame(
    Map<String, Object?> frame, {
    required int before,
  }) {
    if (frame['type'] == 'snapshot') {
      final projection = _map(frame['projections']);
      final values = _map(projection?['values']);
      final cut = projection?['asOfSeq'] ?? projection?['seq'];
      if (values != null && cut is int) _applyConfiguration(values, cut);
      return;
    }
    final event = _map(frame['event']);
    final seq = event?['seq'];
    if (seq is! int || seq <= before) return;
    final type = event?['type'];
    if (type == 'model/selection' || type == 'request/header') {
      _modelConfigurationCut = seq;
    } else if (type == 'permission/preset' ||
        type == 'sandbox/mode' ||
        type == 'approval/policy') {
      _permissionConfigurationCut = seq;
    } else {
      return;
    }
    _backgroundConfiguration(_generation);
  }

  void _applyConfiguration(Map<String, Object?> values, int cut) {
    var modelId = _configuration.modelId;
    var modelLabel = _configuration.modelLabel;
    var permissionPreset = _configuration.permissionPreset;
    if (values.containsKey('modelSelection') && cut >= _modelConfigurationCut) {
      final selection = _map(values['modelSelection']);
      if (selection == null || !selection.containsKey('next')) throw _invalid;
      final next = _map(selection['next']);
      if (selection['next'] != null && next == null) throw _invalid;
      if (next == null) {
        modelId = null;
        modelLabel = null;
      } else {
        final provider = _text(next['provider']), model = _text(next['model']);
        if (provider == null || model == null) throw _invalid;
        modelId = jsonEncode([provider, model]);
        modelLabel = model;
      }
      _modelConfigurationCut = cut;
    }
    if (values.containsKey('permissions') &&
        cut >= _permissionConfigurationCut) {
      final preset = _text(_map(values['permissions'])?['currentValue']);
      if (preset == null) throw _invalid;
      permissionPreset = preset;
      _permissionConfigurationCut = cut;
    }
    _publishConfiguration(
      ComposerConfiguration(
        modelId: modelId,
        modelLabel: modelLabel,
        permissionPreset: permissionPreset,
      ),
    );
  }

  /// Reads exact current values; cached migration hints cannot grant permissions.
  Future<void> refreshConfiguration() {
    if (_closed) return Future.error(_closedError);
    if (_configurationRead != null) return _configurationRead!;
    final generation = _generation, revision = _configurationRevision;
    final operation = () async {
      final result = _map(
        await rpc.call('session/projections', {
          'request': {'sessionId': sessionId},
        }),
      );
      _check(generation);
      if (revision != _configurationRevision) return;
      final cut = result?['asOfSeq'], values = _map(result?['values']);
      if (result?['kind'] != 'sequenced' ||
          cut is! int ||
          cut < -1 ||
          values == null) {
        throw _invalid;
      }
      _applyConfiguration(values, cut);
    }();
    _configurationRead = operation;
    unawaited(
      operation.then<void>(
        (_) {
          if (identical(_configurationRead, operation))
            _configurationRead = null;
        },
        onError: (Object _, StackTrace _) {
          if (identical(_configurationRead, operation))
            _configurationRead = null;
        },
      ),
    );
    return operation;
  }

  void _backgroundConfiguration(int generation) {
    unawaited(
      refreshConfiguration().catchError((Object error) {
        if (_current(generation)) capabilityError = error;
      }),
    );
  }

  Future<void> refreshCatalog() {
    if (_closed) return Future.error(_closedError);
    if (_catalogRead != null) return _catalogRead!;
    final generation = _generation;
    final operation = () async {
      final agents = await _subagents.readCatalog(sessionId);
      _check(generation);
      catalogError = null;
      _emit(_projection?.catalog(agents) ?? const []);
    }();
    _catalogRead = operation;
    unawaited(
      operation.then<void>(
        (_) {
          if (identical(_catalogRead, operation)) _catalogRead = null;
        },
        onError: (Object error, StackTrace _) {
          if (_current(generation)) catalogError = error;
          if (identical(_catalogRead, operation)) _catalogRead = null;
        },
      ),
    );
    return operation;
  }

  Future<void> refreshState() {
    if (_closed) return Future.error(_closedError);
    if (_stateRead != null) return _stateRead!;
    final generation = _generation;
    final operation = () async {
      final state = _map(
        await rpc.call('session/state', {
          'request': {'sessionId': sessionId},
        }),
      );
      _check(generation);
      if (state == null ||
          state['running'] is! bool ||
          state['cursor'] is! int ||
          (state['cursor'] as int) < -1)
        throw _invalid;
      final pending = state['pendingInput'];
      if (pending is! List) throw _invalid;
      if (pending.isNotEmpty && _interactions?.state.ready != true)
        throw _unsupported(
          'Pending approval or question requires the DSH Web interface',
        );
      _stateError = null;
      if (state['running'] == false)
        _emit(
          _projection?.idle(throughSeq: state['cursor'] as int) ?? const [],
        );
    }();
    _stateRead = operation;
    unawaited(
      operation.then<void>(
        (_) {
          if (identical(_stateRead, operation)) _stateRead = null;
        },
        onError: (Object _, StackTrace _) {
          if (identical(_stateRead, operation)) _stateRead = null;
        },
      ),
    );
    return operation;
  }

  void _backgroundCatalog(int generation) {
    unawaited(
      refreshCatalog().catchError((Object error) {
        if (_current(generation)) catalogError = error;
      }),
    );
  }

  void _backgroundState(int generation) {
    unawaited(
      refreshState().catchError((Object error) {
        if (!_current(generation) || !_following) return;
        final detail = error.toString();
        if (detail != _stateError) {
          _stateError = detail;
          final projection = _projection;
          if (projection != null) _emit([projection.error(detail)]);
        }
      }),
    );
  }

  @override
  Future<void> sendPrompt(String text) async {
    _checkOpen();
    if (text.trim().isEmpty)
      throw const DshRpcException(
        'session/invalid-prompt',
        'Prompt text is required',
      );
    await connect();
    final generation = _generation;
    final value = _map(
      await rpc.call('session/prompt', {
        'request': {
          'sessionId': sessionId,
          'requestId': _requestId(),
          'mode': 'queue',
          'content': [
            {'type': 'text', 'text': text},
          ],
        },
      }),
    );
    _check(generation);
    if (value?['accepted'] != true) throw _invalid;
  }

  /// Writes the bound session's permission now, without a prompt or a turn.
  ///
  /// The composer's draft selection keeps its own lifecycle; this path exists
  /// for the settings page, where choosing a value is the whole action. It
  /// refuses presets the Host never advertised, never addresses another
  /// session, writes at most once per requested value, and only resolves after
  /// the Host reads the same preset back.
  @override
  Future<void> applySessionPermission(ComposerPermission mode) {
    _checkOpen();
    final preset = mode.wireValue;
    final running = _permissionWrite;
    if (running != null) {
      // Repeating the same value joins the write in flight; a different one is
      // refused instead of queued behind it.
      if (_permissionWritePreset == preset) return running;
      return Future.error(
        const SessionPermissionException(
          'permission/busy',
          '另一个权限写入尚未完成，请等它结束后再改。',
        ),
      );
    }
    // Register the in-flight write before the first await runs, so two taps in
    // the same frame cannot both start a command.
    final completer = Completer<void>();
    final operation = completer.future;
    _permissionWrite = operation;
    _permissionWritePreset = preset;
    unawaited(
      _prepareSessionPermission(preset).then<void>(
        (_) => completer.complete(),
        onError: (Object error, StackTrace stack) =>
            completer.completeError(error, stack),
      ),
    );
    unawaited(
      operation.then<void>(
        (_) => _clearPermissionWrite(operation),
        onError: (Object _, StackTrace _) => _clearPermissionWrite(operation),
      ),
    );
    return operation;
  }

  @override
  Future<void> refreshSessionPermission() {
    _checkOpen();
    return refreshConfiguration();
  }

  Future<void> _prepareSessionPermission(String preset) async {
    // The advertised catalog decides what exists; an empty catalog is read
    // fresh instead of being treated as "nothing supported".
    if (_permissions.isEmpty) await refreshCapabilities();
    _checkOpen();
    if (!_permissions.contains(preset))
      throw SessionPermissionException(
        'permission/unavailable',
        '当前引擎未提供 ' + preset + ' 模式，请使用 Web 版查看。',
      );
    if (_configuration.permissionPreset == preset) return;
    await _writeSessionPermission(preset);
  }

  void _clearPermissionWrite(Future<void> operation) {
    if (!identical(_permissionWrite, operation)) return;
    _permissionWrite = null;
    _permissionWritePreset = null;
  }

  Future<void> _writeSessionPermission(String preset) async {
    if (_submissionBusy)
      throw const SessionPermissionException(
        'permission/busy',
        '正在发送消息，权限写入请稍后重试。',
      );
    var generation = -1;
    try {
      await connect();
      generation = _generation;
      final execution = _map(
        await rpc.call('commands/execute', {
          'agentId': sessionId,
          'line': '/permission ' + preset,
          'submittedAttachments': <Object?>[],
        }),
      );
      _check(generation);
      final result = _map(execution?['result']);
      if (execution == null ||
          _text(execution['commandId']) == null ||
          result == null)
        throw const SessionPermissionException(
          'permission/unsupported',
          '宿主没有可用的 /permission 命令。',
        );
      if (result['kind'] != 'success')
        throw SessionPermissionException(
          'permission/rejected',
          _text(result['text']) ?? '宿主拒绝了这个权限模式。',
        );
      // The receipt only says the command ran. Read the exact value back from
      // the Host; a receipt alone would let a refused write look applied.
      final read = _map(
        await rpc.call('session/projections', {
          'request': {'sessionId': sessionId},
        }),
      );
      _check(generation);
      final cut = read?['asOfSeq'], values = _map(read?['values']);
      if (read?['kind'] != 'sequenced' ||
          cut is! int ||
          cut < -1 ||
          values == null)
        throw _invalid;
      // Publish what the Host reports, never the value we asked for. The cut
      // only moves forward, so an older follow frame cannot undo the read-back.
      ++_configurationRevision;
      if (cut > _permissionConfigurationCut) _permissionConfigurationCut = cut;
      final current = _text(_map(values['permissions'])?['currentValue']);
      _publishConfiguration(
        ComposerConfiguration(
          modelId: _configuration.modelId,
          modelLabel: _configuration.modelLabel,
          permissionPreset: current ?? _configuration.permissionPreset,
        ),
      );
      if (current != preset)
        throw SessionPermissionException(
          'permission/unconfirmed',
          '宿主回读的权限是 ' + (current ?? '未知') + '，不是 ' + preset + '。',
        );
    } on SessionPermissionException {
      rethrow;
    } on Object catch (error) {
      if (generation >= 0 && generation != _generation)
        throw const SessionPermissionException(
          'permission/stale',
          '连接已更换，这次权限写入不能视为已生效。',
        );
      if (error is DshRpcException && error.code == 'session/closed')
        throw const SessionPermissionException(
          'permission/stale',
          '连接已关闭，这次权限写入不能视为已生效。',
        );
      throw SessionPermissionException(
        'permission/failed',
        '权限写入没有完成：' + error.toString(),
      );
    }
  }

  @override
  Future<void> sendSubmission(PromptSubmission submission) async {
    _checkOpen();
    if (_permissionWrite != null)
      throw const DshRpcException(
        'session/permission-write-busy',
        'A permission write is still in progress',
      );
    // Validate every option before the first mutation; no unsupported input is discarded.
    if (submission.attachments.isNotEmpty)
      throw _unsupported(
        'Native attachment upload is unavailable; use the DSH Web interface',
      );
    if (submission.text.trim().isEmpty)
      throw const DshRpcException(
        'session/invalid-prompt',
        'Prompt text is required',
      );
    if (_submissionBusy)
      throw const DshRpcException(
        'session/submission-busy',
        'A prompt submission is already in progress',
      );
    _submissionBusy = true;
    try {
      await connect();
      final generation = _generation;
      final permission = submission.permission.wireValue;
      if (!submission.applyPermission && _configuration.permission == null) {
        throw _unsupported(
          'The inherited session permission is unknown; explicitly select a permission before sending',
        );
      }
      if (submission.applyPermission && !_permissions.contains(permission))
        throw _unsupported(
          'The host does not advertise permission preset ' + permission,
        );
      final model = !submission.applyModel || submission.modelId == null
          ? null
          : _routes[submission.modelId];
      if (submission.applyModel && submission.modelId != null && model == null)
        throw _unsupported('The selected model is unavailable');
      if (model != null) {
        final selected = _map(
          await rpc.call('session/selectModel', {
            'request': {'sessionId': sessionId, ...model},
          }),
        );
        _check(generation);
        final route = _map(selected?['selected']);
        if (route?['provider'] != model['provider'] ||
            route?['model'] != model['model'])
          throw _invalid;
        ++_configurationRevision;
        _publishConfiguration(
          ComposerConfiguration(
            modelId: submission.modelId,
            modelLabel: _models
                .where((m) => m.id == submission.modelId)
                .firstOrNull
                ?.label,
            permissionPreset: _configuration.permissionPreset,
          ),
        );
      }
      if (submission.applyPermission) {
        final execution = _map(
          await rpc.call('commands/execute', {
            'agentId': sessionId,
            'line': '/permission ' + permission,
            'submittedAttachments': <Object?>[],
          }),
        );
        _check(generation);
        final result = _map(execution?['result']);
        if (execution == null ||
            _text(execution['commandId']) == null ||
            result == null)
          throw _unsupported('The host has no usable /permission command');
        if (result['kind'] != 'success')
          throw DshRpcException(
            'session/permission-failed',
            _text(result['text']) ?? 'The host rejected the permission preset',
          );
        ++_configurationRevision;
        _publishConfiguration(
          ComposerConfiguration(
            modelId: _configuration.modelId,
            modelLabel: _configuration.modelLabel,
            permissionPreset: permission,
          ),
        );
      }
      if (!submission.applyPermission && _configuration.permission == null) {
        throw _unsupported(
          'The inherited session permission changed while configuring the model; explicitly select a permission before sending',
        );
      }
      await sendPrompt(submission.text);
    } finally {
      _submissionBusy = false;
    }
  }

  @override
  Future<void> stop() async {
    _checkOpen();
    await connect();
    final generation = _generation;
    final value = _map(
      await rpc.call('session/cancel', {
        'request': {'sessionId': sessionId},
      }),
    );
    _check(generation);
    if (value?['accepted'] != true) throw _invalid;
  }

  @override
  Future<void> approve(String requestId) async {
    _checkOpen();
    final service = _interactions;
    if (service == null)
      throw _unsupported('Approval replies require the DSH Web interface');
    await service.approve(requestId);
  }

  @override
  Future<void> rejectApproval(String requestId) async {
    _checkOpen();
    final service = _interactions;
    if (service == null) {
      throw _unsupported('Approval replies require the DSH Web interface');
    }
    await service.decideApproval(requestId, NativeApprovalDecision.reject);
  }

  @override
  Future<void> answer(String requestId, String answer) async {
    _checkOpen();
    final service = _interactions;
    if (service == null)
      throw _unsupported('Question replies require the DSH Web interface');
    await service.answer(requestId, answer);
  }

  @override
  Future<SubagentConversation> readSubagent(SubagentAddress address) async {
    _checkChild(address);
    final generation = _generation;
    final result = await _subagents.readSubagent(address);
    _check(generation);
    return result;
  }

  @override
  Stream<SubagentConversation> watchSubagent(SubagentAddress address) async* {
    _checkChild(address);
    final generation = _generation;
    await for (final value in _subagents.watchSubagent(address)) {
      _check(generation);
      yield value;
    }
  }

  @override
  Future<SubagentReceipt> sendSubagent(
    SubagentAddress address,
    String text, {
    required String requestId,
  }) async {
    _checkChild(address);
    final generation = _generation;
    final result = await _subagents.sendSubagent(
      address,
      text,
      requestId: requestId,
    );
    _check(generation);
    return result;
  }

  @override
  Future<void> answerQuestion(String requestId, QuestionAnswer answer) async {
    _checkOpen();
    final service = _interactions;
    if (service == null)
      throw _unsupported('Question replies require the DSH Web interface');
    final item = service.state.pending
        .where((item) => item.requestId == requestId)
        .firstOrNull;
    final question = item?.question;
    if (question == null)
      throw _unsupported('The question is no longer available');
    await service.answerStructured(
      requestId,
      NativeQuestionReply(
        questionId: question.id,
        selected: answer.selected,
        custom: answer.custom.isEmpty ? null : answer.custom,
      ),
    );
  }

  void _syncInteractions() {
    final service = _interactions, projection = _projection;
    if (service == null || projection == null) return;
    final state = service.state;
    final pending = state.pending.firstOrNull;
    _emit(
      projection.interaction(
        pending?.action,
        question: pending?.kind == NativeInteractionKind.question,
      ),
    );
    final unsupported = state.interactions
        .where((i) => i.agentId == sessionId && i.unsupportedReason != null)
        .lastOrNull;
    final error = state.error ?? unsupported?.unsupportedReason;
    if (error != null && error != _interactionError && projection.opened) {
      _interactionError = error;
      _emit([projection.error(error + ' 请使用 Web 版处理。')]);
    }
  }

  Future<void> _closeInteractions() async {
    final service = _interactions;
    _interactions = null;
    _interactionError = null;
    await _interactionUpdates?.cancel();
    _interactionUpdates = null;
    await service?.close();
  }

  void _checkChild(SubagentAddress address) {
    _checkOpen();
    if (address.parentSessionId != sessionId)
      throw const DshRpcException(
        'subagent/invalid-address',
        'Child must belong to this parent session',
      );
  }

  void _fail(int generation, Object error, Completer<void> opened) {
    if (!_current(generation) || !_following) return;
    _following = false;
    _timer?.cancel();
    _timer = null;
    final subscription = _follow;
    _follow = null;
    if (subscription != null) unawaited(subscription.cancel());
    if (!opened.isCompleted) opened.completeError(error);
    final projection = _projection;
    if (projection != null && projection.opened) {
      _emit([projection.error(error.toString())]);
    } else if (!_closed) {
      _events.addError(error);
    }
  }

  bool _current(int generation) => !_closed && generation == _generation;
  void _checkOpen() {
    if (_closed) throw _closedError;
  }

  void _check(int generation) {
    _checkOpen();
    if (generation != _generation)
      throw const DshRpcException(
        'session/stale-generation',
        'Session operation belongs to an old connection',
      );
  }

  void _emit(List<ChatEvent> events) {
    if (!_closed) {
      for (final event in events) {
        _events.add(event);
      }
    }
  }

  String _requestId() =>
      'native-' +
      DateTime.now().microsecondsSinceEpoch.toString() +
      '-' +
      (++_requestSerial).toString();

  @override
  Future<void> close() {
    final closing = _closing;
    if (closing != null) return closing;
    _closed = true;
    ++_generation;
    _following = false;
    _timer?.cancel();
    _timer = null;
    final subscription = _follow;
    _follow = null;
    final opened = _opening;
    _opening = null;
    if (opened != null && !opened.isCompleted)
      opened.completeError(_closedError);
    _closing = () async {
      await _closeInteractions();
      await subscription?.cancel();
      await rpc.close();
      // An unobserved single-subscription stream must not delay resource disposal.
      unawaited(_events.close());
      unawaited(_configurationChanges.close());
    }();
    return _closing!;
  }
}

const _invalid = DshRpcException(
  'session/protocol',
  'Invalid session RPC response',
);
const _closedError = DshRpcException(
  'session/closed',
  'Session transport is closed',
);
DshRpcException _unsupported(String message) =>
    DshRpcException('session/unsupported', message);
Map<String, Object?>? _map(Object? value) =>
    value is Map<String, Object?> ? value : null;
String? _text(Object? value) =>
    value is String && value.isNotEmpty ? value : null;
