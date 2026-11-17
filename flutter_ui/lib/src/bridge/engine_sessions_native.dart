import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../model/engine_session.dart';
import '../model/settings_model.dart';
import '../state/chat_controller.dart';
import '../state/composer_draft.dart';
import '../state/native_conversation_actions.dart';
import '../transport/dsh_rpc_connection.dart';
import '../transport/native_action_transport.dart';
import '../transport/native_session_transport.dart';
import '../ui/chat_shell.dart';
import '../ui/settings/settings_detail_pages.dart';
import '../ui/settings/settings_home.dart';
import '../ui/settings/settings_page.dart';
import '../ui/settings/settings_scope.dart';
import 'host_bridge.dart';

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

  /// Create a Session without naming a directory: the Host applies its own
  /// default working directory, so no value is invented on this side.
  Future<String> createBlank() async {
    final value = await rpc.call('session/create', {
      'request': <String, Object?>{},
    });
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
    if (value is! Map ||
        value['sessionId'] is! String ||
        (value['sessionId'] as String).trim().isEmpty) {
      throw const FormatException('Invalid DSH created session identity');
    }
    return value['sessionId'] as String;
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
        oldWidget.host.available != widget.host.available) {
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
  /// One composer draft for the whole home: a rewind fills it and the next
  /// step opens the forked Session, which rebuilds the shell around it.
  final ComposerDraft _draft = ComposerDraft();
  String? _currentSessionId;
  List<EngineSession> _sessions = const [];
  bool _sessionsLoading = false;
  String? _sessionsError;
  bool _bootstrapping = false;

  @override
  void initState() {
    super.initState();
    unawaited(_bootstrap());
  }

  @override
  void dispose() {
    _draft.dispose();
    _closeChatPeer();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant _EngineHomeContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.rpc != widget.rpc) {
      _controller = null;
      _currentSessionId = null;
      _sessions = const [];
      _sessionsError = null;
      _closeChatPeer();
      unawaited(_bootstrap());
    }
  }

  void _closeChatPeer() {
    _actions = null;
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
    if (rpc == null || _bootstrapping) return;
    _bootstrapping = true;
    try {
      // The catalogue and the handoff are independent reads, issued together so
      // startup costs one round trip. The handoff is bounded: a host that never
      // answers (an older build, a test harness) must not delay the first
      // Session, and a missing handoff simply means "no Session was carried".
      final handoff = HostBridge.readInitialSessionId().timeout(
        const Duration(milliseconds: 400),
        onTimeout: () => null,
      );
      final results = await Future.wait<Object?>([
        _loadSessions(rpc),
        handoff,
      ]);
      if (!mounted || !widget.current()) return;
      final rows = results[0] as List<EngineSession>?;
      final handedOver = results[1] as String?;
      if (rows == null) return;
      final carried = handedOver == null
          ? null
          : rows
                .where((row) => row.openable && row.id == handedOver)
                .firstOrNull;
      if (carried != null) {
        unawaited(_open(carried));
        return;
      }
      final target = rows.where((row) => row.openable).firstOrNull;
      if (target != null) {
        unawaited(_open(target));
        return;
      }
      await _createBlank(rpc);
    } finally {
      _bootstrapping = false;
    }
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
    }
  }

  Future<void> _refreshSessions() async {
    final rpc = widget.rpc;
    if (rpc == null) return;
    await _loadSessions(rpc);
  }

  Future<void> _createBlank(DshRpcConnection rpc) async {
    final String id;
    try {
      id = await EngineSessionService(rpc).createBlank();
    } on Object catch (error) {
      if (mounted && widget.current()) {
        setState(() => _sessionsError = '新建会话失败：' + error.toString());
      }
      return;
    }
    if (!mounted || !widget.current()) return;
    await _open(EngineSession(id: id, updatedAt: 0, blank: true));
  }

  Future<void> _open(EngineSession row) async {
    if (!mounted || !widget.current() || !row.openable) return;
    if (row.id == _currentSessionId && _controller != null) return;
    final rpc = widget.createPeer();
    final controller = ChatController(
      NativeSessionTransport(
        rpc: rpc,
        sessionId: row.id,
        enableInteractions: true,
      ),
    );
    _closeChatPeer();
    _chatRpc = rpc;
    final actions = NativeConversationActions(
      transport: NativeMessageActions(rpc, sessionId: row.id),
      openForkHandler: _openFork,
    );
    setState(() {
      _controller = controller;
      _actions = actions;
      _currentSessionId = row.id;
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
    await _open(row);
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
    return controller.sendPrompt(prompt);
  }

  /// Wait until the open Session reports a state that accepts a prompt.
  Future<bool> _awaitSendable(ChatController controller) async {
    for (var attempt = 0; attempt < 40; attempt++) {
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
      return Scaffold(
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
                  if (rpc != null && _sessionsError != null) ...[
                    const SizedBox(height: 12),
                    FilledButton(
                      key: const ValueKey('engine-bootstrap-retry'),
                      onPressed: () => unawaited(_bootstrap()),
                      child: const Text('重试'),
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
      );
    }
    return ChatShell(
      key: ObjectKey(controller),
      controller: controller,
      disposeController: true,
      hostInfo: widget.host,
      currentSessionId: _currentSessionId,
      sessions: _sessions,
      sessionsLoading: _sessionsLoading,
      sessionsError: _sessionsError,
      onRefreshSessions: () => unawaited(_refreshSessions()),
      onOpenSession: (row) => unawaited(_open(row)),
      onNewSession: () => unawaited(_createBlank(rpc)),
      actions: _actions,
      draft: _draft,
      onOpenForkSession: _openFork,
      onOpenWebFallback: () => openSettingsWeb(context),
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