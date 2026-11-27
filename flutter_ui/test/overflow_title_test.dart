import 'package:dsh_native_chat/src/ui/overflow_title.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

const _titleKey = ValueKey('session-title');
const _style = TextStyle(inherit: false, fontSize: 16);
const _longTitle = 'Complete session title beyond the small header';

Future<void> _pump(
  WidgetTester tester, {
  String text = _longTitle,
  double width = 96,
  TextStyle? style,
  TextAlign textAlign = TextAlign.start,
  TextDirection direction = TextDirection.ltr,
  TextScaler scaler = TextScaler.noScaling,
  bool reduceMotion = false,
  bool tickerEnabled = true,
}) async {
  await tester.pumpWidget(
    Directionality(
      textDirection: direction,
      child: MediaQuery(
        data: MediaQueryData(
          disableAnimations: reduceMotion,
          textScaler: scaler,
        ),
        child: TickerMode(
          enabled: tickerEnabled,
          child: DefaultTextStyle(
            style: _style,
            child: Center(
              child: SizedBox(
                width: width,
                child: OverflowTitle(
                  key: _titleKey,
                  text: text,
                  style: style,
                  textAlign: textAlign,
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  // Establish the ticker's zero timestamp without settling a repeating animation.
  await tester.pump();
}

Finder get _text => find
    .descendant(of: find.byType(OverflowTitle), matching: find.byType(RichText))
    .first;
Finder get _texts => find.descendant(
  of: find.byType(OverflowTitle),
  matching: find.byType(RichText),
);

double _x(WidgetTester tester) => tester.getTopLeft(_text).dx;
double _left(WidgetTester tester) =>
    tester.getTopLeft(find.byKey(_titleKey)).dx;

double _measure(String text) {
  final painter = TextPainter(
    text: TextSpan(text: text, style: _style),
    textDirection: TextDirection.ltr,
    maxLines: 1,
  )..layout();
  final width = painter.width;
  painter.dispose();
  return width;
}

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() {
    binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
  });

  testWidgets('short text stays on one line without scheduling animation', (
    tester,
  ) async {
    await _pump(tester, text: 'Short', width: 240);
    final start = _x(tester);
    await tester.pump(const Duration(seconds: 20));
    expect(_x(tester), start);
    final text = tester.widget<RichText>(_text);
    expect(text.maxLines, 1);
    expect(text.softWrap, isFalse);
    expect(text.overflow, TextOverflow.clip);
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets(
    'overflow loops forward with a two-space gap and a 1.5-second pause',
    (tester) async {
      await _pump(tester);
      final start = _x(tester);
      final gap = _measure('  ');
      final distance = _measure(_longTitle) + gap;
      final travel = Duration(microseconds: (distance / 28 * 1000000).round());
      expect(_texts, findsNWidgets(2));
      final first = tester.getRect(_texts.at(0));
      final second = tester.getRect(_texts.at(1));
      expect(second.left - first.right, closeTo(gap, 0.1));
      await tester.pump(const Duration(milliseconds: 1499));
      expect(_x(tester), closeTo(start, 0.01));
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump(const Duration(milliseconds: 500));
      expect(_x(tester), closeTo(start - 14, 0.1));
      expect(tester.widget<RichText>(_text).overflow, TextOverflow.clip);
      await tester.pump(travel - const Duration(milliseconds: 501));
      expect(tester.getTopLeft(_texts.at(1)).dx, closeTo(start + 0.028, 0.1));
      await tester.pump(const Duration(milliseconds: 2));
      // At the join, an identical copy occupies the same visible pixels.
      expect(tester.binding.transientCallbackCount, 0);
      await tester.pump(const Duration(milliseconds: 1499));
      expect(_x(tester), closeTo(start, 0.1));
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump(const Duration(milliseconds: 500));
      expect(_x(tester), closeTo(start - 14, 0.1));
    },
  );

  testWidgets('widening to fit restores the origin and cancels motion', (
    tester,
  ) async {
    await _pump(tester);
    await tester.pump(const Duration(milliseconds: 1500));
    await tester.pump(const Duration(milliseconds: 500));
    expect(_x(tester), lessThan(_left(tester)));
    await _pump(tester, width: _measure(_longTitle) + 20);
    expect(_x(tester), closeTo(_left(tester), 0.01));
    await tester.pump(const Duration(seconds: 20));
    expect(_x(tester), closeTo(_left(tester), 0.01));
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('shrinking restarts with a complete initial pause', (
    tester,
  ) async {
    await _pump(tester, width: _measure(_longTitle) + 20);
    await _pump(tester);
    final start = _x(tester);
    await tester.pump(const Duration(milliseconds: 1499));
    expect(_x(tester), closeTo(start, 0.01));
    await tester.pump(const Duration(milliseconds: 1));
    await tester.pump(const Duration(milliseconds: 500));
    expect(_x(tester), closeTo(start - 14, 0.1));
  });

  testWidgets('reduced motion stays at the origin and stops active motion', (
    tester,
  ) async {
    await _pump(tester, reduceMotion: true);
    final start = _x(tester);
    await tester.pump(const Duration(seconds: 20));
    expect(_x(tester), start);
    expect(tester.binding.transientCallbackCount, 0);
    await _pump(tester);
    await tester.pump(const Duration(milliseconds: 1500));
    await tester.pump(const Duration(milliseconds: 500));
    expect(_x(tester), lessThan(start));
    await _pump(tester, reduceMotion: true);
    expect(_x(tester), closeTo(start, 0.01));
    await tester.pump(const Duration(seconds: 20));
    expect(_x(tester), closeTo(start, 0.01));
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets(
    'changing a long title returns to its beginning and waits again',
    (tester) async {
      await _pump(tester);
      await tester.pump(const Duration(milliseconds: 1500));
      await tester.pump(const Duration(milliseconds: 500));
      expect(_x(tester), lessThan(_left(tester)));
      // Same length and metrics: resetting must depend on content, not width alone.
      final replacement = _longTitle.replaceFirst('Complete', 'Replaced');
      await _pump(tester, text: replacement);
      expect(_x(tester), closeTo(_left(tester), 0.01));
      await tester.pump(const Duration(milliseconds: 1499));
      expect(_x(tester), closeTo(_left(tester), 0.01));
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump(const Duration(milliseconds: 500));
      expect(_x(tester), closeTo(_left(tester) - 14, 0.1));
    },
  );

  testWidgets('semantics exposes the full title exactly once while scrolling', (
    tester,
  ) async {
    final handle = tester.ensureSemantics();
    try {
      await _pump(tester);
      expect(find.semantics.byLabel(_longTitle), findsOne);
      await tester.pump(const Duration(milliseconds: 1500));
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.semantics.byLabel(_longTitle), findsOne);
      expect(
        find.semantics.byPredicate((node) => node.label.isNotEmpty),
        findsOne,
      );
    } finally {
      handle.dispose();
    }
  });

  testWidgets(
    'RTL begins at the right edge and moves toward the trailing end',
    (tester) async {
      await _pump(tester);
      await tester.pump(const Duration(milliseconds: 1500));
      await tester.pump(const Duration(milliseconds: 500));
      await _pump(tester, direction: TextDirection.rtl);
      final start = _x(tester);
      final viewport = tester.getRect(find.byKey(_titleKey));
      expect(tester.getRect(_text).right, closeTo(viewport.right, 0.1));
      await tester.pump(const Duration(milliseconds: 1499));
      expect(_x(tester), closeTo(start, 0.01));
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump(const Duration(milliseconds: 500));
      expect(_x(tester), closeTo(start + 14, 0.1));
    },
  );

  testWidgets('text scale changes recompute whether the title overflows', (
    tester,
  ) async {
    const title = 'Medium title';
    final width = _measure(title) * 1.25;
    await _pump(tester, text: title, width: width);
    await tester.pump(const Duration(milliseconds: 1500));
    await tester.pump(const Duration(milliseconds: 500));
    expect(_x(tester), closeTo(_left(tester), 0.01));
    await _pump(
      tester,
      text: title,
      width: width,
      scaler: const TextScaler.linear(2),
    );
    await tester.pump(const Duration(milliseconds: 1500));
    await tester.pump(const Duration(milliseconds: 500));
    expect(_x(tester), closeTo(_left(tester) - 14, 0.1));
    await _pump(tester, text: title, width: width);
    expect(_x(tester), closeTo(_left(tester), 0.01));
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('style changes recompute overflow using actual glyph widths', (
    tester,
  ) async {
    const title = 'Medium title';
    final width = _measure(title) * 1.25;
    await _pump(tester, text: title, width: width);
    await _pump(
      tester,
      text: title,
      width: width,
      style: const TextStyle(fontSize: 32),
    );
    await tester.pump(const Duration(milliseconds: 1500));
    await tester.pump(const Duration(milliseconds: 500));
    expect(_x(tester), closeTo(_left(tester) - 14, 0.1));
    await _pump(tester, text: title, width: width);
    expect(_x(tester), closeTo(_left(tester), 0.01));
  });

  testWidgets('short text honors the supplied alignment', (tester) async {
    await _pump(tester, text: 'Short', width: 240, textAlign: TextAlign.center);
    final paragraph = tester.renderObject<RenderParagraph>(_text);
    final boxes = paragraph.getBoxesForSelection(
      const TextSelection(baseOffset: 0, extentOffset: 5),
    );
    expect(boxes.single.left, closeTo((240 - _measure('Short')) / 2, 0.1));
  });

  testWidgets('TickerMode pauses without advancing by hidden elapsed time', (
    tester,
  ) async {
    await _pump(tester);
    await tester.pump(const Duration(milliseconds: 1500));
    await tester.pump(const Duration(milliseconds: 500));
    final before = _x(tester);
    await _pump(tester, tickerEnabled: false);
    await tester.pump(const Duration(seconds: 20));
    expect(_x(tester), closeTo(before, 0.01));
    expect(tester.binding.transientCallbackCount, 0);
    await _pump(tester);
    expect(_x(tester), closeTo(before, 0.1));
    await tester.pump(const Duration(milliseconds: 500));
    expect(_x(tester), closeTo(before - 14, 0.1));
  });

  testWidgets('a pause near the loop boundary preserves direction and speed', (
    tester,
  ) async {
    await _pump(tester);
    final gap = _measure('  ');
    final distance = _measure(_longTitle) + gap;
    final untilBoundary = Duration(
      microseconds: ((distance / 28 - 0.5) * 1000000).round(),
    );
    await tester.pump(const Duration(milliseconds: 1500));
    await tester.pump(untilBoundary);
    final before = _x(tester);
    expect(before, closeTo(_left(tester) - distance + 14, 0.1));
    await _pump(tester, tickerEnabled: false);
    await tester.pump(const Duration(seconds: 20));
    expect(_x(tester), closeTo(before, 0.1));
    await _pump(tester);
    await tester.pump(const Duration(milliseconds: 502));
    final cycleOrigin = _left(tester);
    expect(_x(tester), closeTo(cycleOrigin, 0.1));
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('application background pauses and resumes without a time jump', (
    tester,
  ) async {
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    addTearDown(() {
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    });
    await _pump(tester);
    await tester.pump(const Duration(milliseconds: 1500));
    await tester.pump(const Duration(milliseconds: 500));
    final before = _x(tester);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 20));
    expect(_x(tester), closeTo(before, 0.01));
    expect(tester.binding.transientCallbackCount, 0);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(_x(tester), closeTo(before, 0.1));
    await tester.pump(const Duration(milliseconds: 500));
    expect(_x(tester), closeTo(before - 14, 0.1));
  });

  testWidgets(
    'disposing during motion or its initial pause cancels callbacks',
    (tester) async {
      for (final elapsed in [
        const Duration(milliseconds: 100),
        const Duration(seconds: 2),
      ]) {
        await _pump(tester);
        await tester.pump(elapsed);
        await tester.pumpWidget(const SizedBox());
        await tester.pump(const Duration(seconds: 20));
        expect(tester.takeException(), isNull);
        expect(tester.binding.transientCallbackCount, 0);
      }
    },
  );
}
