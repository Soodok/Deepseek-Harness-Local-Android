import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/src/model/settings_model.dart';
import 'package:dsh_native_chat/src/theme/display_scale.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_detail_pages.dart';

/// 需求⑤：设置里必须真的有 SSH / Windows 连接入口，而且没有真实主机时
/// 界面只能显示「不可用 / 未打开」，绝不能先画成已连接。

const _channel = MethodChannel('app.dsh.mobile/native_chat');

Future<void> _pump(
  WidgetTester tester,
  Widget page, {
  DshDisplaySize size = DshDisplaySize.compact,
}) async {
  tester.view.physicalSize = const Size(432, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    DshDisplayScope(
      size: size,
      child: MaterialApp(
        theme: DshTheme.light(displayFactor: size.factor),
        builder: (context, child) =>
            DshDisplayCanvas(child: child ?? const SizedBox.shrink()),
        home: page,
      ),
    ),
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 400));
}

void main() {
  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });

  testWidgets('without a native host the page claims no connection', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call);
      return null;
    });
    await _pump(tester, const ConnectionsPage());
    expect(tester.takeException(), isNull);
    // 三个入口都在，且没有一个假装已连接。
    expect(find.byKey(const ValueKey('connections-native')), findsOneWidget);
    expect(find.byKey(const ValueKey('connections-ssh-windows')), findsOneWidget);
    expect(find.byKey(const ValueKey('connections-ssh-linux')), findsOneWidget);
    expect(find.byKey(const ValueKey('connections-status')), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('connections-status')),
        matching: find.textContaining('未检测到'),
      ),
      findsOneWidget,
    );
    expect(find.textContaining('已连接'), findsNothing);
    // 页面只读引擎信息，不会偷偷去建连接或读凭据。
    expect(calls.map((call) => call.method), ['engineInfo']);
  });

  testWidgets('an observed Host names its runtime instead of a fake peer', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      if (call.method != 'engineInfo') return true;
      return {
        'webUrl': 'http://127.0.0.1:7060',
        'state': 'Healthy',
        'available': true,
        'runtimeMode': 'linux',
        'runtimeResolved': true,
        'distro': 'alpine',
      };
    });
    await _pump(tester, const ConnectionsPage());
    expect(tester.takeException(), isNull);
    expect(find.textContaining('DSH Alpine'), findsWidgets);
    // 仅本机引擎报告了环境；SSH 主机从未握手，仍必须显示未连接。
    expect(find.textContaining('未连接任何 SSH 主机'), findsOneWidget);
    expect(find.textContaining('已连接 1'), findsNothing);
  });

  testWidgets('SSH settings open the shared native form and return', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
          calls.add(call);
          return call.method == 'engineInfo' ? null : true;
        });
    await _pump(tester, const ConnectionsPage());
    for (final (platform, title) in [('windows', 'Windows'), ('linux', 'Linux')]) {
      await tester.tap(find.byKey(ValueKey('connections-ssh-$platform')));
      await tester.pumpAndSettle();
      expect(find.text('$title SSH'), findsOneWidget);
      expect(find.byKey(const ValueKey('ssh-host')), findsOneWidget);
      expect(find.byKey(const ValueKey('ssh-connect')), findsOneWidget);
      expect(find.textContaining('已连接'), findsNothing);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('connections-status')), findsOneWidget);
    }
    expect(calls.map((call) => call.method), ['engineInfo']);
  });

  test('the destination names the connection work, not the engine internals', () {
    expect(SettingsDestination.connections.title, '连接与远程');
    expect(SettingsDestination.connections.subtitle, contains('SSH'));
  });
}
