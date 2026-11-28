import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/tool_run_card.dart';

void main() {
  testWidgets(
    'short tool output has full-copy access and handles clipboard failure',
    (tester) async {
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
        MaterialApp(
          theme: DshTheme.light(),
          home: const Scaffold(
            body: ToolRunCard(
              tool: ToolRun(
                id: 'short',
                name: 'read',
                status: ToolStatus.complete,
                detail: '{"ok":true}',
              ),
            ),
          ),
        ),
      );
      expect(find.textContaining('"ok"'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('tool-card-short')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('查看完整输出'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('复制全部'));
      await tester.pump();
      expect(find.text('复制失败，请重试'), findsOneWidget);
      expect(find.text('已复制完整输出'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'compact ordinary tool row keeps its target and bounded preview',
    (tester) async {
      final detail = List.generate(500, (i) => 'line $i').join('\n');
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: Scaffold(
            body: SingleChildScrollView(
              child: ToolRunCard(
                compact: true,
                tool: ToolRun(
                  id: 'lines',
                  name: 'read',
                  status: ToolStatus.complete,
                  detail: detail,
                ),
              ),
            ),
          ),
        ),
      );
      final row = find.byKey(const ValueKey('tool-card-lines'));
      final rowSize = tester.getSize(row);
      expect(rowSize.height, greaterThanOrEqualTo(48));
      final rowRect = tester.getRect(row);
      await tester.tapAt(Offset(rowRect.center.dx, rowRect.bottom - 1));
      await tester.pumpAndSettle();
      final preview = tester.widget<SelectableText>(
        find.byType(SelectableText).first,
      );
      // Compact previews bound what is painted; the full text stays reachable.
      expect(preview.data!.split('\n').length, lessThanOrEqualTo(3));
      expect(preview.maxLines, 3);
      expect(find.text('查看完整输出'), findsOneWidget);
    },
  );

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
