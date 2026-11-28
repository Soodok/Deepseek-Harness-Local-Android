import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

class _PendingApprovalTransport extends FixtureTransport
    implements ApprovalRejectionTransport {
  final rejected = <String>[];

  @override
  Stream<ChatEvent> get events => Stream.fromIterable([
    ChatEvent.snapshot(
      generation: 1,
      sequence: 0,
      messages: List.generate(
        48,
        (index) => ChatMessage(
          id: 'history-$index',
          role: index.isEven ? ChatRole.user : ChatRole.assistant,
          text: List.filled(
            1 + index % 6,
            'History from message $index.',
          ).join('\n'),
        ),
      ),
    ),
    const ChatEvent.approval(
      generation: 1,
      sequence: 1,
      requestId: 'pending-from-web',
      title: 'Waiting for confirmation',
      detail: 'This is the existing request from the selected session.',
    ),
  ]);

  @override
  Future<void> rejectApproval(String requestId) async {
    rejected.add(requestId);
  }
}

void main() {
  testWidgets('handoff shows one tappable inline approval above the composer', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 780);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final transport = _PendingApprovalTransport();
    final controller = ChatController(transport);
    addTearDown(controller.dispose);
    await tester.pumpWidget(
      MaterialApp(
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: const TextScaler.linear(1.6)),
          child: child!,
        ),
        home: ChatShell(controller: controller),
      ),
    );
    for (var frame = 0; frame < 14; frame++) {
      await tester.pump(const Duration(milliseconds: 30));
    }
    expect(controller.state.approval?.requestId, 'pending-from-web');
    final reject = find.byKey(const ValueKey('approval-reject'));
    expect(reject, findsOneWidget);
    expect(reject.hitTestable(), findsOneWidget);
    final composer = find.byKey(const ValueKey('floating-composer-extent'));
    expect(
      tester.getRect(reject).bottom,
      lessThanOrEqualTo(tester.getRect(composer).top),
    );
    await tester.tap(reject);
    await tester.pump();
    expect(transport.rejected, ['pending-from-web']);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
