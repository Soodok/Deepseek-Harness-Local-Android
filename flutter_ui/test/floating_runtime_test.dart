import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';

void main() {
  testWidgets('successful native navigation closes stale runtime sheet', (
    tester,
  ) async {
    const channel = MethodChannel('app.dsh.mobile/native_chat');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async => true);
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    final controller = ChatController(FixtureTransport());
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(controller: controller),
      ),
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('runtime-selector')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('runtime-native')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('runtime-linux')), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });
  test('runtime bridge preserves actual fallback and unresolved modes', () {
    final fallback = HostInfo.fromMap({
      'runtimeMode': 'native_fallback',
      'runtimeResolved': true,
      'runtimeReason': 'missing rootfs',
      'available': true,
    });
    expect(fallback.runtimeLabel, 'Bionic Node');
    expect(fallback.runtimeDetail, contains('兼容模式'));
    expect(fallback.runtimeDetail, contains('missing rootfs'));
    expect(
      HostInfo.fromMap({'runtimeMode': 'linux', 'runtimeResolved': false})
          .runtimeLabel,
      '检测中',
    );
    expect(
      HostInfo.fromMap({'runtimeMode': 'future', 'runtimeResolved': true})
          .runtimeLabel,
      '未知环境',
    );
  });
  for (final width in [360.0, 390.0, 432.0]) {
    testWidgets(
      'joined floating header and composer spacing ' + width.toString(),
      (tester) async {
        tester.view.physicalSize = Size(width, 800);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final controller = ChatController(FixtureTransport());
        await tester.pumpWidget(
          MaterialApp(
            theme: DshTheme.light(),
            home: ChatShell(controller: controller),
          ),
        );
        await tester.pump();
        final attach = tester.getRect(
          find.byKey(const ValueKey('composer-attach')),
        );
        final model = tester.getRect(
          find.byKey(const ValueKey('composer-model')),
        );
        final permission = tester.getRect(
          find.byKey(const ValueKey('composer-permission')),
        );
        final send = tester.getRect(
          find.byKey(const ValueKey('composer-send')),
        );
        expect(model.left - attach.right, inInclusiveRange(4, 8));
        expect(send.left - permission.right, greaterThanOrEqualTo(8));
        expect(
          tester
              .widget<TextButton>(find.byKey(const ValueKey('composer-model')))
              .style
              ?.side,
          isNotNull,
        );
        expect(
          find.descendant(
            of: find.byKey(const ValueKey('floating-header')),
            matching: find.byKey(const ValueKey('runtime-selector')),
          ),
          findsOneWidget,
        );
        await tester.tap(find.byTooltip('打开会话抽屉'));
        await tester.pumpAndSettle();
        expect(find.byType(Drawer), findsOneWidget);
        Navigator.of(tester.element(find.byType(Drawer))).pop();
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('runtime-selector')));
        await tester.pumpAndSettle();
        expect(find.byKey(const ValueKey('runtime-native')), findsOneWidget);
        expect(find.byKey(const ValueKey('runtime-linux')), findsOneWidget);
        expect(
          find.byKey(const ValueKey('runtime-ssh-windows')),
          findsOneWidget,
        );
        expect(find.byKey(const ValueKey('runtime-ssh-linux')), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
        controller.dispose();
      },
    );
  }
  testWidgets(
    'runtime entries open native managers without faking host selection',
    (tester) async {
      const channel = MethodChannel('app.dsh.mobile/native_chat');
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            return false;
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );
      final controller = ChatController(FixtureTransport());
      final host = HostInfo.fromMap({
        'runtimeMode': 'linux',
        'runtimeResolved': true,
        'distro': 'Alpine 3.22',
        'available': true,
      });
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: ChatShell(controller: controller, hostInfo: host),
        ),
      );
      await tester.pump();
      expect(find.text('DSH Alpine'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('runtime-selector')));
      await tester.pumpAndSettle();
      final windows = find.byKey(const ValueKey('runtime-ssh-windows'));
      await tester.ensureVisible(windows);
      await tester.tap(windows);
      await tester.pumpAndSettle();
      expect(calls.last.method, 'openSshManager');
      expect(calls.last.arguments, {'platform': 'windows'});
      expect(find.textContaining('当前环境：DSH Alpine'), findsOneWidget);
      expect(find.textContaining('未打开'), findsOneWidget);
      final native = find.byKey(const ValueKey('runtime-native'));
      await tester.ensureVisible(native);
      await tester.tap(native);
      await tester.pumpAndSettle();
      expect(calls.last.method, 'openRuntimeSettings');
      expect(calls.last.arguments, {'target': 'native'});
      final linux = find.byKey(const ValueKey('runtime-linux'));
      await tester.ensureVisible(linux);
      await tester.tap(linux);
      await tester.pumpAndSettle();
      expect(calls.last.method, 'openRuntimeSettings');
      expect(calls.last.arguments, {'target': 'linux'});
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );
}
