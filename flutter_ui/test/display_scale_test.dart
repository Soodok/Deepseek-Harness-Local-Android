import 'package:dsh_native_chat/src/theme/display_scale.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  test('the compact, standard and relaxed sizes carry one factor', () {
    expect(DshDisplaySize.compact.factor, 0.9);
    expect(DshDisplaySize.standard.factor, 1);
    expect(DshDisplaySize.relaxed.factor, 1.1);
    expect(DshDisplaySize.fallback, DshDisplaySize.standard);
  });

  test('unknown and missing stored values fall back to standard', () {
    expect(DshDisplaySize.fromWire('compact'), DshDisplaySize.compact);
    expect(DshDisplaySize.fromWire('relaxed'), DshDisplaySize.relaxed);
    expect(DshDisplaySize.fromWire(null), DshDisplaySize.standard);
    expect(DshDisplaySize.fromWire(''), DshDisplaySize.standard);
    expect(DshDisplaySize.fromWire('huge'), DshDisplaySize.standard);
    // Old persisted values from the previous beta are not reinterpreted.
    expect(DshDisplaySize.fromWire('1.5'), DshDisplaySize.standard);
  });

  for (final (size, factor) in [
    (DshDisplaySize.compact, 0.9),
    (DshDisplaySize.standard, 1.0),
    (DshDisplaySize.relaxed, 1.1),
  ]) {
    testWidgets('the touch floor stays 48dp on the glass at ${size.name}', (
      tester,
    ) async {
      late double floor;
      late Size wide;
      late Size row;
      await tester.pumpWidget(
        DshDisplayScope(
          size: size,
          child: MaterialApp(
            home: Builder(
              builder: (context) {
                floor = DshDisplayScope.minTargetOf(context);
                wide = DshDisplayScope.targetSize(context, width: 64);
                row = DshDisplayScope.targetSize(context, width: 0);
                return const SizedBox.shrink();
              },
            ),
          ),
        ),
      );
      // The canvas multiplies logical units by the factor, so the logical floor
      // must rise when the canvas shrinks (48 / factor) and never drop below the
      // design value when it grows. Either way the glass sees at least 48dp.
      expect(
        floor,
        greaterThanOrEqualTo(DshDisplayScope.physicalTarget / factor),
      );
      expect(floor, greaterThanOrEqualTo(DshDisplayScope.physicalTarget));
      expect(floor * factor, greaterThanOrEqualTo(48));
      // At compact the inverse is exact, so a 48dp target really is 48dp.
      if (factor < 1) {
        expect(floor, closeTo(48 / factor, 0.001));
        expect(floor * factor, closeTo(48, 0.001));
      }
      // A wider design target keeps its width where the factor allows it, and
      // both extents are never below the physical floor on the glass.
      expect(wide.width * factor, greaterThanOrEqualTo(48));
      expect(wide.height * factor, greaterThanOrEqualTo(48));
      // width 0 still means "let the row decide".
      expect(row.width, 0);
      expect(row.height * factor, greaterThanOrEqualTo(48));
    });
  }

  testWidgets('the canvas never folds the system text scaler into its own', (
    tester,
  ) async {
    // The old DshScaledTextScaler multiplied a text scale factor on top of a
    // base scaler and clamped only the factor, so 13dp at 1.1 grew to 28.6
    // instead of the legal 15.6 ceiling. The canvas draws instead of scaling
    // type, so the platform scaler arrives untouched and is applied once.
    const platform = TextScaler.linear(2);
    late TextScaler seen;
    late EdgeInsets insets;
    late Size logical;
    await tester.pumpWidget(
      DshDisplayScope(
        size: DshDisplaySize.compact,
        child: MediaQuery(
          data: const MediaQueryData(
            size: Size(400, 800),
            textScaler: platform,
            padding: EdgeInsets.only(top: 24),
            viewInsets: EdgeInsets.only(bottom: 300),
          ),
          child: MaterialApp(
            builder: (context, child) =>
                DshDisplayCanvas(child: child ?? const SizedBox.shrink()),
            home: Builder(
              builder: (context) {
                final media = MediaQuery.of(context);
                seen = media.textScaler;
                insets = media.viewInsets;
                logical = media.size;
                return const SizedBox.shrink();
              },
            ),
          ),
        ),
      ),
    );
    expect(seen, same(platform));
    expect(seen.scale(13), 26);
    // The logical viewport is the inverse of the paint scale...
    expect(logical.width, closeTo(400 / 0.9, 0.001));
    expect(logical.height, closeTo(800 / 0.9, 0.001));
    // ...and so are the safe area and the IME, so they land on real pixels.
    expect(insets.bottom * 0.9, closeTo(300, 0.001));
  });
}
