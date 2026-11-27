import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:material_ui/material_ui.dart';

import '../theme/dsh_theme.dart';

/// Resolved glass material for one brightness. Values follow the same physical
/// model as the reference (blurred backdrop → translucent fill → hairline +
/// top rim highlight → shadow drawn only outside the shape), but the painting
/// is written here against Flutter's public [BackdropFilter]/[ImageFilter] API.
@immutable
class DshGlassStyle {
  const DshGlassStyle({
    required this.fill,
    required this.fallbackFill,
    required this.hairline,
    required this.rim,
    required this.blurSigma,
    required this.saturation,
    required this.shadows,
  });

  /// Fill used while the backdrop is visible through the blur.
  final Color fill;

  /// Near-opaque fill used when blur is off (reduce motion / high contrast).
  final Color fallbackFill;

  /// 0.5–1dp edge stroke that defines the pane on any background.
  final Color hairline;
  final Color rim;

  /// Gaussian blur radius in logical pixels; 0 disables the backdrop filter.
  final double blurSigma;
  final double saturation;
  final List<BoxShadow> shadows;

  bool get blurs => blurSigma > 0;

  /// One deliberate tier: floating chrome (top bar, composer, sheets).
  static DshGlassStyle resolve(
    BuildContext context, {
    double scale = 1,
    bool composer = false,
  }) {
    final palette = DshPalette.of(context);
    final light = Theme.of(context).brightness == Brightness.light;
    final ink = light ? const Color(0xFF1D2320) : const Color(0xFF000000);
    if (composer) {
      // The composer floats over the transcript rather than sitting in its own
      // band, so it needs the lift that separation used to give it: one soft
      // shadow, no second hairline ring.
      return DshGlassStyle(
        fill: palette.surface.withValues(alpha: .34),
        fallbackFill: palette.surface.withValues(alpha: .97),
        hairline: Colors.transparent,
        rim: Colors.transparent,
        blurSigma: 8 * scale,
        saturation: 1,
        shadows: [
          BoxShadow(
            color: ink.withValues(alpha: light ? .10 : .34),
            blurRadius: 22,
            offset: const Offset(0, 8),
          ),
        ],
      );
    }
    return light
        ? DshGlassStyle(
            fill: palette.surface.withValues(alpha: .62),
            fallbackFill: palette.surface.withValues(alpha: .97),
            hairline: ink.withValues(alpha: .10),
            rim: Colors.white.withValues(alpha: .82),
            blurSigma: 16 * scale,
            saturation: 1.12,
            shadows: [
              BoxShadow(
                color: ink.withValues(alpha: .10),
                blurRadius: 26,
                offset: const Offset(0, 10),
              ),
              BoxShadow(
                color: ink.withValues(alpha: .06),
                blurRadius: 3,
                offset: const Offset(0, 1),
              ),
            ],
          )
        : DshGlassStyle(
            fill: palette.surface.withValues(alpha: .58),
            fallbackFill: palette.surface.withValues(alpha: .97),
            hairline: Colors.white.withValues(alpha: .12),
            rim: Colors.white.withValues(alpha: .16),
            blurSigma: 16 * scale,
            saturation: 1.06,
            shadows: [
              BoxShadow(
                color: Colors.black.withValues(alpha: .38),
                blurRadius: 26,
                offset: const Offset(0, 10),
              ),
              BoxShadow(
                color: Colors.black.withValues(alpha: .22),
                blurRadius: 3,
                offset: const Offset(0, 1),
              ),
            ],
          );
  }
}

/// A bounded floating pane over scrolling content.
///
/// The subtree keeps its element and State when the material changes: editors,
/// focus and scroll offsets survive a reduce-motion or theme switch.
class GlassPanel extends StatelessWidget {
  const GlassPanel({
    super.key,
    required this.child,
    this.radius = 28,
    this.blurScale = 1,
    this.composerGlass = false,
  });

  final Widget child;
  final double radius;
  final double blurScale;
  final bool composerGlass;

  @override
  Widget build(BuildContext context) {
    final style = DshGlassStyle.resolve(
      context,
      scale: blurScale,
      composer: composerGlass,
    );
    final calm =
        MediaQuery.disableAnimationsOf(context) ||
        MediaQuery.highContrastOf(context);
    final useBlur = !calm && style.blurs;
    final borderRadius = BorderRadius.circular(radius);
    final fill = useBlur ? style.fill : style.fallbackFill;

    // The widget structure is identical for both materials so switching to the
    // calm fallback keeps every descendant's element, State, focus and scroll
    // offset. Only the blur's [enabled] flag and the fill color change.
    final pane = ClipRRect(
      borderRadius: borderRadius,
      child: BackdropFilter(
        enabled: useBlur,
        filter: ui.ImageFilter.compose(
          outer: ui.ColorFilter.matrix(_saturate(style.saturation)),
          inner: ui.ImageFilter.blur(
            sigmaX: style.blurSigma,
            sigmaY: style.blurSigma,
          ),
        ),
        child: CustomPaint(
          foregroundPainter: _GlassEdgePainter(
            radius: radius,
            hairline: style.hairline,
            rim: style.rim,
          ),
          child: DecoratedBox(
            decoration: BoxDecoration(color: fill, borderRadius: borderRadius),
            child: child,
          ),
        ),
      ),
    );
    return CustomPaint(
      key: const ValueKey('glass-panel'),
      painter: _GlassShadowPainter(radius: radius, shadows: style.shadows),
      child: pane,
    );
  }

  /// Luminance-preserving saturation matrix (Rec. 709 weights).
  static List<double> _saturate(double s) {
    final r = 0.2126 * (1 - s);
    final g = 0.7152 * (1 - s);
    final b = 0.0722 * (1 - s);
    return <double>[
      r + s, g, b, 0, 0, //
      r, g + s, b, 0, 0, //
      r, g, b + s, 0, 0, //
      0, 0, 0, 1, 0, //
    ];
  }
}

/// Hairline plus a top rim highlight that fades out by 45% height.
class _GlassEdgePainter extends CustomPainter {
  const _GlassEdgePainter({
    required this.radius,
    required this.hairline,
    required this.rim,
  });

  final double radius;
  final Color hairline;
  final Color rim;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final rect = Offset.zero & size;
    final width = math.max(0.5, 0.6);
    final path = Path()
      ..addRRect(
        RRect.fromRectAndRadius(
          rect.deflate(width / 2),
          Radius.circular(radius),
        ),
      );
    if (hairline.a > 0) {
      canvas.drawPath(
        path,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = width
          ..color = hairline,
      );
    }
    if (rim.a > 0) {
      canvas.drawPath(
        path,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = math.max(width, 0.9)
          ..shader = LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [rim, rim.withValues(alpha: 0)],
            stops: const [0, .45],
          ).createShader(rect),
      );
    }
  }

  @override
  bool shouldRepaint(_GlassEdgePainter old) =>
      old.radius != radius || old.hairline != hairline || old.rim != rim;
}

/// Shadows are clipped to the shape's exterior: a shadow painted underneath a
/// translucent pane would show through the glass and be blurred into grey.
class _GlassShadowPainter extends CustomPainter {
  const _GlassShadowPainter({required this.radius, required this.shadows});

  final double radius;
  final List<BoxShadow> shadows;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty || shadows.isEmpty) return;
    final rect = Offset.zero & size;
    var extent = 0.0;
    for (final s in shadows) {
      extent = math.max(
        extent,
        s.blurRadius * 2 + s.spreadRadius.abs() + s.offset.distance,
      );
    }
    final inner = Path()
      ..addRRect(RRect.fromRectAndRadius(rect, Radius.circular(radius)));
    final outside = Path()
      ..fillType = PathFillType.evenOdd
      ..addRect(rect.inflate(extent + 2))
      ..addPath(inner, Offset.zero);
    canvas.save();
    canvas.clipPath(outside);
    for (final s in shadows) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          rect.shift(s.offset).inflate(s.spreadRadius),
          Radius.circular(radius),
        ),
        s.toPaint(),
      );
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_GlassShadowPainter old) =>
      old.radius != radius || old.shadows != shadows;
}

/// Page backdrop the glass panes sample: a vertical wash plus two soft glows,
/// so the blur has real content behind it instead of a flat color.
class DshBackdrop extends StatelessWidget {
  const DshBackdrop({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    final light = Theme.of(context).brightness == Brightness.light;
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Color.alphaBlend(
              palette.ice.withValues(alpha: light ? .10 : .14),
              palette.paper,
            ),
            palette.paper,
            Color.alphaBlend(
              palette.iceDeep.withValues(alpha: light ? .07 : .10),
              palette.paper,
            ),
          ],
          stops: const [0, .55, 1],
        ),
      ),
      child: Stack(
        children: [
          Positioned(
            key: const ValueKey('backdrop-glow-top'),
            top: -120,
            right: -80,
            child: _Glow(
              color: palette.ice.withValues(alpha: light ? .16 : .22),
              size: 340,
            ),
          ),
          Positioned(
            key: const ValueKey('backdrop-glow-bottom'),
            bottom: -140,
            left: -100,
            child: _Glow(
              color: palette.iceDeep.withValues(alpha: light ? .12 : .18),
              size: 380,
            ),
          ),
          Positioned.fill(child: child),
        ],
      ),
    );
  }
}

class _Glow extends StatelessWidget {
  const _Glow({required this.color, required this.size});
  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) => IgnorePointer(
    child: Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: RadialGradient(colors: [color, color.withValues(alpha: 0)]),
      ),
    ),
  );
}
