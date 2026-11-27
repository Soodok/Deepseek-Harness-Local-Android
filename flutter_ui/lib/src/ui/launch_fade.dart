import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';

/// Keeps the native handoff image above Flutter until the real page is ready.
///
/// The handoff has three independent gates:
/// 1. the PNG is decoded and painted by Flutter;
/// 2. the page owner calls [markContentReady] after its accepted snapshot and
///    layout are available;
/// 3. the host acknowledges removal of its panel.
///
/// The cover is never removed merely because Flutter produced an early frame.
/// This is deliberate: an early frame can still be the engine placeholder.
class LaunchFade extends StatefulWidget {
  const LaunchFade({super.key, required this.child, this.backdrop});

  final Widget child;
  final Uint8List? backdrop;

  /// The fade duration while frames keep arriving in the foreground.
  static const fadeDuration = Duration(seconds: 1);
  static const int steps = 12;
  static const decodeTimeout = Duration(seconds: 5);
  static const hostAckTimeout = Duration(seconds: 3);
  static const readyTimeout = Duration(seconds: 30);
  static const fadeTimeout = Duration(seconds: 15);

  /// Call from a descendant after the accepted snapshot and layout are ready.
  ///
  /// Returns false for a duplicate call, after a failure, or when no
  /// [LaunchFade] ancestor exists. The call is safe to make from a post-frame
  /// callback or an event handler.
  static bool markContentReady(BuildContext context) =>
      context.findAncestorStateOfType<_LaunchFadeState>()?._markReady() ?? false;

  /// Finishes the handoff through the bounded host failure path.
  static bool markContentFailed(
    BuildContext context, {
    String reason = 'content-failed',
  }) =>
      context
          .findAncestorStateOfType<_LaunchFadeState>()
          ?._markFailed(reason) ??
      false;

  @override
  State<LaunchFade> createState() => _LaunchFadeState();
}

class _LaunchFadeState extends State<LaunchFade>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  static const _stalledFrame = Duration(milliseconds: 500);

  late final Ticker _ticker = createTicker(_onTick);

  Timer? _readyTimer;
  Timer? _fadeTimer;
  double _opacity = 1;
  Duration? _previousTick;
  int _visibleMicros = 0;
  bool _foreground = true;
  bool _resumeStepPending = false;
  bool _decoded = false;
  bool _contentReady = false;
  bool _handoverStarted = false;
  bool _started = false;
  bool _failed = false;
  bool _completed = false;

  @override
  void initState() {
    super.initState();
    final backdrop = widget.backdrop;
    if (backdrop == null) {
      _opacity = 0;
      return;
    }
    final binding = WidgetsBinding.instance;
    final lifecycle = binding.lifecycleState;
    _foreground = lifecycle == null || lifecycle == AppLifecycleState.resumed;
    binding.addObserver(this);
    _readyTimer = Timer(LaunchFade.readyTimeout, () {
      _markFailed('content-ready-timeout');
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(_decodeAndPaint());
    });
  }

  Future<void> _decodeAndPaint() async {
    final backdrop = widget.backdrop;
    if (backdrop == null || _failed) return;
    try {
      Object? decodeError;
      await precacheImage(
        MemoryImage(backdrop),
        context,
        onError: (error, stack) => decodeError = error,
      ).timeout(LaunchFade.decodeTimeout);
      if (decodeError != null) {
        _markFailed('backdrop-decode-failed');
        return;
      }
      if (!mounted || _failed) return;
      // The image must have reached a Flutter frame before the host panel is
      // asked to leave; otherwise a slow raster thread exposes a blank page.
      await WidgetsBinding.instance.endOfFrame;
      if (!mounted || _failed) return;
      _decoded = true;
      _maybeStartHandover();
    } catch (_) {
      _markFailed('backdrop-decode-failed');
    }
  }

  bool _markReady() {
    if (!mounted ||
        widget.backdrop == null ||
        _contentReady ||
        _failed ||
        _started) {
      return false;
    }
    _contentReady = true;
    _maybeStartHandover();
    return true;
  }

  bool _markFailed(String reason) {
    if (!mounted || widget.backdrop == null || _failed || _completed) return false;
    _failed = true;
    _readyTimer?.cancel();
    _readyTimer = null;
    _ticker.stop();
    _fadeTimer?.cancel();
    // Reveal Flutter's own failure/placeholder state immediately. The host
    // receives the same bounded failure signal and removes its panel or
    // returns to Web; this local update prevents a permanent Dart-side mask.
    setState(() => _opacity = 0);
    unawaited(
      _invokeHost('launchFadeFailed', {'reason': reason}).timeout(
        LaunchFade.hostAckTimeout,
        onTimeout: () => false,
      ),
    );
    return true;
  }

  void _maybeStartHandover() {
    if (!_decoded || !_contentReady || _handoverStarted || _failed || !mounted) {
      return;
    }
    _handoverStarted = true;
    unawaited(_requestHostRelease());
  }

  Future<void> _requestHostRelease() async {
    final acknowledged = await _invokeHost('releaseBackdrop').timeout(
      LaunchFade.hostAckTimeout,
      onTimeout: () => false,
    );
    if (!mounted || _failed) return;
    if (!acknowledged) {
      _markFailed('host-release-not-acknowledged');
      return;
    }
    _readyTimer?.cancel();
    _readyTimer = null;
    _play();
  }

  void _play() {
    if (_started || _failed || !mounted) return;
    _started = true;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _completed = true;
      setState(() => _opacity = 0);
      return;
    }
    _fadeTimer = Timer(LaunchFade.fadeTimeout, () {
      _markFailed('fade-frame-timeout');
    });
    _ticker.start();
  }

  Future<bool> _invokeHost(
    String method, [
    Map<String, String>? arguments,
  ]) async {
    try {
      const channel = MethodChannel('app.dsh.mobile/native_chat');
      return await channel.invokeMethod<bool>(method, arguments) == true;
    } catch (_) {
      return false;
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final foreground = state == AppLifecycleState.resumed;
    if (_foreground && !foreground && _previousTick != null) {
      _resumeStepPending = true;
    }
    _foreground = foreground;
  }

  void _onTick(Duration elapsed) {
    if (!mounted || _failed || !_foreground) return;
    final previous = _previousTick;
    _previousTick = elapsed;
    if (previous == null) return;

    final interval = elapsed - previous;
    final durationMicros = LaunchFade.fadeDuration.inMicroseconds;
    // Regular slow frames use their actual interval. A stall or resumption
    // discards excess time so the next frame still shows the transition.
    final limit = _resumeStepPending || interval >= _stalledFrame
        ? (durationMicros + LaunchFade.steps - 1) ~/ LaunchFade.steps
        : durationMicros;
    _resumeStepPending = false;
    _visibleMicros += interval.inMicroseconds.clamp(0, limit).toInt();
    final progress = (_visibleMicros / durationMicros).clamp(0.0, 1.0);
    setState(() => _opacity = 1 - progress);
    if (progress >= 1) {
      _completed = true;
      _fadeTimer?.cancel();
      _ticker.stop();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _readyTimer?.cancel();
    _fadeTimer?.cancel();
    _ticker.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final backdrop = widget.backdrop;
    if (backdrop == null) return widget.child;
    return Stack(
      alignment: Alignment.topLeft,
      fit: StackFit.expand,
      children: [
        widget.child,
        IgnorePointer(
          child: Opacity(
            opacity: _opacity,
            child: Image.memory(
              backdrop,
              fit: BoxFit.cover,
              gaplessPlayback: true,
              errorBuilder: (context, error, stack) => const SizedBox.shrink(),
            ),
          ),
        ),
      ],
    );
  }
}
