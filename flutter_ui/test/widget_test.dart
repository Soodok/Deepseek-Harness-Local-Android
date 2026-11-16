import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/main.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_scope.dart';

void main() {
  testWidgets(
    'host loss replaces the old account service and late results stay inert',
    (tester) async {
      const channel = MethodChannel('app.dsh.mobile/native_chat');
      var available = true;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            channel,
            (call) async => call.method == 'engineInfo'
                ? {
                    'state': 'Healthy',
                    'available': available,
                    'webUrl': 'http://127.0.0.1:1/?token=test',
                    'runtimeMode': 'native',
                    'runtimeResolved': true,
                  }
                : true,
          );
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );
      await tester.pumpWidget(const DshApp());
      await tester.pump();
      final old = SettingsScope.of(
        tester.element(find.text('DSH', findRichText: true)),
      );
      expect(old.isDemo, isFalse);
      available = false;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
      final next = SettingsScope.of(
        tester.element(find.text('DSH', findRichText: true)),
      );
      expect(identical(old, next), isFalse);
      expect(next.profile, isNull);
      expect(next.usage, isNull);
      expect(next.isDemo, isFalse);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
  testWidgets('host runtime refreshes after returning from native settings', (
    tester,
  ) async {
    const channel = MethodChannel('app.dsh.mobile/native_chat');
    var runtime = 'native';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          channel,
          (call) async => call.method == 'engineInfo'
              ? {
                  'runtimeMode': runtime,
                  'runtimeResolved': true,
                  'state': 'Healthy',
                  'available': true,
                  'distro': 'Alpine 3.22',
                }
              : true,
        );
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    await tester.pumpWidget(const DshApp());
    await tester.pump();
    expect(find.text('Bionic Node'), findsOneWidget);
    runtime = 'linux';
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(find.text('DSH Alpine'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('DSH app bootstrap shows independent chat shell', (tester) async {
    await tester.pumpWidget(const DshApp());
    await tester.pump();
    expect(find.text('DSH', findRichText: true), findsOneWidget);
    expect(find.byTooltip('fixture 模式'), findsOneWidget);
  });
}
