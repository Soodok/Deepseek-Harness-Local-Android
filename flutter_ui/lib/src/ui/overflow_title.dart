import 'dart:async';

import 'package:flutter/widgets.dart';

const double _scrollSpeed = 28;
const Duration _pause = Duration(milliseconds: 1500);

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
  double _textWidth = 0;
  double _gap = 0;
  double _travelSeconds = 0;
  Timer? _pauseTimer;
  bool _foreground = true;
  bool _tickerEnabled = true;
  bool _reduceMotion = false;
  bool _restartPending = false;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      animationBehavior: AnimationBehavior.preserve,
    );
    _controller.addStatusListener(_onAnimationStatus);
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
    final reset = _reduceMotion || _travelSeconds <= 0;
    if (reset || !_foreground || !_tickerEnabled || _restartPending) {
      _cancelMotion(reset: reset);
      return;
    }
    if (_controller.isAnimating || _pauseTimer != null) return;
    if (_controller.value == 0) {
      _pauseTimer = Timer(_pause, () {
        _pauseTimer = null;
        _controller.forward();
      });
    } else {
      _controller.forward();
    }
  }

  void _onAnimationStatus(AnimationStatus status) {
    if (status != AnimationStatus.completed) return;
    // The second copy is now exactly where the first copy started.
    _controller.value = 0;
    _syncMotion();
  }

  void _cancelMotion({bool reset = false}) {
    _pauseTimer?.cancel();
    _pauseTimer = null;
    _controller.stop();
    if (reset && _controller.value != 0) _controller.value = 0;
  }

  void _configure(Object configuration, double textWidth, double gap) {
    final travel = textWidth <= 0 ? 0.0 : (textWidth + gap) / _scrollSpeed;
    if (_configuration == configuration && _travelSeconds == travel) return;
    _configuration = configuration;
    _textWidth = textWidth;
    _gap = gap;
    _travelSeconds = travel;
    if (_restartPending) return;
    _restartPending = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _restartPending = false;
      _cancelMotion(reset: true);
      _controller.duration = Duration(microseconds: (_travelSeconds * 1000000).round());
      _syncMotion();
    });
  }

  double get _offset {
    if (_restartPending || _reduceMotion || _travelSeconds <= 0) return 0;
    return _controller.value * (_textWidth + _gap);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pauseTimer?.cancel();
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
          final gap = TextPainter(
            text: TextSpan(text: '  ', style: style),
            textDirection: direction,
            textScaler: scaler,
            maxLines: 1,
          )..layout();
          final gapWidth = gap.width;
          painter.dispose();
          gap.dispose();
          final width = constraints.hasBoundedWidth
              ? constraints.maxWidth
              : constraints.constrainWidth(textWidth);
          final overflowing = width > 0 && textWidth > width;
          _configure(
            (widget.text, style, widget.textAlign, direction, scaler, width),
            overflowing ? textWidth : 0,
            gapWidth,
          );
          final text = RichText(
            text: span,
            textAlign: widget.textAlign,
            textDirection: direction,
            textScaler: scaler,
            maxLines: 1,
            softWrap: false,
            overflow: TextOverflow.clip,
          );
          if (!overflowing) {
            return SizedBox(width: width, height: height, child: text);
          }
          final copy = RichText(
            text: span,
            textAlign: TextAlign.start,
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
                minWidth: textWidth * 2 + gapWidth,
                maxWidth: textWidth * 2 + gapWidth,
                child: AnimatedBuilder(
                  animation: _controller,
                  builder: (context, _) {
                    final offset = _offset;
                    final signed = direction == TextDirection.rtl
                        ? offset
                        : -offset;
                    return Transform.translate(
                      offset: Offset(signed, 0),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        textDirection: direction,
                        children: [
                          text,
                          SizedBox(width: gapWidth),
                          copy,
                        ],
                      ),
                    );
                  },
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
