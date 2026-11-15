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

enum DshMood {
  mist('雾灰'),
  sand('暖砂'),
  sage('鼠尾草'),
  plum('暮紫');

  const DshMood(this.label);
  final String label;
}

class DshColorTheme extends ThemeExtension<DshColorTheme> {
  const DshColorTheme(this.colors);
  final DshColors colors;
  @override
  DshColorTheme copyWith({DshColors? colors}) =>
      DshColorTheme(colors ?? this.colors);
  @override
  DshColorTheme lerp(covariant DshColorTheme? other, double t) {
    if (other == null) return this;
    return DshColorTheme(
      DshColors(
        ink: Color.lerp(colors.ink, other.colors.ink, t)!,
        inkSoft: Color.lerp(colors.inkSoft, other.colors.inkSoft, t)!,
        inkFaint: Color.lerp(colors.inkFaint, other.colors.inkFaint, t)!,
        paper: Color.lerp(colors.paper, other.colors.paper, t)!,
        paperDeep: Color.lerp(colors.paperDeep, other.colors.paperDeep, t)!,
        surface: Color.lerp(colors.surface, other.colors.surface, t)!,
        surfaceRaised: Color.lerp(
          colors.surfaceRaised,
          other.colors.surfaceRaised,
          t,
        )!,
        line: Color.lerp(colors.line, other.colors.line, t)!,
        ice: Color.lerp(colors.ice, other.colors.ice, t)!,
        sendBlue: Color.lerp(colors.sendBlue, other.colors.sendBlue, t)!,
        sendBlueMuted: Color.lerp(
          colors.sendBlueMuted,
          other.colors.sendBlueMuted,
          t,
        )!,
        iceDeep: Color.lerp(colors.iceDeep, other.colors.iceDeep, t)!,
        userWash: Color.lerp(colors.userWash, other.colors.userWash, t)!,
        good: Color.lerp(colors.good, other.colors.good, t)!,
        warn: Color.lerp(colors.warn, other.colors.warn, t)!,
        bad: Color.lerp(colors.bad, other.colors.bad, t)!,
        thought: Color.lerp(colors.thought, other.colors.thought, t)!,
        terminal: Color.lerp(colors.terminal, other.colors.terminal, t)!,
        edit: Color.lerp(colors.edit, other.colors.edit, t)!,
      ),
    );
  }
}

class DshPalette {
  const DshPalette._();
  static const light = DshColors(
    ink: Color(0xFF242E2C),
    inkSoft: Color(0xFF4D5E59),
    inkFaint: Color(0xFF52665E),
    paper: Color(0xFFF5F5F2),
    paperDeep: Color(0xFFEBEEEA),
    surface: Color(0xFFFEFFFD),
    surfaceRaised: Color(0xFFE8ECE8),
    line: Color(0xFFC9D1CC),
    ice: Color(0xFF356B59),
    sendBlue: Color(0xFF2563D9),
    sendBlueMuted: Color(0xFFB8CCDF),
    iceDeep: Color(0xFF356B59),
    userWash: Color(0xFFE3E8E4),
    good: Color(0xFF216B4A),
    warn: Color(0xFF875800),
    bad: Color(0xFFAE313F),
    thought: Color(0xFF6250A6),
    terminal: Color(0xFF285D9B),
    edit: Color(0xFF286B50),
  );
  static const dark = DshColors(
    ink: Color(0xFFE7EEE9),
    inkSoft: Color(0xFFB7C6BC),
    inkFaint: Color(0xFF9BB1A3),
    paper: Color(0xFF151A19),
    paperDeep: Color(0xFF101413),
    surface: Color(0xFF202725),
    surfaceRaised: Color(0xFF2B3531),
    line: Color(0xFF3A4941),
    ice: Color(0xFF93C8AE),
    sendBlue: Color(0xFF3B82F6),
    sendBlueMuted: Color(0xFF294C66),
    iceDeep: Color(0xFF93C8AE),
    userWash: Color(0xFF2C3B35),
    good: Color(0xFF6FD3A0),
    warn: Color(0xFFF0BE62),
    bad: Color(0xFFFF978F),
    thought: Color(0xFFC7AEE3),
    terminal: Color(0xFF9ABDEB),
    edit: Color(0xFF95C9B1),
  );
  static const sandLight = DshColors(
    ink: Color(0xFF332D26),
    inkSoft: Color(0xFF685A49),
    inkFaint: Color(0xFF715D47),
    paper: Color(0xFFF8F5EF),
    paperDeep: Color(0xFFF0EBE2),
    surface: Color(0xFFFFFCF5),
    surfaceRaised: Color(0xFFF0E9DE),
    line: Color(0xFFD6CBBB),
    ice: Color(0xFF8B5B31),
    sendBlue: Color(0xFF2563D9),
    sendBlueMuted: Color(0xFFC7C9CC),
    iceDeep: Color(0xFF8B5B31),
    userWash: Color(0xFFEBE2D5),
    good: Color(0xFF216B4A),
    warn: Color(0xFF875800),
    bad: Color(0xFFAE313F),
    thought: Color(0xFF6250A6),
    terminal: Color(0xFF285D9B),
    edit: Color(0xFF286B50),
  );
  static const sandDark = DshColors(
    ink: Color(0xFFF2E9DC),
    inkSoft: Color(0xFFD0BEA6),
    inkFaint: Color(0xFFBAA58A),
    paper: Color(0xFF1D1A17),
    paperDeep: Color(0xFF161310),
    surface: Color(0xFF29241F),
    surfaceRaised: Color(0xFF363028),
    line: Color(0xFF4C4135),
    ice: Color(0xFFE1B27C),
    sendBlue: Color(0xFF3B82F6),
    sendBlueMuted: Color(0xFF344653),
    iceDeep: Color(0xFFE1B27C),
    userWash: Color(0xFF3D3127),
    good: Color(0xFF6FD3A0),
    warn: Color(0xFFF0BE62),
    bad: Color(0xFFFF978F),
    thought: Color(0xFFC7AEE3),
    terminal: Color(0xFF9ABDEB),
    edit: Color(0xFF95C9B1),
  );
  static const sageLight = DshColors(
    ink: Color(0xFF233329),
    inkSoft: Color(0xFF4C604E),
    inkFaint: Color(0xFF50674E),
    paper: Color(0xFFF1F5EF),
    paperDeep: Color(0xFFE6EDE2),
    surface: Color(0xFFFAFDF7),
    surfaceRaised: Color(0xFFE3ECDF),
    line: Color(0xFFC3D1BC),
    ice: Color(0xFF346344),
    sendBlue: Color(0xFF2563D9),
    sendBlueMuted: Color(0xFFB9CDCF),
    iceDeep: Color(0xFF346344),
    userWash: Color(0xFFDDE8D8),
    good: Color(0xFF216B4A),
    warn: Color(0xFF875800),
    bad: Color(0xFFAE313F),
    thought: Color(0xFF6250A6),
    terminal: Color(0xFF285D9B),
    edit: Color(0xFF286B50),
  );
  static const sageDark = DshColors(
    ink: Color(0xFFE5EFE3),
    inkSoft: Color(0xFFB6CBB2),
    inkFaint: Color(0xFFA0B69B),
    paper: Color(0xFF151D18),
    paperDeep: Color(0xFF101612),
    surface: Color(0xFF202C24),
    surfaceRaised: Color(0xFF2C3B30),
    line: Color(0xFF3D5140),
    ice: Color(0xFF9ACB91),
    sendBlue: Color(0xFF3B82F6),
    sendBlueMuted: Color(0xFF2B4C5B),
    iceDeep: Color(0xFF9ACB91),
    userWash: Color(0xFF2D4031),
    good: Color(0xFF6FD3A0),
    warn: Color(0xFFF0BE62),
    bad: Color(0xFFFF978F),
    thought: Color(0xFFC7AEE3),
    terminal: Color(0xFF9ABDEB),
    edit: Color(0xFF95C9B1),
  );
  static const plumLight = DshColors(
    ink: Color(0xFF352A39),
    inkSoft: Color(0xFF645269),
    inkFaint: Color(0xFF705775),
    paper: Color(0xFFF6F2F7),
    paperDeep: Color(0xFFEEE6F0),
    surface: Color(0xFFFFFAFF),
    surfaceRaised: Color(0xFFEDE3EF),
    line: Color(0xFFD4C2D8),
    ice: Color(0xFF795086),
    sendBlue: Color(0xFF2563D9),
    sendBlueMuted: Color(0xFFC3C5E0),
    iceDeep: Color(0xFF795086),
    userWash: Color(0xFFE8DCEC),
    good: Color(0xFF216B4A),
    warn: Color(0xFF875800),
    bad: Color(0xFFAE313F),
    thought: Color(0xFF6250A6),
    terminal: Color(0xFF285D9B),
    edit: Color(0xFF286B50),
  );
  static const plumDark = DshColors(
    ink: Color(0xFFF0E6F3),
    inkSoft: Color(0xFFCDB7D4),
    inkFaint: Color(0xFFB7A0BF),
    paper: Color(0xFF1C1820),
    paperDeep: Color(0xFF141017),
    surface: Color(0xFF29222F),
    surfaceRaised: Color(0xFF372D3F),
    line: Color(0xFF514058),
    ice: Color(0xFFCFA8DE),
    sendBlue: Color(0xFF3B82F6),
    sendBlueMuted: Color(0xFF394562),
    iceDeep: Color(0xFFCFA8DE),
    userWash: Color(0xFF3E2F46),
    good: Color(0xFF6FD3A0),
    warn: Color(0xFFF0BE62),
    bad: Color(0xFFFF978F),
    thought: Color(0xFFC7AEE3),
    terminal: Color(0xFF9ABDEB),
    edit: Color(0xFF95C9B1),
  );
  static DshColors colorsFor(DshMood mood, Brightness brightness) {
    final lightMode = brightness == Brightness.light;
    return switch (mood) {
      DshMood.mist => lightMode ? light : dark,
      DshMood.sand => lightMode ? sandLight : sandDark,
      DshMood.sage => lightMode ? sageLight : sageDark,
      DshMood.plum => lightMode ? plumLight : plumDark,
    };
  }

  static DshColors of(BuildContext context) =>
      Theme.of(context).extension<DshColorTheme>()?.colors ??
      (Theme.of(context).brightness == Brightness.light ? light : dark);
}

class DshTheme {
  const DshTheme._();

  static ThemeData light({DshMood mood = DshMood.mist}) =>
      _build(DshPalette.colorsFor(mood, Brightness.light), Brightness.light);
  static ThemeData dark({DshMood mood = DshMood.mist}) =>
      _build(DshPalette.colorsFor(mood, Brightness.dark), Brightness.dark);
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
      extensions: [DshColorTheme(colors)],
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
    this.mood = DshMood.mist,
    this.onMoodChanged,
    required super.child,
  });
  final ThemeMode mode;
  final DshMood mood;
  final ValueChanged<DshMood>? onMoodChanged;
  final ValueChanged<ThemeMode> onChanged;
  static DshAppearance? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<DshAppearance>();
  @override
  bool updateShouldNotify(DshAppearance oldWidget) =>
      mode != oldWidget.mode || mood != oldWidget.mood;
}
