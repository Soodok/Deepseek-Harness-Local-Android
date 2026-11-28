import 'dart:convert';
import 'dart:typed_data';

import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/native_file_delivery_service.dart';
import 'package:dsh_native_chat/src/ui/file_preview_sheet.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  Future<void> show(
    WidgetTester tester,
    FilePreviewData data, {
    VoidCallback? onOpen,
    VoidCallback? onShare,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => showFilePreviewSheet(
                context,
                data,
                onOpen: onOpen ?? () {},
                onShare: onShare ?? () {},
              ),
              child: const Text('show'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('show'));
    await tester.pumpAndSettle();
  }

  testWidgets('preview preserves source and highlights real code tokens', (
    tester,
  ) async {
    const source = 'const count = 42;\n// comment\nreturn "ok";';
    await show(
      tester,
      const FilePreviewData(
        displayName: 'demo.dart',
        target: 'demo.dart',
        mime: 'text/plain',
        absolutePath: '/work/demo.dart',
        text: source,
      ),
    );
    final text = tester.widget<Text>(
      find.byKey(const ValueKey('file-preview-text')),
    );
    expect(text.textSpan!.toPlainText(), source);
    final spans = (text.textSpan! as TextSpan).children!
        .whereType<TextSpan>()
        .toList();
    expect(
      spans.where((s) => s.text == 'const' && s.style?.color != null),
      isNotEmpty,
    );
    expect(
      spans.where((s) => s.text == '42' && s.style?.color != null),
      isNotEmpty,
    );
    expect(
      spans.where((s) => s.text == '// comment' && s.style?.color != null),
      isNotEmpty,
    );
    expect(
      spans.where((s) => s.text == 'return' && s.style?.color != null),
      isNotEmpty,
    );
    expect(
      spans.where((s) => s.text == '"ok"' && s.style?.color != null),
      isNotEmpty,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('decoded image uses original bytes and supports zoom', (
    tester,
  ) async {
    final bytes = Uint8List.fromList(
      base64Decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==',
      ),
    );
    await show(
      tester,
      FilePreviewData(
        displayName: 'pixel.png',
        target: 'pixel.png',
        mime: 'image/png',
        absolutePath: '/work/pixel.png',
        bytes: bytes,
      ),
    );
    final image = tester.widget<Image>(
      find.byKey(const ValueKey('file-preview-image')),
    );
    expect((image.image as MemoryImage).bytes, orderedEquals(bytes));
    expect(find.byType(InteractiveViewer), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('preview actions dispatch once and dismiss before opening', (
    tester,
  ) async {
    const data = FilePreviewData(
      displayName: 'report.txt',
      target: 'report.txt',
      mime: 'text/plain',
      absolutePath: '/work/report.txt',
      text: 'Report',
    );
    var opened = 0;
    var shared = 0;
    await show(tester, data, onOpen: () => opened++, onShare: () => shared++);
    await tester.tap(find.byKey(const ValueKey('file-preview-share')));
    await tester.pumpAndSettle();
    expect(shared, 1);
    expect(opened, 0);
    expect(find.byKey(const ValueKey('file-preview-text')), findsNothing);
    await show(tester, data, onOpen: () => opened++, onShare: () => shared++);
    await tester.tap(find.byKey(const ValueKey('file-preview-open')));
    await tester.pumpAndSettle();
    expect(opened, 1);
    expect(shared, 1);
  });
}
