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
      expectInside(
        tester,
        find.byKey(const ValueKey('composer-send')),
        viewport,
      );
      expectInside(
        tester,
        find.byKey(const ValueKey('composer-input')),
        viewport,
      );
      expectInside(
        tester,
        find.byKey(const ValueKey('web-fallback')),
        viewport,
      );
      await tester.enterText(
        find.byKey(const ValueKey('composer-input')),
        '检查项目',
      );
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('composer-send')));
      await tester.pump();
      expect(tester.takeException(), isNull);
      expectInside(
        tester,
        find.byKey(const ValueKey('composer-send')),
        viewport,
      );
      expectInside(
        tester,
        find.byKey(const ValueKey('composer-stop')),
        viewport,
      );
      await tester.enterText(
        find.byKey(const ValueKey('composer-input')),
        '下一轮',
      );
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('composer-send')));
      await tester.pump();
      final dispatch = find.widgetWithText(TextButton, '发送下一条');
      expectInside(tester, dispatch, viewport);
      expect(tester.getSize(dispatch).height, greaterThanOrEqualTo(48));
      final stopRect = tester.getRect(
        find.byKey(const ValueKey('composer-stop')),
      );
      expect(stopRect.width, greaterThanOrEqualTo(48));
      expect(stopRect.height, greaterThanOrEqualTo(48));
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
