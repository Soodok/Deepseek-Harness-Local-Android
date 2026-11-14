import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/subagent_model.dart';
import 'package:dsh_native_chat/src/state/subagent_controller.dart';

class Service implements SubagentTransport {
  final changes = StreamController<SubagentConversation>.broadcast(sync: true);
  final gate = Completer<void>();
  int calls = 0;
  bool fail = false;
  @override
  Stream<SubagentConversation> watchSubagent(SubagentAddress a) =>
      changes.stream;
  @override
  Future<SubagentConversation> readSubagent(SubagentAddress a) async =>
      SubagentConversation(address: a, messages: const [], revision: 0);
  @override
  Future<SubagentReceipt> sendSubagent(
    SubagentAddress a,
    String text, {
    required String requestId,
  }) async {
    calls++;
    await gate.future;
    if (fail) throw StateError('offline');
    return SubagentReceipt(requestId: requestId, delivery: 'queue');
  }
}

class ThrowingService extends Service {
  @override
  Stream<SubagentConversation> watchSubagent(SubagentAddress a) =>
      throw StateError('watch unavailable');
}

void main() {
  test('subscription failure is visible and loading is released', () async {
    final s = ThrowingService();
    const a = SubagentAddress(
      parentSessionId: 'p',
      childSessionId: 'c',
      mode: SubagentMode.continuable,
    );
    final c = SubagentController(service: s, address: a);
    await c.start();
    expect(c.loading, false);
    expect(c.error, contains('watch unavailable'));
    c.dispose();
    await s.changes.close();
  });
  const a = SubagentAddress(
    parentSessionId: 'parent',
    childSessionId: 'child',
    mode: SubagentMode.continuable,
  );
  test(
    'send is once while pending, accepted text is not an assistant reply',
    () async {
      final s = Service();
      final c = SubagentController(service: s, address: a);
      await c.start();
      final first = c.send('hello');
      final second = c.send('hello');
      expect(s.calls, 1);
      expect(await second, false);
      s.gate.complete();
      expect(await first, true);
      expect(c.conversation!.messages, isEmpty);
      expect(c.lastReceipt!.delivery, 'queue');
      c.dispose();
      await s.changes.close();
    },
  );
  test('one-shot and unknown addresses remain read only', () async {
    for (final mode in [SubagentMode.oneShot, SubagentMode.unknown]) {
      final s = Service();
      final c = SubagentController(
        service: s,
        address: SubagentAddress(
          parentSessionId: 'p',
          childSessionId: 'c',
          mode: mode,
        ),
      );
      await c.start();
      expect(await c.send('hello'), false);
      expect(s.calls, 0);
      c.dispose();
      await s.changes.close();
    }
  });
  test('old revisions do not replace newer child conversation', () async {
    final s = Service();
    final c = SubagentController(service: s, address: a);
    await c.start();
    s.changes.add(
      SubagentConversation(
        address: a,
        revision: 2,
        messages: const [
          SubagentMessage(id: 'm', role: 'assistant', text: 'new'),
        ],
      ),
    );
    s.changes.add(
      SubagentConversation(address: a, revision: 1, messages: const []),
    );
    expect(c.conversation!.messages.single.text, 'new');
    c.dispose();
    await s.changes.close();
  });
  test(
    'watch retry during a pending delivery does not strand the send lock',
    () async {
      final s = Service();
      final c = SubagentController(service: s, address: a);
      await c.start();
      final pending = c.send('keep this draft');
      s.changes.addError(StateError('watch disconnected'));
      expect(c.error, contains('watch disconnected'));
      await c.start();
      expect(c.loading, isFalse);
      s.gate.complete();
      expect(await pending, isTrue);
      expect(c.sending, isFalse);
      expect(c.canSend, isTrue);
      expect(c.lastReceipt?.delivery, 'queue');
      expect(s.calls, 1);
      c.dispose();
      await s.changes.close();
    },
  );
  test('delivery failures stay retryable', () async {
    final s = Service()..fail = true;
    final c = SubagentController(service: s, address: a);
    await c.start();
    final f = c.send('hello');
    s.gate.complete();
    expect(await f, false);
    expect(c.error, contains('offline'));
    expect(c.sending, false);
    expect(c.lastReceipt, isNull);
    c.dispose();
    await s.changes.close();
  });
}
