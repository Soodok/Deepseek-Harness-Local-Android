import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/tool_run_card.dart';

void main() {
  testWidgets('collapsed logs are not built and long logs remain accessible', (
    t,
  ) async {
    final detail = List.filled(10000, 'log data ').join();
    await t.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: SingleChildScrollView(
            child: ToolRunCard(
              tool: ToolRun(
                id: 'long',
                name: '长输出',
                detail: detail,
                status: ToolStatus.complete,
              ),
            ),
          ),
        ),
      ),
    );
    expect(find.byType(SelectableText), findsNothing);
    await t.tap(find.text('长输出'));
    await t.pumpAndSettle();
    expect(find.text('查看完整输出'), findsOneWidget);
    final text = t
        .widgetList<SelectableText>(find.byType(SelectableText))
        .first
        .data!;
    expect(text.length, lessThan(2000));
    await t.ensureVisible(find.text('查看完整输出'));
    await t.tap(find.text('查看完整输出'));
    await t.pumpAndSettle();
    expect(find.text('复制全部'), findsOneWidget);
    expect(t.takeException(), isNull);
  });
}
