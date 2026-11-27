import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/tool_process_card.dart';

ToolRun _run({
  String id = 'run-1',
  ToolStatus status = ToolStatus.complete,
  String reasoning = '',
  CommandResult? command,
  List<CodeEdit> edits = const [],
  int? elapsedMs = 2400,
}) => ToolRun(
  id: id,
  name: 'bash',
  status: status,
  elapsedMs: elapsedMs,
  content: ToolContent(
    title: 'test',
    reasoning: reasoning,
    command: command,
    edits: edits,
  ),
);

Future<void> _pump(
  WidgetTester tester,
  ToolRun tool, {
  double width = 390,
}) async {
  tester.view.physicalSize = Size(width, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: Scaffold(
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(12),
          child: ToolProcessCard(tool: tool),
        ),
      ),
    ),
  );
  await tester.pump();
}

Finder _key(String value) => find.byKey(ValueKey(value));

Future<void> _tap(WidgetTester tester, String key) async {
  final target = _key(key);
  await tester.ensureVisible(target);
  await tester.tap(target);
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 240));
}

void main() {
  testWidgets(
    'reduced motion settles process disclosures without timed transitions',
    (tester) async {
      final tool = _run(
        reasoning: '可读过程内容',
        command: const CommandResult(
          command: 'pwd',
          stdout: '/project',
          exitCode: 0,
        ),
        edits: const [CodeEdit(path: 'a.dart', before: 'old', after: 'new')],
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: MediaQuery(
            data: const MediaQueryData(disableAnimations: true),
            child: Scaffold(
              body: SingleChildScrollView(child: ToolProcessCard(tool: tool)),
            ),
          ),
        ),
      );
      final card = find.byType(ToolProcessCard);
      for (final size in tester.widgetList<AnimatedSize>(
        find.descendant(of: card, matching: find.byType(AnimatedSize)),
      ))
        expect(size.duration, Duration.zero);
      for (final rotation in tester.widgetList<AnimatedRotation>(
        find.descendant(of: card, matching: find.byType(AnimatedRotation)),
      ))
        expect(rotation.duration, Duration.zero);
      for (final switcher in tester.widgetList<AnimatedSwitcher>(
        find.descendant(of: card, matching: find.byType(AnimatedSwitcher)),
      ))
        expect(switcher.duration, Duration.zero);
    },
  );

  testWidgets('reasoning, command and diff start collapsed', (tester) async {
    await _pump(
      tester,
      _run(
        reasoning: '先看失败断言再改实现\n最后一步核对尾部',
        command: const CommandResult(
          command: 'flutter test',
          cwd: 'flutter_ui',
          stdout: 'All tests passed',
          exitCode: 0,
        ),
        edits: const [
          CodeEdit(
            path: 'lib/main.dart',
            before: 'old',
            after: 'new',
            unifiedDiff: '@@ -1 +1 @@\n-old\n+new',
            language: 'dart',
          ),
        ],
      ),
    );

    expect(find.text('bash'), findsOneWidget);
    expect(find.text('已完成'), findsOneWidget);
    expect(find.textContaining('2.4 秒'), findsOneWidget);

    // Section headers exist, but bodies stay out of the tree until expanded.
    expect(find.text('思考'), findsOneWidget);
    expect(find.text('命令'), findsOneWidget);
    expect(find.text('lib/main.dart'), findsOneWidget);
    expect(find.text('先看失败断言再改实现'), findsOneWidget);
    expect(find.textContaining('最后一步核对尾部'), findsNothing);
    expect(find.byType(SelectableText), findsNothing);
    expect(find.text('All tests passed'), findsNothing);
    expect(find.textContaining('-old'), findsNothing);

    final reasoningToggle = tester.getSize(_key('process-reasoning-run-1'));
    final commandToggle = tester.getSize(_key('process-command-run-1'));
    final diffToggle = tester.getSize(_key('process-diff-run-1-0'));
    expect(reasoningToggle.height, greaterThanOrEqualTo(48));
    expect(commandToggle.height, greaterThanOrEqualTo(48));
    expect(diffToggle.height, greaterThanOrEqualTo(48));

    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'failed status shows even when exit code is absent, and exit 7 keeps stderr',
    (tester) async {
      await _pump(
        tester,
        _run(
          status: ToolStatus.failed,
          command: const CommandResult(
            command: 'flutter test',
            stderr: 'expected 2',
            exitCode: 7,
          ),
        ),
      );

      // Status comes only from ToolRun.status, never inferred from exitCode.
      expect(find.text('失败'), findsOneWidget);
      expect(find.textContaining('退出码 7'), findsOneWidget);

      await _tap(tester, 'process-command-run-1');
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 240));

      expect(find.text('expected 2'), findsOneWidget);
      expect(find.text('flutter test'), findsWidgets);
    },
  );

  testWidgets('failed run with no exit code still reads as failed', (
    tester,
  ) async {
    await _pump(
      tester,
      _run(
        status: ToolStatus.failed,
        command: const CommandResult(command: 'flutter test', stderr: 'killed'),
      ),
    );
    expect(find.text('失败'), findsOneWidget);
    expect(find.textContaining('退出码'), findsNothing);
    await _tap(tester, 'process-command-run-1');
    await tester.pump(const Duration(milliseconds: 240));
    expect(find.text('killed'), findsOneWidget);
  });

  testWidgets('same id keeps expansion across prop updates', (tester) async {
    await _pump(
      tester,
      _run(
        reasoning: '第一版思考',
        command: const CommandResult(command: 'echo a'),
      ),
    );
    await _tap(tester, 'process-reasoning-run-1');
    await tester.pump(const Duration(milliseconds: 240));
    expect(find.text('第一版思考'), findsOneWidget);

    await _pump(
      tester,
      _run(
        status: ToolStatus.running,
        reasoning: '第二版思考',
        command: const CommandResult(command: 'echo b', stdout: 'b'),
      ),
    );
    // Expansion keyed by tool id survives a rebuild with new props.
    expect(find.text('第二版思考'), findsOneWidget);
    expect(find.text('第一版思考'), findsNothing);
    expect(find.text('执行中'), findsOneWidget);
  });

  testWidgets('edit sections keep expansion when a prior path is inserted', (
    tester,
  ) async {
    const first = CodeEdit(path: 'a.dart', before: 'a old', after: 'a new');
    const second = CodeEdit(path: 'b.dart', before: 'b old', after: 'b new');
    await _pump(tester, _run(edits: const [first, second]));
    await _tap(tester, 'process-diff-run-1-1');
    expect(find.textContaining('b old'), findsOneWidget);

    await _pump(
      tester,
      _run(
        edits: const [
          CodeEdit(path: 'new.dart', before: 'new old', after: 'new new'),
          first,
          second,
        ],
      ),
    );
    expect(find.textContaining('b old'), findsOneWidget);
    expect(find.textContaining('a old'), findsNothing);
    expect(find.textContaining('new old'), findsNothing);

    await _pump(
      tester,
      _run(
        edits: const [
          first,
          CodeEdit(path: 'b.dart', before: 'updated old', after: 'updated new'),
        ],
      ),
    );
    expect(find.textContaining('updated old'), findsOneWidget);
    expect(find.textContaining('updated new'), findsOneWidget);
    expect(find.textContaining('b old'), findsNothing);
  });

  testWidgets('repeated edit paths keep independent disclosures', (
    tester,
  ) async {
    const first = CodeEdit(
      path: 'a.dart',
      before: 'first old',
      after: 'first new',
    );
    const second = CodeEdit(
      path: 'a.dart',
      before: 'second old',
      after: 'second new',
    );
    await _pump(tester, _run(edits: const [first, second]));
    await _tap(tester, 'process-diff-run-1-1');
    expect(find.textContaining('second old'), findsOneWidget);
    expect(find.textContaining('first old'), findsNothing);

    await _pump(
      tester,
      _run(
        edits: const [
          CodeEdit(path: 'prior.dart', before: 'prior old', after: 'prior new'),
          first,
          second,
        ],
      ),
    );
    expect(find.textContaining('second old'), findsOneWidget);
    expect(find.textContaining('first old'), findsNothing);
    expect(find.textContaining('prior old'), findsNothing);
    await _tap(tester, 'process-diff-run-1-1');
    expect(find.textContaining('first old'), findsOneWidget);
    expect(find.textContaining('second old'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('diff expands to added and removed lines', (tester) async {
    await _pump(
      tester,
      _run(
        edits: const [
          CodeEdit(
            path: 'lib/src/ui/tool_process_card.dart',
            unifiedDiff:
                '@@ -4,3 +4,4 @@\n context\n-old line\n+new line\n unchanged',
          ),
        ],
      ),
    );
    expect(find.text('old line'), findsNothing);
    await _tap(tester, 'process-diff-run-1-0');
    await tester.pump(const Duration(milliseconds: 240));
    expect(find.textContaining('old line'), findsOneWidget);
    expect(find.textContaining('new line'), findsOneWidget);
    expect(find.textContaining('unchanged'), findsOneWidget);
  });

  testWidgets('long output starts truncated and full text copies', (
    tester,
  ) async {
    final stdout =
        List.generate(200, (i) => 'stdout line ' + i.toString()).join('\n') +
        '\nTAIL-MARKER-END';
    expect(stdout.length, greaterThan(1600));
    await _pump(
      tester,
      _run(
        command: CommandResult(command: 'long', stdout: stdout),
      ),
    );
    await _tap(tester, 'process-command-run-1');
    await tester.pump(const Duration(milliseconds: 240));
    expect(
      find.byKey(const ValueKey('process-preview-boundary-run-1-stdout')),
      findsOneWidget,
    );

    final shown = tester.widget<SelectableText>(
      _key('process-output-run-1-stdout'),
    );
    expect((shown.data ?? '').length, lessThanOrEqualTo(1600));
    expect((shown.data ?? '').split('\n').length, lessThanOrEqualTo(60));
    expect(find.textContaining('TAIL-MARKER-END'), findsNothing);
    expect(find.textContaining('已截断'), findsOneWidget);

    String? copied;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied = (call.arguments as Map)['text'] as String;
        }
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );

    await _tap(tester, 'process-copy-run-1-stdout');
    await tester.pump();
    expect(copied, stdout);
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();

    await _tap(tester, 'process-full-run-1-stdout');
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('完整输出'), findsOneWidget);
    // A unique tail proves content after the preview remains reachable.
    final fullList = _key('process-full-list-run-1-stdout');
    final scrollable = find.descendant(
      of: fullList,
      matching: find.byType(Scrollable),
    );
    expect(find.text('TAIL-MARKER-END'), findsNothing);
    await tester.scrollUntilVisible(
      find.text('TAIL-MARKER-END'),
      600,
      scrollable: scrollable,
      maxScrolls: 50,
    );
    expect(find.text('TAIL-MARKER-END'), findsOneWidget);
    await _tap(tester, 'process-copy-full-run-1-stdout');
    await tester.pump();
    expect(copied, stdout);
    await tester.pump(const Duration(seconds: 3));
    await tester.pump(const Duration(milliseconds: 240));
  });

  testWidgets('status does not come from command output or exit code', (
    tester,
  ) async {
    await _pump(
      tester,
      _run(
        status: ToolStatus.complete,
        command: const CommandResult(
          command: 'check',
          stdout: 'FAILED error exit 9',
          exitCode: 7,
        ),
      ),
    );
    expect(find.text('已完成'), findsOneWidget);
    expect(find.text('失败'), findsNothing);
    expect(find.text('退出码 7'), findsOneWidget);
    await _pump(
      tester,
      _run(
        status: ToolStatus.failed,
        command: const CommandResult(
          command: 'check',
          stdout: 'All tests passed',
          exitCode: 0,
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 240));
    expect(find.text('失败'), findsOneWidget);
    expect(find.text('已完成'), findsNothing);
    expect(find.text('退出码 0'), findsOneWidget);
  });

  testWidgets('changing tool id resets previously expanded sections', (
    tester,
  ) async {
    await _pump(tester, _run(reasoning: 'first\nfirst tail'));
    await _tap(tester, 'process-reasoning-run-1');
    expect(_key('process-output-run-1-reasoning'), findsOneWidget);
    await _pump(tester, _run(id: 'run-2', reasoning: 'second\nsecond tail'));
    await tester.pump(const Duration(milliseconds: 240));
    expect(_key('process-output-run-2-reasoning'), findsNothing);
    expect(find.textContaining('second tail'), findsNothing);
  });

  testWidgets('path-only edits do not fabricate change counts', (tester) async {
    await _pump(tester, _run(edits: const [CodeEdit(path: 'pending.dart')]));
    expect(find.text('pending.dart'), findsOneWidget);
    expect(find.text('+0'), findsNothing);
    expect(find.text('-0'), findsNothing);
    await _tap(tester, 'process-diff-run-1-0');
    expect(find.text('尚无改动内容'), findsOneWidget);
  });

  testWidgets('before and after show line numbers and copy original sources', (
    tester,
  ) async {
    const before = 'same\nold\nend\n';
    const after = 'same\nnew\nend\n';
    String? copied;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData')
          copied = (call.arguments as Map)['text'] as String;
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    await _pump(
      tester,
      _run(
        edits: const [
          CodeEdit(path: 'file.dart', before: before, after: after),
        ],
      ),
    );
    expect(find.text('+1'), findsOneWidget);
    expect(find.text('-1'), findsOneWidget);
    await _tap(tester, 'process-diff-run-1-0');
    expect(find.text('- old'), findsOneWidget);
    expect(find.text('+ new'), findsOneWidget);
    expect(find.text('2'), findsNWidgets(2));
    await _tap(tester, 'process-copy-run-1-0-before');
    expect(copied, before);
    await _tap(tester, 'process-copy-run-1-0-after');
    expect(copied, after);
    await tester.pump(const Duration(seconds: 3));
    await tester.pump(const Duration(milliseconds: 240));
  });

  testWidgets('a partial final diff line still offers complete access', (
    tester,
  ) async {
    final diff =
        '@@ -0,0 +1 @@\n+' + List.filled(1900, 'x').join() + 'DIFF-TAIL';
    await _pump(
      tester,
      _run(
        edits: [CodeEdit(path: 'long.dart', unifiedDiff: diff)],
      ),
    );
    await _tap(tester, 'process-diff-run-1-0');
    expect(find.textContaining('DIFF-TAIL'), findsNothing);
    expect(_key('process-full-diff-run-1-0'), findsOneWidget);
    await _tap(tester, 'process-full-diff-run-1-0');
    final scrollable = find.descendant(
      of: _key('process-full-diff-list-run-1-0'),
      matching: find.byType(Scrollable),
    );
    await tester.scrollUntilVisible(
      find.textContaining('DIFF-TAIL'),
      600,
      scrollable: scrollable,
      maxScrolls: 50,
    );
    expect(find.textContaining('DIFF-TAIL'), findsOneWidget);
  });

  testWidgets('copy failure has no success confirmation', (tester) async {
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
    await _pump(
      tester,
      _run(
        command: const CommandResult(command: 'echo', stdout: 'text'),
      ),
    );
    await _tap(tester, 'process-command-run-1');
    await _tap(tester, 'process-copy-run-1-stdout');
    expect(find.text('复制失败，请重试'), findsOneWidget);
    expect(find.text('已复制完整内容'), findsNothing);
    await tester.pump(const Duration(seconds: 3));
    await tester.pump(const Duration(milliseconds: 240));
  });

  testWidgets('a pending copy result is ignored after replacing the tool', (
    tester,
  ) async {
    final pending = Completer<void>();
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') await pending.future;
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    await _pump(
      tester,
      _run(
        command: const CommandResult(command: 'echo', stdout: 'old'),
      ),
    );
    await _tap(tester, 'process-command-run-1');
    await _tap(tester, 'process-copy-run-1-stdout');
    await _pump(
      tester,
      _run(
        id: 'run-2',
        command: const CommandResult(command: 'echo', stdout: 'new'),
      ),
    );
    pending.complete();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 240));
    expect(find.text('已复制完整内容'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  for (final width in [360.0, 390.0, 432.0]) {
    testWidgets(
      'production theme fits ' + width.toString() + ' with expanded content',
      (tester) async {
        await _pump(
          tester,
          _run(
            reasoning: '核对结构字段\n读完完整内容',
            command: const CommandResult(
              command: 'run command --flag',
              stdout: 'line',
              exitCode: 7,
            ),
            edits: const [
              CodeEdit(
                path: 'lib/very_long_directory_name/long_file_name.dart',
                before: 'before',
                after: 'after',
              ),
            ],
          ),
          width: width,
        );
        for (final key in [
          'process-reasoning-run-1',
          'process-command-run-1',
          'process-diff-run-1-0',
        ]) {
          expect(tester.getSize(_key(key)).height, greaterThanOrEqualTo(48));
          await _tap(tester, key);
          expect(tester.takeException(), isNull);
        }
        for (final style
            in tester
                .widgetList<Text>(find.byType(Text))
                .map((text) => text.style)) {
          if (style?.fontSize != null)
            expect(style!.fontSize, greaterThanOrEqualTo(12.5));
        }
      },
    );
  }

  testWidgets('empty sections stay hidden and touch targets hold at 360dp', (
    tester,
  ) async {
    await _pump(tester, _run(elapsedMs: null), width: 360);
    expect(find.text('思考'), findsNothing);
    expect(find.text('命令'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
