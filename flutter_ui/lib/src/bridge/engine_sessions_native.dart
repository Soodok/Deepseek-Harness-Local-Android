import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../model/engine_session.dart';
import '../state/chat_controller.dart';
import '../transport/dsh_rpc_connection.dart';
import '../transport/native_session_transport.dart';
import '../ui/chat_shell.dart';
import '../ui/engine_session_picker.dart';
import 'host_bridge.dart';

Future<void> showEngineSessions(BuildContext context, HostInfo host) =>
    Navigator.of(context).push<void>(
      MaterialPageRoute(builder: (_) => _EngineSessions(host: host)),
    );

class _EngineSessions extends StatefulWidget {
  const _EngineSessions({required this.host});
  final HostInfo host;
  @override
  State<_EngineSessions> createState() => _EngineSessionsState();
}

class _EngineSessionsState extends State<_EngineSessions> {
  late final _rpc = DshRpcConnection(webUrl: widget.host.webUrl);
  @override
  void dispose() {
    unawaited(_rpc.close());
    super.dispose();
  }

  Future<List<EngineSession>> _load() async => EngineSession.fromResponse(
    await _rpc.call('session/list', {'_request': <String, Object?>{}}),
  );
  Future<void> _open(EngineSession row) async {
    final controller = ChatController(
      NativeSessionTransport(
        rpc: DshRpcConnection(webUrl: widget.host.webUrl),
        sessionId: row.id,
        enableInteractions: true,
      ),
    );
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (context) => ChatShell(
          controller: controller,
          disposeController: true,
          hostInfo: widget.host,
          onBack: () => Navigator.of(context).pop(),
          onOpenWebFallback: HostBridge.openWebFallback,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) =>
      EngineSessionPicker(load: _load, onOpen: _open);
}
