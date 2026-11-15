import 'dart:async';

import 'package:flutter/widgets.dart';

/// A single-line title that pans only when its measured text exceeds its width.
class OverflowTitle extends StatefulWidget {
  const OverflowTitle({
    super.key,
    required this.text,
    this.style,
    this.textAlign = TextAlign.start,
  });
  final String text;
  final TextStyle? style;
  final TextAlign textAlign;
  @override
  State<OverflowTitle> createState() => _OverflowTitleState();
}

class _OverflowTitleState extends State<OverflowTitle>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  late final AnimationController _controller;
  Object? _configuration;
  double _distance = 0, _travelSeconds = 0;
  bool _foreground = true, _tickerEnabled = true, _reduceMotion = false;
  bool _restartPending = false;
  int _generation = 0;
  int? _activeGeneration;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      animationBehavior: AnimationBehavior.preserve,
    );
    final state = WidgetsBinding.instance.lifecycleState;
    _foreground = state == null || state == AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _tickerEnabled = TickerMode.valuesOf(context).enabled;
    _reduceMotion = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    _syncMotion();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _syncMotion();
  }

  void _syncMotion() {
    final reset = _reduceMotion || _distance <= 0;
    if (reset || !_foreground || !_tickerEnabled || _restartPending) {
      _cancelMotion(reset: reset);
    } else if (_activeGeneration == null) {
      final generation = _generation;
      _activeGeneration = generation;
      unawaited(_play(generation));
    }
  }

  void _cancelMotion({bool reset = false}) {
    _generation++;
    _activeGeneration = null;
    _controller.stop();
    if (reset && _controller.value != 0) _controller.value = 0;
  }

  Future<void> _play(int generation) async {
    try {
      while (mounted && generation == _generation) {
        final remaining = (3 + 2 * _travelSeconds) * (1 - _controller.value);
        await _controller
            .animateTo(
              1,
              duration: Duration(microseconds: (remaining * 1000000).round()),
            )
            .orCancel;
        if (generation != _generation) return;
        _controller.value = 0;
      }
    } on TickerCanceled {
      // stop() deliberately cancels this generation during pause or rebuild.
    } finally {
      if (_activeGeneration == generation) _activeGeneration = null;
    }
  }

  void _configure(Object configuration, double distance) {
    if (_configuration == configuration && _distance == distance) return;
    _configuration = configuration;
    _distance = distance;
    _travelSeconds = distance / 28;
    if (_restartPending) return;
    _restartPending = true;
    _generation++;
    _activeGeneration = null;
    // Layout only records geometry; controller notifications happen after layout.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _restartPending = false;
      _controller.stop();
      _controller.value = 0;
      _syncMotion();
    });
  }

  double get _progress {
    if (_restartPending || _reduceMotion || _distance <= 0) return 0;
    final seconds = _controller.value * (3 + 2 * _travelSeconds);
    if (seconds < 1.5) return 0;
    if (seconds < 1.5 + _travelSeconds) {
      return (seconds - 1.5) / _travelSeconds;
    }
    if (seconds < 3 + _travelSeconds) return 1;
    return (3 + 2 * _travelSeconds - seconds) / _travelSeconds;
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _generation++;
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final defaults = DefaultTextStyle.of(context);
    final media = MediaQuery.maybeOf(context);
    var style = defaults.style.merge(widget.style);
    if (media?.boldText ?? false) {
      style = style.merge(const TextStyle(fontWeight: FontWeight.bold));
    }
    final direction = Directionality.of(context);
    final scaler = media?.textScaler ?? TextScaler.noScaling;
    final span = TextSpan(text: widget.text, style: style);
    return Semantics(
      label: widget.text,
      textDirection: direction,
      excludeSemantics: true,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final painter = TextPainter(
            text: span,
            textAlign: widget.textAlign,
            textDirection: direction,
            textScaler: scaler,
            maxLines: 1,
          )..layout();
          final textWidth = painter.width;
          final height = painter.height;
          painter.dispose();
          final width = constraints.hasBoundedWidth
              ? constraints.maxWidth
              : constraints.constrainWidth(textWidth);
          final distance = width > 0
              ? (textWidth - width).clamp(0.0, double.infinity).toDouble()
              : 0.0;
          _configure((
            widget.text,
            style,
            widget.textAlign,
            direction,
            scaler,
            width,
          ), distance);
          final text = RichText(
            text: span,
            textAlign: widget.textAlign,
            textDirection: direction,
            textScaler: scaler,
            maxLines: 1,
            softWrap: false,
            overflow: TextOverflow.clip,
          );
          return SizedBox(
            width: width,
            height: height,
            child: ClipRect(
              child: OverflowBox(
                alignment: AlignmentDirectional.centerStart,
                minWidth: distance > 0 ? textWidth : width,
                maxWidth: distance > 0 ? textWidth : width,
                child: AnimatedBuilder(
                  animation: _controller,
                  child: text,
                  builder: (context, child) => Transform.translate(
                    offset: Offset(
                      (direction == TextDirection.rtl ? 1 : -1) *
                          _distance *
                          _progress,
                      0,
                    ),
                    child: child,
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
