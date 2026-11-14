import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/action_cards.dart';

void main() {
  testWidgets('unsubmitted question survives scrolling outside lazy cache', (
    t,
  ) async {
    final scroll = ScrollController();
    addTearDown(scroll.dispose);
    await t.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: ListView.builder(
            controller: scroll,
            itemCount: 70,
            itemBuilder: (_, i) => i == 69
                ? QuestionCard(
                    key: const ValueKey('request'),
                    request: const ActionRequest(
                      requestId: 'q',
                      title: '问题',
                      detail: '',
                      choices: ['选项'],
                    ),
                    onSubmit: (_) {},
                  )
                : SizedBox(height: 64, child: Text('history ' + i.toString())),
          ),
        ),
      ),
    );
    await t.scrollUntilVisible(
      find.byKey(const ValueKey('question-input')),
      500,
    );
    await t.enterText(find.byKey(const ValueKey('question-input')), '保留草稿');
    await t.pump();
    await t.ensureVisible(find.byKey(const ValueKey('question-choice-0')));
    await t.tap(find.byKey(const ValueKey('question-choice-0')));
    FocusManager.instance.primaryFocus?.unfocus();
    await t.pump();
    scroll.jumpTo(0);
    await t.pumpAndSettle();
    await t.scrollUntilVisible(
      find.byKey(const ValueKey('question-input')),
      500,
    );
    await t.pump();
    expect(
      t
          .widget<TextField>(find.byKey(const ValueKey('question-input')))
          .controller!
          .text,
      '保留草稿',
    );
    await t.pumpWidget(const SizedBox.shrink());
    expect(t.takeException(), isNull);
  });
}
