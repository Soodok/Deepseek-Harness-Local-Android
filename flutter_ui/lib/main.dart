import 'dart:async';

import 'package:flutter/foundation.dart' show kIsWeb;

import 'package:material_ui/material_ui.dart';

import 'src/bridge/host_bridge.dart';
import 'src/bridge/engine_sessions_stub.dart'
    if (dart.library.io) 'src/bridge/engine_sessions_native.dart';
import 'src/state/chat_controller.dart';
import 'src/theme/dsh_theme.dart';
import 'src/transport/fixture_transport.dart';
import 'src/ui/chat_shell.dart';
import 'src/state/settings_controller.dart';
import 'src/theme/display_scale.dart';
import 'src/ui/settings/settings_scope.dart';
import 'src/bridge/settings_service_stub.dart'
    if (dart.library.io) 'src/bridge/settings_service_native.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // The chrome is drawn with Flutter's own BackdropFilter/ImageFilter, so the
  // app no longer initializes a third-party glass renderer at startup.
  runApp(const DshApp());
}

class DshApp extends StatefulWidget {
  const DshApp({super.key, this.preview = kIsWeb});

  final bool preview;

  @override
  State<DshApp> createState() => _DshAppState();
}

class _DshAppState extends State<DshApp> with WidgetsBindingObserver {
  late ChatController _controller;
  HostInfo? _hostInfo;
  late SettingsController _settings;
  String _settingsHostUrl = 'preview';
  int _hostReadEpoch = 0;
  Timer? _hostPoll;
  bool _hostResolved = false;
  ThemeMode _themeMode = ThemeMode.dark;
  DshMood _mood = DshMood.mist;
  DshDisplaySize _displaySize = DshDisplaySize.fallback;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Web preview has no Android bridge; native hosts enter real engine sessions.
    _controller = ChatController(FixtureTransport());
    _settingsHostUrl = widget.preview ? 'preview' : 'unavailable';
    _settings = SettingsController(
      createSettingsService(
        widget.preview
            ? null
            : const HostInfo(
                webUrl: '',
                state: 'unavailable',
                available: false,
              ),
      ),
    );
    _hostResolved = widget.preview;
    _settings.refresh();
    if (!widget.preview) _refreshHostInfo();
    _readAppearance();
    _readDisplaySize();
  }

  int _appearanceEpoch = 0;

  /// Read once at startup. A slow read must not overwrite a size the user has
  /// already picked in this run, so the epoch of the last local write wins.
  int _displayEpoch = 0;
  Future<void> _readDisplaySize() async {
    final epoch = _displayEpoch;
    final saved = await HostBridge.readDisplaySize();
    if (!mounted || epoch != _displayEpoch || saved == null) return;
    setState(() => _displaySize = DshDisplaySize.fromWire(saved));
  }

  Future<void> _saveDisplaySize(DshDisplaySize size) async {
    ++_displayEpoch;
    final ok = await HostBridge.writeDisplaySize(size.wireValue);
    if (ok || !mounted) return;
    final context = _navigatorKey.currentContext;
    if (context != null && context.mounted) {
      ScaffoldMessenger.maybeOf(context)
          ?.showSnackBar(const SnackBar(content: Text('显示大小没能保存，重启后会恢复上一个选择')));
    }
  }

  Future<void> _readAppearance() async {
    final epoch = _appearanceEpoch;
    final saved = await HostBridge.readAppearance();
    if (!mounted || epoch != _appearanceEpoch || saved == null) return;
    setState(() {
      _themeMode =
          ThemeMode.values.where((m) => m.name == saved['mode']).firstOrNull ??
          _themeMode;
      _mood =
          DshMood.values.where((m) => m.name == saved['mood']).firstOrNull ??
          _mood;
    });
  }

  Future<void> _saveAppearance() async {
    ++_appearanceEpoch;
    final ok = await HostBridge.writeAppearance(_themeMode.name, _mood.name);
    if (!ok && mounted && _hostInfo != null) {
      final context = _navigatorKey.currentContext;
      if (context != null && context.mounted) {
        ScaffoldMessenger.maybeOf(context)
            ?.showSnackBar(const SnackBar(content: Text('外观保存失败，请重试')));
      }
    }
  }

  final _navigatorKey = GlobalKey<NavigatorState>();

  void _refreshHostInfo({bool refreshSettings = true}) {
    final readEpoch = ++_hostReadEpoch;
    HostBridge.readEngineInfo().then((info) {
      if (!mounted || readEpoch != _hostReadEpoch) return;
      final key = info == null
          ? 'unavailable'
          : info.available && info.webUrl.isNotEmpty
          ? info.webUrl
          : 'unavailable';
      final changed = key != _settingsHostUrl;
      setState(() {
        if (changed) {
          _settings.dispose();
          _settingsHostUrl = key;
          _settings = SettingsController(
            createSettingsService(
              key == 'unavailable'
                  ? const HostInfo(
                      webUrl: '',
                      state: 'unavailable',
                      available: false,
                    )
                  : info,
            ),
          );
        }
        _hostResolved = true;
        _hostInfo =
            info ??
            (key == 'unavailable'
                ? const HostInfo(
                    webUrl: '',
                    state: 'unavailable',
                    available: false,
                  )
                : null);
      });
      if (changed || refreshSettings) unawaited(_settings.refresh());
      if (_hostInfo != null && _hostPoll == null) _startHostPolling();
    });
  }

  void _startHostPolling() {
    _hostPoll?.cancel();
    _hostPoll = Timer.periodic(
      const Duration(seconds: 2),
      (_) => _refreshHostInfo(refreshSettings: false),
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !widget.preview) {
      _refreshHostInfo();
      if (_hostInfo != null) _startHostPolling();
    } else {
      _hostPoll?.cancel();
      _hostPoll = null;
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _hostPoll?.cancel();
    _controller.dispose();
    _settings.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SettingsScope(
      controller: _settings,
      child: DshDisplayScope(
        size: _displaySize,
        onChanged: (value) {
          if (_displaySize == value) return;
          setState(() => _displaySize = value);
          unawaited(_saveDisplaySize(value));
        },
        child: DshAppearance(
          mode: _themeMode,
          mood: _mood,
          onMoodChanged: (value) {
            setState(() => _mood = value);
            unawaited(_saveAppearance());
          },
          onChanged: (mode) {
            setState(() => _themeMode = mode);
            unawaited(_saveAppearance());
          },
          child: MaterialApp(
            navigatorKey: _navigatorKey,
            title: 'DSH',
            theme: DshTheme.light(
              mood: _mood,
              displayFactor: _displaySize.factor,
            ),
            darkTheme: DshTheme.dark(
              mood: _mood,
              displayFactor: _displaySize.factor,
            ),
            themeMode: _themeMode,
            debugShowCheckedModeBanner: false,
            // The in-app size is applied above the navigator, so every pushed
            // route and overlay scales together, and below nothing that owns
            // state: the widget chain is identical at every size, so switching
            // never remounts routes, focus, drafts or scroll offsets.
            builder: (context, child) =>
                DshDisplayCanvas(child: child ?? const SizedBox.shrink()),
            home: Builder(
              builder: (context) => !_hostResolved
                  ? const Scaffold(
                      body: Center(child: CircularProgressIndicator()),
                    )
                  : _hostInfo != null
                  ? buildEngineHome(_hostInfo!)
                  : ChatShell(
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
        ),
      ),
    );
  }
}
