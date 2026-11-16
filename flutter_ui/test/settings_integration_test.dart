import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/main.dart';
import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/model/settings_model.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_home.dart';
import 'package:dsh_native_chat/src/state/settings_controller.dart';

import 'settings_controller_test.dart' show FakeService;

Future<void> settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 350));
}

void main() {
  testWidgets(
    'drawer account menu opens settings, preview sign-in and confirmed logout share identity',
    (tester) async {
      await tester.pumpWidget(const DshApp(preview: true));
      await settle(tester);
      await tester.tap(find.byTooltip('打开会话抽屉'));
      await settle(tester);
      await tester.tap(find.byKey(const ValueKey('account-menu')));
      await settle(tester);
      await tester.tap(find.text('设置'));
      await settle(tester);
      expect(find.text('Token 记录'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('settings-login')));
      await settle(tester);
      await tester.tap(find.text('查看演示'));
      await settle(tester);
      expect(find.text('DeepSeek 用户'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('settings-back')));
      await settle(tester);
      await tester.tap(find.byTooltip('打开会话抽屉'));
      await settle(tester);
      expect(find.text('DeepSeek 用户'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('account-menu')));
      await settle(tester);
      await tester.tap(find.text('退出登录'));
      await settle(tester);
      await tester.tap(find.byKey(const ValueKey('confirm-signout')));
      await settle(tester);
      expect(find.text('DeepSeek 用户'), findsNothing);
      expect(find.text('DeepSeek 账号'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
  testWidgets(
    'synchronous account failure never leaves an orphan progress dialog',
    (tester) async {
      final service = FakeService();
      final c = SettingsController(service);
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => startAccountSignIn(context, c),
                child: const Text('login'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('login'));
      await settle(tester);
      expect(find.byType(AlertDialog), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
      c.dispose();
    },
  );
  test('native management section calls only its typed destination', () async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('app.dsh.mobile/native_chat'),
          (call) async {
            calls.add(call);
            return true;
          },
        );
    expect(
      await HostBridge.openSettingsSection(SettingsDestination.plugins),
      isTrue,
    );
    expect(calls.single.method, 'openSettingsSection');
    expect(calls.single.arguments, {'section': 'plugins'});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('app.dsh.mobile/native_chat'),
          null,
        );
  });
}
