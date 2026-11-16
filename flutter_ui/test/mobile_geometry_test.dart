import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';

void expectInside(WidgetTester tester, Finder finder, Size viewport) {
  expect(finder, findsOneWidget);
  final rect = tester.getRect(finder);
  expect(rect.left, greaterThanOrEqualTo(0));
  expect(rect.right, lessThanOrEqualTo(viewport.width));
  expect(rect.top, greaterThanOrEqualTo(0));
  expect(rect.bottom, lessThanOrEqualTo(viewport.height));
  expect(finder.hitTestable(), findsOneWidget);
}

void main() {
  for (final width in [360.0, 390.0, 432.0]) {
    testWidgets('mobile controls stay reachable at width ' + width.toString(), (
      tester,
    ) async {
      final viewport = Size(width, 800);
      tester.view.physicalSize = viewport;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final controller = ChatController(FixtureTransport());
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: ChatShell(controller: controller, onOpenWebFallback: () {}),
        ),
      );
      await tester.pump();
      expect(tester.takeException(), isNull);
      // Idle with an empty draft: the slot is send, disabled, and there is no
      // second stop button anywhere.
      expectInside(tester, find.byKey(const ValueKey('composer-send')), viewport);
      expect(find.byKey(const ValueKey('composer-stop')), findsNothing);
      expect(
        tester
            .widget<IconButton>(find.byKey(const ValueKey('composer-send')))
            .onPressed,
        isNull,
      );
      expectInside(tester, find.byKey(const ValueKey('composer-input')), viewport);
      expectInside(tester, find.byKey(const ValueKey('web-fallback')), viewport);

      await tester.enterText(
        find.byKey(const ValueKey('composer-input')),
        '检查项目',
      );
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('composer-send')));
      await tester.pump();
      expect(tester.takeException(), isNull);
      // The turn now runs and the draft was consumed: the same slot is stop.
      expectInside(tester, find.byKey(const ValueKey('composer-stop')), viewport);
      expect(find.byKey(const ValueKey('composer-send')), findsNothing);

      // Typing a new prompt flips the slot back to send (queue/steering), and
      // the stop disappears instead of sitting next to it.
      await tester.enterText(find.byKey(const ValueKey('composer-input')), '下一轮');
      await tester.pump();
      expectInside(tester, find.byKey(const ValueKey('composer-send')), viewport);
      expect(find.byKey(const ValueKey('composer-stop')), findsNothing);
      await tester.tap(find.byKey(const ValueKey('composer-send')));
      await tester.pump();
      final dispatch = find.widgetWithText(TextButton, '发送下一条');
      expectInside(tester, dispatch, viewport);
      expect(tester.getSize(dispatch).height, greaterThanOrEqualTo(48));

      // Clearing the draft restores the stop in the same slot.
      await tester.enterText(find.byKey(const ValueKey('composer-input')), '');
      await tester.pump();
      expectInside(tester, find.byKey(const ValueKey('composer-stop')), viewport);
      final stopRect = tester.getRect(
        find.byKey(const ValueKey('composer-stop')),
      );
      expect(stopRect.width, greaterThanOrEqualTo(48));
      expect(stopRect.height, greaterThanOrEqualTo(48));

      await tester.tap(find.byKey(const ValueKey('composer-stop')));
      await tester.pump();
      await tester.pump();
      // After the cancel completes the turn is over: only a disabled send stays.
      expect(find.byKey(const ValueKey('composer-stop')), findsNothing);
      final sendRect = tester.getRect(
        find.byKey(const ValueKey('composer-send')),
      );
      expect(sendRect.width, greaterThanOrEqualTo(48));
      expect(sendRect.height, greaterThanOrEqualTo(48));
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    });
  }
}
