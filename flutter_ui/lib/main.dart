import 'package:material_ui/material_ui.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import 'src/bridge/host_bridge.dart';
import 'src/bridge/engine_sessions_stub.dart'
    if (dart.library.io) 'src/bridge/engine_sessions_native.dart';
import 'src/state/chat_controller.dart';
import 'src/theme/dsh_theme.dart';
import 'src/transport/fixture_transport.dart';
import 'src/ui/chat_shell.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await LiquidGlassWidgets.initialize(enablePerformanceMonitor: false);
  runApp(
    LiquidGlassWidgets.wrap(
      child: const DshApp(),
      brightnessResolver: Theme.maybeBrightnessOf,
    ),
  );
}

class DshApp extends StatefulWidget {
  const DshApp({super.key});

  @override
  State<DshApp> createState() => _DshAppState();
}

class _DshAppState extends State<DshApp> with WidgetsBindingObserver {
  late ChatController _controller;
  HostInfo? _hostInfo;
  ThemeMode _themeMode = ThemeMode.dark;
  DshMood _mood = DshMood.mist;

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
    return DshAppearance(
      mode: _themeMode,
      mood: _mood,
      onMoodChanged: (value) => setState(() => _mood = value),
      onChanged: (mode) => setState(() => _themeMode = mode),
      child: MaterialApp(
        title: 'DSH',
        theme: DshTheme.light(mood: _mood),
        darkTheme: DshTheme.dark(mood: _mood),
        themeMode: _themeMode,
        debugShowCheckedModeBanner: false,
        home: Builder(
          builder: (context) => ChatShell(
            controller: _controller,
            hostInfo: _hostInfo,
            onOpenEngineSessions: _hostInfo?.available == true
                ? () => showEngineSessions(context, _hostInfo!)
                : null,
            onOpenWebFallback: () async {
              final opened = await HostBridge.openWebFallback();
              if (!opened && context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('Web 版入口由 Android HostBridge 提供。'),
                  ),
                );
              }
            },
          ),
        ),
      ),
    );
  }
}
