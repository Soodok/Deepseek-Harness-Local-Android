import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';

void main() {
  testWidgets('renders the complete fixture lifecycle', (tester) async {
    final transport = FixtureTransport();
    final controller = ChatController(transport);
    var fallbackOpened = 0;
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(
          controller: controller,
          onOpenWebFallback: () => fallbackOpened++,
        ),
      ),
    );
    await tester.pump();

    expect(find.text('DSH'), findsOneWidget);
    expect(find.text('帮我检查一下项目状态，并说明接下来该做什么。'), findsOneWidget);
    expect(find.byKey(const ValueKey('composer-input')), findsOneWidget);
    expect(find.byTooltip('fixture 模式'), findsOneWidget);
    expect(find.byKey(const ValueKey('composer-send')), findsOneWidget);

    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '继续检查',
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('composer-send')));
    await tester.pump();
    await tester.ensureVisible(find.byKey(const ValueKey('approval-approve')));
    expect(find.text('允许继续读取吗？'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('读取项目状态'),
      160,
      scrollable: find
          .descendant(
            of: find.byKey(const ValueKey('conversation-list')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    expect(find.text('读取项目状态'), findsOneWidget);
    expect(find.byKey(const ValueKey('approval-approve')), findsOneWidget);

    await tester.ensureVisible(find.byKey(const ValueKey('approval-approve')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('approval-approve')));
    await tester.pump();
    expect(find.text('检查完成：当前可以继续做 Flutter 聊天页的协议接线。'), findsOneWidget);
    expect(find.byKey(const ValueKey('approval-approve')), findsNothing);

    transport.emitQuestion();
    await tester.pump();
    await tester.ensureVisible(find.byKey(const ValueKey('question-input')));
    expect(find.text('接下来想看什么？'), findsOneWidget);
    expect(find.byKey(const ValueKey('question-input')), findsOneWidget);
    await tester.ensureVisible(find.byKey(const ValueKey('question-input')));
    await tester.enterText(find.byKey(const ValueKey('question-input')), '继续');
    await tester.pump();
    await tester.ensureVisible(find.byKey(const ValueKey('question-submit')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('question-submit')));
    await tester.pump();
    expect(find.text('已记录你的回答：继续'), findsOneWidget);

    transport.emitDisconnect();
    await tester.pump();
    expect(find.text('连接已中断'), findsOneWidget);
    expect(find.byKey(const ValueKey('connection-retry')), findsOneWidget);
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('connection-retry')));
    await tester.pump();
    expect(find.textContaining('已连接'), findsOneWidget);

    transport.emitGap();
    await tester.pump();
    await tester.pump();
    expect(controller.state.needsResync, isFalse);
    expect(controller.state.generation, 3);

    transport.emitError();
    await tester.pump();
    expect(find.text('引擎连接失败'), findsOneWidget);
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('connection-retry')));
    await tester.pump();
    await tester.pump();
    expect(find.textContaining('已连接'), findsOneWidget);

    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('runtime-selector')));
    await tester.pumpAndSettle();
    final webFallback = find.byKey(const ValueKey('runtime-web-fallback'));
    await tester.ensureVisible(webFallback);
    await tester.tap(webFallback);
    await tester.pump();
    expect(fallbackOpened, 1);
    controller.dispose();
  });

  testWidgets('queues a prompt while a turn is waiting for action', (
    tester,
  ) async {
    final transport = FixtureTransport();
    final controller = ChatController(transport);
    await tester.pumpWidget(
      MaterialApp(home: ChatShell(controller: controller)),
    );
    await tester.pump();

    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '排到下一轮',
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('composer-send')));
    await tester.pump();
    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '插话内容',
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('composer-send')));
    await tester.pump();
    await tester.ensureVisible(find.byKey(const ValueKey('queue-card')));
    expect(find.text('排队中 1 条'), findsOneWidget);
    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '第二条排队',
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('composer-send')));
    await tester.pump();
    await tester.ensureVisible(find.byKey(const ValueKey('queue-card')));
    expect(find.text('排队中 2 条'), findsOneWidget);

    final dispatch = find.widgetWithText(TextButton, '发送下一条');
    expect(tester.widget<TextButton>(dispatch).onPressed, isNull);
    expect(controller.state.queuedPrompts, ['插话内容', '第二条排队']);
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('composer-stop')));
    await tester.pump();
    expect(tester.widget<TextButton>(dispatch).onPressed, isNotNull);
    await tester.pump();
    await tester.tap(dispatch);
    await tester.pump();
    expect(controller.state.queuedPrompts, ['第二条排队']);
    expect(
      controller.state.messages.where((m) => m.text == '插话内容'),
      hasLength(1),
    );
    expect(find.text('排队中 1 条'), findsOneWidget);
    controller.dispose();
  });

  testWidgets('stops a waiting action', (tester) async {
    final transport = FixtureTransport();
    final controller = ChatController(transport);
    await tester.pumpWidget(
      MaterialApp(home: ChatShell(controller: controller)),
    );
    await tester.pump();

    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '停止测试',
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('composer-send')));
    await tester.pump();
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('approval-stop')),
      240,
      scrollable: find
          .descendant(
            of: find.byType(ListView).first,
            matching: find.byType(Scrollable),
          )
          .first,
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('approval-stop')));
    await tester.pump();

    expect(find.text('已停止'), findsOneWidget);
    expect(find.byKey(const ValueKey('approval-card')), findsNothing);
    controller.dispose();
  });
}
