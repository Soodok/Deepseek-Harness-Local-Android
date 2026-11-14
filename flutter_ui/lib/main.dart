import 'package:material_ui/material_ui.dart';

import 'src/bridge/host_bridge.dart';
import 'src/state/chat_controller.dart';
import 'src/theme/dsh_theme.dart';
import 'src/transport/fixture_transport.dart';
import 'src/ui/chat_shell.dart';

void main() => runApp(const DshApp());

class DshApp extends StatefulWidget {
  const DshApp({super.key});

  @override
  State<DshApp> createState() => _DshAppState();
}

class _DshAppState extends State<DshApp> with WidgetsBindingObserver {
  late ChatController _controller;
  HostInfo? _hostInfo;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // The real engine event schema is not captured yet. Keep fixture mode explicit
    // until snapshot, delta, tool, and action frames are verified against a live host.
    _controller = ChatController(FixtureTransport());
    _refreshHostInfo();
  }

  void _refreshHostInfo() {
    HostBridge.readEngineInfo().then((info) {
      if (mounted) setState(() => _hostInfo = info);
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refreshHostInfo();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'DSH',
      theme: DshTheme.light(),
      debugShowCheckedModeBanner: false,
      home: ChatShell(
        controller: _controller,
        hostInfo: _hostInfo,
        onOpenWebFallback: () async {
          final opened = await HostBridge.openWebFallback();
          if (!opened && context.mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Web 版入口由 Android HostBridge 提供。')),
            );
          }
        },
      ),
    );
  }
}
