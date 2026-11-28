import 'dart:async';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/transport/native_file_delivery_service.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

class _Conversation extends FixtureTransport {
  @override
  Future<void> connect() async {}
  @override
  Stream<ChatEvent> get events => Stream.value(
    ChatEvent.snapshot(
      generation: 1,
      sequence: 0,
      messages: const [
        ChatMessage(
          id: 'answer',
          role: ChatRole.assistant,
          text: '[文件](report.txt)',
        ),
      ],
    ),
  );
}

class _Files extends NativeFileDeliveryService {
  _Files()
    : super(
        rpc: DshRpcConnection(webUrl: 'http://localhost/?token=test'),
        sessionId: 's1',
        workspaceRoot: '/work',
      );
  final pending = Completer<FilePreviewData>();
  int reads = 0;
  @override
  Future<FilePreviewData> preview({
    required String displayName,
    required String target,
  }) {
    reads++;
    return pending.future;
  }
}

const _page = FilePreviewData(
  displayName: 'report.txt',
  target: 'report.txt',
  mime: 'text/plain',
  absolutePath: '/work/report.txt',
  text: 'Original session content',
);

void main() {
  Future<void> render(
    WidgetTester tester,
    ChatController controller,
    _Files files,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: ChatShell(
          controller: controller,
          fileDelivery: files,
          disposeController: false,
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
  }

  Future<void> request(WidgetTester tester) async {
    await tester.tap(
      find.byKey(const ValueKey('file-delivery-open-report.txt')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('file-delivery-preview')));
    await tester.pumpAndSettle();
  }

  testWidgets('session switch during a file read discards the old preview', (
    tester,
  ) async {
    final controller = ChatController(_Conversation());
    final oldFiles = _Files();
    final newFiles = _Files();
    await render(tester, controller, oldFiles);
    await request(tester);
    expect(oldFiles.reads, 1);
    await render(tester, controller, newFiles);
    oldFiles.pending.complete(_page);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('file-preview-text')), findsNothing);
    expect(newFiles.reads, 0);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });
  testWidgets('repeated file request shares one read and opens one preview', (
    tester,
  ) async {
    final controller = ChatController(_Conversation());
    final files = _Files();
    await render(tester, controller, files);
    await request(tester);
    await request(tester);
    expect(files.reads, 1);
    files.pending.complete(_page);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('file-preview-text')), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });
}
