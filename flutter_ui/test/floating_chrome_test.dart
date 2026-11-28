import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/message_list.dart';

void main() {
  testWidgets(
    'composer keeps a stable bottom gesture inset during IME resize',
    (tester) async {
      tester.view.physicalSize = const Size(390, 800);
      tester.view.devicePixelRatio = 1;
      tester.view.viewPadding = const FakeViewPadding(bottom: 24);
      addTearDown(tester.view.reset);
      final controller = ChatController(FixtureTransport());
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: ChatShell(controller: controller),
        ),
      );
      await tester.pump();
      await tester.pump();
      final composer = find.byKey(const ValueKey('floating-composer-extent'));
      for (final inset in [0.0, 1.0, 12.0, 120.0, 280.0, 0.0]) {
        tester.view.padding = FakeViewPadding(bottom: inset <= 12 ? 24 : 0);
        tester.view.viewInsets = FakeViewPadding(bottom: inset);
        await tester.pump();
        expect(
          tester.getRect(composer).bottom,
          closeTo(800 - (inset < 24 ? 24 : inset), .1),
          reason: 'IME inset $inset',
        );
      }
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );

  testWidgets('keyboard resize keeps active question overlay inset accurate', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final transport = FixtureTransport();
    final controller = ChatController(transport);
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(controller: controller),
      ),
    );
    await tester.pump();
    await tester.pump();
    transport.emitQuestion();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump();
    expect(find.byKey(const ValueKey('composer-dock')), findsOneWidget);
    for (final inset in [120.0, 280.0, 120.0, 0.0]) {
      tester.view.viewInsets = FakeViewPadding(bottom: inset);
      await tester.pump();
      final composer = tester.getRect(
        find.byKey(const ValueKey('floating-composer-extent')),
      );
      final list = tester.widget<MessageList>(find.byType(MessageList));
      expect(
        list.padding!.bottom,
        closeTo(composer.height, .1),
        reason: 'inset $inset',
      );
    }
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets(
    'composer stays anchored and transcript reserve converges after layout',
    (tester) async {
      tester.view.physicalSize = const Size(390, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final controller = ChatController(FixtureTransport());
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: ChatShell(controller: controller),
        ),
      );
      await tester.pump();
      await tester.pump();
      await tester.enterText(
        find.byKey(const ValueKey('composer-input')),
        'one\ntwo\nthree\nfour\nfive\nsix',
      );
      await tester.pump();
      await tester.pump();
      for (final inset in [120.0, 280.0, 120.0, 0.0]) {
        tester.view.viewInsets = FakeViewPadding(bottom: inset);
        await tester.pump();
        final composer = tester.getRect(
          find.byKey(const ValueKey('floating-composer-extent')),
        );
        expect(composer.bottom, closeTo(800 - inset, .1));
        final beforeMeasure = tester.widget<MessageList>(
          find.byType(MessageList),
        );
        expect(
          beforeMeasure.padding!.bottom,
          lessThanOrEqualTo((800 - inset) * .5 - 40),
        );
        // The text line budget changes in this layout pass. The measured
        // footprint must be reflected by the very next frame, not after a timer.
        await tester.pump();
        final measured = tester.getRect(
          find.byKey(const ValueKey('floating-composer-extent')),
        );
        expect(measured.bottom, closeTo(composer.bottom, .1));
        expect(
          tester.widget<MessageList>(find.byType(MessageList)).padding!.bottom,
          closeTo(measured.height, .1),
          reason: 'inset $inset',
        );
      }
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );

  testWidgets(
    'keyboard resizing does not displace the composer between frames',
    (tester) async {
      tester.view.physicalSize = const Size(390, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final controller = ChatController(FixtureTransport());
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: ChatShell(controller: controller),
        ),
      );
      await tester.pump();
      await tester.pump();
      final composer = find.byKey(const ValueKey('floating-composer-extent'));
      for (final inset in [60.0, 120.0, 200.0, 280.0]) {
        tester.view.viewInsets = FakeViewPadding(bottom: inset);
        await tester.pump();
        final first = tester.getRect(composer).bottom;
        await tester.pump();
        final second = tester.getRect(composer).bottom;
        expect(
          first,
          closeTo(800 - inset, .1),
          reason: 'first frame inset $inset',
        );
        expect(second, closeTo(first, .1), reason: 'settling inset $inset');
      }
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );
  testWidgets('the transcript runs under the floating header and composer', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final controller = ChatController(FixtureTransport());
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(controller: controller),
      ),
    );
    await tester.pump();
    await tester.pump();

    final list = tester.getRect(
      find.byKey(const ValueKey('conversation-list')),
    );
    final header = tester.getRect(
      find.byKey(const ValueKey('floating-header-extent')),
    );
    final composer = tester.getRect(
      find.byKey(const ValueKey('floating-composer-extent')),
    );

    // A transcript that merely sits in its own band shares no pixels with the
    // chrome, so the glass has nothing to blur. Overlap is the whole point.
    expect(list.top, lessThan(header.bottom));
    expect(list.bottom, greaterThan(composer.top));
    expect(list.bottom, closeTo(844, 1));
    expect(tester.takeException(), isNull);
    controller.dispose();
  });
}
