import 'package:material_ui/material_ui.dart';

class DshColors {
  const DshColors({
    required this.ink,
    required this.inkSoft,
    required this.inkFaint,
    required this.paper,
    required this.paperDeep,
    required this.surface,
    required this.surfaceRaised,
    required this.line,
    required this.ice,
    required this.sendBlue,
    required this.sendBlueMuted,
    required this.iceDeep,
    required this.userWash,
    required this.good,
    required this.warn,
    required this.bad,
    required this.thought,
    required this.terminal,
    required this.edit,
  });
  final Color ink;
  final Color inkSoft;
  final Color inkFaint;
  final Color paper;
  final Color paperDeep;
  final Color surface;
  final Color surfaceRaised;
  final Color line;
  final Color ice;
  final Color sendBlue;
  final Color sendBlueMuted;
  final Color iceDeep;
  final Color userWash;
  final Color good;
  final Color warn;
  final Color bad;
  final Color thought;
  final Color terminal;
  final Color edit;
}

/// Blue surfaces selected from the inherited Material theme.
class DshPalette {
  const DshPalette._();
  static const dark = DshColors(
    ink: Color(0xFFEAF1FF),
    inkSoft: Color(0xFFB6C6E3),
    inkFaint: Color(0xFF94A9C9),
    paper: Color(0xFF0C1528),
    paperDeep: Color(0xFF080F1D),
    surface: Color(0xFF142440),
    surfaceRaised: Color(0xFF1D3357),
    line: Color(0xFF304D78),
    ice: Color(0xFF8AB6FF),
    sendBlue: Color(0xFF3B82F6),
    sendBlueMuted: Color(0xFF234777),
    iceDeep: Color(0xFF8AB6FF),
    userWash: Color(0xFF1B3865),
    good: Color(0xFF6FD3A0),
    warn: Color(0xFFF0BE62),
    bad: Color(0xFFFF978F),
    thought: Color(0xFFB8B3FF),
    terminal: Color(0xFF9ABDEB),
    edit: Color(0xFF95C9B1),
  );
  static const light = DshColors(
    ink: Color(0xFF182D4C),
    inkSoft: Color(0xFF445F83),
    inkFaint: Color(0xFF4E698E),
    paper: Color(0xFFF6F9FF),
    paperDeep: Color(0xFFEAF1FC),
    surface: Color(0xFFFFFFFF),
    surfaceRaised: Color(0xFFE8F0FF),
    line: Color(0xFFC1D1E9),
    ice: Color(0xFF235AC1),
    sendBlue: Color(0xFF2563D9),
    sendBlueMuted: Color(0xFFB8CCEF),
    iceDeep: Color(0xFF235AC1),
    userWash: Color(0xFFDFEAFF),
    good: Color(0xFF216B4A),
    warn: Color(0xFF875800),
    bad: Color(0xFFAE313F),
    thought: Color(0xFF6250A6),
    terminal: Color(0xFF285D9B),
    edit: Color(0xFF286B50),
  );
  static DshColors of(BuildContext context) =>
      Theme.of(context).brightness == Brightness.light ? light : dark;
}

class DshTheme {
  const DshTheme._();

  static ThemeData light() => _build(DshPalette.light, Brightness.light);
  static ThemeData dark() => _build(DshPalette.dark, Brightness.dark);
  static ThemeData _build(DshColors colors, Brightness brightness) {
    final ink = colors.ink;
    final paper = colors.paper;
    final scheme =
        ColorScheme.fromSeed(
          seedColor: colors.ice,
          brightness: brightness,
        ).copyWith(
          primary: colors.ice,
          onPrimary: colors.paperDeep,
          surface: paper,
          onSurface: ink,
        );
    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: paper,
      splashFactory: InkRipple.splashFactory,
      highlightColor: Colors.transparent,
      dividerColor: colors.line,
      textTheme: TextTheme(
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
          color: colors.inkSoft,
          fontSize: 12.5,
          letterSpacing: 0.1,
        ),
      ),
      dividerTheme: DividerThemeData(
        color: colors.line,
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
          backgroundColor: colors.ice,
          foregroundColor: colors.paperDeep,
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
          side: BorderSide(color: colors.line),
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
          foregroundColor: colors.ice,
          minimumSize: const Size(64, 48),
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: colors.surfaceRaised,
        contentTextStyle: TextStyle(color: colors.ink, fontSize: 14),
        behavior: SnackBarBehavior.floating,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: colors.paperDeep,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(14)),
          borderSide: BorderSide(color: colors.line),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(14)),
          borderSide: BorderSide(color: colors.line),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(14)),
          borderSide: BorderSide(color: colors.ice, width: 1.2),
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
      painter: _DshMarkPainter(dim: dim, colors: DshPalette.of(context)),
    );
  }
}

class _DshMarkPainter extends CustomPainter {
  const _DshMarkPainter({required this.dim, required this.colors});

  final bool dim;
  final DshColors colors;

  @override
  void paint(Canvas canvas, Size size) {
    final s = size.shortestSide;
    final body = Paint()
      ..color = (dim ? colors.inkFaint : colors.ice).withValues(
        alpha: dim ? 0.7 : 1,
      )
      ..style = PaintingStyle.fill;
    final fin = Paint()
      ..color = colors.iceDeep.withValues(alpha: dim ? 0.45 : 0.9)
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
      Paint()..color = colors.paperDeep,
    );
  }

  @override
  bool shouldRepaint(covariant _DshMarkPainter oldDelegate) =>
      oldDelegate.dim != dim || oldDelegate.colors != colors;
}

class DshAppearance extends InheritedWidget {
  const DshAppearance({
    super.key,
    required this.mode,
    required this.onChanged,
    required super.child,
  });
  final ThemeMode mode;
  final ValueChanged<ThemeMode> onChanged;
  static DshAppearance? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<DshAppearance>();
  @override
  bool updateShouldNotify(DshAppearance oldWidget) => mode != oldWidget.mode;
}
