import 'package:material_ui/material_ui.dart';

/// Neutral reading palette for the minimal conversation surface.
class DshPalette {
  const DshPalette._();

  static const ink = Color(0xFFE3ECEA);
  static const inkSoft = Color(0xFFA8B8B8);
  static const inkFaint = Color(0xFF93A4A6);
  static const paper = Color(0xFF0F1517);
  static const paperDeep = Color(0xFF0B1012);
  static const surface = Color(0xFF172023);
  static const surfaceRaised = Color(0xFF1E2A2D);
  static const line = Color(0xFF2A383C);
  static const ice = Color(0xFF7DB6D6);
  static const sendBlue = Color(0xFF3B82F6);
  static const sendBlueMuted = Color(0xFF203B62);
  static const iceDeep = Color(0xFF7DB6D6);
  static const userWash = Color(0xFF1A2B2E);
  static const good = Color(0xFF6FCB9A);
  static const warn = Color(0xFFE6B45A);
  static const bad = Color(0xFFE6877D);
}

class DshTheme {
  const DshTheme._();

  static ThemeData light() {
    const ink = DshPalette.ink;
    const paper = DshPalette.paper;
    final scheme =
        ColorScheme.fromSeed(
          seedColor: DshPalette.ice,
          brightness: Brightness.dark,
        ).copyWith(
          primary: DshPalette.ice,
          onPrimary: const Color(0xFF0F1517),
          surface: paper,
          onSurface: ink,
        );
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: scheme,
      scaffoldBackgroundColor: paper,
      splashFactory: InkRipple.splashFactory,
      highlightColor: Colors.transparent,
      dividerColor: DshPalette.line,
      textTheme: const TextTheme(
        bodyLarge: TextStyle(
          color: ink,
          fontSize: 16.5,
          height: 1.62,
          letterSpacing: 0.1,
        ),
        bodyMedium: TextStyle(color: ink, fontSize: 14.5, height: 1.55),
        titleMedium: TextStyle(
          color: ink,
          fontSize: 16,
          fontWeight: FontWeight.w600,
          letterSpacing: -0.1,
        ),
        labelMedium: TextStyle(
          color: DshPalette.inkSoft,
          fontSize: 12.5,
          letterSpacing: 0.1,
        ),
      ),
      dividerTheme: const DividerThemeData(
        color: DshPalette.line,
        thickness: 0.6,
        space: 0.6,
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(
          foregroundColor: ink,
          minimumSize: const Size(48, 48),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: DshPalette.ice,
          foregroundColor: const Color(0xFF0F1517),
          minimumSize: const Size(64, 48),
          padding: const EdgeInsets.symmetric(horizontal: 18),
          textStyle: const TextStyle(
            fontSize: 14.5,
            fontWeight: FontWeight.w600,
          ),
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(12)),
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: ink,
          minimumSize: const Size(64, 48),
          side: const BorderSide(color: DshPalette.line),
          padding: const EdgeInsets.symmetric(horizontal: 16),
          textStyle: const TextStyle(
            fontSize: 14.5,
            fontWeight: FontWeight.w500,
          ),
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(12)),
          ),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: DshPalette.ice,
          minimumSize: const Size(64, 48),
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
        ),
      ),
      snackBarTheme: const SnackBarThemeData(
        backgroundColor: DshPalette.surfaceRaised,
        contentTextStyle: TextStyle(color: DshPalette.ink, fontSize: 14),
        behavior: SnackBarBehavior.floating,
      ),
      inputDecorationTheme: const InputDecorationTheme(
        filled: true,
        fillColor: DshPalette.paperDeep,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(14)),
          borderSide: BorderSide(color: DshPalette.line),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(14)),
          borderSide: BorderSide(color: DshPalette.line),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(14)),
          borderSide: BorderSide(color: DshPalette.ice, width: 1.2),
        ),
        contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      ),
    );
  }
}

/// Simplified blue identity mark drawn in geometry, no external asset.
class DshMark extends StatelessWidget {
  const DshMark({super.key, this.size = 22, this.dim = false});

  final double size;
  final bool dim;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: Size.square(size),
      painter: _DshMarkPainter(dim: dim),
    );
  }
}

class _DshMarkPainter extends CustomPainter {
  const _DshMarkPainter({required this.dim});

  final bool dim;

  @override
  void paint(Canvas canvas, Size size) {
    final s = size.shortestSide;
    final body = Paint()
      ..color = (dim ? DshPalette.inkFaint : DshPalette.ice).withValues(
        alpha: dim ? 0.7 : 1,
      )
      ..style = PaintingStyle.fill;
    final fin = Paint()
      ..color = DshPalette.iceDeep.withValues(alpha: dim ? 0.45 : 0.9)
      ..style = PaintingStyle.fill;

    final spine = Path()
      ..moveTo(s * 0.10, s * 0.56)
      ..quadraticBezierTo(s * 0.30, s * 0.30, s * 0.62, s * 0.34)
      ..quadraticBezierTo(s * 0.90, s * 0.37, s * 0.84, s * 0.52)
      ..quadraticBezierTo(s * 0.74, s * 0.74, s * 0.34, s * 0.72)
      ..quadraticBezierTo(s * 0.12, s * 0.70, s * 0.10, s * 0.56)
      ..close();
    canvas.drawPath(spine, body);

    final tail = Path()
      ..moveTo(s * 0.16, s * 0.50)
      ..lineTo(s * 0.02, s * 0.34)
      ..lineTo(s * 0.05, s * 0.66)
      ..close();
    canvas.drawPath(tail, fin);

    canvas.drawCircle(
      Offset(s * 0.66, s * 0.45),
      s * 0.035,
      Paint()..color = const Color(0xFF0F1517),
    );
  }

  @override
  bool shouldRepaint(covariant _DshMarkPainter oldDelegate) =>
      oldDelegate.dim != dim;
}
