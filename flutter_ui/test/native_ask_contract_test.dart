import 'dart:async';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/transport/native_interactions.dart';
import 'package:dsh_native_chat/src/ui/action_cards.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const _skipKey = ValueKey('question-skip');

Future<void> _pumpCard(WidgetTester tester, QuestionCard card) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: Scaffold(body: SingleChildScrollView(child: card)),
    ),
  );
  await tester.pump();
}

class _QuestionTransport extends FixtureTransport
    implements StructuredQuestionTransport {
  final replies = <({String id, QuestionAnswer answer})>[];
  final receipt = Completer<void>();
  int legacyReplies = 0;

  @override
  Future<void> answerQuestion(String requestId, QuestionAnswer answer) async {
    replies.add((id: requestId, answer: answer));
    await receipt.future;
  }

  @override
  Future<void> answer(String requestId, String answer) async {
    legacyReplies++;
  }
}

class _AskRpc extends DshRpcConnection {
  _AskRpc() : super(webUrl: 'http://127.0.0.1:1/?token=unused-test-only');

  final channels = <StreamController<Map<String, Object?>>>[];
  final results = <Map<String, Object?>>[];
  Completer<Object?>? receipt;

  @override
  Stream<Map<String, Object?>> follow(
    String endpoint,
    Map<String, Object?> args,
  ) {
    expect(endpoint, r'$events');
    final channel = StreamController<Map<String, Object?>>();
    channels.add(channel);
    return channel.stream;
  }

  @override
  Future<Object?> call(String endpoint, Map<String, Object?> args) async {
    expect(endpoint, r'$events/result');
    results.add(args);
    return receipt == null ? null : await receipt!.future;
  }

  void send(Map<String, Object?> frame) => channels.last.add(frame);

  void disposeChannels() {
    for (final channel in channels) {
      unawaited(channel.close());
    }
  }
}

Map<String, Object?> _question() => {
  'type': 'waterfall',
  'event': 'user-questions/request',
  'eventId': 'same-question-event',
  'agentId': 'parent',
  'request': {
    'questions': [
      {
        'id': 'target',
        'question': 'Which target?',
        'options': [
          {'label': 'Alpha', 'description': 'First target'},
          {'label': 'Beta'},
        ],
        'multiSelect': true,
      },
    ],
  },
};

Future<void> _open(
  NativeInteractions service,
  _AskRpc rpc,
  String clientId,
  String home, {
  bool reconnect = false,
}) async {
  final opening = reconnect ? service.reconnect() : service.start();
  await Future<void>.delayed(Duration.zero);
  rpc.send({
    'type': 'ready',
    'clientId': clientId,
    'host': {'home': home},
  });
  await opening;
}

Future<NativeInteraction> _deliver(
  NativeInteractions service,
  _AskRpc rpc,
) async {
  final generation = service.state.generation;
  final received = service.updates.firstWhere(
    (state) => state.pending.any((item) => item.generation == generation),
  );
  rpc.send(_question());
  return (await received.timeout(const Duration(seconds: 3))).pending.single;
}

void main() {
  testWidgets('native Ask explicitly skips without submitting the current draft', (
    tester,
  ) async {
    final replies = <QuestionAnswer>[];
    await _pumpCard(
      tester,
      QuestionCard(
        request: const ActionRequest(
          requestId: 'ask',
          title: 'Which target?',
          detail: '',
          choices: ['Alpha', 'Beta'],
          multiSelect: true,
        ),
        onSubmit: (_) => fail('Native Ask must not use the text fallback'),
        onStructuredSubmit: replies.add,
      ),
    );
    await tester.tap(find.byKey(const ValueKey('question-choice-0')));
    await tester.enterText(
      find.byKey(const ValueKey('question-input')),
      'Unsent draft',
    );
    await tester.pump();
    expect(replies, isEmpty);
    expect(find.byKey(_skipKey), findsOneWidget);
    await tester.tap(find.byKey(_skipKey));
    expect(replies.single.selected, isEmpty);
    expect(replies.single.custom, isEmpty);
    expect(
      tester
          .widget<TextField>(find.byKey(const ValueKey('question-input')))
          .controller!
          .text,
      'Unsent draft',
    );
  });

  testWidgets('native Ask skip is disabled while an answer is submitting', (
    tester,
  ) async {
    final replies = <QuestionAnswer>[];
    QuestionCard card(bool busy) => QuestionCard(
      request: const ActionRequest(requestId: 'ask', title: 'Target?', detail: ''),
      busy: busy,
      onSubmit: (_) => fail('Native Ask must not use the text fallback'),
      onStructuredSubmit: replies.add,
    );
    await _pumpCard(tester, card(false));
    expect(find.byKey(_skipKey), findsOneWidget);
    final oldCallback = tester.widget<TextButton>(find.byKey(_skipKey)).onPressed!;
    await _pumpCard(tester, card(true));
    expect(tester.widget<TextButton>(find.byKey(_skipKey)).onPressed, isNull);
    oldCallback();
    expect(replies, isEmpty);
  });

  testWidgets('legacy text-only cards do not offer an unencodable skip', (
    tester,
  ) async {
    await _pumpCard(
      tester,
      QuestionCard(
        request: const ActionRequest(requestId: 'legacy', title: 'Why?', detail: ''),
        onSubmit: (_) => fail('No answer was submitted'),
      ),
    );
    expect(find.byKey(_skipKey), findsNothing);
    expect(
      tester
          .widget<IconButton>(find.byKey(const ValueKey('question-submit')))
          .onPressed,
      isNull,
    );
  });

  test('controller dispatches an explicit structured skip exactly once', () async {
    final transport = _QuestionTransport();
    final controller = ChatController(transport);
    addTearDown(controller.dispose);
    await controller.start();
    await Future<void>.delayed(Duration.zero);
    transport.emitQuestion();
    await Future<void>.delayed(Duration.zero);
    final id = controller.state.question!.requestId;
    await controller.answerQuestion('stale', QuestionAnswer());
    expect(transport.replies, isEmpty);
    final skipping = controller.answerQuestion(id, QuestionAnswer());
    await controller.answerQuestion(id, QuestionAnswer());
    expect(transport.replies, hasLength(1));
    expect(transport.replies.single.id, id);
    expect(transport.replies.single.answer.selected, isEmpty);
    expect(transport.replies.single.answer.custom, isEmpty);
    expect(transport.legacyReplies, 0);
    expect(controller.actionBusy(id), isTrue);
    transport.receipt.complete();
    await skipping;
    await controller.answerQuestion(id, QuestionAnswer());
    expect(transport.replies, hasLength(1));
  });

  test('Ask replay retains its draft identity only within the same host home', () async {
    final rpc = _AskRpc();
    final service = NativeInteractions(rpc: rpc, sessionId: 'parent');
    addTearDown(() async {
      await service.close();
      rpc.disposeChannels();
    });
    await _open(service, rpc, 'client-1', '/host-a');
    final original = await _deliver(service, rpc);
    await _open(service, rpc, 'client-2', '/host-a', reconnect: true);
    final replay = await _deliver(service, rpc);
    expect(replay.requestId, isNot(original.requestId));
    expect(replay.action!.draftKey, original.action!.draftKey);
    await _open(service, rpc, 'client-3', '/host-b', reconnect: true);
    final differentHost = await _deliver(service, rpc);
    expect(differentHost.action!.draftKey, isNot(original.action!.draftKey));
    expect(rpc.results, isEmpty);
  });

  test('old Ask receipt cannot answer or auto-submit a replayed question', () async {
    final rpc = _AskRpc();
    final service = NativeInteractions(rpc: rpc, sessionId: 'parent');
    addTearDown(() async {
      await service.close();
      rpc.disposeChannels();
    });
    await _open(service, rpc, 'client-1', '/host');
    final original = await _deliver(service, rpc);
    final receipt = Completer<Object?>();
    rpc.receipt = receipt;
    final answering = service.answerStructured(
      original.requestId,
      NativeQuestionReply(questionId: 'target', selected: ['Alpha']),
    );
    final invalidated = expectLater(
      answering,
      throwsA(
        isA<DshRpcException>().having(
          (error) => error.code,
          'code',
          'interactions/stale-generation',
        ),
      ),
    );
    await _open(service, rpc, 'client-2', '/host', reconnect: true);
    final replay = await _deliver(service, rpc);
    await invalidated;
    receipt.complete(null);
    await Future<void>.delayed(Duration.zero);
    expect(rpc.results, hasLength(1));
    expect(rpc.results.single['clientId'], 'client-1');
    expect(service.state.pending.single.requestId, replay.requestId);
    expect(service.state.pending.single.status, NativeInteractionStatus.pending);
    expect(service.state.pending.single.answerDraft, isNull);
  });
}
