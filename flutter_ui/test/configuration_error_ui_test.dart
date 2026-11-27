import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/composer_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';

import 'composer_options_test.dart' show SessionFixture;

class EmptyModelsSession extends SessionFixture {
  @override
  List<ComposerModel> get models => const [];
}

class OpeningSession extends SessionFixture {
  final connection = Completer<void>();

  @override
  Future<void> connect() async {
    publish(const ComposerConfiguration());
    await connection.future;
    await super.connect();
  }
}

void main() {
  testWidgets('an unread session permission does not interrupt connection', (
    tester,
  ) async {
    final transport = OpeningSession();
    final controller = ChatController(transport);
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.dark(),
        home: ChatShell(controller: controller),
      ),
    );
    await tester.pump();

    expect(controller.state.status, ChatStatus.connecting);
    expect(controller.canSend, isFalse);
    expect(find.text('请确认会话权限'), findsNothing);

    transport.publish(
      const ComposerConfiguration(permissionPreset: 'read-only'),
    );
    transport.connection.complete();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 10));
    expect(controller.state.status, ChatStatus.ready);
    expect(controller.sessionPermission, ComposerPermission.readOnly);
    expect(controller.canSend, isTrue);
    expect(find.text('请确认会话权限'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('an unread session permission does not hide a link failure', (
    tester,
  ) async {
    final transport = OpeningSession();
    final controller = ChatController(transport);
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.dark(),
        home: ChatShell(controller: controller),
      ),
    );
    await tester.pump();
    transport.connection.completeError(
      StateError('Host unavailable during connect'),
    );
    await tester.pump();
    transport.publish(const ComposerConfiguration());
    await tester.pump();

    expect(controller.canSend, isFalse);
    expect(find.text('引擎连接失败'), findsOneWidget);
    expect(
      find.textContaining('Host unavailable during connect'),
      findsOneWidget,
    );
    expect(find.text('请确认会话权限'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets(
    'empty model catalog explains setup and opens the real host configuration',
    (tester) async {
      final calls = <String>[];
      const channel = MethodChannel('app.dsh.mobile/native_chat');
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call.method);
            return null;
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );
      final controller = ChatController(EmptyModelsSession());
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.dark(),
          home: ChatShell(controller: controller),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 10));
      await tester.tap(find.byKey(const ValueKey('composer-model')));
      await tester.pumpAndSettle();
      expect(find.text('还没有可用模型，请先配置模型提供商或登录账号。'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('model-configure')));
      await tester.pumpAndSettle();
      expect(find.text('模型配置'), findsOneWidget);
      expect(calls, isNot(contains('openWebFallback')));
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );
  testWidgets(
    'unknown permission shows its reason and recovery while the connection is ready',
    (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final transport = SessionFixture();
      final controller = ChatController(transport);
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.dark(),
          home: ChatShell(controller: controller),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 10));
      transport.publish(
        const ComposerConfiguration(
          modelId: 'fixture-fast',
          permissionPreset: 'custom',
        ),
      );
      await tester.pump();
      expect(controller.state.status, ChatStatus.ready);
      expect(controller.canSend, isFalse);
      expect(find.textContaining('当前会话工具权限为 custom'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('connection-action-retry')),
        findsNothing,
      );
      expect(tester.takeException(), isNull);
      controller.selectPermission(ComposerPermission.readOnly);
      await tester.pump();
      expect(controller.canSend, isTrue);
      expect(find.textContaining('当前会话工具权限为 custom'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );
}
