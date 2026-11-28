import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/bridge/native_workspace_directories.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/native_workspace_picker.dart';
import 'package:dsh_native_chat/src/ui/native_workspace_directory_sheet.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('app.dsh.mobile/native_workspace');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  testWidgets('existing picker obtains directory actions through scope, not Home selection', (tester) async {
    var privatePicks = 0, sharedPicks = 0, homePicks = 0;
    await tester.pumpWidget(MaterialApp(theme: DshTheme.light(), home: Scaffold(
      body: NativeWorkspaceDirectoryScope(selectedDirectory: null,
        onPickPrivate: () async { privatePicks++; return true; },
        onPickShared: () async { sharedPicks++; return true; },
        child: NativeWorkspacePicker(workspaces: const [], selected: null,
          onSelect: (_) async { homePicks++; return true; }),
      ),
    )));
    await tester.tap(find.byKey(const ValueKey('welcome-workspace-capsule')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('workspace-pick-private')));
    await tester.pumpAndSettle();
    expect([privatePicks, sharedPicks, homePicks], [1, 0, 0]);
    await tester.tap(find.byKey(const ValueKey('welcome-workspace-capsule')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('workspace-pick-shared')));
    await tester.pumpAndSettle();
    expect([privatePicks, sharedPicks, homePicks], [1, 1, 0]);
    expect(tester.takeException(), isNull);
  });

  testWidgets('private browser selects a real existing directory through the bridge', (tester) async {
    const host = HostInfo(webUrl: 'http://127.0.0.1:3080', state: 'Healthy', available: true,
      workspaceDomain: 'domain', workspaceGeneration: 'boot');
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'listPrivateWorkspaces') {
        final path = call.arguments['path'] as String? ?? '/app/private';
        return {'domain': 'domain', 'generation': 'boot', 'root': '/app/private', 'path': path,
          'directories': path == '/app/private' ? [{'path': '/app/private/alpha', 'name': 'alpha'}] : []};
      }
      return {'domain': 'domain', 'generation': 'boot', 'kind': 'private',
        'path': '/app/private/alpha', 'name': 'alpha'};
    });
    NativeWorkspaceDirectory? selected;
    await tester.pumpWidget(MaterialApp(theme: DshTheme.light(), home: Scaffold(body: Builder(
      builder: (context) => TextButton(onPressed: () async {
        selected = await showPrivateWorkspaceDirectorySheet(context, NativeWorkspaceDirectories(host));
      }, child: const Text('选择目录')),
    ))));
    await tester.tap(find.text('选择目录'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('alpha'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('使用此目录'));
    await tester.pumpAndSettle();
    expect(selected?.path, '/app/private/alpha');
    expect(calls.last.method, 'selectPrivateWorkspace');
    expect(calls.last.arguments['path'], '/app/private/alpha');
    expect(tester.takeException(), isNull);
  });

  testWidgets('private browser creates and returns a new directory', (tester) async {
    const host = HostInfo(webUrl: 'http://127.0.0.1:3080', state: 'Healthy', available: true,
      workspaceDomain: 'domain', workspaceGeneration: 'boot');
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'listPrivateWorkspaces') {
        return {
          'domain': 'domain',
          'generation': 'boot',
          'root': '/app/private',
          'path': '/app/private',
          'directories': <Object?>[],
        };
      }
      if (call.method == 'createPrivateWorkspace') {
        expect(call.arguments['parent'], '/app/private');
        expect(call.arguments['name'], 'native-check');
        return {
          'domain': 'domain',
          'generation': 'boot',
          'kind': 'private',
          'path': '/app/private/native-check',
          'name': 'native-check',
        };
      }
      return null;
    });
    NativeWorkspaceDirectory? selected;
    await tester.pumpWidget(MaterialApp(theme: DshTheme.light(), home: Scaffold(body: Builder(
      builder: (context) => TextButton(onPressed: () async {
        selected = await showPrivateWorkspaceDirectorySheet(context, NativeWorkspaceDirectories(host));
      }, child: const Text('选择目录')),
    ))));
    await tester.tap(find.text('选择目录'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('新建文件夹'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'native-check');
    await tester.tap(find.widgetWithText(FilledButton, '新建'));
    await tester.pumpAndSettle();

    expect(selected?.path, '/app/private/native-check');
    expect(selected?.kind, 'private');
    expect(calls.map((call) => call.method), [
      'listPrivateWorkspaces',
      'createPrivateWorkspace',
    ]);
    expect(tester.takeException(), isNull);
  });

  testWidgets('unconfirmed default neither checks Home nor claims it', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: NativeWorkspacePicker(
            workspaces: const [],
            selected: null,
            confirmed: false,
            status: '默认工作区尚未读取，请重试',
            onRetry: () {},
            onSelect: (_) async => true,
          ),
        ),
      ),
    );
    expect(find.text('工作区：工作区暂不可用'), findsOneWidget);
    expect(find.byIcon(Icons.check_rounded), findsNothing);
    expect(find.text('重试'), findsOneWidget);
    expect(find.text('重新读取工作区'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('welcome-workspace-capsule')));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.check_rounded), findsNothing);
    expect(tester.takeException(), isNull);
    semantics.dispose();
  });

  testWidgets('confirmed Home keeps its check mark and full semantic label', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: NativeWorkspacePicker(
            workspaces: const [],
            selected: null,
            confirmed: true,
            onSelect: (_) async => true,
          ),
        ),
      ),
    );
    expect(find.text('工作区：Home'), findsOneWidget);
    expect(find.bySemanticsLabel('工作区：Home'), findsOneWidget);
    expect(find.text('重试'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('welcome-workspace-capsule')));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.check_rounded), findsOneWidget);
    expect(tester.takeException(), isNull);
    semantics.dispose();
  });

  testWidgets('long directory name stays readable in the semantic label', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    const long = '/storage/emulated/0/Documents/2026-11-27-beta141-very-long';
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: NativeWorkspaceDirectoryScope(
            selectedDirectory: const NativeWorkspaceDirectory(
              path: long,
              name: 'beta141-very-long',
              kind: 'private',
            ),
            onPickPrivate: null,
            onPickShared: null,
            child: NativeWorkspacePicker(
              workspaces: const [],
              selected: null,
              confirmed: true,
              onSelect: (_) async => true,
            ),
          ),
        ),
      ),
    );
    expect(find.text('工作区：beta141-very-long'), findsOneWidget);
    expect(find.bySemanticsLabel('工作区：' + long), findsOneWidget);
    expect(tester.takeException(), isNull);
    semantics.dispose();
  });
}
