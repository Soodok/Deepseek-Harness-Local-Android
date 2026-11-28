import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../model/engine_agent_preset.dart';
import '../model/engine_session.dart';
import '../model/engine_workspace.dart';
import '../model/settings_model.dart';
import '../state/chat_controller.dart';
import '../state/composer_draft.dart';
import '../state/session_drafts.dart';
import '../state/native_conversation_actions.dart';
import '../transport/dsh_rpc_connection.dart';
import '../transport/native_action_transport.dart';
import '../transport/native_file_delivery_service.dart';
import '../transport/native_session_transport.dart';
import '../ui/chat_shell.dart';
import '../ui/native_workspace_directory_sheet.dart';
import '../ui/session_drawer.dart';
import '../ui/settings/settings_detail_pages.dart';
import '../ui/settings/settings_home.dart';
import '../ui/settings/settings_page.dart';
import '../ui/settings/settings_scope.dart';
import 'host_bridge.dart';
import 'native_workspace_directories.dart';
import 'native_workspace_policy.dart';
import '../ui/native_workspace_picker.dart';

/// Android root entry. The caller keeps HostInfo current, including its token URL.
Widget buildEngineHome(HostInfo host) => EngineHome(host: host);

Future<void> showEngineSessions(BuildContext context, HostInfo host) =>
    Navigator.of(context)
        .push<void>(MaterialPageRoute(builder: (_) => buildEngineHome(host)));

/// Session Controller commands use the generated Remote parameter names.
class EngineSessionService {
  const EngineSessionService(this.rpc);
  final DshRpcConnection rpc;

  Future<List<EngineSession>> list() async => EngineSession.fromResponse(
    await rpc.call('session/list', {'_request': <String, Object?>{}}),
  );

  Stream<Map<String, Object?>> followWorkspaces() =>
      rpc.follow('workspace/follow', const {});

  Future<EngineWorkspace> registerWorkspace(String path) async {
    final value = await rpc.call('workspace/create', {
      'request': {'path': path},
    });
    if (value is! Map || value['created'] is! bool) {
      throw const FormatException('Host 工作区注册回执无效');
    }
    final row = EngineWorkspace.fromWire(value['workspace']);
    if (row.id.trim().isEmpty || row.path != path) {
      throw const FormatException('Host 返回路径与授权目录不一致');
    }
    return row;
  }

  Future<List<EngineAgentPreset>> listAgentPresets() async =>
      EngineAgentPreset.fromResponse(
        await rpc.call('agentPresets/list', const {}),
      );

  Future<String> selectAgentPreset(String sessionId, String presetId) async {
    final result = await rpc.call('agentPresets/select', {
      'agentId': sessionId,
      'agentPreset': presetId,
    });
    if (result is! String || result.isEmpty) {
      throw const FormatException('Invalid DSH selected Agent preset');
    }
    return result;
  }

  Future<String> createBlank() async => (await createSession()).id;

  /// Omit absent values; the Host resolves its own directory and preset.
  Future<EngineSession> createSession({
    EngineWorkspace? workspace,
    String? agentPresetId,
  }) async {
    final result = await rpc.call('session/create', {
      'request': {
        if (workspace != null) 'workspaceId': workspace.id,
        'agentPreset': ?agentPresetId,
      },
    });
    return EngineSession(
      id: _createdId(result),
      updatedAt: 0,
      blank: true,
      cwd: workspace?.path ?? '',
      agentPresetId: (result as Map)['agentPreset'] as String?,
    );
  }

  static String _createdId(Object? value) {
    if (value is! Map ||
        value['sessionId'] is! String ||
        (value['sessionId'] as String).trim().isEmpty) {
      throw const FormatException('Invalid DSH created session identity');
    }
    return value['sessionId'] as String;
  }

  static bool validCwd(String cwd) =>
      cwd.isNotEmpty &&
      !cwd.codeUnits.any((c) => c < 32 || c == 127) &&
      (cwd.startsWith('/') ||
          RegExp(r'^[A-Za-z]:[\\/]').hasMatch(cwd) ||
          RegExp(r'^\\\\[^\\]+\\[^\\]+').hasMatch(cwd));

  Future<String> create(String cwd) async {
    if (!validCwd(cwd)) throw ArgumentError('请输入引擎工作目录的合法绝对路径');
    // session/create accepts cwd OR workspaceId. No client-generated identity,
    // model, permission, or fabricated workspace is sent in this request.
    final value = await rpc.call('session/create', {
      'request': {'cwd': cwd},
    });
    return _createdId(value);
  }
}

/// Owns one Host generation; all routes and live RPCs remain inside this home.
class EngineHome extends StatefulWidget {
  const EngineHome({super.key, required this.host, this.createRpc});
  final HostInfo host;
  final DshRpcConnection Function(String webUrl)? createRpc;
  @override
  State<EngineHome> createState() => _EngineHomeState();
}

class _EngineHomeState extends State<EngineHome> {
  final _peers = <DshRpcConnection>{};
  int _generation = 0;
  DshRpcConnection? _catalogRpc;
  GlobalKey<NavigatorState> _navigator = GlobalKey<NavigatorState>();
  String? _error;

  @override
  void initState() {
    super.initState();
    _bindHost();
  }

  DshRpcConnection _createPeer() {
    final peer =
        widget.createRpc?.call(widget.host.webUrl) ??
        DshRpcConnection(webUrl: widget.host.webUrl);
    _peers.add(peer);
    return peer;
  }

  void _bindHost() {
    _error = null;
    _catalogRpc = null;
    if (!widget.host.available || widget.host.webUrl.isEmpty) return;
    try {
      _catalogRpc = _createPeer();
    } on Object {
      _error = '本机引擎连接信息暂不可用，请打开设置或 Web 检查。';
    }
  }

  void _closePeers() {
    for (final peer in _peers) {
      unawaited(peer.close());
    }
    _peers.clear();
  }

  @override
  void didUpdateWidget(covariant EngineHome oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.host.webUrl != widget.host.webUrl ||
        oldWidget.host.available != widget.host.available ||
        oldWidget.host.workspaceDomain != widget.host.workspaceDomain ||
        oldWidget.host.workspaceGeneration != widget.host.workspaceGeneration) {
      ++_generation;
      _navigator = GlobalKey<NavigatorState>();
      _closePeers();
      _bindHost();
    }
  }

  @override
  void dispose() {
    ++_generation;
    _closePeers();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final generation = _generation;
    // Replacing this local Navigator also removes old dialogs/settings pages.
    // It never pops or replaces the application's root navigation stack.
    return NavigatorPopHandler<Object?>(
      onPopWithResult: (result) => _navigator.currentState?.maybePop(result),
      child: Navigator(
        key: _navigator,
        onDidRemovePage: (_) {},
        pages: [
          MaterialPage<void>(
            key: const ValueKey('engine-home-page'),
            child: _EngineHomeContent(
              host: widget.host,
              rpc: _catalogRpc,
              error: _error,
              createPeer: _createPeer,
              releasePeer: _peers.remove,
              current: () => mounted && generation == _generation,
            ),
          ),
        ],
      ),
    );
  }
}

class _EngineHomeContent extends StatefulWidget {
  const _EngineHomeContent({
    required this.host,
    required this.rpc,
    required this.error,
    required this.createPeer,
    required this.releasePeer,
    required this.current,
  });
  final HostInfo host;
  final DshRpcConnection? rpc;
  final String? error;
  final DshRpcConnection Function() createPeer;
  final bool Function(DshRpcConnection) releasePeer;
  final bool Function() current;
  @override
  State<_EngineHomeContent> createState() => _EngineHomeContentState();
}

class _EngineHomeContentState extends State<_EngineHomeContent> {
  ChatController? _controller;
  DshRpcConnection? _chatRpc;
  NativeConversationActions? _actions;
  NativeFileDeliveryService? _fileDelivery;

  final _drafts = SessionDrafts(
    read: HostBridge.readSessionDraft,
    write: HostBridge.writeSessionDraft,
    autosave: true,
  );
  ComposerDraft? _draft;
  int _openRequest = 0;
  String? _currentSessionId;
  List<EngineSession> _sessions = const [];
  bool _sessionsLoading = false;
  String? _sessionsError;
  bool _bootstrapping = false;
  Timer? _catalogRetry;
  int _catalogRetrySeconds = 1;
  final _workspaces = EngineWorkspaceCatalog();
  StreamSubscription<Map<String, Object?>>? _workspaceFeed;
  Timer? _workspaceRetry;
  int _workspaceRetrySeconds = 1;
  int _workspaceFeedGeneration = 0;
  String? _workspaceError;
  bool _workspaceOnline = false;
  late final NativeWorkspacePolicy _workspacePolicy;
  bool _savingWorkspaceDefault = false;
  bool _awaitingExplicitCreate = false;
  bool _refreshAfterLoad = false;
  bool _creating = false;
  List<EngineAgentPreset> _agentPresets = const [];
  String? _selectedAgentPresetId;
  bool _agentPresetLocked = true;
  bool _agentPresetReady = false;
  bool _selectingPreset = false;
  Future<void>? _presetRead;
  Future<String?>? _initialSessionFuture;
  ({
    EngineWorkspace expected,
    int afterRevision,
    Completer<EngineWorkspace?> ready,
  })?
  _directoryConfirmation;
  int _workspaceFeedRevision = 0;
  bool _pickingDirectory = false;
  NativeWorkspaceDirectory? _selectedDirectory;

  bool get _workspaceBusy =>
      _creating || _pickingDirectory || _savingWorkspaceDefault;
  bool get _directoryActionsAvailable =>
      widget.host.available &&
      widget.host.workspaceDomain.isNotEmpty &&
      widget.host.workspaceGeneration.isNotEmpty;

  Widget _withWorkspaceDirectoryActions(Widget child) =>
      NativeWorkspaceDirectoryScope(
        selectedDirectory: _selectedDirectory,
        onPickPrivate: !_directoryActionsAvailable || _workspaceBusy
            ? null
            : () => _pickDirectory(shared: false),
        onPickShared: !_directoryActionsAvailable || _workspaceBusy
            ? null
            : () => _pickDirectory(shared: true),
        child: child,
      );

  @override
  void initState() {
    super.initState();
    final host = widget.host;
    _workspacePolicy = NativeWorkspacePolicy(
      domain: host.workspaceDomain,
      generation: host.workspaceGeneration,
      read: () => HostBridge.readDefaultWorkspace(host),
      write: (target) =>
          HostBridge.writeDefaultWorkspace(host, target.toWire()),
      remove: () => HostBridge.clearDefaultWorkspace(host),
    );
    unawaited(_readWorkspacePreference());
    final rpc = widget.rpc;
    if (rpc != null) {
      _followWorkspaces(rpc);
      unawaited(_loadPresets(rpc));
    }
    unawaited(_bootstrap());
  }

  @override
  void dispose() {
    _catalogRetry?.cancel();
    _workspaceRetry?.cancel();
    _cancelDirectoryConfirmation();
    unawaited(_workspaceFeed?.cancel());
    final drafts = _drafts;
    unawaited(drafts.flushAll().whenComplete(drafts.dispose));
    _closeChatPeer();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant _EngineHomeContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.rpc != widget.rpc) {
      _catalogRetry?.cancel();
      _catalogRetry = null;
      _catalogRetrySeconds = 1;
      _initialSessionFuture = null;
      _awaitingExplicitCreate = false;
      _selectedDirectory = null;
      _cancelDirectoryConfirmation();
      _workspaceFeedRevision = 0;
      _pickingDirectory = false;
      _creating = false;
      _controller = null;
      _currentSessionId = null;
      _openRequest++;
      _sessions = const [];
      _sessionsError = null;
      _closeChatPeer();
      unawaited(_bootstrap());
    }
  }

  void _closeChatPeer() {
    _actions = null;
    _fileDelivery = null;
    final peer = _chatRpc;
    _chatRpc = null;
    if (peer != null) {
      widget.releasePeer(peer);
      unawaited(peer.close());
    }
  }

  /// A drawer row or the startup step asked for a Session; nothing else opens one.
  ///
  /// The catalogue is the only source of identity. When it is empty the Host is
  /// asked for a blank Session with no directory value of our own choosing, so
  /// the engine's own default applies instead of an invented path.
  Future<void> _bootstrap() async {
    final rpc = widget.rpc;
    if (rpc == null || _bootstrapping || _awaitingExplicitCreate) return;
    _bootstrapping = true;
    try {
      // Read the catalogue and handoff together, retaining the original handoff
      // across retries. A timeout means the identity is still pending; only a
      // completed null result permits choosing a default Session.
      final handoff =
          (_initialSessionFuture ??= HostBridge.readInitialSessionId()).timeout(
            const Duration(milliseconds: 400),
          );
      final results = await Future.wait<Object?>([_loadSessions(rpc), handoff]);
      if (!_currentCatalog(rpc) || _awaitingExplicitCreate) return;
      final rows = results[0] as List<EngineSession>?;
      final handedOver = results[1] as String?;
      if (rows == null) {
        _scheduleCatalogRetry(rpc);
        return;
      }
      _catalogRetrySeconds = 1;
      if (handedOver != null) {
        final carried = rows
            .where((row) => row.openable && row.id == handedOver)
            .firstOrNull;
        if (carried == null) {
          _scheduleCatalogRetry(rpc);
        } else {
          unawaited(_open(carried));
        }
        return;
      }
      final target = rows.where((row) => row.openable).firstOrNull;
      if (target != null) {
        unawaited(_open(target));
        return;
      }
      if (_currentCatalog(rpc)) await _createBlank(rpc);
    } on TimeoutException {
      if (_currentCatalog(rpc)) _scheduleCatalogRetry(rpc);
    } finally {
      _bootstrapping = false;
    }
  }

  void _scheduleCatalogRetry(DshRpcConnection rpc) {
    _catalogRetry?.cancel();
    _catalogRetry = Timer(Duration(seconds: _catalogRetrySeconds), () {
      _catalogRetry = null;
      if (mounted && widget.current() && identical(rpc, widget.rpc)) {
        unawaited(_bootstrap());
      }
    });
    if (_catalogRetrySeconds < 16) _catalogRetrySeconds *= 2;
  }

  Future<List<EngineSession>?> _loadSessions(DshRpcConnection rpc) async {
    setState(() {
      _sessionsLoading = true;
      _sessionsError = null;
    });
    try {
      final rows = await EngineSessionService(rpc).list();
      if (!mounted || !widget.current() || !identical(rpc, widget.rpc)) {
        return null;
      }
      setState(() {
        _sessions = rows;
        _sessionsLoading = false;
      });
      return rows;
    } on Object catch (error) {
      if (!mounted || !widget.current() || !identical(rpc, widget.rpc)) {
        return null;
      }
      setState(() {
        _sessionsLoading = false;
        _sessionsError = '会话列表读取失败：' + error.toString();
      });
      return null;
    } finally {
      if (_currentCatalog(rpc) && _refreshAfterLoad) {
        _refreshAfterLoad = false;
        unawaited(_refreshSessions());
      }
    }
  }

  bool _currentCatalog(DshRpcConnection rpc) =>
      mounted && widget.current() && identical(rpc, widget.rpc);

  void _followWorkspaces(DshRpcConnection rpc) {
    final generation = ++_workspaceFeedGeneration;
    unawaited(_workspaceFeed?.cancel());
    bool current() =>
        _currentCatalog(rpc) && generation == _workspaceFeedGeneration;
    // Each subscription requires a new baseline, even when old rows are shown.
    var needsBaseline = true;
    void disconnected(Object error) {
      if (!current() || _workspaceRetry != null) return;
      unawaited(_workspaceFeed?.cancel());
      _workspaceFeed = null;
      setState(() {
        _workspaceOnline = false;
        _workspaceError = '工作区读取失败：' + error.toString();
      });
      _workspaceRetry = Timer(Duration(seconds: _workspaceRetrySeconds), () {
        _workspaceRetry = null;
        if (current()) _followWorkspaces(rpc);
      });
      if (_workspaceRetrySeconds < 16) _workspaceRetrySeconds *= 2;
    }

    _workspaceFeed = EngineSessionService(rpc).followWorkspaces().listen(
      (frame) {
        if (!current()) return;
        try {
          if (needsBaseline && frame['type'] != 'baseline') {
            throw const FormatException('Missing DSH Workspace baseline');
          }
          final previous = _workspaces.workspaces
              .expand((row) => row.sessionIds)
              .toSet();
          _workspaces.accept(frame);
          needsBaseline = false;
          final known = _sessions.map((row) => row.id).toSet();
          final unknownMembers = _workspaces.workspaces
              .expand((row) => row.sessionIds)
              .any((id) => !previous.contains(id) && !known.contains(id));
          setState(() {
            _workspaceOnline = true;
            _workspaceError = null;
            _workspaceRetrySeconds = 1;
          });
          _confirmRegisteredDirectory(frame, ++_workspaceFeedRevision);
          if (_controller == null && !_workspaceBusy) unawaited(_bootstrap());
          if (unknownMembers && _controller != null)
            unawaited(_refreshSessions());
        } on Object catch (error) {
          disconnected(error);
        }
      },
      onError: disconnected,
      onDone: () => disconnected('连接已断开'),
    );
  }

  Future<void> _refreshSessions({bool refreshPresets = false}) async {
    final rpc = widget.rpc;
    if (rpc == null) return;
    if (_sessionsLoading) {
      _refreshAfterLoad = true;
      return;
    }
    if (refreshPresets) unawaited(_loadPresets(rpc));
    await _loadSessions(rpc);
    if (_workspaceFeed == null && _currentCatalog(rpc)) {
      _workspaceRetry?.cancel();
      _workspaceRetry = null;
      _followWorkspaces(rpc);
    }
  }

  Future<void> _loadPresets(DshRpcConnection rpc) =>
      _presetRead ??= EngineSessionService(rpc)
          .listAgentPresets()
          .then<void>(
            (rows) {
              if (_currentCatalog(rpc)) setState(() => _agentPresets = rows);
            },
            onError: (Object _) {
              if (_currentCatalog(rpc))
                setState(() => _agentPresets = const []);
            },
          )
          .whenComplete(() => _presetRead = null);

  String? get _defaultPresetId =>
      _agentPresets.where((row) => row.isDefault).firstOrNull?.id;

  Future<bool> _selectAgentPreset(String id) async {
    final rpc = _chatRpc;
    final sessionId = _currentSessionId;
    if (rpc == null ||
        sessionId == null ||
        !_agentPresetReady ||
        _agentPresetLocked ||
        _selectingPreset ||
        !_agentPresets.any((row) => row.id == id && row.broken == null))
      return false;
    bool current() => mounted && widget.current() && identical(rpc, _chatRpc);
    setState(() => _selectingPreset = true);
    try {
      final selected = await EngineSessionService(rpc)
          .selectAgentPreset(sessionId, id);
      if (!current()) return false;
      setState(() => _selectedAgentPresetId = selected);
      return true;
    } on Object catch (error) {
      if (mounted && current()) {
        if (error is DshRpcException && error.code == 'agent-preset/locked') {
          setState(() => _agentPresetLocked = true);
        }
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('会话模式切换失败：' + error.toString())));
      }
      return false;
    } finally {
      if (current()) setState(() => _selectingPreset = false);
    }
  }

  Future<void> _readWorkspacePreference() async {
    await _workspacePolicy.refresh();
    if (!mounted || !widget.current()) return;
    setState(() {});
    if (_controller == null && !_workspaceBusy) unawaited(_bootstrap());
  }

  Future<void> _retryWorkspace() async {
    final rpc = widget.rpc;
    if (rpc == null || _workspaceBusy) return;
    _workspaceRetry?.cancel();
    _workspaceRetry = null;
    setState(() => _workspaceOnline = false);
    _followWorkspaces(rpc);
    await _readWorkspacePreference();
  }

  WorkspaceTarget? get _displayWorkspace => _workspacePolicy.defaultTarget;

  String? get _workspaceStatus => _workspacePolicy.error ?? _workspaceError;

  Future<bool> _selectWelcomeWorkspace(String? id) async {
    final rpc = widget.rpc;
    if (rpc == null || !_currentCatalog(rpc) || _workspaceBusy) return false;
    final row = _workspaces.workspaces.where((row) => row.id == id).firstOrNull;
    if (id != null && (!_workspaceOnline || row == null)) return false;
    final target = row == null
        ? const WorkspaceTarget.home()
        : WorkspaceTarget.workspace(row.id, row.path);
    setState(() {
      _savingWorkspaceDefault = true;
      if (_controller == null) _awaitingExplicitCreate = true;
    });
    try {
      if (row != null) {
        await NativeWorkspaceDirectories(widget.host)
            .validateRegistered(row.path);
        if (!_currentCatalog(rpc)) return false;
        _requireCurrentWorkspace(row);
      }
      final saved = await _workspacePolicy.save(target);
      if (!_currentCatalog(rpc) || !saved) return false;
      if (row == null || row.path != _selectedDirectory?.path) {
        _selectedDirectory = null;
      }
      return true;
    } finally {
      if (_currentCatalog(rpc)) setState(() => _savingWorkspaceDefault = false);
    }
  }

  void _cancelDirectoryConfirmation() {
    final pending = _directoryConfirmation;
    _directoryConfirmation = null;
    if (pending != null && !pending.ready.isCompleted) {
      pending.ready.complete(null);
    }
  }

  void _confirmRegisteredDirectory(Map<String, Object?> frame, int revision) {
    final pending = _directoryConfirmation;
    if (pending == null ||
        pending.ready.isCompleted ||
        !_workspaceOnline ||
        revision <= pending.afterRevision) {
      return;
    }
    final candidates = switch (frame['type']) {
      'upsert' => [frame['workspace']],
      'baseline' || 'replace' =>
        (frame['value'] is Map && (frame['value'] as Map)['items'] is List)
            ? ((frame['value'] as Map)['items'] as List)
            : const <Object?>[],
      _ => const <Object?>[],
    };
    final receipt = candidates
        .whereType<Map>()
        .where((item) => item['workspaceId'] == pending.expected.id)
        .firstOrNull;
    if (receipt == null) return;
    if (receipt['path'] != pending.expected.path) {
      pending.ready.completeError(const FormatException('Host 工作区路径已变化，请重新选择'));
      return;
    }
    final row = _workspaces.workspaces
        .where((item) => item.id == pending.expected.id)
        .firstOrNull;
    if (row != null && row.path == pending.expected.path) {
      pending.ready.complete(row);
    }
  }

  Future<EngineWorkspace?> _awaitRegisteredDirectory(
    DshRpcConnection rpc,
    EngineWorkspace expected,
    int afterRevision,
  ) async {
    final ready = Completer<EngineWorkspace?>();
    _directoryConfirmation = (
      expected: expected,
      afterRevision: afterRevision,
      ready: ready,
    );
    final confirmed = ready.future.timeout(
      const Duration(seconds: 8),
      onTimeout: () => throw StateError('Host 工作区列表未确认此目录，请刷新后重选'),
    );
    try {
      _followWorkspaces(rpc);
      return await confirmed;
    } finally {
      if (identical(_directoryConfirmation?.ready, ready)) {
        _directoryConfirmation = null;
      }
    }
  }

  Future<bool> _pickDirectory({required bool shared}) async {
    final rpc = widget.rpc;
    if (rpc == null ||
        !_currentCatalog(rpc) ||
        _workspaceBusy ||
        !_directoryActionsAvailable) {
      return false;
    }
    final directories = NativeWorkspaceDirectories(widget.host);
    setState(() {
      _pickingDirectory = true;
      if (_controller == null) _awaitingExplicitCreate = true;
    });
    try {
      final selected = shared
          ? await directories.pickShared()
          : await showPrivateWorkspaceDirectorySheet(context, directories);
      if (selected == null || !_currentCatalog(rpc)) return false;
      final checked = await directories.validate(selected);
      if (!_currentCatalog(rpc)) return false;
      final feedRevision = _workspaceFeedRevision;
      final registered = await EngineSessionService(rpc)
          .registerWorkspace(checked.path);
      if (!_currentCatalog(rpc)) return false;
      final row = await _awaitRegisteredDirectory(
        rpc,
        registered,
        feedRevision,
      );
      if (row == null || !_currentCatalog(rpc)) return false;
      await directories.remember(checked);
      if (!_currentCatalog(rpc)) return false;
      _requireCurrentWorkspace(row);
      final saved = await _workspacePolicy.save(
        WorkspaceTarget.workspace(row.id, row.path),
      );
      if (!_currentCatalog(rpc)) return false;
      if (!saved) {
        throw StateError(_workspacePolicy.error ?? '默认工作区保存失败，请重试');
      }
      setState(() => _selectedDirectory = checked);
      return true;
    } on Object catch (error) {
      if (mounted && _currentCatalog(rpc)) {
        ScaffoldMessenger.maybeOf(context)?.showSnackBar(
          SnackBar(content: Text('工作区目录不可用：' + error.toString())),
        );
      }
      return false;
    } finally {
      if (_currentCatalog(rpc)) setState(() => _pickingDirectory = false);
    }
  }

  void _requireCurrentWorkspace(EngineWorkspace expected) {
    if (!_workspaceOnline ||
        !_workspaces.workspaces.any(
          (row) => row.id == expected.id && row.path == expected.path,
        )) {
      throw StateError('工作区目录已变化，请刷新后重新选择');
    }
  }

  Future<void> _createBlank(
    DshRpcConnection rpc, {
    EngineWorkspace? workspace,
    bool explicitWorkspace = false,
  }) async {
    if (!_currentCatalog(rpc) || _workspaceBusy) return;
    final intent = _openRequest;
    try {
      final explicit = explicitWorkspace
          ? (workspace == null
                ? const WorkspaceTarget.home()
                : WorkspaceTarget.workspace(workspace.id, workspace.path))
          : null;
      final resolution = _workspacePolicy.resolve(
        rows: _workspaces.workspaces,
        online: _workspaceOnline,
        explicit: explicit,
      );
      setState(() => _creating = true);
      final resolvedWorkspace = resolution.workspace;
      if (resolvedWorkspace != null) {
        await NativeWorkspaceDirectories(widget.host)
            .validateRegistered(resolvedWorkspace.path);
        if (!_currentCatalog(rpc)) return;
        _requireCurrentWorkspace(resolvedWorkspace);
      }
      final row = await EngineSessionService(rpc)
          .createSession(workspace: resolvedWorkspace);
      if (!_currentCatalog(rpc)) return;
      if (_openRequest != intent) return;
      await _open(row);
    } on Object catch (error) {
      if (_currentCatalog(rpc)) {
        setState(() => _sessionsError = '新建会话失败：' + error.toString());
        if (mounted && _controller != null) {
          ScaffoldMessenger.of(context)
              .showSnackBar(SnackBar(content: Text(_sessionsError!)));
        }
      }
    } finally {
      if (_currentCatalog(rpc)) setState(() => _creating = false);
    }
  }

  Future<void> _open(EngineSession row) async {
    if (!mounted || !widget.current() || !row.openable) return;
    final request = ++_openRequest;
    if (row.id == _currentSessionId && _controller != null) return;
    // Newly created/forked rows can be missing cwd until the catalogue catches
    // up. Resolve it from the Host rather than inventing a local directory.
    var workspaceRoot = row.cwd;
    if (workspaceRoot.isEmpty) {
      final catalogueRpc = widget.rpc;
      if (catalogueRpc != null) {
        try {
          final rows = await EngineSessionService(catalogueRpc).list();
          workspaceRoot =
              rows.where((item) => item.id == row.id).firstOrNull?.cwd ?? '';
        } on Object {
          // File actions will report an unavailable workspace; chat still opens.
        }
        if (!mounted || !widget.current()) return;
      }
    }
    if (!mounted || !widget.current() || request != _openRequest) return;
    final rpc = widget.createPeer();
    final controller = ChatController(
      NativeSessionTransport(
        rpc: rpc,
        sessionId: row.id,
        enableInteractions: true,
        activateBlankSession: row.blank,
        onAgentPresetState: (id, started) {
          if (!mounted || !widget.current() || !identical(rpc, _chatRpc))
            return;
          setState(() {
            _agentPresetReady = true;
            if (id != null) _selectedAgentPresetId = id;
            if (started) _agentPresetLocked = true;
          });
        },
      ),
    );
    _closeChatPeer();
    _chatRpc = rpc;
    _fileDelivery = NativeFileDeliveryService(
      rpc: rpc,
      sessionId: row.id,
      workspaceRoot: workspaceRoot,
    );
    final actions = NativeConversationActions(
      transport: NativeMessageActions(rpc, sessionId: row.id),
      openForkHandler: _openFork,
    );
    setState(() {
      _controller = controller;
      _actions = actions;
      _currentSessionId = row.id;
      _selectedAgentPresetId = row.agentPresetId;
      _agentPresetLocked = !row.blank || row.running;
      _agentPresetReady = false;
      _selectingPreset = false;
      _draft = _drafts.attach(row.id);
    });
    // Keep the native side's idea of "the open Session" current, so switching
    // back to the Web shell opens this same Session, not the latest one.
    unawaited(HostBridge.reportSession(row.id));
    // The row's own title can lag the catalogue after the first turn; refresh
    // so the drawer names the Session the way the Host does.
    if ((row.title ?? '').isEmpty) unawaited(_refreshSessions());
  }

  /// Open the Session a rewind produced and, when [prompt] is not empty, send
  /// it there once that Session can accept a prompt.
  ///
  /// The forked Session is a real Session on the Host, so it is opened the same
  /// way a drawer row is — through the catalogue identity, never by guesswork.
  Future<bool> _openFork(String sessionId, String prompt) async {
    if (!mounted || !widget.current()) return false;
    var row = _sessions.where((item) => item.id == sessionId).firstOrNull;
    row ??= EngineSession(
      id: sessionId,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
      blank: prompt.isEmpty,
    );
    final draft = await _drafts.open(sessionId);
    if (!mounted || !widget.current()) return false;
    if (prompt.trim().isNotEmpty) draft.replace(prompt);
    await _open(row);
    if (!mounted || !widget.current() || _currentSessionId != sessionId)
      return false;
    if (prompt.trim().isEmpty) return true;
    final controller = _controller;
    if (controller == null) return false;
    // The transport connects asynchronously; wait for a state that can send.
    final ready = await _awaitSendable(controller);
    if (!ready) {
      if (mounted && widget.current()) {
        setState(() => _sessionsError = '新会话已打开，但暂时不能发送，请稍后重试');
      }
      return false;
    }
    if (!mounted || !identical(_controller, controller)) return false;
    final accepted = await controller.sendPrompt(prompt);
    if (accepted && draft.text == prompt) draft.clear();
    return accepted;
  }

  /// Wait until the open Session reports a state that accepts a prompt.
  Future<bool> _awaitSendable(ChatController controller) async {
    for (var attempt = 0; attempt < 40; attempt++) {
      if (!mounted || !widget.current() || !identical(_controller, controller))
        return false;
      if (controller.canSend) return true;
      if (controller.state.status == ChatStatus.error) return false;
      await Future<void>.delayed(const Duration(milliseconds: 250));
      if (!mounted || !widget.current()) return false;
    }
    return false;
  }

  void _settings() {
    unawaited(
      Navigator.of(context)
          .push<void>(MaterialPageRoute(builder: (_) => const _HomeSettings())),
    );
  }

  @override
  Widget build(BuildContext context) {
    final rpc = widget.rpc;
    final controller = _controller;
    if (rpc == null || controller == null) {
      return _withWorkspaceDirectoryActions(
        Scaffold(
          appBar: AppBar(title: const Text('本机引擎')),
          body: SafeArea(
            child: Center(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (rpc == null)
                      const Icon(Icons.hourglass_empty, size: 40)
                    else if (_sessionsError != null)
                      const Icon(Icons.error_outline_rounded, size: 40)
                    else
                      const SizedBox(
                        width: 28,
                        height: 28,
                        child: CircularProgressIndicator(strokeWidth: 2.4),
                      ),
                    const SizedBox(height: 16),
                    Text(
                      rpc == null
                          ? (widget.error ?? '等待本机引擎就绪')
                          : (_sessionsError ?? '正在准备会话…'),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 8),
                    Text('引擎状态：' + widget.host.state),
                    if (rpc != null &&
                        _sessionsError?.startsWith('新建会话失败：') == true) ...[
                      const SizedBox(height: 12),
                      FilledButton(
                        key: const ValueKey('engine-bootstrap-retry'),
                        onPressed: _workspaceBusy
                            ? null
                            : () => unawaited(_createBlank(rpc)),
                        child: const Text('新建会话'),
                      ),
                      const SizedBox(height: 8),
                      NativeWorkspacePicker(
                        workspaces: _workspaces.workspaces,
                        selected: _displayWorkspace?.id,
                        confirmed: _workspacePolicy.known,
                        onSelect: _selectWelcomeWorkspace,
                        onRetry: () => unawaited(_retryWorkspace()),
                        status: _workspaceStatus,
                        onPickPrivate:
                            _directoryActionsAvailable && !_workspaceBusy
                            ? () => _pickDirectory(shared: false)
                            : null,
                        onPickShared:
                            _directoryActionsAvailable && !_workspaceBusy
                            ? () => _pickDirectory(shared: true)
                            : null,
                      ),
                      TextButton(
                        key: const ValueKey('workspace-once-home'),
                        onPressed: _workspaceBusy
                            ? null
                            : () => unawaited(
                                _createBlank(rpc, explicitWorkspace: true),
                              ),
                        child: const Text('本次在 Home 新建'),
                      ),
                    ],
                    const SizedBox(height: 20),
                    FilledButton.icon(
                      key: const ValueKey('engine-settings'),
                      onPressed: _settings,
                      icon: const Icon(Icons.settings_outlined),
                      label: const Text('设置'),
                    ),
                    TextButton(
                      key: const ValueKey('engine-web'),
                      onPressed: () => openSettingsWeb(context),
                      child: const Text('打开完整 Web'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
    }
    // The open Session's own title, as the catalogue reports it. Absent title
    // means "not named yet" - the shell then uses the first prompt.
    final currentTitle = _currentSessionId == null
        ? null
        : _sessions
              .where((row) => row.id == _currentSessionId)
              .firstOrNull
              ?.title;
    return _withWorkspaceDirectoryActions(
      SessionWorkspaceScope(
        catalog: _workspaces,
        online: _workspaceOnline,
        error: _workspaceError,
        onCreate: _workspaceBusy
            ? null
            : (workspace) => unawaited(
                _createBlank(
                  rpc,
                  workspace: workspace,
                  explicitWorkspace: true,
                ),
              ),
        child: ChatShell(
          key: ObjectKey(controller),
          controller: controller,
          disposeController: true,
          hostInfo: widget.host,
          sessionTitle: currentTitle,
          currentSessionId: _currentSessionId,
          sessions: _sessions,
          sessionsLoading: _sessionsLoading,
          sessionsError: _sessionsError,
          onRefreshSessions: () =>
              unawaited(_refreshSessions(refreshPresets: true)),
          onOpenSession: (row) => unawaited(_open(row)),
          onNewSession: _workspaceBusy
              ? null
              : () => unawaited(_createBlank(rpc)),
          workspaceOptions: _workspaces.workspaces,
          selectedWorkspaceId: _displayWorkspace?.id,
          onSelectWorkspace: _selectWelcomeWorkspace,
          onRetryWorkspace: () => unawaited(_retryWorkspace()),
          workspaceStatus: _workspaceStatus,
          // A null selection only means Home once the persisted default was
          // read back; before that the picker must not paint Home as chosen.
          workspaceConfirmed: _workspacePolicy.known,
          agentPresets: _agentPresets,
          selectedAgentPresetId: _selectedAgentPresetId ?? _defaultPresetId,
          agentPresetLocked: _agentPresetLocked,
          onSelectAgentPreset:
              _agentPresetReady && !_agentPresetLocked && !_selectingPreset
              ? _selectAgentPreset
              : null,
          actions: _actions,
          fileDelivery: _fileDelivery,
          draft: _draft,
          onOpenForkSession: _openFork,
          onOpenWebFallback: () => openSettingsWeb(context),
        ),
      ),
    );
  }
}

/// Uses the same account/settings state and page as the conversation drawer.
/// With no selected Session, Session-specific settings go to the host manager.
class _HomeSettings extends StatelessWidget {
  const _HomeSettings();
  @override
  Widget build(BuildContext context) {
    final settings = SettingsScope.maybeOf(context);
    if (settings == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('设置')),
        body: Center(
          child: FilledButton(
            onPressed: () async {
              final opened = await HostBridge.openRuntimeSettings();
              if (!opened && context.mounted) await openSettingsWeb(context);
            },
            child: const Text('打开 Android 设置'),
          ),
        ),
      );
    }
    return SettingsPage(
      profile: settings.profile,
      usage: settings.usage,
      loading: settings.loading,
      busy: settings.busy,
      error: settings.error,
      isDemo: settings.isDemo,
      onLogin: () => startAccountSignIn(context, settings),
      onLogout: () => confirmAccountSignOut(context, settings),
      onRefresh: settings.refresh,
      onOpenUsage: () => openOfficialUsage(context, settings),
      onOpen: (destination) async {
        if (destination == SettingsDestination.appearance) {
          await Navigator.of(context).push<void>(
            MaterialPageRoute(builder: (_) => const PersonalizationPage()),
          );
        } else if (destination == SettingsDestination.connections) {
          await Navigator.of(context).push<void>(
            MaterialPageRoute(builder: (_) => const ConnectionsPage()),
          );
        } else if (destination == SettingsDestination.webUi ||
            destination == SettingsDestination.models) {
          await openSettingsWeb(context);
        } else {
          final opened = await HostBridge.openSettingsSection(destination);
          if (!opened && context.mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('请在 Android 应用设置中配置；也可以打开完整 Web。')),
            );
          }
        }
      },
    );
  }
}
