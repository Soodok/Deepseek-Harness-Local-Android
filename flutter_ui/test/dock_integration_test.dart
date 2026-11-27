import 'package:material_ui/material_ui.dart' hide StepState;
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/action_cards.dart';

class PlanHistoryController extends ChatController {
  PlanHistoryController() : super(FixtureTransport());
  ChatState view = ChatState.empty().copyWith(
    generation: 1,
    status: ChatStatus.ready,
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

ToolRun historyPlan(String id, String tail) => ToolRun(
  id: id,
  name: id,
  status: ToolStatus.complete,
  content: ToolContent(
    title: '计划',
    steps: [PlanStep(id: 'step', title: tail, status: StepState.completed)],
  ),
);
void main() {
  testWidgets('replaced active plan keeps the historical steps reachable', (
    tester,
  ) async {
    final c = PlanHistoryController();
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(controller: c),
      ),
    );
    await tester.pumpAndSettle();
    final old = historyPlan('old-plan', 'OLD-TAIL');
    c.publish([old]);
    await tester.pumpAndSettle();
    c.publish([old, historyPlan('new-plan', 'NEW-TAIL')]);
    await tester.pumpAndSettle();
    final trace = find.byKey(
      const ValueKey('execution-toggle-unbound-old-plan'),
    );
    await tester.tap(trace);
    await tester.pumpAndSettle();
    final oldCard = find.byKey(const ValueKey('tool-card-old-plan'));
    expect(oldCard, findsOneWidget);
    await tester.ensureVisible(oldCard);
    await tester.tap(oldCard);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('tool-plan')));
    await tester.pumpAndSettle();
    expect(find.text('OLD-TAIL'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    c.dispose();
  });

  testWidgets(
    'full shell remains usable with keyboard, large text and a long draft',
    (tester) async {
      tester.view.physicalSize = const Size(360, 640);
      tester.view.devicePixelRatio = 1;
      tester.view.viewInsets = const FakeViewPadding(bottom: 260);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetViewInsets);
      final f = FixtureTransport();
      final c = ChatController(f);
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: const TextScaler.linear(2)),
            child: child!,
          ),
          home: ChatShell(controller: c),
        ),
      );
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      f.emitShowcase();
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      await tester.enterText(
        find.byKey(const ValueKey('composer-input')),
        '保留输入草稿\n第二行\n第三行\n第四行\n第五行\n第六行',
      );
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(tester.takeException(), isNull);
      // 断链不再要求用户先找到「重试」按钮：横幅立刻出现，后台退避重连
      // 成功后自己消失。这里两条都验——先是横幅可见可点，再是不点它也会恢复。
      for (final fault in [f.emitError, f.emitDisconnect]) {
        fault();
        for (var i = 0; i < 2; i++) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expect(tester.takeException(), isNull);
        expect(find.byKey(const ValueKey('connection-retry')), findsNothing);
        for (var i = 0; i < 24; i++) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expect(tester.takeException(), isNull);
        expect(find.byKey(const ValueKey('connection-retry')), findsNothing);
        expect(c.state.status, ChatStatus.ready);
      }
      await tester.ensureVisible(find.byKey(const ValueKey('composer-send')));
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(
        find.byKey(const ValueKey('composer-send')).hitTestable(),
        findsOneWidget,
      );
      await tester.pumpWidget(const SizedBox.shrink());
      c.dispose();
    },
  );

  testWidgets('active question lives above composer outside the history list', (
    tester,
  ) async {
    final f = FixtureTransport();
    final c = ChatController(f);
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(controller: c),
      ),
    );
    await tester.pumpAndSettle();
    f.emitShowcase();
    await tester.pumpAndSettle();
    final question = find.byType(QuestionCard);
    expect(question, findsOneWidget);
    expect(
      find.ancestor(
        of: question,
        matching: find.byKey(const ValueKey('conversation-list')),
      ),
      findsNothing,
    );
    expect(
      tester.getBottomLeft(find.byKey(const ValueKey('composer-dock'))).dy,
      lessThan(
        tester.getTopLeft(find.byKey(const ValueKey('composer-input'))).dy,
      ),
    );
    expect(find.byKey(const ValueKey('subagent-selector')), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    c.dispose();
  });
}
