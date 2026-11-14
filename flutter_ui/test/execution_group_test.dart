import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';

class _Controller extends ChatController {
  _Controller() : super(FixtureTransport());
  ChatState view = ChatState.empty().copyWith(
    generation: 1,
    status: ChatStatus.ready,
    messages: const [
      ChatMessage(id: 'turn-1', role: ChatRole.assistant, text: '准备检查项目。'),
    ],
  );
  @override
  ChatState get state => view;
  @override
  Future<void> start() async {}
  void publish(List<ToolRun> tools) {
    view = view.copyWith(tools: tools);
    notifyListeners();
  }
}

const _command = ToolRun(
  id: 'shell',
  name: '执行命令',
  messageId: 'turn-1',
  status: ToolStatus.complete,
  content: null,
);
void main() {
  testWidgets(
    'one collapsed summary lazily reveals tools and keeps their full detail',
    (tester) async {
      final c = _Controller();
      c.publish([
        const ToolRun(
          id: 'thought',
          name: '思考过程',
          messageId: 'turn-1',
          status: ToolStatus.complete,
          content: null,
        ),
        _command.copyWith(
          content: ToolContent(
            title: '检查',
            command: const CommandResult(
              command: 'flutter analyze',
              stdout: 'COMPLETE-OUTPUT',
              exitCode: 0,
            ),
          ),
        ),
      ]);
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: ChatShell(controller: c),
        ),
      );
      await tester.pumpAndSettle();
      final toggle = find.byKey(
        const ValueKey('execution-toggle-message-turn-1'),
      );
      expect(toggle, findsOneWidget);
      expect(find.textContaining('flutter analyze'), findsNothing);
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(find.textContaining('flutter analyze'), findsOneWidget);
      expect(find.text('执行命令'), findsNothing);
      final command = find.byKey(const ValueKey('process-command-shell'));
      await tester.ensureVisible(command);
      await tester.tap(command);
      await tester.pumpAndSettle();
      expect(find.text('COMPLETE-OUTPUT'), findsOneWidget);
      await tester.ensureVisible(toggle);
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(find.text('COMPLETE-OUTPUT'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
      c.dispose();
    },
  );
  testWidgets(
    'tool updates preserve manual expansion and show failure in collapsed summary',
    (tester) async {
      final c = _Controller();
      c.publish([_command]);
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: ChatShell(controller: c),
        ),
      );
      await tester.pumpAndSettle();
      final toggle = find.byKey(
        const ValueKey('execution-toggle-message-turn-1'),
      );
      expect(toggle, findsOneWidget);
      expect(tester.getSize(toggle).height, greaterThanOrEqualTo(48));
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('tool-card-shell')), findsOneWidget);
      c.publish([
        _command.copyWith(status: ToolStatus.failed, detail: '真实失败正文'),
      ]);
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('tool-card-shell')), findsOneWidget);
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(find.textContaining('1项失败'), findsOneWidget);
      expect(find.byKey(const ValueKey('tool-card-shell')), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
      c.dispose();
    },
  );
}
