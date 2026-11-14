import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/action_cards.dart';

const _inputKey = ValueKey('question-input');
const _submitKey = ValueKey('question-submit');

Future<void> _pumpCard(WidgetTester tester, Widget card) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: Scaffold(
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: card,
        ),
      ),
    ),
  );
  await tester.pump();
}

void main() {
  testWidgets('free text is trimmed and an empty answer cannot submit', (
    tester,
  ) async {
    final answers = <String>[];
    await _pumpCard(
      tester,
      QuestionCard(
        request: const ActionRequest(
          requestId: 'free',
          title: '你希望怎么做？',
          detail: '',
        ),
        onSubmit: answers.add,
      ),
    );
    expect(find.byKey(const ValueKey('question-card')), findsOneWidget);
    expect(tester.widget<IconButton>(find.byKey(_submitKey)).onPressed, isNull);
    await tester.enterText(find.byKey(_inputKey), '  \n  ');
    await tester.pump();
    await tester.tap(find.byKey(_submitKey));
    expect(answers, isEmpty);
    expect(tester.widget<IconButton>(find.byKey(_submitKey)).onPressed, isNull);
    await tester.enterText(find.byKey(_inputKey), '  请先检查权限  ');
    await tester.pump();
    await tester.tap(find.byKey(_submitKey));
    expect(answers, ['请先检查权限']);
  });

  testWidgets(
    'single choice replaces the selection and includes optional text',
    (tester) async {
      final answers = <String>[];
      await _pumpCard(
        tester,
        QuestionCard(
          request: const ActionRequest(
            requestId: 'single',
            title: '选一种检查方式',
            detail: '',
            choices: ['快速检查', '完整审查'],
          ),
          onSubmit: answers.add,
        ),
      );
      await tester.tap(find.byKey(const ValueKey('question-choice-0')));
      await tester.pump();
      expect(answers, isEmpty);
      await tester.tap(find.byKey(const ValueKey('question-choice-1')));
      await tester.pump();
      await tester.enterText(find.byKey(_inputKey), '  顺便检查权限  ');
      await tester.pump();
      await tester.testTextInput.receiveAction(TextInputAction.done);
      expect(answers, isEmpty);
      await tester.tap(find.byKey(_submitKey));
      expect(answers, ['完整审查\n顺便检查权限']);
    },
  );

  testWidgets('an option can be submitted without any free text', (
    tester,
  ) async {
    final answers = <String>[];
    await _pumpCard(
      tester,
      QuestionCard(
        request: const ActionRequest(
          requestId: 'option-only',
          title: '下一步做什么？',
          detail: '',
          choices: ['先检查', '直接实现'],
        ),
        onSubmit: answers.add,
      ),
    );
    await tester.tap(find.byKey(const ValueKey('question-choice-0')));
    await tester.pump();
    expect(answers, isEmpty);
    await tester.tap(find.byKey(_submitKey));
    expect(answers, ['先检查']);
  });

  testWidgets('multiple choices toggle and submit labels in request order', (
    tester,
  ) async {
    final answers = <String>[];
    await _pumpCard(
      tester,
      QuestionCard(
        request: const ActionRequest(
          requestId: 'multiple',
          title: '需要检查哪些？',
          detail: '',
          choices: ['聊天', '工具', '附件'],
          multiSelect: true,
        ),
        onSubmit: answers.add,
      ),
    );
    for (final index in [2, 0, 1, 1]) {
      await tester.tap(find.byKey(ValueKey('question-choice-$index')));
      await tester.pump();
    }
    expect(answers, isEmpty);
    await tester.enterText(find.byKey(_inputKey), '  保留已有草稿  ');
    await tester.pump();
    await tester.tap(find.byKey(_submitKey));
    expect(answers, ['聊天\n附件\n保留已有草稿']);
  });

  testWidgets('changing the request ID clears selection and draft only then', (
    tester,
  ) async {
    final answers = <String>[];
    QuestionCard question(String id) => QuestionCard(
      request: ActionRequest(
        requestId: id,
        title: '选择检查方式',
        detail: '',
        choices: const ['快速检查', '完整审查'],
      ),
      onSubmit: answers.add,
    );
    await _pumpCard(tester, question('first'));
    await tester.tap(find.byKey(const ValueKey('question-choice-0')));
    await tester.enterText(find.byKey(_inputKey), '上一题草稿');
    await tester.pump();
    await _pumpCard(tester, question('first'));
    expect(
      tester.widget<TextField>(find.byKey(_inputKey)).controller!.text,
      '上一题草稿',
    );
    expect(
      tester.widget<IconButton>(find.byKey(_submitKey)).onPressed,
      isNotNull,
    );
    await _pumpCard(tester, question('second'));
    expect(
      tester.widget<TextField>(find.byKey(_inputKey)).controller!.text,
      isEmpty,
    );
    expect(tester.widget<IconButton>(find.byKey(_submitKey)).onPressed, isNull);
    await tester.tap(find.byKey(_submitKey));
    expect(answers, isEmpty);
    await tester.tap(find.byKey(const ValueKey('question-choice-1')));
    await tester.pump();
    await tester.tap(find.byKey(_submitKey));
    expect(answers, ['完整审查']);
  });

  testWidgets(
    'busy disables all question controls and preserves an unsent draft',
    (tester) async {
      final answers = <String>[];
      QuestionCard question({bool busy = false}) => QuestionCard(
        request: const ActionRequest(
          requestId: 'busy',
          title: '选择检查方式',
          detail: '',
          choices: ['快速检查', '完整审查'],
        ),
        busy: busy,
        onSubmit: answers.add,
      );
      await _pumpCard(tester, question());
      await tester.tap(find.byKey(const ValueKey('question-choice-0')));
      await tester.enterText(find.byKey(_inputKey), '保留草稿');
      await tester.pump();
      final priorSubmit = tester
          .widget<IconButton>(find.byKey(_submitKey))
          .onPressed!;
      await _pumpCard(tester, question(busy: true));
      expect(tester.widget<TextField>(find.byKey(_inputKey)).enabled, isFalse);
      expect(
        tester.widget<IconButton>(find.byKey(_submitKey)).onPressed,
        isNull,
      );
      for (final index in [0, 1]) {
        final choice = find.byKey(ValueKey('question-choice-$index'));
        expect(tester.widget<OutlinedButton>(choice).onPressed, isNull);
        await tester.tap(choice);
      }
      priorSubmit();
      await tester.tap(find.byKey(_submitKey));
      expect(answers, isEmpty);
      await _pumpCard(tester, question());
      await tester.tap(find.byKey(_submitKey));
      expect(answers, ['快速检查\n保留草稿']);
    },
  );

  testWidgets(
    'approval keeps its callbacks and disables both buttons while busy',
    (tester) async {
      var approvals = 0;
      var stops = 0;
      ApprovalCard approval({bool busy = false}) => ApprovalCard(
        request: const ActionRequest(
          requestId: 'approval',
          title: '允许检查项目吗？',
          detail: '',
        ),
        busy: busy,
        onApprove: () => approvals++,
        onStop: () => stops++,
      );
      await _pumpCard(tester, approval(busy: true));
      expect(find.byKey(const ValueKey('approval-card')), findsOneWidget);
      final approve = find.byKey(const ValueKey('approval-approve'));
      final stop = find.byKey(const ValueKey('approval-stop'));
      expect(tester.widget<FilledButton>(approve).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(stop).onPressed, isNull);
      await tester.tap(approve);
      await tester.tap(stop);
      expect(approvals, 0);
      expect(stops, 0);
      await _pumpCard(tester, approval());
      await tester.tap(approve);
      await tester.tap(stop);
      expect(approvals, 1);
      expect(stops, 1);
    },
  );

  testWidgets('production theme keeps 48dp targets within a 360dp viewport', (
    tester,
  ) async {
    const viewport = Size(360, 960);
    tester.view.physicalSize = viewport;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await _pumpCard(
      tester,
      Column(
        children: [
          ApprovalCard(
            request: const ActionRequest(
              requestId: 'narrow-approval',
              title: '在保留全部已有工作内容的前提下继续检查项目？',
              detail: '检查完成后会展示结果，再由你决定下一步。',
            ),
            onApprove: () {},
            onStop: () {},
          ),
          QuestionCard(
            request: const ActionRequest(
              requestId: 'narrow-question',
              title: '你想先检查哪些内容？',
              detail: '',
              choices: ['先检查聊天、排队与工具权限，并保留未发送的草稿', '再检查附件与失败重试，完整展示需要我决定的结果'],
              multiSelect: true,
            ),
            onSubmit: (_) {},
          ),
        ],
      ),
    );
    expect(tester.takeException(), isNull);
    for (final key in [
      'approval-approve',
      'approval-stop',
      'question-choice-0',
      'question-choice-1',
      'question-input',
      'question-submit',
    ]) {
      final finder = find.byKey(ValueKey(key));
      await tester.ensureVisible(finder);
      await tester.pump();
      final rect = tester.getRect(finder);
      expect(rect.width, greaterThanOrEqualTo(48), reason: key);
      expect(rect.height, greaterThanOrEqualTo(48), reason: key);
      expect(rect.left, greaterThanOrEqualTo(0), reason: key);
      expect(rect.right, lessThanOrEqualTo(viewport.width), reason: key);
      expect(finder.hitTestable(), findsOneWidget, reason: key);
      expect(tester.takeException(), isNull);
    }
  });
}
