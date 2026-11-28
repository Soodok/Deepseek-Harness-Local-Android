import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:dsh_native_chat/src/ui/conversation_edge_blur.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/conversation_flow.dart';
import 'package:dsh_native_chat/src/ui/message_list.dart';
import 'package:dsh_native_chat/src/ui/subagent_panel.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

Finder _edgeViewport() => find.byType(ConversationEdgeBlur);

bool _blurs(WidgetTester tester, String edge) => tester
    .widgetList<BackdropFilter>(
      find.descendant(
        of: find.byKey(ValueKey('conversation-edge-$edge')),
        matching: find.byType(BackdropFilter),
      ),
    )
    .any((filter) => filter.enabled);

class _PreviewController extends ChatController {
  _PreviewController() : super(FixtureTransport());

  @override
  ChatState get state => ChatState.empty().copyWith(
    status: ChatStatus.ready,
    messages: [
      const ChatMessage(
        id: 'prompt',
        role: ChatRole.user,
        text: 'How do the new conversation edges work?',
      ),
      for (var i = 0; i < 12; i++)
        ChatMessage(
          id: 'reply-$i',
          role: ChatRole.assistant,
          text:
              'Keep the conversation in focus.\n\n'
              'Messages soften gently near the floating controls. '
              'Scroll to the beginning or the end to read every line clearly.\n\n'
              'The input stays sharp and your draft stays here.',
        ),
    ],
  );
}

class _Stripes extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    for (var x = 0.0; x < size.width; x += 12) {
      canvas.drawRect(
        Rect.fromLTWH(x, 0, 12, size.height),
        Paint()..color = (x ~/ 12).isEven ? Colors.black : Colors.white,
      );
    }
  }

  @override
  bool shouldRepaint(_Stripes oldDelegate) => false;
}

Widget _harness({
  required ScrollController scroll,
  required TextEditingController draft,
  required FocusNode focus,
  required VoidCallback onTap,
  MediaQueryData media = const MediaQueryData(),
  Key? captureKey,
  EdgeInsets contentInsets = EdgeInsets.zero,
  bool effectEnabled = true,
}) {
  Widget list = ListView.builder(
    controller: scroll,
    padding: EdgeInsets.zero,
    itemExtent: 48,
    itemCount: 40,
    itemBuilder: (_, index) => GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Semantics(
        label: 'row $index',
        child: CustomPaint(painter: _Stripes()),
      ),
    ),
  );
  if (effectEnabled) {
    list = ConversationEdgeBlur(contentInsets: contentInsets, child: list);
  }
  return MaterialApp(
    theme: DshTheme.light(),
    home: MediaQuery(
      data: media,
      child: Scaffold(
        resizeToAvoidBottomInset: false,
        body: Column(
          children: [
            SizedBox(
              width: 320,
              height: 240,
              child: RepaintBoundary(key: captureKey, child: list),
            ),
            TextField(controller: draft, focusNode: focus),
          ],
        ),
      ),
    ),
  );
}

Future<Uint8List> _pixels(WidgetTester tester, Key key) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(key));
  return (await tester.runAsync(() async {
    final image = await boundary.toImage(pixelRatio: 1);
    final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
    image.dispose();
    return data!.buffer.asUint8List();
  }))!;
}

void main() {
  testWidgets(
    'composer footprint stays softened through its side and bottom gaps',
    (tester) async {
      const capture = ValueKey('composer-footprint-capture');
      const insets = EdgeInsets.only(top: 44, bottom: 120);
      final scroll = ScrollController(initialScrollOffset: 480);
      final draft = TextEditingController();
      final focus = FocusNode();
      Future<Uint8List> captureMode(bool calm) async {
        await tester.pumpWidget(
          _harness(
            scroll: scroll,
            draft: draft,
            focus: focus,
            onTap: () {},
            media: MediaQueryData(highContrast: calm),
            captureKey: capture,
            contentInsets: insets,
          ),
        );
        await tester.pump();
        return _pixels(tester, capture);
      }

      final normal = await captureMode(false);
      final calm = await captureMode(true);
      int difference(int x, int y) =>
          (normal[(y * 320 + x) * 4] - calm[(y * 320 + x) * 4]).abs();
      // At H=240 the measured composer footprint starts at y=120. Its 8dp
      // top padding precedes the glass; side gutters and the bottom 12dp are
      // transparent. The old band stops at y=136, leaving these exposed.
      for (final point in [
        const Offset(10, 144),
        const Offset(10, 180),
        const Offset(10, 234),
      ]) {
        expect(
          difference(point.dx.toInt(), point.dy.toInt()),
          greaterThan(8),
          reason: 'composer gap at $point must not expose sharp transcript',
        );
      }
      for (final y in [144, 180, 234]) {
        final values = [
          for (var x = 28; x <= 44; x++) normal[(y * 320 + x) * 4],
        ];
        final lo = values.reduce(math.min);
        final hi = values.reduce(math.max);
        final ramp = values
            .where((v) => v > lo + (hi - lo) * .2 && v < hi - (hi - lo) * .2)
            .length;
        expect(
          ramp,
          greaterThanOrEqualTo(3),
          reason: 'gap at y=$y must blur, not just tint',
        );
      }
      await tester.pumpWidget(const SizedBox());
      scroll.dispose();
      draft.dispose();
      focus.dispose();
    },
  );

  testWidgets('visible chrome gaps feather continuously into sharp body', (
    tester,
  ) async {
    const capture = ValueKey('visible-gap-capture');
    const insets = EdgeInsets.only(top: 44, bottom: 44);
    final scroll = ScrollController(initialScrollOffset: 480);
    final draft = TextEditingController();
    final focus = FocusNode();
    Future<void> pump(bool calm) async {
      await tester.pumpWidget(
        _harness(
          scroll: scroll,
          draft: draft,
          focus: focus,
          onTap: () {},
          media: MediaQueryData(highContrast: calm),
          captureKey: capture,
          contentInsets: insets,
        ),
      );
      await tester.pump();
    }

    await pump(false);
    final blurred = await _pixels(tester, capture);
    await pump(true);
    final sharp = await _pixels(tester, capture);
    await tester.pumpWidget(
      _harness(
        scroll: scroll,
        draft: draft,
        focus: focus,
        onTap: () {},
        captureKey: capture,
        contentInsets: insets,
        effectEnabled: false,
      ),
    );
    await tester.pump();
    final disabled = await _pixels(tester, capture);
    int red(Uint8List pixels, int x, int y) => pixels[(y * 320 + x) * 4];
    // Band geometry in this 320x240 harness: insets are 44dp, the feather starts
    // 16dp inside each inset and is 88dp tall, so the top band covers y=28..116
    // and the bottom band covers y=124..212. Rows 116..124 belong to neither.
    int effect(int y) => (red(blurred, 34, y) - red(sharp, 34, y)).abs();
    final top = [
      for (final y in [32, 40, 48, 56, 64, 72, 80, 88]) effect(y),
    ];
    final bottom = [
      for (final y in [208, 200, 192, 184, 176, 168, 160, 152]) effect(y),
    ];
    expect(
      top.reduce(math.max),
      greaterThan(0),
      reason: 'the top visible gap must be feathered',
    );
    expect(
      bottom.reduce(math.max),
      greaterThan(0),
      reason: 'the composer gap must be feathered',
    );
    for (final samples in [top, bottom]) {
      final peak = samples.reduce(math.max);
      expect(
        samples.where((value) => value > peak / 4).length,
        greaterThanOrEqualTo(6),
        reason: 'the fade must span most of the band, not one or two rows',
      );
      final active = samples.where((value) => value > 0).toList();
      for (var i = 1; i < active.length; i++) {
        expect(active[i], lessThanOrEqualTo(active[i - 1]));
        expect(active[i - 1] / active[i], lessThanOrEqualTo(1.6));
      }
    }
    // The band must actually reach the calm transcript at its inner end, not
    // hand over a still-veiled row: that is what made the edge read as a seam.
    expect(effect(112), lessThanOrEqualTo(8), reason: 'top band ends calm');
    expect(effect(128), lessThanOrEqualTo(8), reason: 'bottom band ends calm');
    // The final three in-band samples must hand off to the first outside row
    // without a jump, using the measured 1.6 continuity ceiling.
    final topTail = [effect(104), effect(108), effect(112)];
    final bottomTail = [effect(136), effect(132), effect(128)];
    final topOutside = effect(116);
    final bottomOutside = effect(124);
    expect(topOutside / math.max(1, topTail.last), lessThanOrEqualTo(1.6));
    expect(
      bottomOutside / math.max(1, bottomTail.last),
      lessThanOrEqualTo(1.6),
    );
    // The rows between the bands are untouched: identical to the calm capture.
    for (var y = 116; y <= 124; y++) {
      for (var x = 0; x < 320; x++) {
        for (var c = 0; c < 4; c++) {
          expect(
            blurred[(y * 320 + x) * 4 + c],
            sharp[(y * 320 + x) * 4 + c],
            reason: 'band-outside pixel changed at x=$x y=$y channel=$c',
          );
        }
      }
    }
    // A disabled effect is the negative control: if sigma or frostShare is
    // removed, this pixel-level difference must disappear and fail the test.
    expect(
      blurred,
      isNot(equals(disabled)),
      reason: 'the rendered edge must differ from the disabled A/B',
    );
    draft.dispose();
    focus.dispose();
  });

  testWidgets(
    'real shell keeps draft and viewport when accessibility changes',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(390, 844));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final controller = _PreviewController();
      const capture = ValueKey('shell-edge-capture');
      Future<void> pump({bool calm = false, bool dark = false}) async {
        await tester.pumpWidget(
          MaterialApp(
            theme: dark ? DshTheme.dark() : DshTheme.light(),
            themeAnimationDuration: Duration.zero,
            home: MediaQuery(
              data: MediaQueryData(disableAnimations: calm),
              child: RepaintBoundary(
                key: capture,
                child: ChatShell(controller: controller),
              ),
            ),
          ),
        );
        await tester.pump();
        expect(
          Theme.of(tester.element(find.byType(ChatShell))).brightness,
          dark ? Brightness.dark : Brightness.light,
        );
      }

      await pump();
      final measuredInset = tester
          .widget<ConversationEdgeBlur>(_edgeViewport())
          .contentInsets
          .bottom;
      final measuredComposer = tester
          .getSize(find.byKey(const ValueKey('floating-composer-extent')))
          .height;
      expect(measuredInset, closeTo(measuredComposer, .5));
      expect(measuredInset, greaterThan(16));
      final list = find
          .descendant(
            of: find.byType(ListView),
            matching: find.byType(Scrollable),
          )
          .first;
      final state = tester.state<ScrollableState>(list);
      state.position.jumpTo(480);
      await tester.pump();
      final filters = find.descendant(
        of: _edgeViewport(),
        matching: find.byWidgetPredicate((w) => w is BackdropFilter && w.enabled),
      );
      expect(tester.widgetList(filters), hasLength(8));
      if (const bool.fromEnvironment('EDGE_BLUR_SCREENSHOTS')) {
        for (final dark in [false, true]) {
          await pump(dark: dark);
          await tester.runAsync(() async {
            final boundary = tester.renderObject<RenderRepaintBoundary>(
              find.byKey(capture),
            );
            final image = await boundary.toImage(pixelRatio: 2);
            final png = await image.toByteData(format: ui.ImageByteFormat.png);
            final theme = dark ? 'dark' : 'light';
            final target = File(
              '../docs/reports/assets/2026-11-18-edge-blur-shell-$theme.png',
            );
            await target.parent.create(recursive: true);
            await target.writeAsBytes(png!.buffer.asUint8List());
            image.dispose();
          });
        }
      }
      await tester.enterText(
        find.byKey(const ValueKey('composer-input')),
        'unsent draft',
      );
      await tester.pump();
      final input = tester.widget<TextField>(
        find.byKey(const ValueKey('composer-input')),
      );
      final before = state.position.pixels;
      await pump(calm: true);
      expect(tester.state<ScrollableState>(list), same(state));
      expect(state.position.pixels, before);
      expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('composer-input')))
            .controller,
        same(input.controller),
      );
      expect(input.controller!.text, 'unsent draft');
      expect(_blurs(tester, 'top'), isFalse);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );

  testWidgets('main transcript uses the shared edge viewport', (tester) async {
    final controller = ChatController(FixtureTransport());
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(controller: controller),
      ),
    );
    await tester.pump();
    expect(
      find.descendant(of: find.byType(MessageList), matching: _edgeViewport()),
      findsOneWidget,
    );
    expect(
      find.descendant(of: _edgeViewport(), matching: find.byType(ListView)),
      findsOneWidget,
    );
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('child transcript uses the same edge viewport', (tester) async {
    final service = FixtureTransport();
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: SubagentChatPage(
          agent: const AgentTask(
            id: 'edge-fixture',
            name: '局部预览',
            task: '边缘过渡',
            status: AgentState.completed,
          ),
          service: service,
        ),
      ),
    );
    await tester.pump();
    expect(
      find.descendant(
        of: find.byType(ConversationFlow),
        matching: _edgeViewport(),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(of: _edgeViewport(), matching: find.byType(ListView)),
      findsOneWidget,
    );
    await tester.pumpWidget(const SizedBox());
    await service.close();
  });

  testWidgets('latest prompt is painted above the transcript edge blur', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(390, 700));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    const capture = ValueKey('latest-prompt-capture');
    final controller = _PreviewController();

    Future<Uint8List> render({required bool calm}) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(highContrast: calm),
            child: child!,
          ),
          home: RepaintBoundary(
            key: capture,
            child: ChatShell(controller: controller),
          ),
        ),
      );
      await tester.pump();
      await tester.pump();
      return _pixels(tester, capture);
    }

    await render(calm: false);
    final list = tester.state<ScrollableState>(
      find.descendant(
        of: find.byKey(const ValueKey('conversation-list')),
        matching: find.byType(Scrollable),
      ).first,
    );
    list.position.jumpTo(1);
    await tester.pump();
    await tester.pump();

    final prompt = find.byKey(const ValueKey('conversation-scroll-bottom'));
    expect(prompt, findsOneWidget);
    expect(
      find.ancestor(of: prompt, matching: _edgeViewport()),
      findsNothing,
      reason: 'the floating prompt must not be a backdrop-filter descendant',
    );
    final normal = await _pixels(tester, capture);

    await render(calm: true);
    final calm = await _pixels(tester, capture);
    final label = tester.getRect(find.text('回到底部'));
    final captureRect = tester.getRect(find.byKey(capture));
    for (var y = label.top.floor(); y < label.bottom.ceil(); y++) {
      for (var x = label.left.floor(); x < label.right.ceil(); x++) {
        final localX = x - captureRect.left.floor();
        final localY = y - captureRect.top.floor();
        final index = (localY * 390 + localX) * 4;
        expect(
          normal.sublist(index, index + 4),
          calm.sublist(index, index + 4),
          reason: 'latest prompt text pixel changed under the edge effect',
        );
      }
    }

    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets(
    'edge decorations allow taps and drags and release first/last text',
    (tester) async {
      final scroll = ScrollController();
      final draft = TextEditingController();
      final focus = FocusNode();
      var taps = 0;
      await tester.pumpWidget(
        _harness(
          scroll: scroll,
          draft: draft,
          focus: focus,
          onTap: () => taps++,
        ),
      );
      await tester.pump();
      expect(_blurs(tester, 'top'), isFalse);
      expect(_blurs(tester, 'bottom'), isTrue);
      scroll.jumpTo(480);
      await tester.pump();
      expect(_blurs(tester, 'top'), isTrue);
      final bounds = tester.getRect(_edgeViewport());
      await tester.tapAt(bounds.topCenter + const Offset(0, 4));
      await tester.tapAt(bounds.bottomCenter - const Offset(0, 4));
      expect(taps, 2);
      await tester.dragFrom(
        bounds.topCenter + const Offset(0, 12),
        const Offset(0, -80),
      );
      await tester.pumpAndSettle();
      expect(scroll.offset, greaterThan(480));
      scroll.jumpTo(scroll.position.maxScrollExtent);
      await tester.pump();
      expect(_blurs(tester, 'bottom'), isFalse);
      scroll.jumpTo(0);
      await tester.pump();
      expect(_blurs(tester, 'top'), isFalse);
      await tester.pumpWidget(const SizedBox());
      scroll.dispose();
      draft.dispose();
      focus.dispose();
    },
  );

  testWidgets('calm modes keep the same scroll state draft and focus', (
    tester,
  ) async {
    final scroll = ScrollController(initialScrollOffset: 480);
    final draft = TextEditingController(text: '未发送的草稿');
    final focus = FocusNode();
    Future<void> pump(MediaQueryData media) async {
      await tester.pumpWidget(
        _harness(
          scroll: scroll,
          draft: draft,
          focus: focus,
          media: media,
          onTap: () {},
        ),
      );
      await tester.pump();
    }

    await pump(const MediaQueryData());
    focus.requestFocus();
    await tester.pump();
    final finder = find.descendant(
      of: _edgeViewport(),
      matching: find.byType(Scrollable),
    );
    final state = tester.state<ScrollableState>(finder);
    for (final media in [
      const MediaQueryData(highContrast: true),
      const MediaQueryData(disableAnimations: true),
      const MediaQueryData(accessibleNavigation: true),
      const MediaQueryData(),
    ]) {
      await pump(media);
      expect(tester.state<ScrollableState>(finder), same(state));
      expect(scroll.offset, 480);
      expect(draft.text, '未发送的草稿');
      expect(focus.hasFocus, isTrue);
      expect(
        _blurs(tester, 'top'),
        !media.highContrast &&
            !media.disableAnimations &&
            !media.accessibleNavigation,
      );
      expect(finder, findsOneWidget);
    }
    await tester.pumpWidget(const SizedBox());
    scroll.dispose();
    draft.dispose();
    focus.dispose();
  });

  testWidgets('rendered edge softens a solid stripe while center stays sharp', (
    tester,
  ) async {
    const capture = ValueKey('edge-pixel-capture');
    final scroll = ScrollController(initialScrollOffset: 480);
    final draft = TextEditingController();
    final focus = FocusNode();
    Future<void> pump(MediaQueryData media) async {
      await tester.pumpWidget(
        _harness(
          scroll: scroll,
          draft: draft,
          focus: focus,
          media: media,
          captureKey: capture,
          onTap: () {},
        ),
      );
      await tester.pump();
    }

    await pump(const MediaQueryData());
    final blurred = await _pixels(tester, capture);
    int grey(Uint8List pixels, int x, int y) => pixels[(y * 320 + x) * 4];

    /// How many pixels sit strictly between the darkest and the lightest value
    /// on a row crossing one black/white boundary. A colour veil only rescales
    /// both ends, so the step stays one pixel wide; a real blur smears it
    /// across several pixels. That is the signal separating frosted glass from
    /// a plain tint.
    int rampWidthOf(Uint8List pixels, int y) {
      final values = <int>[
        for (var x = 28; x <= 44; x++) pixels[(y * 320 + x) * 4],
      ];
      final lo = values.reduce((a, b) => a < b ? a : b);
      final hi = values.reduce((a, b) => a > b ? a : b);
      final span = hi - lo;
      if (span < 40) return 0;
      return values
          .where((v) => v > lo + span * .2 && v < hi - span * .2)
          .length;
    }

    // The honest comparison: the same rows with the effect switched off. Calm
    // mode draws neither blur nor veil, so any widening of a stripe edge must
    // come from this widget rather than from the fixture.
    await pump(const MediaQueryData(highContrast: true));
    await tester.pump(const Duration(milliseconds: 32));
    final sharp = await _pixels(tester, capture);

    // Inside the visible gap the stripe edge is smeared over several pixels and
    // the smearing decays row by row toward the body. With the effect off every
    // sampled row is a clean one-pixel step, so this cannot pass on a tint.

    // Measured: 4 px of ramp right under the chrome, 2 px a third of the way
    // down, 0 px at the body edge. The gradient is monotone and the reveal at
    // the body edge happens through the veil's alpha, which reaches zero
    // exactly at the band end, so text reappears continuously rather than
    // snapping back at a clip boundary.
    expect(rampWidthOf(sharp, 8), 0);
    expect(rampWidthOf(blurred, 8), greaterThanOrEqualTo(3));
    expect(rampWidthOf(blurred, 16), greaterThanOrEqualTo(2));
    expect(rampWidthOf(blurred, 28), greaterThanOrEqualTo(2));
    expect(rampWidthOf(blurred, 70), 0);
    // Deep inside the body nothing is smeared: identical pixels either way.
    for (var x = 28; x <= 44; x++) {
      expect(grey(blurred, x, 100), grey(sharp, x, 100));
    }

    if (const bool.fromEnvironment('EDGE_BLUR_SCREENSHOTS')) {
      await tester.runAsync(() async {
        final boundary = tester.renderObject<RenderRepaintBoundary>(
          find.byKey(capture),
        );
        final image = await boundary.toImage(pixelRatio: 2);
        final png = await image.toByteData(format: ui.ImageByteFormat.png);
        final target = File(
          '../docs/reports/assets/2026-11-18-edge-blur-render.png',
        );
        await target.parent.create(recursive: true);
        await target.writeAsBytes(png!.buffer.asUint8List());
        image.dispose();
      });
    }
    await tester.pumpWidget(const SizedBox());
    scroll.dispose();
    draft.dispose();
    focus.dispose();
  });
}
