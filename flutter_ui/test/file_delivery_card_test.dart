import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/theme/display_scale.dart';
import 'package:dsh_native_chat/src/ui/conversation_flow.dart';
import 'package:dsh_native_chat/src/ui/file_delivery_card.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

Widget _app(
  String text, {
  FileDeliveryTap? onTap,
  FileDeliveryShare? onShare,
  ChatRole role = ChatRole.assistant,
}) => MaterialApp(
  theme: DshTheme.light(),
  home: Scaffold(
    body: ConversationFlow(
      conversationIdentity: 'test',
      messages: [ChatMessage(id: 'answer', role: role, text: text)],
      onFileTap: onTap,
      onFileShare: onShare,
    ),
  ),
);

void main() {
  test(
    'file links accept spaces and parentheses, ignore code and external URLs',
    () {
      final code = String.fromCharCode(96);
      final text =
          '[报告](docs/报告 (1).pdf) [文本](<docs/a b.txt>) [本地](file:///work/a.txt) '
          '[外部](https://example.test/a.pdf)\n'
          '$code$code$code md\n[示例](sample.txt)\n$code$code$code\n'
          '$code[v0](v0.txt)$code $code[06](06.txt)$code';
      expect(extractFileDeliveries(text).map((f) => f.target), [
        'docs/报告 (1).pdf',
        'docs/a b.txt',
        'file:///work/a.txt',
      ]);
    },
  );
  testWidgets('extracts file links while preserving assistant text', (
    tester,
  ) async {
    const text = '交付 [报告](docs/report.pdf) 和 [图片](dsh-resource://file/42)';
    await tester.pumpWidget(_app(text));
    expect(find.text(text), findsOneWidget);
    expect(find.byType(FileDeliveryCard), findsNWidgets(2));
    expect(find.text('报告'), findsOneWidget);
    expect(find.text('图片'), findsOneWidget);
    expect(find.textContaining('PDF'), findsOneWidget);
  });

  testWidgets('recognizes local absolute and relative paths only', (
    tester,
  ) async {
    const text =
        '[相对](./docs/report.pdf) [绝对](/tmp/chart.png) [Windows](C:\\docs\\data.csv) [资源](dsh-resource://file/id) ![图](image.png)';
    await tester.pumpWidget(_app(text));
    final cards = tester
        .widgetList<FileDeliveryCard>(find.byType(FileDeliveryCard))
        .toList();
    expect(cards.map((card) => card.file.target), [
      './docs/report.pdf',
      '/tmp/chart.png',
      r'C:\docs\data.csv',
      'dsh-resource://file/id',
    ]);
  });

  testWidgets('accessible file entry fits a narrow dark scaled screen', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final semantics = tester.ensureSemantics();
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.dark(),
        home: MediaQuery(
          data: const MediaQueryData(
            size: Size(320, 640),
            textScaler: TextScaler.linear(2),
          ),
          child: Scaffold(
            body: ConversationFlow(
              conversationIdentity: 'narrow',
              messages: const [
                ChatMessage(
                  id: 'answer',
                  role: ChatRole.assistant,
                  text: '[非常非常非常非常非常长的交付文件名称](report.pdf)',
                ),
              ],
              onFileTap: (_, _) {},
              onFileShare: (_) {},
            ),
          ),
        ),
      ),
    );
    final entry = find.byKey(const ValueKey('file-delivery-open-report.pdf'));
    expect(tester.getSize(entry).height, greaterThanOrEqualTo(48));
    expect(tester.getSemantics(entry).label, contains('打开选项'));
    await tester.tap(entry);
    await tester.pumpAndSettle();
    expect(find.text('在 DSH 中预览'), findsOneWidget);
    expect(find.text('用其他应用打开'), findsOneWidget);
    expect(find.text('分享文件'), findsOneWidget);
    expect(tester.takeException(), isNull);
    semantics.dispose();
  });

  testWidgets('compact canvas keeps a physical 48dp file touch target', (
    tester,
  ) async {
    await tester.pumpWidget(
      DshDisplayScope(
        size: DshDisplaySize.compact,
        child: MaterialApp(
          theme: DshTheme.light(displayFactor: .9),
          home: DshDisplayCanvas(
            child: Scaffold(
              body: ConversationFlow(
                conversationIdentity: 'compact',
                messages: const [
                  ChatMessage(
                    id: 'answer',
                    role: ChatRole.assistant,
                    text: '[x](x.pdf)',
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    final finder = find.byKey(const ValueKey('file-delivery-open-x.pdf'));
    final box = tester.renderObject<RenderBox>(finder);
    final physicalHeight =
        (box.localToGlobal(box.size.bottomLeft(Offset.zero)) -
                box.localToGlobal(Offset.zero))
            .dy;
    expect(physicalHeight, greaterThanOrEqualTo(47.99));
    await tester.tap(finder);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('file-delivery-cancel')), findsOneWidget);
  });

  testWidgets('ignores ordinary prose, web links and other roles', (
    tester,
  ) async {
    await tester.pumpWidget(
      _app(
        '正文 report.pdf [章节](docs/chapter) [网站](https://example.com/file.pdf)',
      ),
    );
    expect(find.byType(FileDeliveryCard), findsNothing);
    await tester.pumpWidget(_app('[报告](report.pdf)', role: ChatRole.user));
    expect(find.byType(FileDeliveryCard), findsNothing);
  });

  testWidgets(
    'menu invokes the selected callback and cancellation invokes none',
    (tester) async {
      final taps = <FileDeliveryAction>[];
      final files = <FileDelivery>[];
      Future<void> open() async {
        await tester.tap(find.byType(FileDeliveryCard));
        await tester.pumpAndSettle();
      }

      await tester.pumpWidget(
        _app(
          '[报告](docs/report.pdf)',
          onTap: (file, action) {
            files.add(file);
            taps.add(action);
          },
          onShare: (file) {
            files.add(file);
          },
        ),
      );
      for (final entry in <String, FileDeliveryAction>{
        'file-delivery-preview': FileDeliveryAction.preview,
        'file-delivery-open-external': FileDeliveryAction.openExternal,
      }.entries) {
        await open();
        await tester.tap(find.byKey(ValueKey(entry.key)));
        await tester.pumpAndSettle();
        expect(taps.last, entry.value);
        expect(files.last.target, 'docs/report.pdf');
        expect(files.last.displayName, '报告');
      }
      await open();
      await tester.tap(find.byKey(const ValueKey('file-delivery-share')));
      await tester.pumpAndSettle();
      expect(files.length, 3);
      expect(files.last.target, 'docs/report.pdf');
      await open();
      await tester.tap(find.byKey(const ValueKey('file-delivery-cancel')));
      await tester.pumpAndSettle();
      expect(files.length, 3);
      expect(taps.length, 2);
    },
  );

  testWidgets('file actions reveal in order on opening', (tester) async {
    await tester.pumpWidget(
      _app('[报告](report.pdf)', onTap: (_, _) {}, onShare: (_) {}),
    );
    await tester.tap(find.byType(FileDeliveryCard));
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    final first = tester.widget<Opacity>(
      find.byKey(const ValueKey('staggered-reveal-opacity-0')),
    );
    final second = tester.widget<Opacity>(
      find.byKey(const ValueKey('staggered-reveal-opacity-1')),
    );
    expect(first.opacity, greaterThan(second.opacity));
    expect(second.opacity, lessThan(1));
  });

  testWidgets('missing callbacks disable operations without crashing', (
    tester,
  ) async {
    await tester.pumpWidget(_app('[报告](report.pdf)'));
    await tester.tap(find.byType(FileDeliveryCard));
    await tester.pumpAndSettle();
    for (final key in [
      'file-delivery-preview',
      'file-delivery-open-external',
      'file-delivery-share',
    ]) {
      final tile = tester.widget<ListTile>(find.byKey(ValueKey(key)));
      expect(tile.onTap, isNull);
    }
    await tester.tap(find.byKey(const ValueKey('file-delivery-cancel')));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });
}
