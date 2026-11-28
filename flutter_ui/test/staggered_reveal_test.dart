import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'package:dsh_native_chat/src/ui/staggered_reveal.dart';

void main() {
  testWidgets('reveal exposes deterministic stagger timing', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: StaggeredReveal(
          duration: const Duration(milliseconds: 170),
          interval: const Duration(milliseconds: 34),
          children: const [
            SizedBox(key: ValueKey('one'), height: 40),
            SizedBox(key: ValueKey('two'), height: 40),
            SizedBox(key: ValueKey('three'), height: 40),
          ],
        ),
      ),
    );

    expect(find.byKey(const ValueKey('one')), findsOneWidget);
    expect(find.byKey(const ValueKey('two')), findsOneWidget);
    expect(find.byKey(const ValueKey('three')), findsOneWidget);

    final first = tester.widget<Opacity>(
      find.byKey(const ValueKey('staggered-reveal-opacity-0')),
    );
    final second = tester.widget<Opacity>(
      find.byKey(const ValueKey('staggered-reveal-opacity-1')),
    );
    expect(first.opacity, 0);
    expect(second.opacity, 0);

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 33));
    final secondAfterInterval = tester.widget<Opacity>(
      find.byKey(const ValueKey('staggered-reveal-opacity-1')),
    );
    expect(secondAfterInterval.opacity, 0);

    await tester.pump(const Duration(milliseconds: 137));
    final firstSettled = tester.widget<Opacity>(
      find.byKey(const ValueKey('staggered-reveal-opacity-0')),
    );
    expect(firstSettled.opacity, 1);
  });

  testWidgets('reduced motion and accessible navigation settle immediately', (
    tester,
  ) async {
    for (final data in [
      const MediaQueryData(disableAnimations: true),
      const MediaQueryData(accessibleNavigation: true),
    ]) {
      await tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: data,
            child: StaggeredReveal(
              children: [
                TextButton(onPressed: () {}, child: const Text('action')),
              ],
            ),
          ),
        ),
      );
      expect(find.byType(Opacity), findsNothing);
      expect(find.text('action'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    }
  });

  testWidgets('hidden item cannot receive focus or pointer', (tester) async {
    var taps = 0;
    final node = FocusNode();
    addTearDown(node.dispose);
    await tester.pumpWidget(
      MaterialApp(
        home: StaggeredReveal(
          children: [
            const SizedBox(height: 48),
            TextButton(
              focusNode: node,
              onPressed: () => taps++,
              child: const Text('late'),
            ),
          ],
        ),
      ),
    );
    node.requestFocus();
    await tester.pump();
    expect(node.hasFocus, isFalse);
    await tester.tap(find.text('late'), warnIfMissed: false);
    expect(taps, 0);
    await tester.pump(const Duration(milliseconds: 320));
    await tester.tap(find.text('late'));
    expect(taps, 1);
  });

  testWidgets('caps custom timing at 320ms and settles every child', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: StaggeredReveal(
          duration: const Duration(milliseconds: 600),
          interval: const Duration(milliseconds: 200),
          maxDelay: const Duration(milliseconds: 900),
          children: [
            for (var i = 0; i < 8; i++) SizedBox(key: ValueKey(i), height: 1),
          ],
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 321));
    for (var i = 0; i < 8; i++) {
      expect(
        tester
            .widget<Opacity>(
              find.byKey(ValueKey('staggered-reveal-opacity-$i')),
            )
            .opacity,
        1,
      );
    }
  });

  testWidgets('rebuild does not replay and long tail settles by 320ms', (
    tester,
  ) async {
    Widget app() => MaterialApp(
      home: StaggeredReveal(
        children: [
          for (var i = 0; i < 20; i++) SizedBox(key: ValueKey(i), height: 1),
        ],
      ),
    );
    await tester.pumpWidget(app());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));
    expect(
      tester
          .widget<Opacity>(
            find.byKey(const ValueKey('staggered-reveal-opacity-19')),
          )
          .opacity,
      1,
    );
    await tester.pumpWidget(app());
    expect(
      tester
          .widget<Opacity>(
            find.byKey(const ValueKey('staggered-reveal-opacity-0')),
          )
          .opacity,
      1,
    );
  });
}
