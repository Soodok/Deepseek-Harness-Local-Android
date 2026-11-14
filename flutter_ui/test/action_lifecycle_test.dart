import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';

class _Actions implements DshTransport {
  final channel = StreamController<ChatEvent>();
  final receipt = Completer<void>();
  int calls = 0;
  @override
  Stream<ChatEvent> get events => channel.stream;
  @override
  bool get isFixture => true;
  @override
  Future<void> connect() async {
    channel.add(
      const ChatEvent.snapshot(generation: 1, sequence: 0, messages: []),
    );
    channel.add(
      const ChatEvent.approval(
        generation: 1,
        sequence: 1,
        requestId: 'approval',
        title: 'Allow?',
        detail: '',
      ),
    );
  }

  @override
  Future<void> reconnect() async {}
  @override
  Future<void> approve(String id) async {
    calls++;
    await receipt.future;
  }

  @override
  Future<void> answer(String id, String text) async {}
  @override
  Future<void> sendPrompt(String text) async {}
  @override
  Future<void> stop() async {}
  @override
  Future<void> close() async {
    unawaited(channel.close());
  }
}

void main() {
  test(
    'unrelated tool completion and snapshot keep slow approval locked',
    () async {
      final t = _Actions();
      final c = ChatController(t);
      await c.start();
      await Future<void>.delayed(Duration.zero);
      final sending = c.approve('approval');
      expect(c.actionBusy('approval'), isTrue);
      t.channel.add(
        const ChatEvent.toolFinished(
          generation: 1,
          sequence: 2,
          toolId: 'child-catalog',
          status: ToolStatus.complete,
        ),
      );
      await Future<void>.delayed(Duration.zero);
      expect(c.state.approval?.requestId, 'approval');
      expect(c.actionBusy('approval'), isTrue);
      t.channel.add(
        const ChatEvent.snapshot(generation: 1, sequence: 3, messages: []),
      );
      t.channel.add(
        const ChatEvent.approval(
          generation: 1,
          sequence: 4,
          requestId: 'approval',
          title: 'Allow?',
          detail: '',
        ),
      );
      await Future<void>.delayed(Duration.zero);
      expect(c.actionBusy('approval'), isTrue);
      await c.approve('approval');
      expect(t.calls, 1);
      t.receipt.complete();
      await sending;
      c.dispose();
    },
  );
}
