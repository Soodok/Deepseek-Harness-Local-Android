import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:material_ui/material_ui.dart';

import '../theme/dsh_theme.dart';

/// Softens only the transcript edges; chrome and the single scroll tree stay put.
class ConversationEdgeBlur extends StatefulWidget {
  const ConversationEdgeBlur({
    super.key,
    required this.child,
    this.contentInsets = EdgeInsets.zero,
  });

  final Widget child;

  /// Visible transcript area reserved for floating chrome. Both blur bands sit
  /// at the edge of what a person can actually read, not under the panes.
  final EdgeInsets contentInsets;
  static const double topExtent = 88;
  static const double bottomExtent = 88;

  /// The feather starts inside the chrome pane, not at its bottom edge: the
  /// pane's own padding leaves a strip of hard text right where the band would
  /// otherwise begin, which reads as the outline snapping back into focus.
  static const double overlap = 16;

  @override
  State<ConversationEdgeBlur> createState() => _ConversationEdgeBlurState();
}

class _ConversationEdgeBlurState extends State<ConversationEdgeBlur> {
  final _edges = ValueNotifier<(double, double)>((0, 0));
  BuildContext? _source;

  void _observe(ScrollMetrics metrics, BuildContext? source) {
    if (metrics.axis != Axis.vertical) return;
    _source = source;
    final reversed = metrics.axisDirection == AxisDirection.up;
    final before = reversed ? metrics.extentAfter : metrics.extentBefore;
    final after = reversed ? metrics.extentBefore : metrics.extentAfter;
    _edges.value = (
      (before / ConversationEdgeBlur.topExtent).clamp(0.0, 1.0),
      (after / ConversationEdgeBlur.bottomExtent).clamp(0.0, 1.0),
    );
  }

  @override
  void didUpdateWidget(ConversationEdgeBlur oldWidget) {
    super.didUpdateWidget(oldWidget);
    // A transcript can become an empty, non-scrolling placeholder. Do not leave
    // the removed viewport's edge strengths on that placeholder.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _source?.mounted == false) _edges.value = (0, 0);
    });
  }

  @override
  void dispose() {
    _edges.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final calm =
        MediaQuery.disableAnimationsOf(context) ||
        MediaQuery.highContrastOf(context) ||
        MediaQuery.accessibleNavigationOf(context);
    return NotificationListener<ScrollMetricsNotification>(
      onNotification: (notification) {
        if (notification.depth == 0) {
          _observe(notification.metrics, notification.context);
        }
        return false;
      },
      child: NotificationListener<ScrollNotification>(
        onNotification: (notification) {
          if (notification.depth == 0) {
            _observe(notification.metrics, notification.context);
          }
          return false;
        },
        child: ClipRect(
          child: LayoutBuilder(
            builder: (context, constraints) => Stack(
              fit: StackFit.expand,
              children: [
                widget.child,
                Positioned.fill(
                  child: IgnorePointer(
                    child: ExcludeSemantics(
                      child: ValueListenableBuilder<(double, double)>(
                        valueListenable: _edges,
                        builder: (context, edges, _) => Stack(
                          children: [
                            Positioned(
                              top: math.max(
                                0,
                                widget.contentInsets.top -
                                    ConversationEdgeBlur.overlap,
                              ),
                              left: 0,
                              right: 0,
                              height: math.min(
                                ConversationEdgeBlur.topExtent,
                                widget.contentInsets.top +
                                    (constraints.maxHeight -
                                            widget.contentInsets.top) /
                                        3,
                              ),
                              child: _Edge(
                                key: const ValueKey('conversation-edge-top'),
                                top: true,
                                strength: calm ? 0 : edges.$1,
                              ),
                            ),
                            Positioned(
                              bottom: 0,
                              left: 0,
                              right: 0,
                              height:
                                  math.max(
                                    0,
                                    widget.contentInsets.bottom -
                                        ConversationEdgeBlur.overlap,
                                  ) +
                                  math.min(
                                    ConversationEdgeBlur.bottomExtent,
                                    widget.contentInsets.bottom +
                                        (constraints.maxHeight -
                                                widget.contentInsets.bottom) /
                                            3,
                                  ),
                              child: _Edge(
                                key: const ValueKey('conversation-edge-bottom'),
                                top: false,
                                extension: math.max(
                                  0,
                                  widget.contentInsets.bottom -
                                      ConversationEdgeBlur.overlap,
                                ),
                                strength: calm ? 0 : edges.$2,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Edge extends StatelessWidget {
  const _Edge({
    super.key,
    required this.top,
    required this.strength,
    this.extension = 0,
  });

  final bool top;
  final double strength;
  final double extension;

  /// Concentric layers inside the frost area: the widest spans it, each next one
  /// is shorter and closer to the chrome. Every layer samples what the previous
  /// layers painted, so a row accumulates frost in proportion to how many layers
  /// still cover it — strength fades row by row instead of switching off at one
  /// boundary. The innermost keeps a small non-zero sigma so the last frosted
  /// row cannot drop from frosted to sharp in a single step.
  static const _layers = 4;
  static const _sigmaInnermost = .25;
  static const _sigmaOutermost = 1.8;

  /// Share of the band the frost covers. The rest is the veil's own tail, so the
  /// frost's straight edge sits under a veil that is still nearly opaque rather
  /// than exactly where content becomes readable again — several attempts showed
  /// that edge is the one thing a reader notices.
  static const _frostShare = .72;

  /// Veil: paper alpha, nearly opaque across the frost area and then fading to
  /// fully transparent over the remaining tail. Alpha changes every row, so the
  /// transition a reader perceives is a continuous fade; the frost only has to
  /// keep the glyphs from staying crisp inside the opaque part.
  static const _veilAlpha = .64;
  static const _veilExtentFactor = 1.15;

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    final outer = top ? Alignment.topCenter : Alignment.bottomCenter;
    final inner = top ? Alignment.bottomCenter : Alignment.topCenter;
    return LayoutBuilder(
      builder: (context, constraints) {
        final band = constraints.maxHeight - extension;
        return Stack(
          fit: StackFit.expand,
          children: [
            Positioned(
              top: top ? 0 : null,
              bottom: top ? null : 0,
              left: 0,
              right: 0,
              height: extension + band * _frostShare,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  for (var i = 0; i < _layers; i++)
                    Positioned(
                      top: top ? 0 : null,
                      bottom: top ? null : 0,
                      left: 0,
                      right: 0,
                      height:
                          extension +
                          band * _frostShare * (_layers - i) / _layers,
                      // ClipRect is load-bearing: an unclipped BackdropFilter
                      // paints past the child's bounds and smears the transcript.
                      child: ClipRect(
                        child: BackdropFilter(
                          enabled: strength > 0,
                          filter: ui.ImageFilter.blur(
                            sigmaX:
                                (_sigmaInnermost +
                                    (_sigmaOutermost - _sigmaInnermost) *
                                        i /
                                        (_layers - 1)) *
                                strength,
                            sigmaY:
                                (_sigmaInnermost +
                                    (_sigmaOutermost - _sigmaInnermost) *
                                        i /
                                        (_layers - 1)) *
                                strength,
                          ),
                          child: const SizedBox.expand(),
                        ),
                      ),
                    ),
                ],
              ),
            ),
            DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: outer,
                  end: inner,
                  colors: [
                    palette.paper.withValues(alpha: _veilAlpha * strength),
                    palette.paper.withValues(alpha: 0),
                  ],
                  stops: [
                    extension / constraints.maxHeight,
                    (extension + band / _veilExtentFactor) /
                        constraints.maxHeight,
                  ],
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}
