import 'package:material_ui/material_ui.dart';

class DshTheme {
  const DshTheme._();

  static ThemeData light() {
    const ink = Color(0xFF20212B);
    const paper = Color(0xFFF8F8FB);
    final scheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFF6557D8),
      brightness: Brightness.light,
    );
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: paper,
      textTheme: const TextTheme(
        bodyLarge: TextStyle(color: ink, height: 1.5),
        bodyMedium: TextStyle(color: ink, height: 1.45),
        titleLarge: TextStyle(color: ink, fontWeight: FontWeight.w700),
        titleMedium: TextStyle(color: ink, fontWeight: FontWeight.w600),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: paper,
        foregroundColor: ink,
        elevation: 0,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: Colors.white,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(20)),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(20)),
          borderSide: BorderSide(color: Color(0xFFE5E3ED)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(20)),
          borderSide: BorderSide(color: Color(0xFF6557D8), width: 1.5),
        ),
        contentPadding: EdgeInsets.symmetric(horizontal: 18, vertical: 13),
      ),
      cardTheme: const CardThemeData(
        margin: EdgeInsets.zero,
        elevation: 0,
        color: Colors.white,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(18)),
          side: BorderSide(color: Color(0xFFE9E7F0)),
        ),
      ),
    );
  }
}
