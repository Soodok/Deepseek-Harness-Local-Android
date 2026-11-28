import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/model/settings_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/state/settings_controller.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_detail_pages.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_home.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_scope.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'settings_controller_test.dart' show FakeService;

const _channel = MethodChannel('app.dsh.mobile/native_chat');
const _petKey = ValueKey('settings-destination-pet');

class _DemoSettings extends FakeService {
  @override
  bool get isDemo => true;
}

Future<void> _openHome(
  WidgetTester tester, {
  Brightness brightness = Brightness.light,
  bool demo = false,
  bool busy = false,
}) async {
  final settings = SettingsController(demo ? _DemoSettings() : FakeService())
    ..busy = busy;
  final chat = ChatController(FixtureTransport());
  addTearDown(settings.dispose);
  addTearDown(chat.dispose);
  await tester.pumpWidget(
    SettingsScope(
      controller: settings,
      child: MaterialApp(
        theme: brightness == Brightness.light
            ? DshTheme.light()
            : DshTheme.dark(),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => showSettingsHome(context, chat),
              child: const Text('打开设置'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('打开设置'));
  await tester.pumpAndSettle();
}

Future<void> _tapPet(WidgetTester tester) async {
  final row = find.byKey(_petKey);
  expect(row, findsOneWidget);
  await tester.ensureVisible(row);
  await tester.pumpAndSettle();
  await tester.tap(row);
  await tester.pumpAndSettle();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late List<MethodCall> calls;

  void mockHost(Future<Object?> Function(MethodCall) handler) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) {
          calls.add(call);
          return handler(call);
        });
  }

  setUp(() {
    calls = [];
    mockHost((_) async => true);
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });

  for (final brightness in Brightness.values) {
    testWidgets('pet row is in display and personalization: $brightness', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await _openHome(tester, brightness: brightness);
      final pet = find.byKey(_petKey);
      final appearance = find.byKey(
        const ValueKey('settings-destination-appearance'),
      );
      expect(find.text('悬浮桌宠'), findsOneWidget);
      expect(
        tester.getTopLeft(find.text('显示与个性化')).dy,
        lessThan(tester.getTopLeft(appearance).dy),
      );
      expect(
        tester.getTopLeft(appearance).dy,
        lessThan(tester.getTopLeft(pet).dy),
      );
      expect(
        tester.getTopLeft(pet).dy,
        lessThan(tester.getTopLeft(find.text('系统与数据')).dy),
      );
      expect(
        find.descendant(of: pet, matching: find.byType(Switch)),
        findsNothing,
      );
      expect(tester.getSize(pet).height, greaterThanOrEqualTo(48));
      expect(calls, isEmpty, reason: 'listing settings must not enable a pet');
      await _tapPet(tester);
      expect(calls.map((call) => call.method), ['openSettingsSection']);
      expect(calls.single.arguments, {'section': 'pet'});
      expect(find.byKey(_petKey), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('searching pet reaches the same existing host bridge', (
    tester,
  ) async {
    await _openHome(tester);
    await tester.enterText(find.byKey(const ValueKey('settings-search')), '桌宠');
    await tester.pumpAndSettle();
    expect(find.text('显示与个性化'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('settings-destination-plugins')),
      findsNothing,
    );
    await _tapPet(tester);
    expect(calls.map((call) => call.method), ['openSettingsSection']);
    expect(calls.single.arguments, {'section': 'pet'});
  });

  for (final failure in ['false', 'missing-plugin', 'platform-error']) {
    testWidgets('pet launch $failure stays in settings with a retry message', (
      tester,
    ) async {
      mockHost((_) async {
        if (failure == 'missing-plugin') throw MissingPluginException();
        if (failure == 'platform-error') {
          throw PlatformException(code: 'activity-unavailable');
        }
        return false;
      });
      await _openHome(tester);
      await _tapPet(tester);
      expect(calls.map((call) => call.method), ['openSettingsSection']);
      expect(calls.single.arguments, {'section': 'pet'});
      expect(find.text('无法打开悬浮桌宠，请从 Android 设置重试。'), findsOneWidget);
      expect(find.byKey(_petKey), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('demo pet entry never invokes the Android host', (tester) async {
    await _openHome(tester, demo: true);
    await _tapPet(tester);
    expect(calls, isEmpty);
    expect(find.byType(UnavailableSettingsPage), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('busy settings cannot dispatch the pet entry', (tester) async {
    await _openHome(tester, busy: true);
    await _tapPet(tester);
    expect(calls, isEmpty);
    expect(find.byKey(_petKey), findsOneWidget);
  });

  test('pet destination uses the existing named section contract', () async {
    expect(SettingsDestination.pet.title, '悬浮桌宠');
    expect(
      await HostBridge.openSettingsSection(SettingsDestination.pet),
      isTrue,
    );
    expect(calls.single.method, 'openSettingsSection');
    expect(calls.single.arguments, {'section': 'pet'});
  });
}
