import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/main.dart';

void main() {
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
    expect(find.text('DSH'), findsOneWidget);
    expect(find.byTooltip('fixture 模式'), findsOneWidget);
  });
}
