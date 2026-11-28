import 'dart:math' as math;

import 'package:material_ui/material_ui.dart';

/// One floating header and one floating composer over a full-height transcript.
///
/// The transcript keeps the whole viewport, including the bands the chrome
/// covers, so the glass panes sample the scrolling text underneath instead of
/// only the flat page backdrop. The chrome footprint is measured here and
/// reserved as list padding, so the first and last rows can still be scrolled
/// clear of the overlays.
class FloatingConversationLayout extends StatefulWidget {
  const FloatingConversationLayout({
    super.key,
    required this.header,
    required this.composer,
    required this.transcriptBuilder,
    this.onContentLaidOut,
  });

  final VoidCallback? onContentLaidOut;

  final Widget header;
  final Widget composer;

  /// Builds the transcript with the padding the overlays need.
  final Widget Function(BuildContext context, EdgeInsets insets)
  transcriptBuilder;

  @override
  State<FloatingConversationLayout> createState() =>
      _FloatingConversationLayoutState();
}

class _FloatingConversationLayoutState
    extends State<FloatingConversationLayout> {
  double _headerHeight = 0;
  double _composerHeight = 0;
  double _composerNaturalHeight = 0;

  void _setHeader(double value) {
    if (!mounted || value <= 0) return;
    if ((value - _headerHeight).abs() < .5) return;
    setState(() => _headerHeight = value);
  }

  void _setComposer(double value, double? naturalHeight) {
    if (!mounted || value <= 0) return;
    if ((value - _composerHeight).abs() < .5 &&
        (naturalHeight == null ||
            (naturalHeight - _composerNaturalHeight).abs() < .5))
      return;
    setState(() {
      _composerHeight = value;
      if (naturalHeight != null) _composerNaturalHeight = naturalHeight;
    });
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final composerLimit = math.max(
          96.0,
          math.min(
            (constraints.maxHeight - _headerHeight) * .62,
            constraints.maxHeight * .5 - 40,
          ),
        );
        final insets = EdgeInsets.only(
          top: _headerHeight,
          bottom: math.min(_composerNaturalHeight, composerLimit),
        );
        final onContentLaidOut = widget.onContentLaidOut;
        if (_headerHeight > 0 &&
            _composerHeight > 0 &&
            onContentLaidOut != null) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted &&
                identical(widget.onContentLaidOut, onContentLaidOut)) {
              onContentLaidOut();
            }
          });
        }
        return Stack(
          fit: StackFit.expand,
          children: [
            Positioned.fill(child: widget.transcriptBuilder(context, insets)),
            Positioned(
              key: const ValueKey('floating-header-extent'),
              top: 0,
              left: 0,
              right: 0,
              child: _MeasuredOverlay(
                onHeight: (height, _) => _setHeader(height),
                child: widget.header,
              ),
            ),
            Positioned(
              key: const ValueKey('floating-composer-extent'),
              bottom: 0,
              left: 0,
              right: 0,
              child: ConstrainedBox(
                // Short screens with a tall composer would let the two floating
                // panes overlap, and then the composer covers the header banner
                // (its retry button stops being tappable). Cap the composer to
                // what the header leaves over.
                constraints: BoxConstraints(
                  // Keep a readable slice of the transcript. With a keyboard
                  // inset, a dock card and a six-line draft at 2x text the
                  // composer can otherwise fill the whole body: then every
                  // scroll gesture starts on the input field, nothing in the
                  // conversation can be tapped, and the glass has nothing left
                  // to sample. The composer scrolls its own content, so capping
                  // it here costs no reachable control.
                  //
                  // Half the body minus 40 keeps the middle of the viewport
                  // clear: centring a row there (the usual way to aim it) lands
                  // above the composer instead of under it.
                  maxHeight: composerLimit,
                ),
                child: _MeasuredOverlay(
                  onHeight: _setComposer,
                  measureComposerContent: true,
                  child: widget.composer,
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

/// Reports the laid-out height of [child] once and again on every resize.
///
/// The overlays keep their natural size; only the transcript's padding reacts,
/// so measuring here cannot feed back into the measured height.
class _MeasuredOverlay extends StatefulWidget {
  const _MeasuredOverlay({
    required this.onHeight,
    required this.child,
    this.measureComposerContent = false,
  });

  final void Function(double height, double? naturalHeight) onHeight;
  final bool measureComposerContent;
  final Widget child;

  @override
  State<_MeasuredOverlay> createState() => _MeasuredOverlayState();
}

class _MeasuredOverlayState extends State<_MeasuredOverlay> {
  final _key = GlobalKey();
  double _reported = 0;
  double _reportedNatural = 0;
  bool _scheduled = false;

  @override
  void initState() {
    super.initState();
    _schedule();
  }

  @override
  void didUpdateWidget(covariant _MeasuredOverlay oldWidget) {
    super.didUpdateWidget(oldWidget);
    _schedule();
  }

  void _schedule() {
    if (_scheduled) return;
    _scheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scheduled = false;
      if (!mounted) return;
      final size = _key.currentContext?.size;
      if (size == null || size.height <= 0) return;
      double? natural;
      if (widget.measureComposerContent) {
        void findContent(Element child) {
          if (child.widget.key == const ValueKey('composer-viewport-content')) {
            final render = child.renderObject;
            if (render is RenderBox && render.hasSize)
              natural = render.size.height;
          } else {
            child.visitChildElements(findContent);
          }
        }

        _key.currentContext?.visitChildElements(findContent);
      }
      if ((size.height - _reported).abs() < .5 &&
          ((natural ?? size.height) - _reportedNatural).abs() < .5)
        return;
      _reported = size.height;
      _reportedNatural = natural ?? size.height;
      widget.onHeight(
        size.height,
        natural ?? (widget.measureComposerContent ? size.height : null),
      );
    });
  }

  @override
  Widget build(BuildContext context) =>
      NotificationListener<SizeChangedLayoutNotification>(
        onNotification: (_) {
          _schedule();
          return false;
        },
        child: SizeChangedLayoutNotifier(
          child: KeyedSubtree(key: _key, child: widget.child),
        ),
      );
}
