import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../model/engine_session.dart';
import '../model/settings_model.dart';
import '../state/chat_controller.dart';
import '../transport/dsh_rpc_connection.dart';
import '../transport/native_session_transport.dart';
import '../ui/chat_shell.dart';
import '../ui/engine_session_picker.dart';
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
  String _cwd = '';
  bool _creating = false;

  void _closeChatPeer() {
    final peer = _chatRpc;
    _chatRpc = null;
    if (peer != null) {
      widget.releasePeer(peer);
      unawaited(peer.close());
    }
  }

  void _showList() {
    if (!mounted || !widget.current()) return;
    _closeChatPeer();
    setState(() => _controller = null);
    // ChatShell owns and disposes the previous controller on unmount.
  }

  Future<void> _open(EngineSession row) async {
    if (!mounted || !widget.current() || !row.openable) return;
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
    if (EngineSessionService.validCwd(row.cwd)) _cwd = row.cwd;
    setState(() => _controller = controller);
  }

  Future<void> _newSession() async {
    if (_creating || widget.rpc == null || !widget.current()) return;
    _creating = true;
    try {
      final created = await showDialog<({String id, String cwd})>(
        context: context,
        useRootNavigator: false,
        builder: (_) => _CreateSessionDialog(
          service: EngineSessionService(widget.rpc!),
          initialCwd: _cwd,
          current: widget.current,
        ),
      );
      if (!mounted || !widget.current() || created == null) return;
      await _open(
        EngineSession(id: created.id, cwd: created.cwd, updatedAt: 0),
      );
    } finally {
      _creating = false;
    }
  }

  void _settings() {
    unawaited(
      Navigator.of(context)
          .push<void>(MaterialPageRoute(builder: (_) => const _HomeSettings())),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (widget.rpc == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('本机引擎')),
        body: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.hourglass_empty, size: 40),
                  const SizedBox(height: 16),
                  Text(widget.error ?? '等待本机引擎就绪'),
                  const SizedBox(height: 8),
                  Text('引擎状态：' + widget.host.state),
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
    final controller = _controller;
    return PopScope(
      canPop: controller == null,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop && controller != null) _showList();
      },
      child: controller == null
          ? EngineSessionPicker(
              load: EngineSessionService(widget.rpc!).list,
              onOpen: _open,
              onCreate: _newSession,
              onSettings: _settings,
              onWebFallback: () => openSettingsWeb(context),
            )
          : ChatShell(
              key: ObjectKey(controller),
              controller: controller,
              disposeController: true,
              hostInfo: widget.host,
              onNewSession: () => unawaited(_newSession()),
              onOpenEngineSessions: _showList,
              onOpenWebFallback: () => openSettingsWeb(context),
            ),
    );
  }
}

class _CreateSessionDialog extends StatefulWidget {
  const _CreateSessionDialog({
    required this.service,
    required this.initialCwd,
    required this.current,
  });
  final EngineSessionService service;
  final String initialCwd;
  final bool Function() current;
  @override
  State<_CreateSessionDialog> createState() => _CreateSessionDialogState();
}

class _CreateSessionDialogState extends State<_CreateSessionDialog> {
  late final _cwd = TextEditingController(text: widget.initialCwd);
  bool _busy = false;
  String? _error;
  @override
  void dispose() {
    _cwd.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy || !widget.current()) return;
    final cwd = _cwd.text;
    if (!EngineSessionService.validCwd(cwd)) {
      setState(() => _error = '请输入引擎工作目录的合法绝对路径');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final id = await widget.service.create(cwd);
      if (!mounted || !widget.current()) return;
      Navigator.of(context).pop((id: id, cwd: cwd));
    } catch (e) {
      if (mounted && widget.current()) {
        setState(() {
          _busy = false;
          _error = '新建失败：$e';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !_busy,
    child: AlertDialog(
      title: const Text('新建真实会话'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('填写引擎所在环境的工作目录绝对路径。不存在的目录将由引擎尝试创建；请确认该目录可用。'),
            const SizedBox(height: 16),
            TextField(
              key: const ValueKey('engine-cwd'),
              controller: _cwd,
              enabled: !_busy,
              autofocus: true,
              autocorrect: false,
              enableSuggestions: false,
              decoration: const InputDecoration(
                labelText: '工作目录',
                helperText: '使用引擎中的路径',
              ),
              onSubmitted: (_) => _submit(),
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: const ValueKey('engine-create-confirm'),
          onPressed: _busy ? null : _submit,
          child: Text(_busy ? '创建中…' : '创建并打开'),
        ),
      ],
    ),
  );
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
