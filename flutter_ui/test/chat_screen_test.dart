import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/execution_group.dart';
import 'package:dsh_native_chat/src/ui/message_list.dart';
import 'package:dsh_native_chat/src/ui/overflow_title.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';

const _longPrompt = 'A precise description of the tasks for the session';

class _LongTitleFixture extends FixtureTransport {
  @override
  Stream<ChatEvent> get events => Stream.value(const ChatEvent.snapshot(
    generation: 1,
    sequence: 0,
    messages: [ChatMessage(id: 'first', role: ChatRole.user, text: _longPrompt)],
  ));
}

class _EmptyChatFixture extends FixtureTransport {
  int sent = 0;

  @override
  Stream<ChatEvent> get events => Stream.value(const ChatEvent.snapshot(
    generation: 1, sequence: 0, messages: [],
  ));

  @override
  Future<void> sendPrompt(String text) async => sent++;
}

void main() {
  testWidgets('an open welcome view updates every hour without remounting', (tester) async {
    var now = DateTime(2026, 11, 22, 23, 59, 59);
    final controller = ChatController(_EmptyChatFixture());
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: MessageList(
      controller: controller,
      now: () => now,
    ))));
    final greeting = find.byKey(const ValueKey('welcome-greeting'));
    String text() => tester.widget<Text>(greeting).data!;
    expect(text(), '睡前有件事想弄明白吗？');
    final greetings = <String>{text()};
    now = now.add(const Duration(seconds: 1));
    await tester.pump(const Duration(seconds: 1));
    expect(text(), '夜深了，想聊点什么？');
    greetings.add(text());
    for (var hour = 1; hour < 23; hour++) {
      now = now.add(const Duration(hours: 1));
      await tester.pump(const Duration(hours: 1));
      expect(greetings.add(text()), isTrue, reason: 'hour $hour');
    }
    expect(greetings, hasLength(24));
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('new chat suggestions fill the composer without sending', (tester) async {
    tester.view.physicalSize = const Size(390, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final transport = _EmptyChatFixture();
    final controller = ChatController(transport);
    await tester.pumpWidget(MaterialApp(theme: DshTheme.light(), home: ChatShell(controller: controller)));
    await tester.pumpAndSettle();
    final cards = find.byWidgetPredicate((widget) => widget.key is ValueKey<String> && (widget.key! as ValueKey<String>).value.startsWith('welcome-suggestion-'));
    expect(cards, findsNWidgets(4));
    expect(find.byKey(const ValueKey('welcome-suggestion-build')), findsNothing);
    expect(find.byKey(const ValueKey('welcome-suggestion-learn')), findsNothing);
    expect(find.byKey(const ValueKey('welcome-brand')), findsOneWidget);
    expect(tester.getRect(find.byKey(const ValueKey('welcome-brand'))).bottom,
        lessThan(tester.getRect(find.byKey(const ValueKey('welcome-greeting'))).top));
    expect(tester.getRect(cards.at(0)).top, tester.getRect(cards.at(1)).top);
    expect(tester.getRect(cards.at(2)).top, greaterThan(tester.getRect(cards.at(0)).top));
    for (var i = 0; i < 4; i++) {
      final card = cards.at(i);
      final icon = find.descendant(of: card, matching: find.byType(Icon));
      final label = find.descendant(of: card, matching: find.byType(Text));
      final frame = tester.getRect(card);
      expect(frame.width, closeTo(frame.height, 0.01), reason: 'card $i');
      expect(tester.getRect(icon).bottom, lessThan(tester.getRect(label).top), reason: 'card $i');
    }
    final input = find.byKey(const ValueKey('composer-input'));
    await tester.enterText(input, '已有草稿');
    await tester.tap(find.byKey(const ValueKey('welcome-suggestion-code')));
    await tester.pump();
    final editable = find.descendant(of: input, matching: find.byType(EditableText));
    final draft = tester.widget<EditableText>(editable).controller.text;
    expect(draft, startsWith('已有草稿\n\n'));
    expect(draft, contains('代码'));
    expect(transport.sent, 0);
    expect(transport.submissions, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });
  testWidgets('welcome squares stay reachable with narrow large text', (tester) async {
    tester.view.physicalSize = const Size(320, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final controller = ChatController(_EmptyChatFixture());
    final selected = <String>[];
    await tester.pumpWidget(MaterialApp(
      theme: DshTheme.light(),
      home: MediaQuery(
        data: const MediaQueryData(textScaler: TextScaler.linear(1.6)),
        child: Scaffold(body: MessageList(controller: controller, onStarter: selected.add)),
      ),
    ));
    await tester.pump();
    for (final id in ['code', 'chat', 'plan', 'access']) {
      final card = find.byKey(ValueKey('welcome-suggestion-$id'));
      await tester.ensureVisible(card);
      await tester.pump();
      final size = tester.getSize(card);
      expect(size.width, closeTo(size.height, 0.01));
      expect(size.width, greaterThanOrEqualTo(48));
      await tester.tap(card);
      await tester.pump();
    }
    expect(selected, hasLength(4));
    expect(selected[0], contains('代码'));
    expect(selected[1], contains('聊'));
    expect(selected[2], contains('步骤'));
    expect(selected[3], contains('无障碍'));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });
  testWidgets('header scrolls the whole first line before a Host title arrives', (tester) async {
    final controller = ChatController(_LongTitleFixture());
    await tester.pumpWidget(MaterialApp(home: ChatShell(controller: controller)));
    await tester.pump();
    expect(tester.widget<OverflowTitle>(find.byKey(const ValueKey('conversation-title'))).text, _longPrompt);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });
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

    // The header names the conversation, not the product: the fixture's first
    // prompt stands in until the Host reports a real session title.
    final title = tester.widget<OverflowTitle>(
      find.byKey(const ValueKey('conversation-title')),
    );
    expect(title.text, '帮我检查一下项目状态，并说明接下来该做什么。');
    expect(find.text('DSH', findRichText: true), findsNothing);
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
    expect(find.ancestor(
      of: find.byKey(const ValueKey('approval-approve')),
      matching: find.byKey(const ValueKey('conversation-list')),
    ), findsOneWidget);
    final tool = find.text('读取项目状态');
    await tester.scrollUntilVisible(
      tool,
      120,
      scrollable: find
          .descendant(
            of: find.byKey(const ValueKey('conversation-list')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    expect(tool, findsOneWidget);
    expect(find.byType(ExecutionGroupHeader), findsNothing);
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
    expect(find.byKey(const ValueKey('connection-retry')), findsNothing);
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(find.textContaining('已连接'), findsOneWidget);

    transport.emitGap();
    await tester.pump();
    await tester.pump();
    expect(controller.state.needsResync, isFalse);
    expect(controller.state.generation, 3);

    transport.emitError();
    await tester.pump();
    await tester.pump();
    expect(find.text('引擎连接失败'), findsOneWidget);
    await tester.pump(const Duration(seconds: 1));
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
    await tester.pump();
    expect(find.text('排队中 1 条'), findsOneWidget);
    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '第二条排队',
    );
    await tester.pump();
    await tester.ensureVisible(find.byKey(const ValueKey('composer-send')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('composer-send')));
    await tester.pump();
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

    final stopped = find.text('已停止');
    await tester.scrollUntilVisible(
      stopped,
      120,
      scrollable: find
          .descendant(
            of: find.byKey(const ValueKey('conversation-list')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    expect(stopped, findsOneWidget);
    expect(find.byType(ExecutionGroupHeader), findsNothing);
    expect(find.byKey(const ValueKey('approval-card')), findsNothing);
    controller.dispose();
  });
}
