import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';

class SlowActions extends FixtureTransport {
  final wait = Completer<void>();
  int approvals = 0;
  int answers = 0;
  @override
  Future<void> approve(String id) async {
    approvals++;
    await wait.future;
  }

  @override
  Future<void> answer(String id, String answer) async {
    answers++;
    await wait.future;
  }
}

void main() {
  test('approval dispatch rejects duplicates and stale requests', () async {
    final transport = SlowActions();
    final c = ChatController(transport);
    await c.start();
    await Future<void>.delayed(Duration.zero);
    await c.sendPrompt('read');
    await Future<void>.delayed(Duration.zero);
    final id = c.state.approval!.requestId;
    final first = c.approve(id);
    await c.approve(id);
    await c.approve('stale');
    expect(transport.approvals, 1);
    expect(c.actionBusy(id), isTrue);
    transport.wait.complete();
    await first;
    await c.approve(id);
    expect(transport.approvals, 1);
    c.dispose();
  });
  test('question dispatch rejects duplicate and empty submissions', () async {
    final transport = SlowActions();
    final c = ChatController(transport);
    await c.start();
    await Future<void>.delayed(Duration.zero);
    transport.emitQuestion();
    await Future<void>.delayed(Duration.zero);
    final id = c.state.question!.requestId;
    await c.answer(id, ' ');
    final first = c.answer(id, 'A');
    await c.answer(id, 'B');
    expect(transport.answers, 1);
    transport.wait.complete();
    await first;
    c.dispose();
  });
}
