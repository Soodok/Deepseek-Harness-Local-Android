import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart' hide StepState;
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/ui/composer_dock.dart';

const _planToggle = ValueKey('dock-plan-toggle');
const _questionToggle = ValueKey('dock-question-toggle');
const _approvalToggle = ValueKey('dock-approval-toggle');

class _IdleTransport implements DshTransport {
  @override
  Stream<ChatEvent> get events => const Stream.empty();
  @override
  bool get isFixture => true;
  @override
  Future<void> connect() async {}
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
  Future<void> close() async {}
}

// The UI owns timing and collapse state; reducer/transport behavior is tested
// separately. Publishing immutable snapshots also models history pagination.
class _DockController extends ChatController {
  _DockController() : super(_IdleTransport());

  ChatState _view = ChatState.empty().copyWith(
    generation: 1,
    status: ChatStatus.ready,
  );
  bool busy = false;
  final answers = <String>[];
  final approvals = <String>[];
  var stops = 0;

  @override
  ChatState get state => _view;

  void publish(ChatState next) {
    _view = next;
    notifyListeners();
  }

  void plan({String id = 'plan-a', int? generation, bool completed = false}) {
    publish(
      state.copyWith(
        generation: generation,
        tools: [_plan(id: id, completed: completed)],
      ),
    );
  }

  @override
  bool actionBusy(String requestId) => busy;

  @override
  Future<void> answer(String requestId, String answer) async {
    answers.add('$requestId:$answer');
    busy = true;
    notifyListeners();
  }

  @override
  Future<void> approve(String requestId) async {
    approvals.add(requestId);
    busy = true;
    notifyListeners();
  }

  @override
  Future<void> stop() async {
    stops++;
    busy = true;
    notifyListeners();
  }
}

ToolRun _plan({String id = 'plan-a', bool completed = false}) => ToolRun(
  id: id,
  name: 'todo_write',
  content: ToolContent(
    title: '项目检查',
    steps: [
      const PlanStep(id: 'read', title: '读取现有配置', status: StepState.completed),
      PlanStep(
        id: 'verify',
        title: '检查聊天与工具权限',
        status: completed ? StepState.completed : StepState.running,
      ),
    ],
  ),
);

const _question = ActionRequest(
  requestId: 'question-a',
  title: '接下来检查哪些内容？',
  detail: '',
  choices: ['聊天', '工具权限'],
  multiSelect: true,
);

Future<void> _pumpDock(
  WidgetTester tester,
  _DockController controller, {
  Size size = const Size(800, 600),
  bool reduceMotion = false,
  double keyboardHeight = 0,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(
          disableAnimations: reduceMotion,
          viewInsets: EdgeInsets.only(bottom: keyboardHeight),
        ),
        child: child!,
      ),
      home: Scaffold(
        body: LayoutBuilder(
          builder: (context, viewport) => Column(
            children: [
              const Expanded(child: SizedBox()),
              ComposerDock(
                controller: controller,
                availableHeight: viewport.maxHeight,
              ),
              const SizedBox(height: 96),
            ],
          ),
        ),
      ),
    ),
  );
  await tester.pump();
}

double _planHeight(WidgetTester tester) =>
    tester.getSize(find.byKey(const ValueKey('dock-plan'))).height;

Future<void> _tapVisible(WidgetTester tester, Key key) async {
  final target = find.byKey(key);
  await tester.ensureVisible(target);
  await tester.pump();
  await tester.tap(target);
  await tester.pump();
}

void main() {
  testWidgets(
    'replayed question restores only an identical stable payload draft',
    (tester) async {
      final c = _DockController();
      const first = ActionRequest(
        requestId: 'old',
        draftKey: 'parent:event:payload-A',
        title: 'Choose',
        detail: '',
        choices: ['Alpha', 'Beta'],
        multiSelect: true,
      );
      c.publish(c.state.copyWith(question: first));
      await _pumpDock(tester, c);
      await tester.tap(find.byKey(const ValueKey('question-choice-0')));
      await tester.enterText(
        find.byKey(const ValueKey('question-input')),
        '保留文字',
      );
      await tester.pump();
      c.publish(c.state.copyWith(generation: 2, clearQuestion: true));
      await tester.pumpAndSettle();
      c.publish(
        c.state.copyWith(
          question: const ActionRequest(
            requestId: 'replayed',
            draftKey: 'parent:event:payload-A',
            title: 'Choose',
            detail: '',
            choices: ['Alpha', 'Beta'],
            multiSelect: true,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('question-input')))
            .controller!
            .text,
        '保留文字',
      );
      expect(c.answers, isEmpty);
      await tester.tap(find.byKey(const ValueKey('question-submit')));
      expect(c.answers.single, contains('replayed:Alpha'));
      expect(c.answers.single, contains('保留文字'));
      c.busy = false;
      c.publish(
        c.state.copyWith(
          question: const ActionRequest(
            requestId: 'changed',
            draftKey: 'parent:event:payload-B',
            title: 'Choose changed',
            detail: '',
            choices: ['Alpha', 'Beta'],
            multiSelect: true,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('question-input')))
            .controller!
            .text,
        isEmpty,
      );
      await tester.pumpWidget(const SizedBox.shrink());
      c.dispose();
    },
  );

  testWidgets('empty state takes no space', (tester) async {
    final c = _DockController();
    addTearDown(c.dispose);
    await _pumpDock(tester, c);
    expect(find.byKey(const ValueKey('composer-dock')), findsNothing);
    expect(tester.getSize(find.byType(ComposerDock)).height, 0);
  });

  testWidgets('plan holds for two seconds then contracts over 260ms', (
    tester,
  ) async {
    final c = _DockController()..plan();
    addTearDown(c.dispose);
    await _pumpDock(tester, c);
    final expanded = _planHeight(tester);
    expect(expanded, greaterThan(48));
    expect(find.text('1/2 已完成'), findsOneWidget);
    expect(find.textContaining('正在进行'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 1999));
    expect(_planHeight(tester), expanded);
    await tester.pump(const Duration(milliseconds: 1));
    await tester.pump(const Duration(milliseconds: 130));
    expect(_planHeight(tester), greaterThan(48));
    expect(_planHeight(tester), lessThan(expanded));
    await tester.pump(const Duration(milliseconds: 130));
    expect(_planHeight(tester), closeTo(48, 0.1));
  });

  testWidgets('same plan status updates do not restart reading or reopen', (
    tester,
  ) async {
    final c = _DockController()..plan();
    addTearDown(c.dispose);
    await _pumpDock(tester, c);
    await tester.pump(const Duration(milliseconds: 1500));
    c.plan(completed: true);
    await tester.pump();
    expect(find.text('2/2 已完成'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump(const Duration(milliseconds: 260));
    expect(_planHeight(tester), closeTo(48, 0.1));
    c.plan();
    await tester.pump();
    await tester.pump(const Duration(seconds: 3));
    expect(_planHeight(tester), closeTo(48, 0.1));
  });

  testWidgets('manual expansion cancels automatic collapse until a new plan', (
    tester,
  ) async {
    final c = _DockController()..plan();
    addTearDown(c.dispose);
    await _pumpDock(tester, c);
    await tester.pump(const Duration(milliseconds: 500));
    await _tapVisible(tester, _planToggle);
    await tester.pump(const Duration(milliseconds: 260));
    expect(_planHeight(tester), closeTo(48, 0.1));
    await _tapVisible(tester, _planToggle);
    await tester.pump(const Duration(milliseconds: 260));
    await tester.pump(const Duration(seconds: 4));
    c.plan(completed: true);
    await tester.pump();
    expect(_planHeight(tester), greaterThan(48));
  });

  testWidgets(
    'new plan id and new generation each receive their own reading time',
    (tester) async {
      final c = _DockController()..plan();
      addTearDown(c.dispose);
      await _pumpDock(tester, c);
      await tester.pump(const Duration(seconds: 2));
      await tester.pump(const Duration(milliseconds: 260));
      c.plan(id: 'plan-b');
      await tester.pump();
      expect(_planHeight(tester), greaterThan(48));
      await tester.pump(const Duration(milliseconds: 260));
      expect(_planHeight(tester), greaterThan(48));
      await tester.pump(const Duration(milliseconds: 1739));
      expect(_planHeight(tester), greaterThan(48));
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump(const Duration(milliseconds: 260));
      expect(_planHeight(tester), closeTo(48, 0.1));
      c.plan(id: 'plan-b', generation: 2);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 260));
      expect(_planHeight(tester), greaterThan(48));
      await tester.pump(const Duration(milliseconds: 1740));
      await tester.pump(const Duration(milliseconds: 260));
      expect(_planHeight(tester), closeTo(48, 0.1));
    },
  );

  testWidgets(
    'latest plan survives ordinary tools and history page replacement',
    (tester) async {
      final c = _DockController();
      c.publish(
        c.state.copyWith(
          tools: [
            _plan(id: 'older'),
            _plan(id: 'latest', completed: true),
            const ToolRun(id: 'shell', name: 'bash'),
          ],
        ),
      );
      addTearDown(c.dispose);
      await _pumpDock(tester, c);
      expect(find.text('2/2 已完成'), findsOneWidget);
      c.publish(
        c.state.copyWith(
          tools: [],
          messages: const [
            ChatMessage(
              id: 'next-page',
              role: ChatRole.assistant,
              text: '后续消息',
            ),
          ],
        ),
      );
      await tester.pump();
      expect(find.byKey(const ValueKey('dock-plan')), findsOneWidget);
      c.publish(c.state.copyWith(tools: [_plan(id: 'older')]));
      await tester.pump();
      expect(find.text('2/2 已完成'), findsOneWidget);
      c.publish(c.state.copyWith(generation: 2, tools: []));
      await tester.pump();
      expect(find.byKey(const ValueKey('composer-dock')), findsNothing);
    },
  );

  testWidgets(
    'question selection and text survive collapse and explicit submit',
    (tester) async {
      final c = _DockController();
      c.publish(c.state.copyWith(question: _question));
      addTearDown(c.dispose);
      await _pumpDock(tester, c, size: const Size(360, 960));
      await _tapVisible(tester, const ValueKey('question-choice-1'));
      await tester.ensureVisible(find.byKey(const ValueKey('question-input')));
      await tester.enterText(
        find.byKey(const ValueKey('question-input')),
        '  保留这份草稿  ',
      );
      await tester.pump();
      expect(c.answers, isEmpty);
      await _tapVisible(tester, _questionToggle);
      await tester.pump(const Duration(milliseconds: 260));
      expect(find.byKey(const ValueKey('question-input')), findsNothing);
      await tester.pump(const Duration(seconds: 4));
      await _tapVisible(tester, _questionToggle);
      await tester.pump(const Duration(milliseconds: 260));
      final input = tester.widget<TextField>(
        find.byKey(const ValueKey('question-input')),
      );
      expect(input.controller!.text, '  保留这份草稿  ');
      expect(c.answers, isEmpty);
      await _tapVisible(tester, const ValueKey('question-submit'));
      expect(c.answers, ['question-a:工具权限\n保留这份草稿']);
      expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('question-input')))
            .enabled,
        isFalse,
      );
      expect(
        tester
            .widget<OutlinedButton>(
              find.byKey(const ValueKey('question-choice-1')),
            )
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<IconButton>(find.byKey(const ValueKey('question-submit')))
            .onPressed,
        isNull,
      );
    },
  );

  testWidgets('request replacement resets draft and starts expanded', (
    tester,
  ) async {
    final c = _DockController();
    c.publish(c.state.copyWith(question: _question));
    addTearDown(c.dispose);
    await _pumpDock(tester, c, size: const Size(360, 960));
    await _tapVisible(tester, const ValueKey('question-choice-0'));
    await tester.ensureVisible(find.byKey(const ValueKey('question-input')));
    await tester.enterText(find.byKey(const ValueKey('question-input')), '旧草稿');
    await _tapVisible(tester, _questionToggle);
    c.publish(
      c.state.copyWith(
        question: const ActionRequest(
          requestId: 'question-b',
          title: '新的问题',
          detail: '',
          choices: ['聊天', '工具权限'],
        ),
      ),
    );
    await tester.pump();
    final input = tester.widget<TextField>(
      find.byKey(const ValueKey('question-input')),
    );
    expect(input.controller!.text, isEmpty);
    expect(
      tester
          .widget<IconButton>(find.byKey(const ValueKey('question-submit')))
          .onPressed,
      isNull,
    );
  });

  testWidgets(
    'pending question stays expanded while only plan auto collapses',
    (tester) async {
      final c = _DockController()..plan();
      c.publish(c.state.copyWith(question: _question));
      addTearDown(c.dispose);
      await _pumpDock(tester, c, size: const Size(360, 960));
      await tester.pump(const Duration(seconds: 3));
      await tester.pump(const Duration(milliseconds: 260));
      expect(find.byKey(const ValueKey('question-input')), findsOneWidget);
      expect(_planHeight(tester), closeTo(48, 0.1));
    },
  );

  testWidgets(
    'approval remains explicit and disables both actions while busy',
    (tester) async {
      final c = _DockController();
      c.publish(
        c.state.copyWith(
          approval: const ActionRequest(
            requestId: 'approval-a',
            title: '允许继续检查？',
            detail: '等待确认',
          ),
        ),
      );
      addTearDown(c.dispose);
      await _pumpDock(tester, c);
      await tester.pump(const Duration(seconds: 3));
      expect(c.approvals, isEmpty);
      await _tapVisible(tester, _approvalToggle);
      await tester.pump(const Duration(milliseconds: 260));
      await _tapVisible(tester, _approvalToggle);
      await tester.pump(const Duration(milliseconds: 260));
      await _tapVisible(tester, const ValueKey('approval-approve'));
      expect(c.approvals, ['approval-a']);
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey('approval-approve')),
            )
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<OutlinedButton>(find.byKey(const ValueKey('approval-stop')))
            .onPressed,
        isNull,
      );
      c.busy = false;
      c.publish(c.state);
      await tester.pump();
      await _tapVisible(tester, const ValueKey('approval-stop'));
      expect(c.stops, 1);
    },
  );

  testWidgets(
    'reduced motion still allows two seconds reading then collapses instantly',
    (tester) async {
      final c = _DockController()..plan();
      addTearDown(c.dispose);
      await _pumpDock(tester, c, reduceMotion: true);
      final expanded = _planHeight(tester);
      await tester.pump(const Duration(milliseconds: 1999));
      expect(_planHeight(tester), expanded);
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump();
      expect(_planHeight(tester), closeTo(48, 0.1));
      await _tapVisible(tester, _planToggle);
      expect(_planHeight(tester), expanded);
      await tester.pump(const Duration(milliseconds: 130));
      expect(_planHeight(tester), expanded);
    },
  );

  testWidgets(
    '360dp small screen with keyboard bounds scrolling and 48dp controls',
    (tester) async {
      final c = _DockController();
      c.publish(
        c.state.copyWith(
          question: _question,
          tools: [
            ToolRun(
              id: 'long-plan',
              name: 'todo_write',
              content: ToolContent(
                title: '长计划',
                steps: List.generate(
                  12,
                  (i) => PlanStep(
                    id: 'step-$i',
                    title: '检查第$i项：保留完整的长标题，窄屏应当换行显示，不能省略任务正文',
                    status: i == 0 ? StepState.running : StepState.pending,
                  ),
                ),
              ),
            ),
          ],
        ),
      );
      addTearDown(c.dispose);
      await _pumpDock(
        tester,
        c,
        size: const Size(360, 640),
        keyboardHeight: 260,
      );
      final dock = tester.getRect(find.byKey(const ValueKey('composer-dock')));
      expect(dock.height, lessThanOrEqualTo((640 - 260) * .42 + .1));
      expect(dock.left, greaterThanOrEqualTo(0));
      expect(dock.right, lessThanOrEqualTo(360));
      expect(tester.takeException(), isNull);
      for (final key in [
        _questionToggle,
        _planToggle,
        const ValueKey('question-choice-0'),
        const ValueKey('question-input'),
        const ValueKey('question-submit'),
      ]) {
        final target = find.byKey(key);
        await tester.ensureVisible(target);
        await tester.pump();
        final rect = tester.getRect(target);
        expect(rect.width, greaterThanOrEqualTo(48), reason: '$key');
        expect(rect.height, greaterThanOrEqualTo(48), reason: '$key');
        expect(target.hitTestable(), findsOneWidget, reason: '$key');
        expect(tester.takeException(), isNull);
      }
      final body = tester.getSize(find.byKey(const ValueKey('dock-plan-body')));
      expect(body.height, lessThanOrEqualTo(200));
      final title = tester.widget<Text>(find.textContaining('检查第11项：'));
      expect(title.maxLines, isNull);
      expect(title.softWrap, isNot(false));
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('dispose cancels the plan reading timer', (tester) async {
    final c = _DockController()..plan();
    addTearDown(c.dispose);
    await _pumpDock(tester, c);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 3));
    expect(tester.takeException(), isNull);
  });
}
