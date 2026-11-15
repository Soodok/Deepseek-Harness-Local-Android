import 'package:material_ui/material_ui.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import '../theme/dsh_theme.dart';

/// A bounded floating material. Content keeps its element when material changes.
class GlassPanel extends StatelessWidget {
  const GlassPanel({super.key, required this.child, this.radius = 28});
  final Widget child;
  final double radius;
  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    final calm =
        MediaQuery.disableAnimationsOf(context) ||
        MediaQuery.highContrastOf(context);
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: Stack(
        fit: StackFit.passthrough,
        children: [
          Positioned.fill(
            child: IgnorePointer(
              child: calm
                  ? DecoratedBox(
                      decoration: BoxDecoration(
                        color: colors.surface,
                        borderRadius: BorderRadius.circular(radius),
                        border: Border.all(color: colors.line, width: .6),
                      ),
                    )
                  : GlassContainer(
                      useOwnLayer: true,
                      quality: GlassQuality.standard,
                      shape: LiquidRoundedSuperellipse(borderRadius: radius),
                      clipBehavior: Clip.antiAlias,
                      settings: LiquidGlassSettings(
                        glassColor: colors.surface.withValues(
                          alpha:
                              Theme.of(context).brightness == Brightness.light
                              ? 0.62
                              : 0.72,
                        ),
                        thickness: 12,
                        blur: 8,
                        chromaticAberration: 0,
                        saturation: 1.05,
                        lightIntensity: .22,
                        ambientStrength: .08,
                        refractiveIndex: 1.08,
                        glowIntensity: 0,
                        shadowElevation: 0,
                      ),
                      child: const SizedBox.expand(),
                    ),
            ),
          ),
          child,
        ],
      ),
    );
  }
}
