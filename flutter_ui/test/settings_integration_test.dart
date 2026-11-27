import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/main.dart';
import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/model/settings_model.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_home.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_detail_pages.dart';
import 'package:dsh_native_chat/src/state/settings_controller.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_scope.dart';

import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/native_settings_service.dart';

import 'settings_controller_test.dart' show FakeService;

class _ProviderRpc extends DshRpcConnection {
  _ProviderRpc() : super(webUrl: 'http://localhost:1234/?token=test');
  final calls = <(String, Map<String, Object?>)>[];
  int revision = 7;
  final providers = <String, Object?>{};

  @override
  Future<Object?> call(String endpoint, Map<String, Object?> args) async {
    calls.add((endpoint, args));
    Map<String, Object?> section() => {
      'ns': 'llm-pi-ai',
      'revision': revision,
      'value': {'providers': Map.of(providers)},
    };
    if (endpoint == 'settings/describe') {
      return {'writable': true, 'namespaces': [section()]};
    }
    if (endpoint == 'settings/mutate') {
      expect(args['ns'], 'llm-pi-ai');
      expect(args['expectedRevision'], revision);
      for (final op in (args['ops'] as List).cast<Map>()) {
        final path = (op['path'] as List).cast<String>();
        expect(path.take(2), ['providers', 'test-local']);
        if (path.length == 2 && op['op'] == 'unset') {
          providers.remove(path[1]);
        } else if (path.length == 2 && op['op'] == 'set') {
          providers[path[1]] = op['value'];
        } else if (path.length == 3 && op['op'] == 'set') {
          providers[path[1]] = {
            ...(providers[path[1]] as Map),
            path[2]: op['value'],
          };
        } else {
          fail('Unexpected settings operation: ');
        }
      }
      revision++;
      return section();
    }
    if (endpoint == 'credentials/set') return null;
    if (endpoint == 'llm/discoverModels') return [
      {'id': 'model-a'}, {'id': 'kept-model'}, {'id': 'model-b'},
    ];
    throw StateError('unexpected RPC: $endpoint');
  }
}

Future<void> settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 350));
}

Map<String, Object?> _configuredProvider() => {
  'api': 'openai-completions',
  'baseURL': 'http://127.0.0.1:8000/v1',
  'apiKeyEnv': 'FIXTURE_SHARED_REF',
  'timeoutMs': 7300,
  'models': [
    {'id': 'model-a', 'name': 'A display name', 'contextWindow': 32000},
  ],
};

Future<void> _showProviderPage(WidgetTester tester, _ProviderRpc rpc) async {
  final service = NativeSettingsService(
    rpc: rpc,
    callbackOrigin: Uri.parse('http://localhost:1234'),
    openBrowser: (_) async => false,
  );
  final chat = ChatController(FixtureTransport());
  addTearDown(chat.dispose);
  await tester.pumpWidget(MaterialApp(home: ModelConfigurationPage(
    controller: chat, providers: service,
  )));
  await settle(tester);
}

Future<void> _tapProvider(WidgetTester tester, String id) async {
  final button = find.byKey(ValueKey('provider-$id'));
  expect(button, findsOneWidget);
  await tester.ensureVisible(button);
  await tester.tap(button);
  await settle(tester);
}

Future<void> _fillProvider(WidgetTester tester, String id, String text) async {
  final field = find.byKey(ValueKey('provider-$id'));
  await tester.ensureVisible(field);
  await tester.enterText(field, text);
  await settle(tester);
}

void main() {
  testWidgets('native provider edit retains settings that the form does not own', (tester) async {
    final rpc = _ProviderRpc();
    final original = _configuredProvider();
    rpc.providers['test-local'] = original;
    rpc.providers['untouched'] = original;
    await _showProviderPage(tester, rpc);
    await _tapProvider(tester, 'edit-test-local');
    final route = tester.widget<TextField>(find.byKey(const ValueKey('provider-route')));
    expect(route.controller?.text, 'test-local');
    expect(route.enabled, isFalse);
    await _fillProvider(tester, 'models', 'model-a, model-b');
    await _tapProvider(tester, 'save');
    expect(find.text('model-a、model-b'), findsOneWidget);
    expect(rpc.providers['test-local'], {
      ...original,
      'models': [...original['models'] as List, {'id': 'model-b'}],
    });
    expect(rpc.providers['untouched'], original);
    expect(rpc.calls.where((c) => c.$1.startsWith('credentials/')), isEmpty);
    await _tapProvider(tester, 'edit-test-local');
    final models = tester.widget<TextField>(find.byKey(const ValueKey('provider-models')));
    expect(models.controller?.text, 'model-a, model-b');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('native provider removal requires confirmation and keeps shared credentials', (tester) async {
    final rpc = _ProviderRpc();
    final original = _configuredProvider();
    rpc.providers['test-local'] = original;
    rpc.providers['untouched'] = original;
    await _showProviderPage(tester, rpc);
    await _tapProvider(tester, 'remove-test-local');
    await tester.tap(find.text('取消'));
    await settle(tester);
    expect(rpc.calls.where((c) => c.$1 == 'settings/mutate'), isEmpty);
    await _tapProvider(tester, 'remove-test-local');
    await _tapProvider(tester, 'confirm-remove');
    expect(find.text('test-local'), findsNothing);
    expect(rpc.providers.keys, ['untouched']);
    expect(rpc.calls.where((c) => c.$1.startsWith('credentials/')), isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('native discovery calls the Host and fills only the draft', (tester) async {
    final rpc = _ProviderRpc();
    await _showProviderPage(tester, rpc);
    await _tapProvider(tester, 'add');
    await _fillProvider(tester, 'url', 'http://127.0.0.1:8000/v1');
    await _fillProvider(tester, 'key', 'fixture-key');
    await _fillProvider(tester, 'models', 'kept-model');
    await _tapProvider(tester, 'discover');
    final field = tester.widget<TextField>(find.byKey(const ValueKey('provider-models')));
    expect(field.controller?.text, 'kept-model, model-a, model-b');
    expect(rpc.calls.singleWhere((c) => c.$1 == 'llm/discoverModels').$2, {
      'settingsNs': 'llm-pi-ai',
      'request': {
        'baseURL': 'http://127.0.0.1:8000/v1',
        'api': 'openai-completions',
        'apiKey': 'fixture-key',
      },
    });
    expect(rpc.calls.map((c) => c.$1), ['settings/describe', 'llm/discoverModels']);
    expect(rpc.providers, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('native model page saves a provider and stores its key separately', (tester) async {
    final rpc = _ProviderRpc();
    final settings = SettingsController(NativeSettingsService(
      rpc: rpc,
      callbackOrigin: Uri.parse('http://localhost:1234'),
      openBrowser: (_) async => false,
    ));
    final chat = ChatController(FixtureTransport());
    addTearDown(settings.dispose);
    addTearDown(chat.dispose);
    await tester.pumpWidget(SettingsScope(
      controller: settings,
      child: MaterialApp(home: Builder(builder: (context) => Scaffold(
        body: TextButton(onPressed: () => showSettingsHome(context, chat), child: const Text('test settings')),
      ))),
    ));
    await tester.tap(find.text('test settings'));
    await settle(tester);
    final search = find.byKey(const ValueKey('settings-search'));
    await tester.enterText(search, '模型配置');
    await settle(tester);
    final models = find.byKey(const ValueKey('settings-destination-models'));
    await tester.ensureVisible(models);
    await tester.tap(models);
    await settle(tester);
    expect(find.text('完整模型配置'), findsNothing);
    final add = find.byKey(const ValueKey('provider-add'));
    await tester.ensureVisible(add);
    await tester.tap(add);
    await settle(tester);
    for (final (key, value) in [
      ('route', 'test-local'), ('url', 'http://127.0.0.1:8000/v1'),
      ('models', 'model-a'), ('key', 'fixture-secret'),
    ]) {
      final field = find.byKey(ValueKey('provider-$key'));
      await tester.ensureVisible(field);
      await tester.enterText(field, value);
    }
    final save = find.byKey(const ValueKey('provider-save'));
    await tester.ensureVisible(save);
    await tester.tap(save);
    await settle(tester);
    expect(find.text('test-local'), findsOneWidget);
    final provider = rpc.providers['test-local'] as Map;
    expect(provider['models'], [{'id': 'model-a'}]);
    final reference = provider['apiKeyEnv'] as String;
    expect(reference, matches(RegExp(r'^DSH_NATIVE_[A-F0-9]{32}_API_KEY$')));
    expect(provider.toString(), isNot(contains('fixture-secret')));
    expect(rpc.calls.where((c) => c.$1 == 'credentials/set').single.$2,
      {'ref': reference, 'value': 'fixture-secret'});
    expect(rpc.calls.where((c) => c.$1 == 'settings/describe'), hasLength(2));
    await tester.pumpWidget(const SizedBox.shrink());
  });
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
  testWidgets('hosted drawer opens each Android management section', (tester) async {
    final calls = <MethodCall>[];
    const channel = MethodChannel('app.dsh.mobile/native_chat');
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'openSettingsSection' || call.method == 'openWebFallback') {
        calls.add(call);
        return true;
      }
      return null;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    final settings = SettingsController(FakeService());
    final chat = ChatController(FixtureTransport());
    addTearDown(settings.dispose);
    addTearDown(chat.dispose);
    await tester.pumpWidget(SettingsScope(
      controller: settings,
      child: MaterialApp(home: ChatShell(controller: chat)),
    ));
    await settle(tester);
    await tester.tap(find.byTooltip('打开会话抽屉'));
    await settle(tester);
    await tester.tap(find.byKey(const ValueKey('account-menu')));
    await settle(tester);
    await tester.tap(find.text('设置'));
    await settle(tester);

    final search = find.byKey(const ValueKey('settings-search'));
    const destinations = [
      SettingsDestination.plugins,
      SettingsDestination.extensions,
      SettingsDestination.containers,
      SettingsDestination.language,
      SettingsDestination.diagnostics,
      SettingsDestination.backup,
      SettingsDestination.about,
    ];
    for (final destination in destinations) {
      await tester.ensureVisible(search);
      await tester.enterText(search, destination.title);
      await settle(tester);
      final row = find.byKey(ValueKey('settings-destination-${destination.name}'));
      await tester.ensureVisible(row);
      await settle(tester);
      await tester.tap(row);
      await settle(tester);
      expect(calls.lastOrNull?.method, 'openSettingsSection', reason: destination.name);
      expect(calls.last.arguments, {'section': destination.name});
      expect(search, findsOneWidget, reason: 'the underlying settings route stays mounted');
    }
    expect(calls, hasLength(destinations.length));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('preview settings stay on local pages or explicit placeholders', (tester) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('app.dsh.mobile/native_chat'), (call) async {
        calls.add(call);
        return call.method == 'openSettingsSection' ? true : null;
      },
    );
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('app.dsh.mobile/native_chat'), null));
    await tester.pumpWidget(const DshApp(preview: true));
    await settle(tester);
    await tester.tap(find.byTooltip('打开会话抽屉'));
    await settle(tester);
    await tester.tap(find.byKey(const ValueKey('account-menu')));
    await settle(tester);
    await tester.tap(find.text('设置'));
    await settle(tester);
    for (final destination in SettingsDestination.values.where((d) => d != SettingsDestination.webUi)) {
      await tester.ensureVisible(find.byKey(const ValueKey('settings-search')));
      await tester.enterText(find.byKey(const ValueKey('settings-search')), destination.title);
      await settle(tester);
      final row = find.byKey(ValueKey('settings-destination-${destination.name}'));
      await tester.ensureVisible(row);
      await settle(tester);
      await tester.tap(row);
      await settle(tester);
      await tester.pump(const Duration(seconds: 2));
      expect(find.byType(AppBar), findsOneWidget, reason: destination.name);
      expect(find.text(destination.title), findsOneWidget, reason: destination.name);
      expect(calls.where((c) => c.method == 'openSettingsSection'), isEmpty, reason: destination.name);
      await tester.pageBack();
      await settle(tester);
      expect(find.byKey(const ValueKey('settings-search')), findsOneWidget);
    }
    await tester.pumpWidget(const SizedBox.shrink());
  });

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
