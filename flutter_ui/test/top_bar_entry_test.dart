import 'dart:async';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/subagent_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// Publishes a real subagent catalog, then can withdraw it again, so the top
/// bar entry is checked against observed state rather than a streaming flag.
class _CatalogTransport implements DshTransport {
  final _events = EventStreamController();
  int _sequence = 0;

  @override
  Stream<ChatEvent> get events => _events.stream;
  @override
  bool get isFixture => false;

  @override
  Future<void> connect() async {
    _events.add(
      const ChatEvent.snapshot(generation: 1, sequence: 0, messages: []),
    );
    _sequence = 1;
  }

  void publish(List<AgentTask> agents) {
    _events.add(
      ChatEvent.toolStarted(
        generation: 1,
        sequence: _sequence,
        toolId: 'catalog-' + _sequence.toString(),
        name: '子智能体',
        content: ToolContent(title: '目录', agents: agents),
      ),
    );
    _sequence++;
  }

  /// A replacement snapshot is what a reconnect actually delivers: the tool
  /// list is rebuilt from the Host, which can be empty again.
  void replaceWithSnapshot() {
    _events.add(
      ChatEvent.snapshot(generation: 2, sequence: 0, messages: const []),
    );
  }

  @override
  Future<void> reconnect() async {}
  @override
  Future<void> sendPrompt(String text) async {}
  @override
  Future<void> stop() async {}
  @override
  Future<void> approve(String requestId) async {}
  @override
  Future<void> answer(String requestId, String answer) async {}
  @override
  Future<void> close() => _events.close();
}

const _child = SubagentAddress(
  parentSessionId: 'p',
  childSessionId: 'c',
  mode: SubagentMode.continuable,
);

const _agent = AgentTask(
  id: 'review',
  name: '代码检查',
  task: '检查边界',
  status: AgentState.running,
  address: _child,
);

Future<void> _pump(WidgetTester tester, ChatController controller) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: ChatShell(controller: controller),
    ),
  );
  await tester.pump();
}

void main() {
  testWidgets('no child observed yet: the entry is not laid out at all', (
    tester,
  ) async {
    final controller = ChatController(_CatalogTransport());
    await _pump(tester, controller);
    expect(find.byKey(const ValueKey('subagent-selector')), findsNothing);
    // The leading slot is a drawer menu, never a back arrow for a main chat.
    expect(find.byKey(const ValueKey('session-menu')), findsOneWidget);
    expect(find.byIcon(Icons.arrow_back_rounded), findsNothing);
    expect(find.byTooltip('返回会话列表'), findsNothing);
    controller.dispose();
  });

  testWidgets('an observed child publishes the entry, reconnecting to an empty '
      'catalog withdraws it again', (tester) async {
    final transport = _CatalogTransport();
    final controller = ChatController(transport);
    await _pump(tester, controller);
    expect(find.byKey(const ValueKey('subagent-selector')), findsNothing);

    transport.publish(const [_agent]);
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('subagent-selector')), findsOneWidget);

    transport.replaceWithSnapshot();
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('subagent-selector')), findsNothing);
    controller.dispose();
  });

  testWidgets('the menu entry opens the real drawer with the session list', (
    tester,
  ) async {
    var newSessions = 0, engineSessions = 0;
    final controller = ChatController(_CatalogTransport());
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(
          controller: controller,
          onNewSession: () => newSessions++,
          onOpenEngineSessions: () => engineSessions++,
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byTooltip('打开会话抽屉'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 350));
    expect(find.byType(Drawer), findsOneWidget);
    expect(find.text('新建会话'), findsOneWidget);
    await tester.tap(find.text('会话列表'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 350));
    expect(engineSessions, 1);
    controller.dispose();
  });

  testWidgets('the child entry opens the catalog sheet, not a fake session', (
    tester,
  ) async {
    final transport = _CatalogTransport();
    final controller = ChatController(transport);
    await _pump(tester, controller);
    transport.publish(const [_agent]);
    await tester.pump();
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('subagent-selector')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 350));
    expect(find.byKey(const ValueKey('subagent-panel')), findsOneWidget);
    // This transport has no subagent capability, so opening is refused with a
    // reason instead of pushing a conversation that cannot exist.
    expect(find.textContaining('不可用'), findsWidgets);
    controller.dispose();
  });
}
