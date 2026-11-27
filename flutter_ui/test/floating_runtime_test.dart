import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';

/// 固定推进若干帧：页面里可能存在持续动效（标题平移、演示入场），
/// 这时 pumpAndSettle 会一直等到超时。
Future<void> _frames(WidgetTester tester, [int count = 8]) async {
  for (var i = 0; i < count; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

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
  for (final width in [360.0, 390.0, 432.0, 600.0]) {
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
        final header = tester.getRect(
          find.byKey(const ValueKey('floating-header')),
        );
        final runtime = tester.getRect(
          find.byKey(const ValueKey('runtime-selector')),
        );
        // No child has been observed yet, so the subagent entry is not laid
        // out at all: nothing is hidden behind an empty slot.
        expect(find.byKey(const ValueKey('subagent-selector')), findsNothing);
        expect(header.right - runtime.right, inInclusiveRange(5, 14));
        final chevron = tester.getRect(
          find.descendant(
            of: find.byKey(const ValueKey('runtime-selector')),
            matching: find.byIcon(Icons.expand_more_rounded),
          ),
        );
        expect(header.right - chevron.right, inInclusiveRange(12, 28));
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
        final composer = tester.getRect(find.byKey(const ValueKey('floating-composer-extent')));
        final attachGlyph = tester.getRect(find.descendant(of: find.byKey(const ValueKey('composer-attach')), matching: find.byIcon(Icons.add_rounded)));
        expect(attachGlyph.left - composer.left, lessThanOrEqualTo(ChatLayout.gutter(width) + 24));
        expect(model.left - attach.right, inInclusiveRange(0, 4));
        expect(send.left - permission.right, closeTo(0, 0.01));
        // The capsule is a 34dp painted surface inside a 48dp touch target,
        // so it reads flatter than the 48dp send button without shrinking
        // either the tap area or the dock row.
        expect(
          tester
              .getSize(find.byKey(const ValueKey('composer-model-surface')))
              .height,
          inInclusiveRange(32, 36),
        );
        expect(model.height, greaterThanOrEqualTo(48));
        expect(send.height, greaterThanOrEqualTo(48));
        expect(
          (tester
                      .widget<Container>(
                        find.byKey(const ValueKey('composer-model-surface')),
                      )
                      .decoration!
                  as BoxDecoration)
              .borderRadius,
          isA<BorderRadius>(),
        );
        expect(
          find.descendant(
            of: find.byKey(const ValueKey('floating-header')),
            matching: find.byKey(const ValueKey('runtime-selector')),
          ),
          findsOneWidget,
        );
        // A real observed catalog publishes the entry beside the runtime chip.
        (controller.transport as FixtureTransport).emitShowcase();
        await tester.pump();
        final agents = tester.getRect(
          find.byKey(const ValueKey('subagent-selector')),
        );
        expect(runtime.left - agents.right, inInclusiveRange(0, 8));
        await tester.tap(find.byTooltip('打开会话抽屉'));
        // The drawer session title may deliberately scroll continuously.
        for (var i = 0; i < 6; i++) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expect(find.byType(Drawer), findsOneWidget);
        Navigator.of(tester.element(find.byType(Drawer))).pop();
        // 标题现在显示的是真实会话标题，窄屏上会溢出并持续平移，
        // pumpAndSettle 永远等不到静止帧，所以按固定帧数推进。
        await _frames(tester);
        await tester.tap(find.byKey(const ValueKey('runtime-selector')));
        await _frames(tester);
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
    'runtime opens SSH natively and keeps Web and local actions separate',
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
      final web = find.byKey(const ValueKey('runtime-web-fallback'));
      for (final platform in ['windows', 'linux']) {
        final entry = find.byKey(ValueKey('runtime-ssh-$platform'));
        await tester.ensureVisible(entry);
        await tester.tap(entry);
        await tester.pumpAndSettle();
        expect(find.byKey(const ValueKey('ssh-host')), findsOneWidget);
        expect(find.byKey(const ValueKey('ssh-password')), findsOneWidget);
        expect(find.byKey(const ValueKey('ssh-fingerprint')), findsOneWidget);
        expect(calls, isEmpty);
        Navigator.of(tester.element(find.byKey(const ValueKey('ssh-host'))))
            .pop();
        await tester.pumpAndSettle();
      }
      final sshLinux = find.byKey(const ValueKey('runtime-ssh-linux'));
      expect(
        tester.getTopLeft(web).dy - tester.getBottomLeft(sshLinux).dy,
        lessThanOrEqualTo(8),
      );
      expect(tester.getSize(web).height, greaterThanOrEqualTo(48));
      expect(find.textContaining('当前环境：DSH Alpine'), findsOneWidget);
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
      await tester.ensureVisible(web);
      await tester.tap(web);
      await tester.pumpAndSettle();
      expect(calls.last.method, 'openWebFallback');
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );
}
