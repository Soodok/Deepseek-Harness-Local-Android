import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/transport/http_ws_transport.dart';

Future<ChatEvent> take(StreamIterator<ChatEvent> iterator) async {
  expect(await iterator.moveNext(), isTrue);
  return iterator.current;
}

void main() {
  test('fixture emits a deterministic prompt and approval flow', () async {
    final transport = FixtureTransport();
    final iterator = StreamIterator<ChatEvent>(transport.events);

    await transport.connect();
    final snapshot = await take(iterator);
    expect(snapshot.kind, ChatEventKind.snapshot);
    expect(snapshot.messages, hasLength(2));

    await transport.sendPrompt('继续检查');
    expect((await take(iterator)).kind, ChatEventKind.delta);
    expect((await take(iterator)).kind, ChatEventKind.delta);
    expect((await take(iterator)).kind, ChatEventKind.toolStarted);
    final approval = await take(iterator);
    expect(approval.kind, ChatEventKind.approval);
    expect(approval.requestId, isNotEmpty);

    await transport.approve(approval.requestId!);
    expect((await take(iterator)).kind, ChatEventKind.toolFinished);
    expect((await take(iterator)).kind, ChatEventKind.delta);
    expect((await take(iterator)).kind, ChatEventKind.turnComplete);

    await iterator.cancel();
    await transport.close();
  });

  test('fixture reconnect starts a new generation', () async {
    final transport = FixtureTransport();
    final iterator = StreamIterator<ChatEvent>(transport.events);
    await transport.connect();
    final first = await take(iterator);
    expect(first.generation, 1);

    transport.emitDisconnect();
    final disconnected = await take(iterator);
    expect(disconnected.kind, ChatEventKind.disconnected);
    await transport.reconnect();
    final second = await take(iterator);
    expect(second.kind, ChatEventKind.snapshot);
    expect(second.generation, 2);

    await iterator.cancel();
    await transport.close();
  });

  test('real transport rejects an empty engine URL', () async {
    final transport = HttpWsTransport(webUrl: '');
    expect(transport.connect, throwsA(isA<TransportException>()));
    await transport.close();
  });
}
