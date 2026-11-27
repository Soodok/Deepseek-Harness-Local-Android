import 'dart:async';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/services.dart';

import 'package:material_ui/material_ui.dart';

import 'src/bridge/host_bridge.dart';
import 'src/bridge/engine_sessions_stub.dart'
    if (dart.library.io) 'src/bridge/engine_sessions_native.dart';
import 'src/state/chat_controller.dart';
import 'src/theme/dsh_theme.dart';
import 'src/transport/fixture_transport.dart';
import 'src/ui/chat_shell.dart';
import 'src/ui/chat_strings.dart';
import 'src/ui/launch_fade.dart';
import 'src/state/settings_controller.dart';
import 'src/theme/display_scale.dart';
import 'src/ui/settings/settings_scope.dart';
import 'src/bridge/settings_service_stub.dart'
    if (dart.library.io) 'src/bridge/settings_service_native.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Preserve the native window geometry when Flutter takes system-UI ownership.
  try {
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge)
        .timeout(LaunchFade.hostAckTimeout);
  } catch (_) {
    // Previews and unsupported platforms have no Android system bars.
  }
  // 外观必须在 runApp 之前读出来。
  //
  // 原先这里直接 runApp，_themeMode 的初始值是 ThemeMode.dark，用户保存的 light
  // 要等一个 MethodChannel 往返回来才生效 —— 于是冷启动的第一帧永远是深色
  // （实测中心像素 #151517），几百毫秒后才跳成浅色。从 Web 切过来时这段深色
  // 正好落在引擎冷启的空窗里，看起来就是「先黑一下再变白」。
  //
  // 这次读取就是一次 SharedPreferences 往返（毫秒级），换来首帧即正确的外观；
  // Web 预览下没有桥，readAppearance 返回 null，行为不变。
  final appearance = await HostBridge.readAppearance();
  // 入场底图：宿主在点击「切换原生对话」那一刻拍下的 Web 那一屏。
  // 它在冷启动那几秒里替我们挡住黑屏，起来后再由 LaunchFade 在自己这层淡掉。
  final backdrop = await HostBridge.readBackdrop();
  final locale = await HostBridge.readLocale();
  // The chrome is drawn with Flutter's own BackdropFilter/ImageFilter, so the
  // app no longer initializes a third-party glass renderer at startup.
  runApp(
    DshApp(
      initialAppearance: appearance,
      backdrop: backdrop,
      initialLocale: locale,
    ),
  );
}

SystemUiOverlayStyle _transparentSystemBars(Brightness brightness) =>
    SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: brightness == Brightness.light
          ? Brightness.dark
          : Brightness.light,
      statusBarBrightness: brightness,
      systemNavigationBarColor: Colors.transparent,
      systemNavigationBarDividerColor: Colors.transparent,
      systemNavigationBarIconBrightness: brightness == Brightness.light
          ? Brightness.dark
          : Brightness.light,
      systemStatusBarContrastEnforced: false,
      systemNavigationBarContrastEnforced: false,
    );

ThemeData _withTransparentSystemBars(ThemeData theme) => theme.copyWith(
  appBarTheme: theme.appBarTheme.copyWith(
    systemOverlayStyle: _transparentSystemBars(theme.brightness),
  ),
);

class DshApp extends StatefulWidget {
  const DshApp({
    super.key,
    this.preview = kIsWeb,
    this.initialAppearance,
    this.backdrop,
    this.initialLocale,
  });

  final bool preview;

  /// 启动前就读到的外观；非 null 时首帧直接用它，不再先画深色再改。
  final Map<String, String>? initialAppearance;

  /// 宿主拍下的 Web 那一屏：入场时盖在页面之上，再淡掉露出会话页。
  final Uint8List? backdrop;

  /// The Android app locale, read before the first Flutter frame.
  final String? initialLocale;

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
  Locale? _locale;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _locale = _localeFromWire(widget.initialLocale);
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
    _applyInitialAppearance();
    _readAppearance();
    _readDisplaySize();
  }

  Locale? _localeFromWire(String? value) {
    final code = value?.trim().toLowerCase();
    if (code == null || code.isEmpty) return null;
    return Locale(code);
  }

  Future<void> _readLocale() async {
    if (widget.preview) return;
    final value = _localeFromWire(await HostBridge.readLocale());
    if (!mounted || value == null || value == _locale) return;
    setState(() => _locale = value);
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

  /// 把 main() 里提前读到的外观落到状态上（在 initState 同步执行）。
  void _applyInitialAppearance() {
    final saved = widget.initialAppearance;
    if (saved == null) return;
    _themeMode =
        ThemeMode.values.where((m) => m.name == saved['mode']).firstOrNull ??
        _themeMode;
    _mood =
        DshMood.values.where((m) => m.name == saved['mood']).firstOrNull ??
        _mood;
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
      const unavailable = HostInfo(
        webUrl: '',
        state: 'unavailable',
        available: false,
      );
      final next = info ?? unavailable;
      if (!_hostResolved || changed || !next.hasSameSnapshot(_hostInfo)) {
        setState(() {
          if (changed) {
            _settings.dispose();
            _settingsHostUrl = key;
            _settings = SettingsController(
              createSettingsService(key == 'unavailable' ? unavailable : next),
            );
          }
          _hostResolved = true;
          _hostInfo = next;
        });
      }
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
      _readLocale();
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
            theme: _withTransparentSystemBars(
              DshTheme.light(mood: _mood, displayFactor: _displaySize.factor),
            ),
            darkTheme: _withTransparentSystemBars(
              DshTheme.dark(mood: _mood, displayFactor: _displaySize.factor),
            ),
            themeMode: _themeMode,
            debugShowCheckedModeBanner: false,
            // The in-app size is applied above the navigator, so every pushed
            // route and overlay scales together, and below nothing that owns
            // state: the widget chain is identical at every size, so switching
            // never remounts routes, focus, drafts or scroll offsets.
            // Keep the handoff image in real window coordinates. Scaling it
            // inside DshDisplayCanvas would crop or magnify the host snapshot
            // when the user chooses compact/relaxed display size.
            builder: (context, child) => DshChatLocale(
              languageCode: _locale?.languageCode ?? 'zh',
              child: AnnotatedRegion<SystemUiOverlayStyle>(
                value: _transparentSystemBars(Theme.of(context).brightness),
                child: LaunchFade(
                  backdrop: widget.backdrop,
                  child: DshDisplayCanvas(
                    child: child ?? const SizedBox.shrink(),
                  ),
                ),
              ),
            ),
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
