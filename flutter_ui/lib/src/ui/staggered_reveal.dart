import 'package:material_ui/material_ui.dart';

class StaggeredReveal extends StatefulWidget {
  const StaggeredReveal({
    super.key,
    required this.children,
    this.duration = const Duration(milliseconds: 170),
    this.interval = const Duration(milliseconds: 34),
    this.offset = 7,
    this.maxDelay = const Duration(milliseconds: 150),
  });

  final List<Widget> children;
  final Duration duration;
  final Duration interval;
  final double offset;
  final Duration maxDelay;

  @override
  State<StaggeredReveal> createState() => _StaggeredRevealState();
}

class _StaggeredRevealState extends State<StaggeredReveal>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  int _childSignature = 0;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(vsync: this, duration: _totalDuration);
    _childSignature = _signature(widget.children);
  }

  bool _started = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_started) {
      _started = true;
      _startIfAllowed();
    } else if (_reduceMotion) {
      _controller.value = 1;
    }
  }

  static const _groupLimit = Duration(milliseconds: 320);

  Duration get _totalDuration {
    final requested = widget.maxDelay + widget.duration;
    return requested > _groupLimit ? _groupLimit : requested;
  }

  @override
  void didUpdateWidget(covariant StaggeredReveal oldWidget) {
    super.didUpdateWidget(oldWidget);
    final signature = _signature(widget.children);
    final timingChanged =
        oldWidget.duration != widget.duration ||
        oldWidget.interval != widget.interval ||
        oldWidget.maxDelay != widget.maxDelay;
    if (signature != _childSignature || timingChanged) {
      _childSignature = signature;
      final settled = _controller.isCompleted;
      _controller.duration = _totalDuration;
      if (settled) _controller.value = 1;
    }
  }

  int _signature(List<Widget> children) =>
      Object.hashAll(children.map((child) => child.key ?? child.runtimeType));

  bool get _reduceMotion =>
      MediaQuery.accessibleNavigationOf(context) ||
      MediaQuery.maybeOf(context)?.disableAnimations == true;

  void _startIfAllowed() {
    if (_reduceMotion) {
      _controller.value = 1;
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && !_reduceMotion) _controller.forward();
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_reduceMotion) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: widget.children,
      );
    }
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) => Column(
        children: [
          for (var index = 0; index < widget.children.length; index++)
            _buildChild(index),
        ],
      ),
    );
  }

  Widget _buildChild(int index) {
    final delay = Duration(
      milliseconds: (widget.interval.inMilliseconds * index).clamp(
        0,
        widget.maxDelay.inMilliseconds,
      ),
    );
    final start = delay.inMicroseconds / _totalDuration.inMicroseconds;
    final end =
        (delay + widget.duration).inMicroseconds /
        _totalDuration.inMicroseconds;
    final span = (end - start).clamp(0.000001, 1.0);
    final raw = _controller.isCompleted
        ? 1.0
        : ((_controller.value - start) / span).clamp(0.0, 1.0);
    final progress = raw == 0 || raw == 1
        ? raw
        : Curves.easeOutCubic.transform(raw);
    return Opacity(
      key: ValueKey('staggered-reveal-opacity-$index'),
      opacity: progress,
      child: ExcludeFocus(
        excluding: progress == 0,
        child: IgnorePointer(
          ignoring: progress == 0,
          child: Transform.translate(
            offset: Offset(0, widget.offset * (1 - progress)),
            child: widget.children[index],
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }
}
