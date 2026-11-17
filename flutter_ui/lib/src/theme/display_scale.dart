import 'dart:math' as math;

import 'package:material_ui/material_ui.dart';

/// In-app display size. It scales the painted text and widget geometry of this
/// Flutter surface; the Android system density and font size are never written.
enum DshDisplaySize {
  compact('compact', '紧凑', 0.9),
  standard('standard', '标准', 1),
  relaxed('relaxed', '宽松', 1.1);

  const DshDisplaySize(this.wireValue, this.label, this.factor);

  final String wireValue;
  final String label;

  /// Multiplier of the whole surface. Type, paddings, icons and fixed extents
  /// all follow it because one coordinate system is scaled, not each widget.
  final double factor;

  static const fallback = DshDisplaySize.standard;

  /// Stored values arrive from SharedPreferences, so anything unknown (an old
  /// build, a manual edit, an absent key) falls back to the standard size
  /// instead of guessing.
  static DshDisplaySize fromWire(Object? value) =>
      DshDisplaySize.values
          .where((size) => size.wireValue == value)
          .firstOrNull ??
      fallback;
}

/// Publishes the chosen display size to the whole Flutter surface, including
/// routes and overlays pushed after the choice.
class DshDisplayScope extends InheritedWidget {
  const DshDisplayScope({
    super.key,
    required this.size,
    required super.child,
    this.onChanged,
  });

  /// The touch target every control must keep in real device dp, at every
  /// display size.
  static const double physicalTarget = 48;

  final DshDisplaySize size;

  /// Applies a new size. Null means the surrounding app cannot persist one
  /// (a bare test or a preview), and the page then says so instead of lying.
  final ValueChanged<DshDisplaySize>? onChanged;

  double get factor => size.factor;

  static DshDisplayScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<DshDisplayScope>();

  static DshDisplaySize sizeOf(BuildContext context) =>
      maybeOf(context)?.size ?? DshDisplaySize.fallback;

  static ValueChanged<DshDisplaySize>? onChangedOf(BuildContext context) =>
      maybeOf(context)?.onChanged;

  /// Paint/layout multiplier of the scaled canvas. A widget outside the scope
  /// (isolated tests, previews) simply stays at 1.
  static double factorOf(BuildContext context) => maybeOf(context)?.factor ?? 1;

  /// The touch floor in the logical units this subtree draws in.
  ///
  /// The canvas multiplies every logical unit by [factorOf], so a control that
  /// kept 48 logical would measure 43.2dp on the glass at the compact size.
  /// The floor rises when the canvas shrinks and stays at the design value when
  /// it grows, so [physicalTarget] is never violated in the compact direction.
  static double minTargetOf(BuildContext context) =>
      math.max(physicalTarget, physicalTarget / factorOf(context));

  /// [base] logical dp, painted no smaller than a real 48dp target.
  static double atLeastTarget(BuildContext context, double base) =>
      math.max(base, minTargetOf(context));

  /// A size whose height is at least a real 48dp target. A [width] of 0 or
  /// less keeps "let the row decide" (for a button that fills its track).
  static Size targetSize(BuildContext context, {double width = 48}) {
    final floor = minTargetOf(context);
    return Size(width <= 0 ? width : math.max(width, floor), floor);
  }

  @override
  bool updateShouldNotify(DshDisplayScope oldWidget) =>
      size != oldWidget.size || onChanged != oldWidget.onChanged;
}

/// Applies the in-app display size to one subtree by scaling its whole
/// coordinate system, not just its type.
///
/// The subtree is laid out in logical units of `size / factor` and painted
/// through a [Transform], so text, paddings, icon sizes, fixed extents, dialogs,
/// bottom sheets and pushed routes all change together from a single switch and
/// no widget needs its own multiplier.
///
/// Three properties are load bearing:
///
/// * The widget chain is identical at every size, including 1.0. Swapping the
///   parent for another would remount the [Navigator] and drop routes,
///   overlays, focus, drafts and scroll offsets.
/// * [MediaQueryData.size], `devicePixelRatio`, `padding`, `viewPadding`,
///   `viewInsets` and `systemGestureInsets` are converted into the same logical
///   units, so safe areas and the IME still sit on their real physical edges.
/// * `textScaler` passes through untouched: the platform font-size setting
///   (non-linear scalers included) keeps working on top of the canvas scale,
///   because the scale is applied by drawing rather than to the font size.
class DshDisplayCanvas extends StatelessWidget {
  const DshDisplayCanvas({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scale = DshDisplayScope.factorOf(context);
    final media = MediaQuery.of(context);
    final logical = scale == 1.0
        ? media
        : media.copyWith(
            size: media.size / scale,
            // One logical unit covers more real pixels, so image caches keep
            // their physical resolution.
            devicePixelRatio: media.devicePixelRatio * scale,
            padding: media.padding / scale,
            viewPadding: media.viewPadding / scale,
            viewInsets: media.viewInsets / scale,
            systemGestureInsets: media.systemGestureInsets / scale,
          );
    // Fixed structure at every size: only the numbers and the paint transform
    // change, never which widgets are in the chain.
    return MediaQuery(
      data: logical,
      child: OverflowBox(
        alignment: Alignment.topLeft,
        minWidth: 0,
        maxWidth: double.infinity,
        minHeight: 0,
        maxHeight: double.infinity,
        child: Transform.scale(
          scale: scale,
          alignment: Alignment.topLeft,
          child: SizedBox.fromSize(size: logical.size, child: child),
        ),
      ),
    );
  }
}
