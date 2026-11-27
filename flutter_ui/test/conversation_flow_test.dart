import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/ui/composer.dart';
import 'package:dsh_native_chat/src/ui/composer_surface.dart';
import 'package:dsh_native_chat/src/ui/conversation_flow.dart';
import 'package:dsh_native_chat/src/ui/message_list.dart';
import 'package:dsh_native_chat/src/ui/tool_run_card.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart' hide StepState;

class _ViewController extends ChatController {
  _ViewController() : super(FixtureTransport());
  ChatState view = ChatState.empty().copyWith(status: ChatStatus.ready);
  @override
  ChatState get state => view;
}

const _message = ChatMessage(
  id: 'reply',
  role: ChatRole.assistant,
  text: '真实正文',
);
ToolRun _command(String id, {ToolStatus status = ToolStatus.complete}) =>
    ToolRun(
      id: id,
      name: 'bash',
      messageId: 'reply',
      status: status,
      content: ToolContent(
        title: 'bash',
        command: const CommandResult(
          command: 'pwd',
          stdout: 'EXACT OUTPUT',
          exitCode: 0,
        ),
      ),
    );

Widget _app(Widget child) => MaterialApp(
  theme: DshTheme.light(),
  home: Scaffold(body: child),
);

void main() {
  testWidgets(
    'long history opens at a bounded recent page and can visit older pages',
    (tester) async {
      final c = _ViewController();
      addTearDown(c.dispose);
      c.view = c.view.copyWith(
        messages: [
          for (var i = 0; i < 120; i++)
            ChatMessage(
              id: 'entry-$i',
              role: ChatRole.assistant,
              text: 'Entry $i',
            ),
        ],
      );
      await tester.pumpWidget(_app(MessageList(controller: c)));
      final older = find.byKey(const ValueKey('history-older'));
      expect(older, findsOneWidget);
      expect(find.text('Entry 0'), findsNothing);
      await tester.tap(older);
      await tester.pump();
      expect(older, findsOneWidget);
      await tester.tap(older);
      await tester.pump();
      expect(find.text('Entry 0'), findsOneWidget);
      expect(older, findsNothing);
    },
  );

  testWidgets(
    'structured assistant content starts collapsed and copies exactly',
    (tester) async {
      const payload = '{"status":"ok","items":[1,2,3]}';
      String? copied;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == 'Clipboard.setData') {
            copied = (call.arguments as Map)['text'] as String;
          }
          return null;
        },
      );
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform,
          null,
        ),
      );
      await tester.pumpWidget(
        _app(
          const ConversationFlow(
            conversationIdentity: 'json',
            messages: [
              ChatMessage(id: 'json', role: ChatRole.assistant, text: payload),
            ],
          ),
        ),
      );
      expect(find.textContaining('"status"'), findsNothing);
      final toggle = find.byKey(
        const ValueKey('message-structured-toggle-json'),
      );
      expect(tester.getSize(toggle).height, greaterThanOrEqualTo(48));
      expect(tester.getSize(toggle).height, lessThanOrEqualTo(56));
      await tester.tap(
        find.byKey(const ValueKey('message-structured-copy-json')),
      );
      await tester.pump();
      expect(copied, payload);
      await tester.tap(toggle);
      await tester.pump();
      expect(find.text(payload), findsOneWidget);
      await tester.tap(toggle);
      await tester.pump();
      expect(find.text(payload), findsNothing);
    },
  );

  testWidgets(
    'assistant timeline renders chart fences alongside Markdown tables',
    (tester) async {
      const source =
          '| Item | Value |\n| --- | --- |\n| A | 4 |\n\n'
          '\x60\x60\x60chart\ntype: comparison\nA: 4\n\x60\x60\x60\n\n'
          '\x60\x60\x60chart\ntype: unsupported\nUnits: minutes\n\x60\x60\x60\n\n'
          '\x60\x60\x60dart\nfinal answer = 4;\n\x60\x60\x60';
      await tester.pumpWidget(
        _app(
          const ConversationFlow(
            conversationIdentity: 'chart',
            messages: [
              ChatMessage(id: 'chart', role: ChatRole.assistant, text: source),
            ],
            tools: [],
          ),
        ),
      );
      expect(
        find.byKey(const ValueKey('assistant-markdown-table')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('assistant-data-chart-comparison')),
        findsOneWidget,
      );
      expect(find.textContaining('Units: minutes'), findsOneWidget);
      expect(find.text('代码 · dart'), findsOneWidget);
      expect(find.text('代码 · chart'), findsNothing);
    },
  );

  testWidgets('fenced blocks fold independently while prose stays readable', (
    tester,
  ) async {
    final fence = String.fromCharCode(96) * 3;
    final text =
        '解释在前。\n\n' + fence + 'dart\nvoid main() {}\n' + fence + '\n\n说明在后。';
    await tester.pumpWidget(
      _app(
        ConversationFlow(
          conversationIdentity: 'code',
          messages: [
            ChatMessage(id: 'code', role: ChatRole.assistant, text: text),
          ],
        ),
      ),
    );
    expect(find.textContaining('void main'), findsNothing);
    expect(find.text('解释在前。'), findsOneWidget);
    expect(find.text('说明在后。'), findsOneWidget);
    final toggle = find.byKey(const ValueKey('message-code-toggle-code-0'));
    await tester.tap(toggle);
    await tester.pump();
    expect(find.text('void main() {}\n'), findsOneWidget);
    await tester.tap(toggle);
    await tester.pump();
    expect(find.textContaining('void main'), findsNothing);
  });

  testWidgets('inserting execution rows retains an opened code block', (
    tester,
  ) async {
    final fence = String.fromCharCode(96) * 3;
    final messages = [
      _message,
      ChatMessage(
        id: 'code',
        role: ChatRole.assistant,
        text: fence + 'dart\nconst answer = 42;\n' + fence,
      ),
    ];
    Future<void> pump() => tester.pumpWidget(
      _app(
        ConversationFlow(
          conversationIdentity: 'stable',
          messages: messages,
          tools: [_command('one')],
        ),
      ),
    );
    await pump();
    final codeToggle = find.byKey(const ValueKey('message-code-toggle-code-0'));
    await tester.tap(codeToggle);
    await tester.pump();
    expect(find.text('const answer = 42;\n'), findsOneWidget);
    await tester.tap(
      find.byKey(const ValueKey('execution-toggle-message-reply')),
    );
    await tester.pump();
    expect(find.text('const answer = 42;\n'), findsOneWidget);
    await pump();
    expect(find.text('const answer = 42;\n'), findsOneWidget);
    expect(find.byType(BackdropFilter), findsNothing);
  });

  testWidgets('unchanged prose reuses its render widget on tool updates', (
    tester,
  ) async {
    Future<void> pump(ToolStatus status) => tester.pumpWidget(
      _app(
        ConversationFlow(
          conversationIdentity: 'render-cache',
          messages: const [_message],
          tools: [_command('one', status: status)],
        ),
      ),
    );
    await pump(ToolStatus.running);
    final before = tester.widget<SelectableText>(
      find.byType(SelectableText).first,
    );
    await pump(ToolStatus.complete);
    final after = tester.widget<SelectableText>(
      find.byType(SelectableText).first,
    );
    expect(identical(before, after), isTrue);
  });

  testWidgets('a new conversation identity resets message disclosure', (
    tester,
  ) async {
    Future<void> pump(String identity) => tester.pumpWidget(
      _app(
        ConversationFlow(
          conversationIdentity: identity,
          messages: const [
            ChatMessage(id: 'json', role: ChatRole.assistant, text: '{"v":1}'),
          ],
        ),
      ),
    );
    await pump('first');
    await tester.tap(
      find.byKey(const ValueKey('message-structured-toggle-json')),
    );
    await tester.pump();
    expect(find.text('{"v":1}'), findsOneWidget);
    await pump('second');
    expect(find.text('{"v":1}'), findsNothing);
  });

  testWidgets('streaming JSON is folded before its closing brace arrives', (
    tester,
  ) async {
    const partial = '{"payload":"partial';
    await tester.pumpWidget(
      _app(
        const ConversationFlow(
          conversationIdentity: 'partial-json',
          messages: [
            ChatMessage(
              id: 'partial-json',
              role: ChatRole.assistant,
              isStreaming: true,
              text: partial,
            ),
          ],
        ),
      ),
    );
    expect(find.textContaining('"payload"'), findsNothing);
    expect(
      find.byKey(const ValueKey('message-structured-toggle-partial-json')),
      findsOneWidget,
    );
  });

  testWidgets(
    'long fenced output keeps full access without a tall inline body',
    (tester) async {
      final fence = String.fromCharCode(96) * 3;
      final code =
          List.generate(300, (i) => 'line $i').join('\n') + '\nTAIL_SENTINEL';
      await tester.pumpWidget(
        _app(
          ConversationFlow(
            conversationIdentity: 'long-code',
            messages: [
              ChatMessage(
                id: 'long-code',
                role: ChatRole.assistant,
                text: fence + 'txt\n' + code + '\n' + fence,
              ),
            ],
          ),
        ),
      );
      final toggle = find.byKey(
        const ValueKey('message-code-toggle-long-code-0'),
      );
      await tester.tap(toggle);
      await tester.pump();
      final preview = tester.widget<SelectableText>(
        find.byType(SelectableText).first,
      );
      expect(preview.maxLines, 12);
      expect(preview.data!.length, lessThanOrEqualTo(1200));
      expect(preview.data!.split('\n').length, lessThanOrEqualTo(12));
      expect(find.textContaining('TAIL_SENTINEL'), findsNothing);
      await tester.tap(
        find.byKey(const ValueKey('message-code-full-long-code-0')),
      );
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.textContaining('TAIL_SENTINEL'),
        600,
        scrollable: find
            .descendant(
              of: find.byType(ListView).last,
              matching: find.byType(Scrollable),
            )
            .first,
      );
      expect(find.textContaining('TAIL_SENTINEL'), findsOneWidget);
    },
  );

  testWidgets('unclosed code keeps disclosure across streaming updates', (
    tester,
  ) async {
    final fence = String.fromCharCode(96) * 3;
    Future<void> pump(String code) => tester.pumpWidget(
      _app(
        ConversationFlow(
          conversationIdentity: 'stream-code',
          messages: [
            ChatMessage(
              id: 'stream-code',
              role: ChatRole.assistant,
              isStreaming: true,
              text: fence + 'dart\n' + code,
            ),
          ],
        ),
      ),
    );
    await pump('const a = 1;');
    expect(find.textContaining('const a'), findsNothing);
    await tester.tap(
      find.byKey(const ValueKey('message-code-toggle-stream-code-0')),
    );
    await tester.pump();
    await pump('const a = 12;\n' + fence);
    expect(find.text('const a = 12;\n'), findsOneWidget);
  });

  testWidgets('bracketed prose and user code stay ordinary selectable text', (
    tester,
  ) async {
    final fence = String.fromCharCode(96) * 3;
    await tester.pumpWidget(
      _app(
        ConversationFlow(
          conversationIdentity: 'prose',
          messages: [
            const ChatMessage(
              id: 'prose',
              role: ChatRole.assistant,
              text: '[请先看说明]',
            ),
            ChatMessage(
              id: 'user-code',
              role: ChatRole.user,
              text: fence + 'dart\nprint(1);\n' + fence,
            ),
          ],
        ),
      ),
    );
    expect(find.text('[请先看说明]'), findsOneWidget);
    expect(find.textContaining('print(1)'), findsOneWidget);
  });

  testWidgets(
    'message disclosure fits 360dp at 2x text and reports clipboard failure',
    (tester) async {
      tester.view.physicalSize = const Size(360, 740);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == 'Clipboard.setData')
            throw PlatformException(code: 'denied');
          return null;
        },
      );
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform,
          null,
        ),
      );
      await tester.pumpWidget(
        _app(
          const ConversationFlow(
            conversationIdentity: 'large-text',
            messages: [
              ChatMessage(
                id: 'large-text',
                role: ChatRole.assistant,
                text: '{"ok":true}',
              ),
            ],
          ),
        ),
      );
      final copy = find.byKey(
        const ValueKey('message-structured-copy-large-text'),
      );
      expect(tester.getSize(copy).width, greaterThanOrEqualTo(48));
      expect(tester.getSize(copy).height, greaterThanOrEqualTo(48));
      await tester.tap(copy);
      await tester.pump();
      expect(find.text('复制失败，请重试。'), findsOneWidget);
      expect(find.text('已复制'), findsNothing);
      await tester.tap(
        find.byKey(const ValueKey('message-structured-toggle-large-text')),
      );
      await tester.pump();
      expect(find.text('{"ok":true}'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('main list and composer use the shared flow and input surface', (
    tester,
  ) async {
    final c = _ViewController();
    c.view = c.view.copyWith(messages: const [_message]);
    await tester.pumpWidget(
      _app(
        Column(
          children: [
            Expanded(child: MessageList(controller: c)),
            Composer(controller: c),
          ],
        ),
      ),
    );
    expect(find.byType(ConversationFlow), findsOneWidget);
    expect(find.byType(ConversationMessage), findsOneWidget);
    expect(find.byType(ComposerSurface), findsOneWidget);
    // The shared composer wraps the send/stop slot in one action button.
    expect(find.byType(ComposerActionButton), findsOneWidget);
    expect(find.byKey(const ValueKey('conversation-list')), findsOneWidget);
    expect(find.byKey(const ValueKey('composer-input')), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });

  testWidgets(
    'expanded execution records stay lazy and updates preserve disclosure',
    (tester) async {
      final identity = Object();
      Future<void> pump({ToolStatus status = ToolStatus.complete}) async {
        await tester.pumpWidget(
          _app(
            ConversationFlow(
              conversationIdentity: identity,
              messages: const [_message],
              tools: [
                for (var i = 0; i < 200; i++)
                  _command('call-$i', status: status),
              ],
            ),
          ),
        );
        await tester.pump();
      }

      await pump();
      const toggle = ValueKey('execution-toggle-message-reply');
      expect(find.byType(ToolRunCard), findsNothing);
      await tester.tap(find.byKey(toggle));
      await tester.pump();
      expect(find.byType(ToolRunCard), findsWidgets);
      expect(find.byType(ToolRunCard).evaluate().length, lessThan(20));
      expect(find.byKey(const ValueKey('tool-card-call-199')), findsNothing);
      await pump(status: ToolStatus.failed);
      expect(find.byType(ToolRunCard), findsWidgets);
      await tester.tap(find.byKey(toggle));
      await tester.pump();
      expect(find.textContaining('200项失败'), findsOneWidget);
      expect(find.byType(ToolRunCard), findsNothing);
      // A new child/controller identity must not inherit a prior disclosure.
      await tester.pumpWidget(
        _app(
          ConversationFlow(
            conversationIdentity: Object(),
            messages: const [_message],
            tools: [_command('other')],
          ),
        ),
      );
      expect(find.byType(ToolRunCard), findsNothing);
    },
  );

  testWidgets('child flow keeps structured plans without a composer dock', (
    tester,
  ) async {
    await tester.pumpWidget(
      _app(
        ConversationFlow(
          conversationIdentity: 'child',
          messages: const [_message],
          tools: [
            ToolRun(
              id: 'plan',
              name: '计划',
              messageId: 'reply',
              status: ToolStatus.complete,
              content: ToolContent(
                title: '真实计划',
                steps: const [
                  PlanStep(
                    id: 'step',
                    title: '核对证据',
                    status: StepState.completed,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
    await tester.tap(
      find.byKey(const ValueKey('execution-toggle-message-reply')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('tool-card-plan')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('tool-plan')));
    await tester.pumpAndSettle();
    expect(find.text('核对证据'), findsOneWidget);
  });
}
