import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/assistant_rich_text.dart';

void main() {
  Future<void> pump(WidgetTester tester, String source) async {
    tester.view.physicalSize = const Size(420, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: AssistantRichText(markdown: source),
          ),
        ),
      ),
    );
    await tester.pump();
  }

  testWidgets(
    'renders markdown headings emphasis lists and quote as semantic text',
    (tester) async {
      await pump(tester, '# 结论\n\n这是 **重要**。\n\n- 第一项\n- 第二项\n\n> 仅供参考');
      expect(find.text('结论'), findsOneWidget);
      expect(find.textContaining('重要'), findsOneWidget);
      expect(find.textContaining('第一项'), findsOneWidget);
      expect(find.textContaining('仅供参考'), findsOneWidget);
      expect(find.byType(SelectableText), findsWidgets);
    },
  );

  testWidgets('renders bounded markdown table with accessible cells', (
    tester,
  ) async {
    await pump(tester, '| 名称 | 数值 |\n| --- | ---: |\n| A | 12 |\n| B | 8 |');
    expect(
      find.byKey(const ValueKey('assistant-markdown-table')),
      findsOneWidget,
    );
    expect(find.text('名称'), findsOneWidget);
    expect(find.text('12'), findsOneWidget);
    expect(find.text('B'), findsOneWidget);
  });

  testWidgets('preserves an escaped pipe within its original table cell', (
    tester,
  ) async {
    await pump(
      tester,
      r'| Command | Result |'
      '\n'
      r'| --- | --- |'
      '\n'
      r'| A \| B | retained |',
    );
    expect(find.text('A | B'), findsOneWidget);
    expect(find.text('retained'), findsOneWidget);
  });

  testWidgets('preserves unsupported chart source verbatim', (tester) async {
    const source =
        '\x60\x60\x60chart\n'
        'type: unsupported\nUnits: minutes\nA: 4\n\x60\x60\x60';
    await pump(tester, source);
    expect(find.text(source), findsOneWidget);
  });

  testWidgets('does not interpret a chart prefix as the chart language', (
    tester,
  ) async {
    const source = '\x60\x60\x60chartjs\ntype: category\nA: 4\n\x60\x60\x60';
    await pump(tester, source);
    expect(
      find.byKey(const ValueKey('assistant-data-chart-category')),
      findsNothing,
    );
    expect(find.textContaining('chartjs'), findsOneWidget);
  });

  testWidgets('renders explicit comparison chart from fenced data only', (
    tester,
  ) async {
    await pump(tester, '```chart\ntype: comparison\nA: 12\nB: 8\n```');
    expect(
      find.byKey(const ValueKey('assistant-data-chart-comparison')),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('assistant-chart-bar-A')), findsOneWidget);
    expect(find.text('12'), findsOneWidget);
  });

  testWidgets(
    'renders explicit category chart and ignores unbounded chart syntax',
    (tester) async {
      await pump(tester, '```chart\ntype: category\n苹果: 4\n香蕉: 9\n```');
      expect(
        find.byKey(const ValueKey('assistant-data-chart-category')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('assistant-chart-bar-香蕉')),
        findsOneWidget,
      );
      await pump(tester, '<script>alert(1)</script>');
      expect(
        find.byKey(const ValueKey('assistant-data-chart-category')),
        findsNothing,
      );
      expect(find.textContaining('<script>'), findsOneWidget);
    },
  );
}
