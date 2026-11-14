import 'dart:ui' show ImageFilter;

import 'package:material_ui/material_ui.dart';

import '../theme/dsh_theme.dart';

/// Blur is clipped to one small surface rather than the scrolling conversation.
class GlassPanel extends StatelessWidget {
  const GlassPanel({super.key, required this.child, this.radius = 28});
  final Widget child;
  final double radius;
  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      borderRadius: BorderRadius.circular(radius),
      boxShadow: [
        BoxShadow(
          color: Theme.of(context).brightness == Brightness.light
              ? const Color(0x14182D4C)
              : const Color(0x55000000),
          blurRadius: 24,
          offset: Offset(0, 8),
        ),
      ],
    ),
    child: ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 12, sigmaY: 12),
        enabled: !MediaQuery.of(context).disableAnimations,
        child: DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [
                DshPalette.of(context).surfaceRaised.withValues(alpha: .94),
                DshPalette.of(context).surface.withValues(alpha: .97),
              ],
            ),
            borderRadius: BorderRadius.circular(radius),
            border: Border.all(
              color: DshPalette.of(context).ink.withValues(alpha: .16),
              width: .8,
            ),
          ),
          child: child,
        ),
      ),
    ),
  );
}
