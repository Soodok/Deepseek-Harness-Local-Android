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
    expect(find.byType(ComposerSendButton), findsOneWidget);
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
